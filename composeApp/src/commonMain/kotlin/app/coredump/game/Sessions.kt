package app.coredump.game

import app.coredump.core.GameConfig
import app.coredump.core.Judge
import app.coredump.core.Judgement
import app.coredump.core.Norm
import app.coredump.core.Prompt
import app.coredump.core.RoundView
import app.coredump.core.Spam
import app.coredump.core.Tier
import app.coredump.core.Views
import app.coredump.data.History
import app.coredump.data.AnswerCheck
import app.coredump.data.QuestionBank
import app.coredump.net.Msg
import app.coredump.net.PeerLink
import app.coredump.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase { WAITING, TYPING, LOCKED, REVEAL, OVER, ERROR }

enum class AttemptStatus { REJECTED, VERIFYING }

/**
 * One rejected (or still being checked) answer in the current round. spam: looked like key-mashing (cost points).
 * offline: the online check could not be reached (the same answer may be sent again).
 */
data class Attempt(
    val text: String,
    val status: AttemptStatus,
    /** Answers to offer for this miss ("did you mean …"), best first. */
    val suggestions: List<String> = emptyList(),
    val spam: Boolean = false,
    val offline: Boolean = false,
) {
    val suggestion: String? get() = suggestions.firstOrNull()
}

/** Autocorrect wants to submit [answer] for what was [typed]; the player accepts or asks for a search instead. */
data class Confirm(val typed: String, val answer: String, val others: List<String> = emptyList())

/**
 * round: 0-based index of the current prompt. total: prompts in the game (count).
 * seconds: limit for this prompt (s), 0 = none. startedAt: epoch ms when the prompt opened (shifted by pauses).
 * missTick: increments on every rejection (drives shake / glitch).
 */
data class MatchState(
    val phase: Phase = Phase.WAITING,
    val round: Int = 0,
    val total: Int = 0,
    val prompt: String = "",
    val topic: String = "",
    val listSize: Int = 0,
    val seconds: Int = 0,
    val startedAt: Long = 0L,
    val reveal: RoundView? = null,
    val rounds: List<RoundView> = emptyList(),
    val myName: String = "",
    val oppName: String? = null,
    val oppLocked: Boolean = false,
    val canAdvance: Boolean = true,
    val message: String? = null,
    val attempts: List<Attempt> = emptyList(),
    val missTick: Int = 0,
    val paused: Boolean = false,
    val canPause: Boolean = false,
    val via: String? = null,
    val daily: Int? = null,
    /** Pending "did you mean …?" question (null = none). */
    val confirm: Confirm? = null,
    /** Key-mashed answers in this prompt (count; each costs [Spam.PENALTY] points, up to [Spam.MAX_PER_ROUND]). */
    val spam: Int = 0,
) {
    val myTotal: Int get() = rounds.sumOf { it.me.points }
    val oppTotal: Int get() = rounds.sumOf { it.opp?.points ?: 0 }
    val isLast: Boolean get() = round + 1 >= total
    val verifying: Boolean get() = attempts.any { it.status == AttemptStatus.VERIFYING }
}

interface MatchSession {
    val state: StateFlow<MatchState>
    val isMulti: Boolean
    fun start()
    /** One typed attempt. Wrong answers are kept as chips; the round continues until accepted or time runs out. */
    fun submit(text: String)
    fun next()
    fun quit()
    fun pause() {}
    fun resume() {}
    /** Give up on the current prompt (0 points). */
    fun skip() {}
    /** Answer to [MatchState.confirm]: true = submit the suggestion, false = check exactly what was typed. */
    fun confirm(accept: Boolean) {}
    /** Local prompt for a round (null for a guest: the host owns the lists). */
    fun promptAt(index: Int): Prompt?
}

/** Judges attempts: local hash/fuzzy lookup first, then an online Wikidata check for unlisted answers. */
class Arbiter(private val bank: QuestionBank?, private val maxEdit: Int, private val online: Boolean) {
    sealed interface Local {
        class Hit(val j: Judgement) : Local
        /** Matched only with typos: ask the player before accepting. */
        class Close(val j: Judgement, val others: List<String> = emptyList()) : Local
        class Miss(val suggestions: List<String>, val canVerify: Boolean, val spam: Boolean) : Local
    }

