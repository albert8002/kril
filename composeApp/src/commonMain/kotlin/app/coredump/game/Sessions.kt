package app.coredump.game

import app.coredump.core.GameConfig
import app.coredump.core.Judge
import app.coredump.core.Judgement
import app.coredump.core.Norm
import app.coredump.core.Prompt
import app.coredump.core.RoundView
import app.coredump.core.Tier
import app.coredump.core.Views
import app.coredump.data.History
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

/** One rejected (or still being checked) answer in the current round. */
data class Attempt(val text: String, val status: AttemptStatus, val suggestion: String?)

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
    /** Local prompt for a round (null for a guest: the host owns the lists). */
    fun promptAt(index: Int): Prompt?
}

/** Judges attempts: local hash/fuzzy lookup first, then an online Wikidata check for unlisted answers. */
class Arbiter(private val bank: QuestionBank?, private val maxEdit: Int, private val online: Boolean) {
    sealed interface Local {
        class Hit(val j: Judgement) : Local
        class Miss(val suggestion: String?, val canVerify: Boolean) : Local
    }

    fun local(p: Prompt, text: String): Local {
        val j = Judge.judge(p, text, maxEdit)
        if (j.tier.isValid) return Local.Hit(j)
        val sug = p.index.suggest(text)?.let { p.entries[it.entry].name }
        return Local.Miss(sug, online && bank != null && p.check != null)
    }

    suspend fun remote(p: Prompt, text: String): Judgement? =
        bank?.verify(p, text)?.let { Judge.verified(text, it.label, it.links, p) }
}

private const val GRACE_MS = 2_500L
private const val VERIFY_SLACK_MS = 6_500L

private fun List<Attempt>.upsert(text: String, status: AttemptStatus, sug: String?): List<Attempt> {
    val k = Norm.key(text)
    val i = indexOfFirst { Norm.key(it.text) == k }
    val a = Attempt(text, status, sug)
    return if (i < 0) this + a else toMutableList().also { it[i] = a }
}

private fun List<Attempt>.has(text: String): Boolean {
    val k = Norm.key(text)
    return any { Norm.key(it.text) == k }
}

