package app.coredump.game

import app.coredump.core.Norm
import app.coredump.core.Prompt
import app.coredump.core.Tier
import app.coredump.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.pow
import kotlin.random.Random

/*
 * MELTDOWN — the battle royale. 2–8 players sit round an overheating core. One holds it: they must name an answer
 * for the category that nobody has said yet in this round; a right answer passes the core on. Every turn has its
 * own visible countdown ([MeltConfig.turnSec], set by the host): whoever still holds the core at 0 melts down and
 * loses a heart (and a new category starts). Everyone starts with 2 hearts (3 at most). Rare answers charge a meter
 * (common +1 … rarest +6): at 10 you win a heart back. Out at 0 hearts; the last one standing wins.
 * Answers not on the list are checked online by the host (as in solo); a miss comes back with "did you mean".
 */

enum class MeltPhase { LOBBY, COUNTDOWN, TURN, BLAST, OVER }

/** Rules the host sets: seconds per turn, the categories' topics (empty = all), bot skill (0 easy … 2 hard). */
data class MeltConfig(val turnSec: Int = 10, val topics: Set<String> = emptySet(), val botLevel: Int = 1)

data class MeltPlayer(
    val id: String,
    val name: String,
    val bot: Boolean,
    val hearts: Int = Meltdown.START_HEARTS,
    /** Rare-answer meter, 0 until [Meltdown.CHARGE] (then a heart). */
    val charge: Int = 0,
    /** Finishing place (1 = winner), 0 while still in. */
    val place: Int = 0,
    /** Answers given in the whole game. */
    val answers: Int = 0,
    /** Shown on the player: rating, current win streak, MELTDOWN wins (0 = unknown). */
    val elo: Int = 0,
    val streak: Int = 0,
    val wins: Int = 0,
) {
    val out: Boolean get() = hearts <= 0
}

data class MeltUsed(val name: String, val tier: Tier, val by: String)

/** The result of one of my attempts: kind ok | miss | dup | wait | check; [suggestions] for a miss. */
data class MeltVerdict(val text: String, val kind: String, val suggestions: List<String> = emptyList())

data class MeltState(
    val phase: MeltPhase = MeltPhase.LOBBY,
    val players: List<MeltPlayer> = emptyList(),
    /** Id of the player holding the core. */
    val holder: String = "",
    val prompt: String = "",
    val topic: String = "",
    /** Answers on the list. */
    val size: Int = 0,
    /** Said in this category, newest first. */
    val used: List<MeltUsed> = emptyList(),
    /** How far this turn's countdown has burnt, 0..1 (the core swells and glows with it). */
    val heat: Float = 0f,
    /** This turn's length and what is left of it (ms). */
    val turnMs: Int = 10_000,
    val leftMs: Int = 10_000,
    val round: Int = 0,
    val countdown: Int = 0,
    /** What just happened: kind = ok | miss | dup | blast | heart | out | win | check; [by] = player id. */
    val event: String = "",
    val eventKind: String = "",
    val eventBy: String = "",
    val eventTier: Tier? = null,
    val winner: String = "",
    /** Bumped on every change (guests ignore older snapshots). */
    val seq: Int = 0,
    /** Bumped on every new event (so the same event text twice still shows twice). */
    val evn: Int = 0,
    /** Topics the host picked (empty = all), shown in the lobby. */
    val topics: List<String> = emptyList(),
) {
    fun player(id: String) = players.firstOrNull { it.id == id }
    val alive: List<MeltPlayer> get() = players.filter { !it.out }
    /** Whole seconds left in this turn (shown on the core). */
    val secondsLeft: Int get() = (leftMs + 999) / 1000
}

/** One game being played on this device: the host's own game, or a guest's mirror of the host's. */
interface MeltSession {
    val state: StateFlow<MeltState>
    val myId: String
    /** My answers' results. */
    val verdicts: SharedFlow<MeltVerdict>
    val online: Boolean
    fun submit(text: String)
    fun leave()
}

/** An answer checked online by the host: shown [name], its [tier]; [entry] = index in the list when it is there under another name. */
data class MeltOnline(val name: String, val tier: Tier, val entry: Int? = null)