    /** Outcome of the online search. */
    sealed interface Remote {
        class Found(val j: Judgement) : Remote
        data object Missing : Remote
        /** Not found as typed; class members with these names contain the typed words ("did you mean"). */
        class Near(val names: List<String>) : Remote
        /** No connection / the services did not answer: nothing learned, the player may send it again. */
        data object Unreachable : Remote
    }

    /**
     * [fuzzy] = false: exact forms only (the player rejected the autocorrect and wants a search).
     * Every miss that is not key-mashing is searched online (when allowed): lists without a class pattern still
     * get their other names resolved (redirects, aliases), lists with one also accept unlisted members.
     */
    fun local(p: Prompt, text: String, fuzzy: Boolean = true): Local {
        val j = Judge.judge(p, text, if (fuzzy) maxEdit else 0)
        if (j.tier.isValid) {
            if (j.distance == 0) return Local.Hit(j)
            // matched only with typos: also offer the other names that contain the typed words
            val others = p.index.wordMatches(text, AnswerCheck.classWords(p.text)).asSequence().map { p.entries[it].name }.filter { it != j.entry?.name }
                .take(MAX_SUGGESTIONS - 1).toList()
            return Local.Close(j, others)
        }
        val promptWords = AnswerCheck.classWords(p.text)
        val sug = if (fuzzy) p.index.suggestions(text, MAX_SUGGESTIONS, promptWords).map { p.entries[it].name } else emptyList()
        val spam = sug.isEmpty() && Spam.isGibberish(text)
        return Local.Miss(sug, !spam && online && bank != null, spam)
    }

    companion object {
        /** "Did you mean" choices shown for one miss (count). */
        const val MAX_SUGGESTIONS = 5
    }

    suspend fun remote(p: Prompt, text: String): Remote {
        val b = bank ?: return Remote.Missing
        return when (val r = b.verify(p, text)) {
            is AnswerCheck.Outcome.Listed -> {
                val j = Judge.judge(p, r.name, 0)
                if (j.tier.isValid) Remote.Found(Judgement(text, j.entry, j.tier, 0)) else Remote.Missing
            }
            is AnswerCheck.Outcome.Found -> Remote.Found(Judge.verified(text, r.label, r.links, p))
            AnswerCheck.Outcome.Missing -> Remote.Missing
            is AnswerCheck.Outcome.Near -> Remote.Near(r.names)
            AnswerCheck.Outcome.Unreachable -> Remote.Unreachable
        }
    }
}

private const val GRACE_MS = 2_500L
private const val VERIFY_SLACK_MS = 6_500L

/** Local "did you mean" choices plus the online near names (class members containing the typed words). */
private fun withNear(sug: List<String>, res: Arbiter.Remote): List<String> =
    if (res is Arbiter.Remote.Near) (sug + res.names).distinctBy { it.lowercase() }.take(Arbiter.MAX_SUGGESTIONS + 2) else sug

private fun List<Attempt>.upsert(text: String, status: AttemptStatus, sug: List<String>, spam: Boolean = false, offline: Boolean = false): List<Attempt> {
    val k = Norm.key(text)
    val i = indexOfFirst { Norm.key(it.text) == k }
    val a = Attempt(text, status, sug, spam, offline)
    return if (i < 0) this + a else toMutableList().also { it[i] = a }
}

/** Already tried (an answer whose check could not reach the internet may be sent again). */
private fun List<Attempt>.has(text: String): Boolean {
    val k = Norm.key(text)
    return any { Norm.key(it.text) == k && !it.offline }
}

private fun timeoutJudgement(attempts: List<Attempt>) =
    Judgement(attempts.lastOrNull()?.text.orEmpty(), null, Tier.TIMEOUT, -1)

