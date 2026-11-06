package app.coredump.game

import app.coredump.core.GameConfig
import app.coredump.core.Judge
import app.coredump.core.PlayView
import app.coredump.core.Prompt
import app.coredump.core.RoundView
import app.coredump.core.Tier
import app.coredump.core.Views
import app.coredump.data.History
import app.coredump.net.LanLink
import app.coredump.net.Msg
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

/**
 * round: 0-based index of the current prompt. total: prompts in the game (count).
 * seconds: limit for this prompt (s), 0 = none. startedAt: epoch ms when the prompt opened.
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
) {
    val myTotal: Int get() = rounds.sumOf { it.me.points }
    val oppTotal: Int get() = rounds.sumOf { it.opp?.points ?: 0 }
    val isLast: Boolean get() = round + 1 >= total
}

interface MatchSession {
    val state: StateFlow<MatchState>
    val isMulti: Boolean
    fun start()
    fun submit(text: String?)
    fun next()
    fun quit()
    /** Local prompt for a round (null for a guest: the host owns the lists). */
    fun promptAt(index: Int): Prompt?
}

private const val GRACE_MS = 2_500L

// ------------------------------------------------------------------ solo

class SoloSession(
    private val prompts: List<Prompt>,
    private val cfg: GameConfig,
    private val name: String,
    private val scope: CoroutineScope,
) : MatchSession {
    private val _s = MutableStateFlow(MatchState(myName = name, total = prompts.size))
    override val state: StateFlow<MatchState> = _s.asStateFlow()
    override val isMulti = false
    private var timer: Job? = null

    override fun promptAt(index: Int): Prompt? = prompts.getOrNull(index)

    override fun start() = begin(0)

    private fun begin(i: Int) {
        val p = prompts[i]
        _s.update {
            it.copy(phase = Phase.TYPING, round = i, prompt = p.text, topic = p.topic, listSize = p.size,
                seconds = cfg.secondsPerRound, startedAt = Platform.epochMillis(), reveal = null)
        }
        timer?.cancel()
        if (cfg.secondsPerRound > 0) {
            timer = scope.launch {
                delay(cfg.secondsPerRound * 1000L)
                submit(null)
            }
        }
    }

    override fun submit(text: String?) {
        val s = _s.value
        if (s.phase != Phase.TYPING) return
        timer?.cancel()
        val p = prompts[s.round]
        val j = Judge.judge(p, text, cfg.difficulty.maxEdit)
        val view = RoundView(s.round, p.id, p.text, p.topic, p.size,
            Views.play(name, j, Platform.epochMillis() - s.startedAt), null, Views.ladder(p))
        _s.update { it.copy(phase = Phase.REVEAL, reveal = view, rounds = it.rounds + view) }
    }

    override fun next() {
        val s = _s.value
        if (s.phase != Phase.REVEAL) return
        if (s.isLast) {
            _s.update { it.copy(phase = Phase.OVER) }
            History.record(prompts.map { it.id }, _s.value.myTotal)
        } else begin(s.round + 1)
    }

    override fun quit() { timer?.cancel() }
}

// ------------------------------------------------------------------ 1v1 host

class HostSession(
    private val link: LanLink,
    private val prompts: List<Prompt>,
    private val cfg: GameConfig,
    private val name: String,
    oppName: String,
    private val scope: CoroutineScope,
) : MatchSession {
    private val _s = MutableStateFlow(MatchState(myName = name, oppName = oppName, total = prompts.size))
    override val state: StateFlow<MatchState> = _s.asStateFlow()
    override val isMulti = true
    private var myAnswer: Msg.Answer? = null
    private var oppAnswer: Msg.Answer? = null
    private var timers = ArrayList<Job>()
    private var listener: Job? = null

    override fun promptAt(index: Int): Prompt? = prompts.getOrNull(index)

    override fun start() {
        listener = scope.launch {
            for (m in link.incoming) when (m) {
                is Msg.Answer -> if (m.i == _s.value.round && oppAnswer == null) {
                    oppAnswer = m
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
        myAnswer = null; oppAnswer = null
        timers.forEach { it.cancel() }; timers.clear()
        _s.update {
            it.copy(phase = Phase.TYPING, round = i, prompt = p.text, topic = p.topic, listSize = p.size,
                seconds = cfg.secondsPerRound, startedAt = Platform.epochMillis(), reveal = null, oppLocked = false)
        }
        link.send(Msg.Round(i, p.text, p.topic, p.size, cfg.secondsPerRound))
        if (cfg.secondsPerRound > 0) {
            val ms = cfg.secondsPerRound * 1000L
            timers += scope.launch { delay(ms); submit(null) }
            timers += scope.launch { delay(ms + GRACE_MS); resolve() }
        }
    }

    override fun submit(text: String?) {
        val s = _s.value
        if (s.phase != Phase.TYPING) return
        myAnswer = Msg.Answer(s.round, text, Platform.epochMillis() - s.startedAt)
        _s.update { it.copy(phase = Phase.LOCKED) }
        scope.launch { link.send(Msg.Locked(s.round)) }
        maybeResolve()
    }

    private fun maybeResolve() {
        if (myAnswer != null && oppAnswer != null) resolve()
    }

    private fun resolve() {
        val s = _s.value
        if (s.phase != Phase.TYPING && s.phase != Phase.LOCKED) return
        timers.forEach { it.cancel() }
        val p = prompts[s.round]
        val mine = myAnswer
        val theirs = oppAnswer
        val jm = Judge.judge(p, mine?.text, cfg.difficulty.maxEdit)
        val jo = Judge.judge(p, theirs?.text, cfg.difficulty.maxEdit)
        val me = Views.play(name, jm, mine?.elapsedMs ?: (s.seconds * 1000L))
        val opp = Views.play(s.oppName ?: "PEER", jo, theirs?.elapsedMs ?: (s.seconds * 1000L))
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
            History.record(prompts.map { it.id }, _s.value.myTotal)
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
    private val link: LanLink,
    private val name: String,
    hostName: String,
    private val scope: CoroutineScope,
) : MatchSession {
    private val _s = MutableStateFlow(
        MatchState(myName = name, oppName = hostName, canAdvance = false, message = "awaiting host to execute…")
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
                is Msg.Round -> begin(m)
                is Msg.Locked -> if (m.i == _s.value.round) _s.update { it.copy(oppLocked = true) }
                is Msg.Result -> if (m.i == _s.value.round) reveal(m)
                Msg.Over -> {
                    _s.update { it.copy(phase = Phase.OVER) }
                    History.record(emptyList(), _s.value.myTotal)
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
        timer?.cancel()
        _s.update {
            it.copy(phase = Phase.TYPING, round = m.i, total = maxOf(it.total, m.i + 1), prompt = m.text,
                topic = m.topic, listSize = m.size, seconds = m.seconds, startedAt = Platform.epochMillis(),
                reveal = null, oppLocked = false, message = null)
        }
        if (m.seconds > 0) timer = scope.launch { delay(m.seconds * 1000L); submit(null) }
    }

    override fun submit(text: String?) {
        val s = _s.value
        if (s.phase != Phase.TYPING) return
        timer?.cancel()
        _s.update { it.copy(phase = Phase.LOCKED) }
        scope.launch { link.send(Msg.Answer(s.round, text, Platform.epochMillis() - s.startedAt)) }
    }

    private fun reveal(m: Msg.Result) {
        timer?.cancel()
        val me: PlayView = m.guest
        val opp: PlayView = m.host
        val s = _s.value
        val view = RoundView(m.i, null, s.prompt, s.topic, s.listSize, me, opp, m.ladder)
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