object Meltdown {
    const val START_HEARTS = 2
    const val MAX_HEARTS = 3
    const val CHARGE = 10
    const val MAX_PLAYERS = 8
    val TURN_CHOICES = listOf(5, 7, 10, 12, 15, 20)
    val botNames = listOf("BYTE", "NULLPTR", "SEGV", "DAEMON", "PANIC", "SUDO", "CRON", "KERNEL", "MALLOC", "FORK", "STACK", "GLITCH")

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(s: MeltState): String = buildJsonObject {
        put("ph", s.phase.name); put("h", s.holder); put("q", s.prompt); put("tp", s.topic); put("n", s.size)
        put("heat", (s.heat * 1000).toInt() / 1000f); put("tm", s.turnMs); put("lm", s.leftMs); put("r", s.round); put("cd", s.countdown)
        put("ev", s.event); put("ek", s.eventKind); put("eb", s.eventBy); s.eventTier?.let { put("et", it.name) }
        put("w", s.winner); put("seq", s.seq); put("evn", s.evn)
        put("tps", JsonArray(s.topics.map { JsonPrimitive(it) }))
        put("p", JsonArray(s.players.map { p ->
            buildJsonObject {
                put("i", p.id); put("n", p.name); put("b", p.bot); put("h", p.hearts); put("c", p.charge); put("pl", p.place); put("a", p.answers)
                if (p.elo != 0) put("e", p.elo)
                if (p.streak != 0) put("s", p.streak)
                if (p.wins != 0) put("wn", p.wins)
            }
        }))
        put("u", JsonArray(s.used.take(40).map { u -> buildJsonObject { put("n", u.name); put("t", u.tier.name); put("b", u.by) } }))
    }.toString()

    fun decode(text: String): MeltState? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun i(k: String) = o[k]?.jsonPrimitive?.intOrNull ?: 0
        MeltState(
            phase = runCatching { MeltPhase.valueOf(s("ph")) }.getOrDefault(MeltPhase.LOBBY),
            holder = s("h"), prompt = s("q"), topic = s("tp"), size = i("n"),
            heat = o["heat"]?.jsonPrimitive?.floatOrNull ?: 0f, turnMs = o["tm"]?.jsonPrimitive?.intOrNull ?: 10_000,
            leftMs = o["lm"]?.jsonPrimitive?.intOrNull ?: 10_000, round = i("r"), countdown = i("cd"),
            event = s("ev"), eventKind = s("ek"), eventBy = s("eb"), eventTier = Tier.byName(s("et")),
            winner = s("w"), seq = i("seq"), evn = i("evn"),
            topics = o["tps"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            players = o["p"]?.jsonArray?.mapNotNull { el ->
                val p = el as? JsonObject ?: return@mapNotNull null
                MeltPlayer(p.str("i"), p.str("n").take(24), p["b"]?.jsonPrimitive?.booleanOrNull ?: false,
                    p["h"]?.jsonPrimitive?.intOrNull ?: 0, p["c"]?.jsonPrimitive?.intOrNull ?: 0,
                    p["pl"]?.jsonPrimitive?.intOrNull ?: 0, p["a"]?.jsonPrimitive?.intOrNull ?: 0,
                    p["e"]?.jsonPrimitive?.intOrNull ?: 0, p["s"]?.jsonPrimitive?.intOrNull ?: 0, p["wn"]?.jsonPrimitive?.intOrNull ?: 0)
            }.orEmpty().take(MAX_PLAYERS),
            used = o["u"]?.jsonArray?.mapNotNull { el ->
                val u = el as? JsonObject ?: return@mapNotNull null
                MeltUsed(u.str("n"), Tier.byName(u.str("t")) ?: Tier.HELLO_WORLD, u.str("b"))
            }.orEmpty(),
        )
    }.getOrNull()

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun newId(): String = (1..8).map { "abcdefghijkmnpqrstuvwxyz23456789"[Random.nextInt(32)] }.joinToString("")

    /** A bot's shown rating by skill. */
    fun botElo(level: Int): Int = when (level) { 0 -> 850; 2 -> 1250; else -> 1050 }
}