private fun skipJudgement(attempts: List<Attempt>) =
    Judgement(attempts.lastOrNull()?.text.orEmpty(), null, Tier.SKIPPED, -1)

// ------------------------------------------------------------------ solo

class SoloSession(
    private val prompts: List<Prompt>,
    private val cfg: GameConfig,
    private val name: String,
    private val scope: CoroutineScope,
    private val arbiter: Arbiter,
    daily: Int? = null,
) : MatchSession {
    private val _s = MutableStateFlow(MatchState(myName = name, total = prompts.size, canPause = true, daily = daily))
    override val state: StateFlow<MatchState> = _s.asStateFlow()
    override val isMulti = false
    private var timer: Job? = null
    private var expired = false
    private var pending = 0
    private var pausedAt = 0L
    private var remainingMs = 0L
    private var close: Judgement? = null
    private var spam = 0

    override fun promptAt(index: Int): Prompt? = prompts.getOrNull(index)

    override fun start() = begin(0)

    private fun begin(i: Int) {
        val p = prompts[i]
        expired = false; pending = 0; close = null; spam = 0
        History.markSeen(p.id)
        _s.update {
            it.copy(phase = Phase.TYPING, round = i, prompt = p.text, topic = p.topic, listSize = p.size,
                seconds = cfg.secondsPerRound, startedAt = Platform.epochMillis(), reveal = null, attempts = emptyList(),
                paused = false, confirm = null, spam = 0)
        }
        arm(cfg.secondsPerRound * 1000L)
    }

    private fun arm(ms: Long) {
        timer?.cancel()
        if (cfg.secondsPerRound > 0) timer = scope.launch { delay(ms); expire() }
    }

    private fun expire() {
        if (_s.value.phase != Phase.TYPING) return
        expired = true
        // typed in time but not confirmed yet: take the suggestion
        close?.let { finish(it); return }
        if (pending == 0) finish(timeoutJudgement(_s.value.attempts))
        else scope.launch {
            val r = _s.value.round
            delay(VERIFY_SLACK_MS)
            if (_s.value.phase == Phase.TYPING && _s.value.round == r) finish(timeoutJudgement(_s.value.attempts))
        }
    }

    override fun submit(text: String) = attempt(text, fuzzy = true)

    override fun confirm(accept: Boolean) {
        val j = close ?: return
        val c = _s.value.confirm
        if (_s.value.phase != Phase.TYPING) return
        close = null
        _s.update { it.copy(confirm = null) }
        if (accept) finish(j) else attempt(c?.typed ?: j.input, fuzzy = false)
    }

    override fun skip() {
        val s = _s.value
        if (s.phase != Phase.TYPING || s.paused) return
        finish(skipJudgement(s.attempts))
    }

    private fun attempt(text: String, fuzzy: Boolean) {
        val s = _s.value
        val t = text.trim()
        if (s.phase != Phase.TYPING || s.paused || expired || t.isEmpty()) return
        if (fuzzy && s.attempts.has(t)) { _s.update { it.copy(missTick = it.missTick + 1) }; return }
        close = null
        if (s.confirm != null) _s.update { it.copy(confirm = null) }
        val p = prompts[s.round]
        when (val r = arbiter.local(p, t, fuzzy)) {
            is Arbiter.Local.Hit -> finish(r.j)
            is Arbiter.Local.Close -> {
                close = r.j
                _s.update { it.copy(confirm = Confirm(t, r.j.entry?.name ?: t, r.others)) }
            }
            is Arbiter.Local.Miss -> if (r.spam) {
                spam++
                _s.update { it.copy(attempts = it.attempts.upsert(t, AttemptStatus.REJECTED, emptyList(), spam = true),
                    missTick = it.missTick + 1, spam = spam) }
            } else if (r.canVerify) {
                _s.update { it.copy(attempts = it.attempts.upsert(t, AttemptStatus.VERIFYING, r.suggestions)) }
                pending++
                val round = s.round
                scope.launch {
                    val res = arbiter.remote(p, t)
                    pending--
                    onRemote(round, t, res, r.suggestions)
                }
            } else {
                _s.update { it.copy(attempts = it.attempts.upsert(t, AttemptStatus.REJECTED, r.suggestions), missTick = it.missTick + 1) }
            }
        }
    }

    private fun onRemote(round: Int, t: String, res: Arbiter.Remote, sug: List<String>) {
        val s = _s.value
        if (s.round != round || s.phase != Phase.TYPING) return
        if (res is Arbiter.Remote.Found) { finish(res.j); return }
        _s.update { it.copy(attempts = it.attempts.upsert(t, AttemptStatus.REJECTED, withNear(sug, res), offline = res == Arbiter.Remote.Unreachable),
            missTick = it.missTick + 1) }
        if (expired && pending == 0) finish(timeoutJudgement(_s.value.attempts))
    }

    private fun finish(j: Judgement) {
        timer?.cancel()
        val s = _s.value
        val p = prompts[s.round]
        val elapsed = (Platform.epochMillis() - s.startedAt).coerceAtMost(if (s.seconds > 0) s.seconds * 1000L else Long.MAX_VALUE)
        val view = RoundView(s.round, p.id, p.text, p.topic, p.size, Views.play(name, j, elapsed, spam), null, Views.ladder(p), Views.all(p))
        close = null
        _s.update { it.copy(phase = Phase.REVEAL, reveal = view, rounds = it.rounds + view, confirm = null) }
    }

    override fun pause() {
        val s = _s.value
        if (s.phase != Phase.TYPING || s.paused) return
        pausedAt = Platform.epochMillis()
        remainingMs = if (s.seconds > 0) s.seconds * 1000L - (pausedAt - s.startedAt) else 0L
        timer?.cancel()
        _s.update { it.copy(paused = true) }
    }

    override fun resume() {
        val s = _s.value
        if (!s.paused) return
        val now = Platform.epochMillis()
        _s.update { it.copy(paused = false, startedAt = it.startedAt + (now - pausedAt)) }
        if (s.phase == Phase.TYPING && s.seconds > 0) arm(remainingMs.coerceAtLeast(0L))
    }

    override fun next() {
        val s = _s.value
        if (s.phase != Phase.REVEAL) return
        if (s.isLast) {
            _s.update { it.copy(phase = Phase.OVER) }
            val st = _s.value
            History.recordRun(prompts.map { it.id }, st.rounds.map { it.me.tier }, mode = if (st.daily != null) "daily" else "solo",
                daily = st.daily, topics = prompts.map { it.topic }, points = st.myTotal)
        } else begin(s.round + 1)
    }

    override fun quit() { timer?.cancel() }
}