private fun timeoutJudgement(attempts: List<Attempt>) =
    Judgement(attempts.lastOrNull()?.text.orEmpty(), null, Tier.TIMEOUT, -1)

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

    override fun promptAt(index: Int): Prompt? = prompts.getOrNull(index)

    override fun start() = begin(0)

    private fun begin(i: Int) {
        val p = prompts[i]
        expired = false; pending = 0
        _s.update {
            it.copy(phase = Phase.TYPING, round = i, prompt = p.text, topic = p.topic, listSize = p.size,
                seconds = cfg.secondsPerRound, startedAt = Platform.epochMillis(), reveal = null, attempts = emptyList(), paused = false)
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
        if (pending == 0) finish(timeoutJudgement(_s.value.attempts))
        else scope.launch {
            val r = _s.value.round
            delay(VERIFY_SLACK_MS)
            if (_s.value.phase == Phase.TYPING && _s.value.round == r) finish(timeoutJudgement(_s.value.attempts))
        }
    }

    override fun submit(text: String) {
        val s = _s.value
        val t = text.trim()
        if (s.phase != Phase.TYPING || s.paused || expired || t.isEmpty()) return
        if (s.attempts.has(t)) { _s.update { it.copy(missTick = it.missTick + 1) }; return }
        val p = prompts[s.round]
        when (val r = arbiter.local(p, t)) {
            is Arbiter.Local.Hit -> finish(r.j)
            is Arbiter.Local.Miss -> if (r.canVerify) {
                _s.update { it.copy(attempts = it.attempts.upsert(t, AttemptStatus.VERIFYING, r.suggestion)) }
                pending++
                val round = s.round
                scope.launch {
                    val j = arbiter.remote(p, t)
                    pending--
                    onRemote(round, t, j, r.suggestion)
                }
            } else {
                _s.update { it.copy(attempts = it.attempts.upsert(t, AttemptStatus.REJECTED, r.suggestion), missTick = it.missTick + 1) }
            }
        }
    }

    private fun onRemote(round: Int, t: String, j: Judgement?, sug: String?) {
        val s = _s.value
        if (s.round != round || s.phase != Phase.TYPING) return
        if (j != null) { finish(j); return }
        _s.update { it.copy(attempts = it.attempts.upsert(t, AttemptStatus.REJECTED, sug), missTick = it.missTick + 1) }
        if (expired && pending == 0) finish(timeoutJudgement(_s.value.attempts))
    }

    private fun finish(j: Judgement) {
        timer?.cancel()
        val s = _s.value
        val p = prompts[s.round]
        val elapsed = (Platform.epochMillis() - s.startedAt).coerceAtMost(if (s.seconds > 0) s.seconds * 1000L else Long.MAX_VALUE)
        val view = RoundView(s.round, p.id, p.text, p.topic, p.size, Views.play(name, j, elapsed), null, Views.ladder(p))
        _s.update { it.copy(phase = Phase.REVEAL, reveal = view, rounds = it.rounds + view) }
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
                daily = st.daily, topics = prompts.map { it.topic })
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
    private val timers = ArrayList<Job>()
    private var listener: Job? = null

    override fun promptAt(index: Int): Prompt? = prompts.getOrNull(index)

    override fun start() {
        listener = scope.launch {
            for (m in link.incoming) when (m) {
                is Msg.Hello -> link.send(Msg.Welcome(name))
                is Msg.Attempt -> onGuestAttempt(m)
                is Msg.Answer -> if (m.i == _s.value.round && oppJ == null) {
                    oppJ = Judgement(oppAttempts.lastOrNull().orEmpty(), null, Tier.TIMEOUT, -1)
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
        timers.forEach { it.cancel() }; timers.clear()
        _s.update {
            it.copy(phase = Phase.TYPING, round = i, prompt = p.text, topic = p.topic, listSize = p.size,
                seconds = cfg.secondsPerRound, startedAt = Platform.epochMillis(), reveal = null, oppLocked = false, attempts = emptyList())
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
        if (myPending == 0) lockMine(timeoutJudgement(_s.value.attempts))
    }

    private fun lockMine(j: Judgement) {
        if (myJ != null) return
        myJ = j
        myMs = Platform.epochMillis() - _s.value.startedAt
        val r = _s.value.round
        _s.update { it.copy(phase = Phase.LOCKED) }
        scope.launch { link.send(Msg.Locked(r)) }
        maybeResolve()
    }

    override fun submit(text: String) {
        val s = _s.value
        val t = text.trim()
        if (s.phase != Phase.TYPING || myJ != null || myExpired || t.isEmpty()) return
        if (s.attempts.has(t)) { _s.update { it.copy(missTick = it.missTick + 1) }; return }
        val p = prompts[s.round]
        when (val r = arbiter.local(p, t)) {
            is Arbiter.Local.Hit -> lockMine(r.j)
            is Arbiter.Local.Miss -> if (r.canVerify) {
                _s.update { it.copy(attempts = it.attempts.upsert(t, AttemptStatus.VERIFYING, r.suggestion)) }
                myPending++
                val round = s.round
                scope.launch {
                    val j = arbiter.remote(p, t)
                    myPending--
                    if (_s.value.round != round || myJ != null) return@launch
                    if (j != null) lockMine(j) else {
                        _s.update { it.copy(attempts = it.attempts.upsert(t, AttemptStatus.REJECTED, r.suggestion), missTick = it.missTick + 1) }
                        if (myExpired && myPending == 0) lockMine(timeoutJudgement(_s.value.attempts))
                    }
                }
            } else _s.update { it.copy(attempts = it.attempts.upsert(t, AttemptStatus.REJECTED, r.suggestion), missTick = it.missTick + 1) }
        }
    }

    private fun onGuestAttempt(m: Msg.Attempt) {
        val s = _s.value
        if (m.i != s.round || oppJ != null || (s.phase != Phase.TYPING && s.phase != Phase.LOCKED)) return
        val t = m.text.trim()
        if (t.isEmpty()) return
        oppAttempts += t
        val p = prompts[s.round]
        when (val r = arbiter.local(p, t)) {
            is Arbiter.Local.Hit -> acceptGuest(r.j, m)
            is Arbiter.Local.Miss -> if (r.canVerify) {
                scope.launch {
                    link.send(Msg.Verdict(m.i, t, "verifying", r.suggestion))
                    val j = arbiter.remote(p, t)
                    if (_s.value.round != m.i || oppJ != null) return@launch
                    if (j != null) acceptGuest(j, m) else link.send(Msg.Verdict(m.i, t, "miss", r.suggestion))
                }
            } else scope.launch { link.send(Msg.Verdict(m.i, t, "miss", r.suggestion)) }
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
        val me = Views.play(name, myJ ?: timeoutJudgement(s.attempts), if (myJ != null) myMs else full)
        val opp = Views.play(s.oppName ?: "PEER", oppJ ?: Judgement(oppAttempts.lastOrNull().orEmpty(), null, Tier.TIMEOUT, -1),
            if (oppJ != null) oppMs else full)
        val ladder = Views.ladder(p)
        val view = RoundView(s.round, p.id, p.text, p.topic, p.size, me, opp, ladder)
        _s.update { it.copy(phase = Phase.REVEAL, reveal = view, rounds = it.rounds + view, oppLocked = true) }
        scope.launch { link.send(Msg.Result(s.round, me, opp, ladder)) }
    }

    override fun next() {
        val s = _s.value
        if (s.phase != Phase.REVEAL) return
        if (s.isLast) {
            _s.update { it.copy(phase = Phase.OVER) }
            val st = _s.value
            History.recordRun(prompts.map { it.id }, st.rounds.map { it.me.tier }, mode = "1v1", daily = null,
                topics = prompts.map { it.topic }, opponent = st.oppName, oppPoints = st.oppTotal)
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
                Msg.Over -> {
                    _s.update { it.copy(phase = Phase.OVER) }
                    val st = _s.value
                    History.recordRun(emptyList(), st.rounds.map { it.me.tier }, mode = "1v1", daily = null,
                        topics = st.rounds.map { it.topic }, opponent = st.oppName, oppPoints = st.oppTotal)
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
                reveal = null, oppLocked = false, message = null, attempts = emptyList())
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

    override fun submit(text: String) {
        val s = _s.value
        val t = text.trim()
        if (s.phase != Phase.TYPING || t.isEmpty()) return
        if (s.attempts.has(t)) { _s.update { it.copy(missTick = it.missTick + 1) }; return }
        _s.update { it.copy(attempts = it.attempts.upsert(t, AttemptStatus.VERIFYING, null)) }
        scope.launch { link.send(Msg.Attempt(s.round, t, Platform.epochMillis() - s.startedAt)) }
    }

    private fun onVerdict(v: Msg.Verdict) {
        val s = _s.value
        if (v.i != s.round) return
        when (v.status) {
            "ok" -> { timer?.cancel(); _s.update { it.copy(phase = Phase.LOCKED) } }
            "verifying" -> _s.update { it.copy(attempts = it.attempts.upsert(v.text, AttemptStatus.VERIFYING, v.suggestion)) }
            else -> _s.update { it.copy(attempts = it.attempts.upsert(v.text, AttemptStatus.REJECTED, v.suggestion), missTick = it.missTick + 1) }
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
        v.me.points > o.points -> "YOU WENT DEEPER"
        v.me.points < o.points -> "${o.player.uppercase()} WENT DEEPER"
        v.me.tier == Tier.TIMEOUT && o.tier == Tier.TIMEOUT -> "BOTH PROCESSES KILLED"
        else -> "STALEMATE"
    }
}