/**
 * The game itself (runs on the host device): turns and their countdowns, bots, hearts. [pickPrompt] gives the next
 * category. [checkOnline] (optional) looks up an answer that is not on the list (the host's network; null = not real).
 * Verdicts go to [verdicts] as (player id, verdict).
 */
class MeltdownGame(
    private val scope: CoroutineScope,
    players: List<MeltPlayer>,
    private val pickPrompt: () -> Prompt?,
    private val config: MeltConfig = MeltConfig(),
    private val checkOnline: (suspend (Prompt, String) -> MeltOnline?)? = null,
) {
    private val botLevel = config.botLevel
    private val turnMs = config.turnSec.coerceIn(3, 60) * 1000
    private val _s = MutableStateFlow(MeltState(players = players, turnMs = turnMs, leftMs = turnMs, topics = config.topics.sorted()))
    val state: StateFlow<MeltState> = _s.asStateFlow()
    private val _verdicts = MutableSharedFlow<Pair<String, MeltVerdict>>(extraBufferCapacity = 32)
    val verdicts: SharedFlow<Pair<String, MeltVerdict>> = _verdicts.asSharedFlow()

    private var prompt: Prompt? = null
    private val usedKeys = HashSet<Int>()
    /** Names said in this category (compact form), so an online answer said twice is caught too. */
    private val usedNames = HashSet<String>()
    private var left = 0L
    /** Clock time (ms) when the bot holding the core answers; -1 = it is stuck this turn. */
    private var botDue = -1L
    /** Players with an online check under way (one at a time each). */
    private val checking = HashSet<String>()
    private var job: Job? = null
    private val rnd = Random(Platform.epochMillis())

    private fun set(f: (MeltState) -> MeltState) { _s.value = f(_s.value).let { it.copy(seq = it.seq + 1) } }
    private fun verdict(id: String, v: MeltVerdict) { _verdicts.tryEmit(id to v) }
    private fun compact(s: String) = Norm.compact(Norm.key(s))

    fun start() {
        if (job != null) return
        job = scope.launch {
            for (c in 3 downTo 1) { set { it.copy(phase = MeltPhase.COUNTDOWN, countdown = c, event = "", eventKind = "") }; delay(900) }
            val alive = _s.value.alive
            if (alive.isEmpty()) return@launch
            newCategory(alive[rnd.nextInt(alive.size)].id)
            var last = Platform.epochMillis()
            var lastPush = 0L
            while (isActive) {
                delay(100)
                val now = Platform.epochMillis()
                val dt = (now - last).coerceIn(0L, 500L)
                last = now
                val s = _s.value
                if (s.phase != MeltPhase.TURN) continue
                left -= dt
                if (left <= 0L) { blast(); continue }
                val h = s.player(s.holder)
                if (h != null && h.bot && botDue in 1..now) botAnswer(h)
                // the countdown goes out 4 times a second (guests show whole seconds and the core's heat)
                if (now - lastPush >= 250) {
                    lastPush = now
                    set { it.copy(leftMs = left.toInt().coerceAtLeast(0), heat = (1f - left.toFloat() / turnMs).coerceIn(0f, 1f)) }
                }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /** A player gives up / disconnects: out at once. */
    fun drop(id: String) {
        val s = _s.value
        val p = s.player(id) ?: return
        if (p.out || s.phase == MeltPhase.OVER) return
        val place = s.alive.size
        set { st -> st.copy(players = st.players.map { if (it.id == id) it.copy(hearts = 0, place = place) else it },
            event = "${p.name} left", eventKind = "out", eventBy = id, evn = st.evn + 1) }
        if (_s.value.holder == id && _s.value.phase == MeltPhase.TURN) passFrom(id)
        checkOver()
    }

    private fun newCategory(holder: String) {
        val p = pickPrompt()
        if (p == null) { set { it.copy(phase = MeltPhase.OVER, event = "no lists to play", eventKind = "win", evn = it.evn + 1) }; return }
        prompt = p
        usedKeys.clear()
        usedNames.clear()
        left = turnMs.toLong()
        set { it.copy(phase = MeltPhase.TURN, holder = holder, prompt = p.text, topic = p.topic, size = p.size, used = emptyList(),
            heat = 0f, leftMs = turnMs, round = it.round + 1, event = "", eventKind = "") }
        scheduleBot()
    }

    /** Next player still in, after [id] in seating order. */
    private fun nextAfter(id: String): String? {
        val ps = _s.value.players
        val i = ps.indexOfFirst { it.id == id }
        for (k in 1..ps.size) {
            val p = ps[(i + k).mod(ps.size)]
            if (!p.out && p.id != id) return p.id
        }
        return null
    }

    /** The core passes on; the next holder gets a full turn. */
    private fun passFrom(id: String) {
        val n = nextAfter(id) ?: return
        left = turnMs.toLong()
        set { it.copy(holder = n, leftMs = turnMs, heat = 0f) }
        scheduleBot()
    }

    private fun scheduleBot() {
        val s = _s.value
        val h = s.player(s.holder) ?: return
        if (!h.bot) { botDue = -1L; return }
        val p = prompt ?: return
        // answer time as a share of the turn: easy bots are slow, hard ones quick
        val (lo, hi) = when (botLevel) { 0 -> 0.35f to 0.9f; 2 -> 0.1f to 0.4f; else -> 0.2f to 0.7f }
        val crowd = usedKeys.size.toFloat() / p.size.coerceAtLeast(1)
        val stuck = (0.04f + 0.55f * crowd) * when (botLevel) { 0 -> 1.6f; 2 -> 0.45f; else -> 1f }
        val at = (turnMs * (lo + rnd.nextFloat() * (hi - lo))).toLong()
        botDue = if (rnd.nextFloat() < stuck) -1L else Platform.epochMillis() + at
    }

    private fun botAnswer(bot: MeltPlayer) {
        val p = prompt ?: return
        // mostly familiar answers, now and then a deep cut (harder bots dig deeper)
        val power = when (botLevel) { 0 -> 3.2; 2 -> 1.5; else -> 2.2 }
        var i = (p.size * rnd.nextDouble().pow(power)).toInt().coerceIn(0, p.size - 1)
        var tries = 0
        while ((i in usedKeys || compact(p.entries[i].name) in usedNames) && tries < p.size) { i = (i + 1) % p.size; tries++ }
        if (i in usedKeys) { botDue = -1L; return }
        accept(bot, p.entries[i].name, p.entries[i].tier, i)
    }

    /** A player's attempt (only the holder's counts). */
    fun submit(id: String, text: String) {
        val s = _s.value
        val p = prompt
        if (s.phase != MeltPhase.TURN || s.holder != id || p == null) { verdict(id, MeltVerdict(text, "wait")); return }
        val who = s.player(id) ?: return
        val input = text.trim().take(80)
        if (input.isEmpty() || id in checking) return
        val hit = p.index.lookup(input, if (input.length >= 8) 2 else 1)
        if (hit != null) {
            val e = p.entries[hit.entry]
            if (hit.entry in usedKeys || compact(e.name) in usedNames) { dup(who, e.name, input); return }
            verdict(id, MeltVerdict(input, "ok"))
            accept(who, e.name, e.tier, hit.entry)
            return
        }
        val check = checkOnline
        if (check == null || compact(input).length < 3) { miss(who, p, input); return }
        // not on the list: the host checks it online while the clock keeps running
        checking += id
        val round = s.round
        verdict(id, MeltVerdict(input, "check"))
        set { it.copy(event = "${who.name}: $input … checking", eventKind = "check", eventBy = id, eventTier = null, evn = it.evn + 1) }
        scope.launch {
            val r = runCatching { check(p, input) }.getOrNull()
            checking -= id
            val now = _s.value
            // too late: the turn passed or the core blew meanwhile
            if (now.phase != MeltPhase.TURN || now.holder != id || now.round != round || prompt !== p) return@launch
            when {
                r == null -> miss(who, p, input)
                (r.entry != null && r.entry in usedKeys) || compact(r.name) in usedNames -> dup(who, r.name, input)
                else -> { verdict(id, MeltVerdict(input, "ok")); accept(who, r.name, r.tier, r.entry) }
            }
        }
    }

    private fun miss(who: MeltPlayer, p: Prompt, input: String) {
        val sug = p.index.suggestions(input, 3).map { p.entries[it].name }
            .filter { compact(it) !in usedNames }
        set { it.copy(event = "${who.name}: $input ✗", eventKind = "miss", eventBy = who.id, eventTier = null, evn = it.evn + 1) }
        verdict(who.id, MeltVerdict(input, "miss", sug))
    }

    private fun dup(who: MeltPlayer, name: String, input: String) {
        set { it.copy(event = "${who.name}: $name — already said", eventKind = "dup", eventBy = who.id, eventTier = null, evn = it.evn + 1) }
        verdict(who.id, MeltVerdict(input, "dup"))
    }

    private fun accept(who: MeltPlayer, name: String, tier: Tier, entry: Int?) {
        if (entry != null) usedKeys += entry
        usedNames += compact(name)
        val gain = tier.heat.coerceAtLeast(1)
        var bonus = false
        set { st ->
            st.copy(
                players = st.players.map { pl ->
                    if (pl.id != who.id) pl else {
                        var c = pl.charge + gain
                        var h = pl.hearts
                        if (c >= Meltdown.CHARGE) { c -= Meltdown.CHARGE; if (h < Meltdown.MAX_HEARTS) { h++; bonus = true } else c = Meltdown.CHARGE - 1 }
                        pl.copy(charge = c, hearts = h, answers = pl.answers + 1)
                    }
                },
                used = listOf(MeltUsed(name, tier, who.id)) + st.used,
                event = "${who.name}: $name" + if (bonus) "  +1 ♥" else "",
                eventKind = if (bonus) "heart" else "ok", eventBy = who.id, eventTier = tier, evn = st.evn + 1,
            )
        }
        passFrom(who.id)
    }

    private suspend fun blast() {
        val s = _s.value
        val victim = s.player(s.holder) ?: return
        val hearts = victim.hearts - 1
        val place = if (hearts <= 0) s.alive.size else 0
        set { st ->
            st.copy(phase = MeltPhase.BLAST, heat = 1f, leftMs = 0,
                players = st.players.map { if (it.id == victim.id) it.copy(hearts = hearts.coerceAtLeast(0), place = place) else it },
                event = if (hearts <= 0) "MELTDOWN — ${victim.name} is out" else "MELTDOWN — ${victim.name} loses a heart",
                eventKind = "blast", eventBy = victim.id, eventTier = null, evn = st.evn + 1)
        }
        delay(2_600)
        if (checkOver()) return
        val next = if (hearts > 0) nextAfter(victim.id) ?: victim.id else nextAfter(victim.id) ?: return
        newCategory(next)
    }

    /** True (and OVER) when one player is left. */
    private fun checkOver(): Boolean {
        val s = _s.value
        val alive = s.alive
        if (alive.size > 1 || s.phase == MeltPhase.OVER) return s.phase == MeltPhase.OVER
        val w = alive.firstOrNull()
        set { st -> st.copy(phase = MeltPhase.OVER, winner = w?.id.orEmpty(),
            players = st.players.map { if (it.id == w?.id) it.copy(place = 1) else it },
            event = if (w != null) "${w.name} wins" else "nobody survived", eventKind = "win", eventBy = w?.id.orEmpty(), evn = st.evn + 1) }
        stop()
        return true
    }
}

/** Playing on this device (bots only, or as the party host): the host's own seat in [game]. */
class LocalMelt(scope: CoroutineScope, override val myId: String, val game: MeltdownGame, override val online: Boolean = false,
                private val onLeave: () -> Unit = {}) : MeltSession {
    override val state: StateFlow<MeltState> get() = game.state
    private val _v = MutableSharedFlow<MeltVerdict>(extraBufferCapacity = 16)
    override val verdicts: SharedFlow<MeltVerdict> = _v.asSharedFlow()
    // pass on my own verdicts only
    private val relay: Job = scope.launch { game.verdicts.collect { (id, v) -> if (id == myId) _v.tryEmit(v) } }

    override fun submit(text: String) = game.submit(myId, text)
    override fun leave() { game.drop(myId); game.stop(); relay.cancel(); onLeave() }
}