// ------------------------------------------------------------------ 1v1 host (authoritative judge)

class HostSession(
    private val link: PeerLink,
    private val prompts: List<Prompt>,
    private val cfg: GameConfig,
    private val name: String,
    oppName: String,
    private val scope: CoroutineScope,
    private val arbiter: Arbiter,
) : MatchSession {
    private val _s = MutableStateFlow(MatchState(myName = name, oppName = oppName, total = prompts.size, via = link.via))
    override val state: StateFlow<MatchState> = _s.asStateFlow()
    override val isMulti = true
    private var myJ: Judgement? = null
    private var oppJ: Judgement? = null
    private var myMs = 0L
    private var oppMs = 0L
    private var oppAttempts = ArrayList<String>()
    private var myPending = 0
    private var myExpired = false
    private var myClose: Judgement? = null
    private var mySpam = 0
    private var oppSpam = 0
    private val timers = ArrayList<Job>()
    private var listener: Job? = null

    override fun promptAt(index: Int): Prompt? = prompts.getOrNull(index)

    override fun start() {
        listener = scope.launch {
            for (m in link.incoming) when (m) {
                is Msg.Hello -> link.send(Msg.Welcome(name))
                is Msg.Attempt -> onGuestAttempt(m)
                is Msg.Answer -> if (m.i == _s.value.round && oppJ == null) {
                    oppJ = Judgement(oppAttempts.lastOrNull().orEmpty(), null, if (m.skip) Tier.SKIPPED else Tier.TIMEOUT, -1)
                    oppMs = m.elapsedMs
                    _s.update { it.copy(oppLocked = true) }
                    maybeResolve()
                }
                is Msg.Bye -> fail("peer left: ${m.reason}")
                else -> Unit
            }
            if (_s.value.phase != Phase.OVER) fail("link lost. peer disconnected.")
        }
        scope.launch {
            link.send(Msg.Start(prompts.size, cfg.secondsPerRound, cfg.difficulty.name))
            begin(0)
        }
    }

    private fun fail(msg: String) {
        if (_s.value.phase == Phase.OVER) return
        timers.forEach { it.cancel() }
        _s.update { it.copy(phase = Phase.ERROR, message = msg) }
    }

    private suspend fun begin(i: Int) {
        val p = prompts[i]
        myJ = null; oppJ = null; myPending = 0; myExpired = false; oppAttempts = ArrayList()
        myClose = null; mySpam = 0; oppSpam = 0
        timers.forEach { it.cancel() }; timers.clear()
        History.markSeen(p.id)
        _s.update {
            it.copy(phase = Phase.TYPING, round = i, prompt = p.text, topic = p.topic, listSize = p.size,
                seconds = cfg.secondsPerRound, startedAt = Platform.epochMillis(), reveal = null, oppLocked = false,
                attempts = emptyList(), confirm = null, spam = 0)
        }
        link.send(Msg.Round(i, p.text, p.topic, p.size, cfg.secondsPerRound))
        if (cfg.secondsPerRound > 0) {
            val ms = cfg.secondsPerRound * 1000L
            timers += scope.launch { delay(ms); myExpire() }
            timers += scope.launch { delay(ms + GRACE_MS + VERIFY_SLACK_MS); resolve() }
        }
    }

    private fun myExpire() {
        if (myJ != null) return
        myExpired = true
        myClose?.let { lockMine(it); return }
        if (myPending == 0) lockMine(timeoutJudgement(_s.value.attempts))
    }

    private fun lockMine(j: Judgement) {
        if (myJ != null) return
        myJ = j
        myClose = null
        if (_s.value.confirm != null) _s.update { it.copy(confirm = null) }
        myMs = Platform.epochMillis() - _s.value.startedAt
        val r = _s.value.round
        _s.update { it.copy(phase = Phase.LOCKED) }
        scope.launch { link.send(Msg.Locked(r)) }
        maybeResolve()
    }

    override fun submit(text: String) = attempt(text, fuzzy = true)

    override fun confirm(accept: Boolean) {
        val j = myClose ?: return
        val c = _s.value.confirm
        myClose = null
        _s.update { it.copy(confirm = null) }
        if (accept) lockMine(j) else attempt(c?.typed ?: j.input, fuzzy = false)
    }

    override fun skip() {
        if (_s.value.phase != Phase.TYPING || myJ != null) return
        lockMine(skipJudgement(_s.value.attempts))
    }

    private fun attempt(text: String, fuzzy: Boolean) {
        val s = _s.value
        val t = text.trim()
        if (s.phase != Phase.TYPING || myJ != null || myExpired || t.isEmpty()) return
        if (fuzzy && s.attempts.has(t)) { _s.update { it.copy(missTick = it.missTick + 1) }; return }
        myClose = null
        if (s.confirm != null) _s.update { it.copy(confirm = null) }
        val p = prompts[s.round]
        when (val r = arbiter.local(p, t, fuzzy)) {
            is Arbiter.Local.Hit -> lockMine(r.j)
            is Arbiter.Local.Close -> {
                myClose = r.j
                _s.update { it.copy(confirm = Confirm(t, r.j.entry?.name ?: t, r.others)) }
            }
            is Arbiter.Local.Miss -> if (r.spam) {
                mySpam++
                _s.update { it.copy(attempts = it.attempts.upsert(t, AttemptStatus.REJECTED, emptyList(), spam = true),
                    missTick = it.missTick + 1, spam = mySpam) }
            } else if (r.canVerify) {
                _s.update { it.copy(attempts = it.attempts.upsert(t, AttemptStatus.VERIFYING, r.suggestions)) }
                myPending++
                val round = s.round
                scope.launch {
                    val res = arbiter.remote(p, t)
                    myPending--
                    if (_s.value.round != round || myJ != null) return@launch
                    if (res is Arbiter.Remote.Found) lockMine(res.j) else {
                        _s.update { it.copy(attempts = it.attempts.upsert(t, AttemptStatus.REJECTED, withNear(r.suggestions, res),
                            offline = res == Arbiter.Remote.Unreachable), missTick = it.missTick + 1) }
                        if (myExpired && myPending == 0) lockMine(timeoutJudgement(_s.value.attempts))
                    }
                }
            } else _s.update { it.copy(attempts = it.attempts.upsert(t, AttemptStatus.REJECTED, r.suggestions), missTick = it.missTick + 1) }
        }
    }

    private fun onGuestAttempt(m: Msg.Attempt) {
        val s = _s.value
        if (m.i != s.round || oppJ != null || (s.phase != Phase.TYPING && s.phase != Phase.LOCKED)) return
        val t = m.text.trim()
        if (t.isEmpty()) return
        oppAttempts += t
        val p = prompts[s.round]
        when (val r = arbiter.local(p, t, fuzzy = !m.raw)) {
            is Arbiter.Local.Hit -> acceptGuest(r.j, m)
            is Arbiter.Local.Close -> scope.launch { link.send(Msg.Verdict(m.i, t, "confirm", r.j.entry?.name, r.others)) }
            is Arbiter.Local.Miss -> if (r.spam) {
                oppSpam++
                scope.launch { link.send(Msg.Verdict(m.i, t, "spam", null)) }
            } else if (r.canVerify) {
                scope.launch {
                    link.send(Msg.Verdict(m.i, t, "verifying", r.suggestions.firstOrNull(), r.suggestions))
                    val res = arbiter.remote(p, t)
                    if (_s.value.round != m.i || oppJ != null) return@launch
                    if (res is Arbiter.Remote.Found) acceptGuest(res.j, m)
                    else {
                        val all = withNear(r.suggestions, res)
                        link.send(Msg.Verdict(m.i, t, if (res == Arbiter.Remote.Unreachable) "offline" else "miss", all.firstOrNull(), all))
                    }
                }
            } else scope.launch { link.send(Msg.Verdict(m.i, t, "miss", r.suggestions.firstOrNull(), r.suggestions)) }
        }
    }

    private fun acceptGuest(j: Judgement, m: Msg.Attempt) {
        if (oppJ != null) return
        oppJ = j
        oppMs = m.elapsedMs
        _s.update { it.copy(oppLocked = true) }
        scope.launch { link.send(Msg.Verdict(m.i, m.text, "ok", null)) }
        maybeResolve()
    }

    private fun maybeResolve() {
        if (myJ != null && oppJ != null) resolve()
    }

    private fun resolve() {
        val s = _s.value
        if (s.phase != Phase.TYPING && s.phase != Phase.LOCKED) return
        timers.forEach { it.cancel() }
        val p = prompts[s.round]
        val full = s.seconds * 1000L
        val me = Views.play(name, myJ ?: myClose ?: timeoutJudgement(s.attempts), if (myJ != null) myMs else full, mySpam)
        val opp = Views.play(s.oppName ?: "PEER", oppJ ?: Judgement(oppAttempts.lastOrNull().orEmpty(), null, Tier.TIMEOUT, -1),
            if (oppJ != null) oppMs else full, oppSpam)
        myClose = null
        val ladder = Views.ladder(p)
        val all = Views.all(p)
        val view = RoundView(s.round, p.id, p.text, p.topic, p.size, me, opp, ladder, all)
        _s.update { it.copy(phase = Phase.REVEAL, reveal = view, rounds = it.rounds + view, oppLocked = true, confirm = null) }
        scope.launch {
            link.send(Msg.Result(s.round, me, opp, ladder))
            // the guest has no lists of its own: the full answer list follows in pieces
            for (piece in Views.pieces(all)) if (!link.send(Msg.Answers(s.round, piece))) break
        }
    }

    override fun next() {
        val s = _s.value
        if (s.phase != Phase.REVEAL) return
        if (s.isLast) {
            _s.update { it.copy(phase = Phase.OVER) }
            val st = _s.value
            History.recordRun(prompts.map { it.id }, st.rounds.map { it.me.tier }, mode = "1v1", daily = null,
                topics = prompts.map { it.topic }, opponent = st.oppName, oppPoints = st.oppTotal, points = st.myTotal,
                oppCode = PeerBook.codeOf(st.oppName))
            scope.launch { link.send(Msg.Over) }
        } else scope.launch { begin(s.round + 1) }
    }

    override fun quit() {
        timers.forEach { it.cancel() }
        listener?.cancel()
        scope.launch {
            link.send(Msg.Bye("host quit"))
            link.close()
        }
    }
}

// ------------------------------------------------------------------ 1v1 guest

class GuestSession(
    private val link: PeerLink,
    private val name: String,
    hostName: String,
    private val scope: CoroutineScope,
) : MatchSession {
    private val _s = MutableStateFlow(
        MatchState(myName = name, oppName = hostName, canAdvance = false, message = "awaiting host to execute…", via = link.via)
    )
    override val state: StateFlow<MatchState> = _s.asStateFlow()
    override val isMulti = true
    private var timer: Job? = null
    private var listener: Job? = null

    override fun promptAt(index: Int): Prompt? = null

    override fun start() {
        listener = scope.launch {
            for (m in link.incoming) when (m) {
                is Msg.Start -> _s.update { it.copy(total = m.rounds, message = null) }
                is Msg.Round -> if (m.i >= _s.value.round || _s.value.phase == Phase.WAITING) begin(m)
                is Msg.Locked -> if (m.i == _s.value.round) _s.update { it.copy(oppLocked = true) }
                is Msg.Verdict -> onVerdict(m)
                is Msg.Result -> if (m.i == _s.value.round) reveal(m)
                is Msg.Answers -> onAnswers(m)
                Msg.Over -> {
                    _s.update { it.copy(phase = Phase.OVER) }
                    val st = _s.value
                    History.recordRun(emptyList(), st.rounds.map { it.me.tier }, mode = "1v1", daily = null,
                        topics = st.rounds.map { it.topic }, opponent = st.oppName, oppPoints = st.oppTotal, points = st.myTotal,
                        oppCode = PeerBook.codeOf(st.oppName))
                }
                is Msg.Bye -> fail("host left: ${m.reason}")
                else -> Unit
            }
            if (_s.value.phase != Phase.OVER) fail("link lost. host disconnected.")
        }
    }

    private fun fail(msg: String) {
        if (_s.value.phase == Phase.OVER) return
        timer?.cancel()
        _s.update { it.copy(phase = Phase.ERROR, message = msg) }
    }

    private fun begin(m: Msg.Round) {
        if (_s.value.phase == Phase.TYPING && _s.value.round == m.i) return
        timer?.cancel()
        _s.update {
            it.copy(phase = Phase.TYPING, round = m.i, total = maxOf(it.total, m.i + 1), prompt = m.text,
                topic = m.topic, listSize = m.size, seconds = m.seconds, startedAt = Platform.epochMillis(),
                reveal = null, oppLocked = false, message = null, attempts = emptyList(), confirm = null, spam = 0)
        }
        if (m.seconds > 0) timer = scope.launch {
            delay(m.seconds * 1000L)
            val s = _s.value
            if (s.phase == Phase.TYPING && s.round == m.i) {
                _s.update { it.copy(phase = Phase.LOCKED) }
                link.send(Msg.Answer(m.i, null, m.seconds * 1000L))
            }
        }
    }

    override fun submit(text: String) = send(text, raw = false)

    private fun send(text: String, raw: Boolean) {
        val s = _s.value
        val t = text.trim()
        if (s.phase != Phase.TYPING || t.isEmpty()) return
        if (!raw && s.attempts.has(t)) { _s.update { it.copy(missTick = it.missTick + 1) }; return }
        _s.update { it.copy(attempts = it.attempts.upsert(t, AttemptStatus.VERIFYING, emptyList()), confirm = null) }
        scope.launch { link.send(Msg.Attempt(s.round, t, Platform.epochMillis() - s.startedAt, raw)) }
    }

    override fun confirm(accept: Boolean) {
        val c = _s.value.confirm ?: return
        _s.update { it.copy(confirm = null) }
        if (accept) send(c.answer, raw = false) else send(c.typed, raw = true)
    }

    override fun skip() {
        val s = _s.value
        if (s.phase != Phase.TYPING) return
        timer?.cancel()
        _s.update { it.copy(phase = Phase.LOCKED, confirm = null) }
        scope.launch { link.send(Msg.Answer(s.round, null, Platform.epochMillis() - s.startedAt, skip = true)) }
    }

    private fun onVerdict(v: Msg.Verdict) {
        val s = _s.value
        if (v.i != s.round) return
        when (v.status) {
            "ok" -> { timer?.cancel(); _s.update { it.copy(phase = Phase.LOCKED) } }
            "verifying" -> _s.update { it.copy(attempts = it.attempts.upsert(v.text, AttemptStatus.VERIFYING, v.choices)) }
            "confirm" -> _s.update { st ->
                val k = Norm.key(v.text)
                st.copy(attempts = st.attempts.filter { Norm.key(it.text) != k }, confirm = Confirm(v.text, v.suggestion ?: v.text, v.more.filter { it != v.suggestion }))
            }
            "spam" -> _s.update { it.copy(attempts = it.attempts.upsert(v.text, AttemptStatus.REJECTED, emptyList(), spam = true),
                missTick = it.missTick + 1, spam = it.spam + 1) }
            "offline" -> _s.update { it.copy(attempts = it.attempts.upsert(v.text, AttemptStatus.REJECTED, v.choices, offline = true),
                missTick = it.missTick + 1) }
            else -> _s.update { it.copy(attempts = it.attempts.upsert(v.text, AttemptStatus.REJECTED, v.choices), missTick = it.missTick + 1) }
        }
    }

    /** A piece of the round's full answer list: added to the reveal (and the kept round). */
    private fun onAnswers(m: Msg.Answers) = _s.update { st ->
        val v = st.reveal
        if (v == null || v.index != m.i || m.groups.isEmpty()) st else {
            val nv = v.withAll(Views.merge(v.all, m.groups))
            st.copy(reveal = nv, rounds = st.rounds.map { if (it === v) nv else it })
        }
    }

    private fun reveal(m: Msg.Result) {
        timer?.cancel()
        val s = _s.value
        val view = RoundView(m.i, null, s.prompt, s.topic, s.listSize, m.guest, m.host, m.ladder)
        _s.update { it.copy(phase = Phase.REVEAL, reveal = view, rounds = it.rounds + view, message = null) }
    }

    override fun next() {
        val s = _s.value
        if (s.phase != Phase.REVEAL) return
        _s.update { it.copy(message = "ready. awaiting host…") }
        scope.launch { link.send(Msg.Ready(s.round)) }
    }

    override fun quit() {
        timer?.cancel()
        listener?.cancel()
        scope.launch {
            link.send(Msg.Bye("guest quit"))
            link.close()
        }
    }
}

/** Label for the outcome of a 1v1 round from my side. */
fun duelVerdict(v: RoundView): String {
    val o = v.opp ?: return ""
    return when {
        v.me.points > o.points -> "YOU TAKE THE ROUND"
        v.me.points < o.points -> "${o.player.uppercase()} TAKES THE ROUND"
        v.me.tier == Tier.TIMEOUT && o.tier == Tier.TIMEOUT -> "BOTH PROCESSES KILLED"
        else -> "STALEMATE"
    }
}
