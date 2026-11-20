package app.coredump.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import app.coredump.core.Norm
import app.coredump.platform.Platform
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Easter eggs: little surprises, played over the whole screen by [EggHost].
 *
 * - Tap the logo on the main menu five times quickly (or hold it): each look has its own surprise ([Kind.LOGO]).
 * - TERMINAL: hold the logo and it glitches harder and harder until the whole screen crashes ([Kind.GLITCH]).
 * - Answer "core dump" when asked to name a game: the app takes a bow ([Kind.SELF]).
 * - Answer "<3" (or ♥): love back ([Kind.LOVE]).
 * - Scroll to the very bottom of the theme screen: someone is waiting there ([BottomFriend], in the theme screen).
 * - Every 10th, 25th, 50th … run on file: a little thank-you party on the results ([Kind.MILESTONE]).
 *
 * None of them touches a score: answer eggs are caught before the answer is sent.
 */
object Eggs {
    enum class Kind(val ms: Long) { LOGO(3400), GLITCH(2800), SELF(3200), LOVE(2300), MILESTONE(3200) }

    /** [note]: words for the egg (the milestone: "50 runs"). */
    class Show(val kind: Kind, val style: Style, val seed: Int, val id: Int, val note: String = "")

    var show by mutableStateOf<Show?>(null)
        private set
    /** How hard the logo is being held right now (0..1, TERMINAL glitch). */
    var hold by mutableFloatStateOf(0f)
    /** The whole app's jolt during a crash (px), read by the root layer. */
    var joltX by mutableFloatStateOf(0f)
    var joltY by mutableFloatStateOf(0f)
    /** Eggs found so far this session (for the self-test). */
    var found by mutableIntStateOf(0)
        private set
    private var n = 0

    fun play(kind: Kind, note: String = "") {
        show = Show(kind, Th.style, Random.nextInt(), ++n, note)
        found++
        Sfx.play(if (kind == Kind.GLITCH) Cue.TIMEOUT else Cue.RANK)
    }

    fun done(id: Int) {
        if (show?.id == id) { show = null; joltX = 0f; joltY = 0f }
    }

    /** Is this answer one of the eggs for a prompt with [promptText]? Plays it and returns true (not sent). */
    fun answer(promptText: String, input: String): Boolean {
        val raw = input.trim()
        val k = Norm.compact(Norm.key(raw))
        if (raw == "<3" || raw == "♥" || raw == "❤" || raw == "❤️" || raw == "♡" || raw == "<33") { play(Kind.LOVE); return true }
        if ((k == "coredump" || k == "coredumptrivia") && isGamePrompt(promptText)) { play(Kind.SELF); return true }
        return false
    }

    /** "Name a video game", "Name a board game" … (not a game show). */
    fun isGamePrompt(text: String): Boolean {
        val t = text.lowercase()
        return Regex("\\bgames?\\b").containsMatchIn(t) && "game show" !in t && "gameshow" !in t
    }
}

/**
 * The logo's hidden buttons: every tap boops it (a squash and a little turn, a click a little higher each time);
 * five quick taps, or holding it, play this look's [Eggs.Kind.LOGO] surprise. TERMINAL: holding glitches the logo
 * more and more ([Eggs.hold]) until, after 1.4 s, the whole screen crashes ([Eggs.Kind.GLITCH]).
 */
fun Modifier.logoEgg(): Modifier = composed {
    val haptic = rememberHaptic()
    val scope = rememberCoroutineScope()
    val boop = remember { Animatable(0f) }
    var taps by remember { mutableIntStateOf(0) }
    var last by remember { mutableLongStateOf(0L) }
    var holding by remember { mutableStateOf<Job?>(null) }
    var fired by remember { mutableStateOf(false) }
    this
        .graphicsLayer {
            val b = boop.value
            scaleX = 1f + 0.07f * b
            scaleY = 1f - 0.06f * b
            rotationZ = 3f * b * (if (taps % 2 == 0) 1f else -1f)
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    val style = Th.style
                    holding?.cancel()
                    holding = scope.launch {
                        delay(350)
                        if (style == Style.TERMINAL) {
                            // glitch harder and harder, then crash
                            val t0 = Platform.epochMillis()
                            while (true) {
                                val f = ((Platform.epochMillis() - t0) / 1100f).coerceIn(0f, 1f)
                                Eggs.hold = f
                                if (f >= 1f) break
                                haptic(f > 0.6f)
                                delay(70)
                            }
                            Eggs.hold = 0f
                            fired = true
                            Eggs.play(Eggs.Kind.GLITCH)
                        } else {
                            delay(450)
                            haptic(true)
                            fired = true
                            Eggs.play(Eggs.Kind.LOGO)
                        }
                        taps = 0
                    }
                    tryAwaitRelease()
                    holding?.cancel(); holding = null
                    Eggs.hold = 0f
                },
                onTap = {
                    // the release after a hold that already played its egg is not a tap
                    if (fired) { fired = false; return@detectTapGestures }
                    val now = Platform.epochMillis()
                    taps = if (now - last < 650) taps + 1 else 1
                    last = now
                    haptic(false)
                    Sfx.play(Cue.TAP)
                    scope.launch { boop.snapTo(1f); boop.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 700f)) }
                    if (taps >= 5) { taps = 0; Eggs.play(Eggs.Kind.LOGO) }
                },
            )
        }
}

/** Plays the current egg over everything (tap to skip). Put it last in the app's root box. */
@Composable
fun EggHost() {
    val show = Eggs.show ?: return
    key(show.id) { EggShow(show) }
}

@Composable
private fun EggShow(show: Eggs.Show) {
    val t by rememberClock(true)
    val fx = LocalFx.current
    val tm = rememberTextMeasurer()
    val font = styleFont(show.style)
    val cache = remember { HashMap<String, TextLayoutResult>() }
    if (show.kind == Eggs.Kind.GLITCH) LaunchedEffect(Unit) {
        // the whole app jolts with the crash (the root layer reads these)
        val t0 = Platform.epochMillis()
        while (true) {
            androidx.compose.runtime.withFrameMillis { }
            val ms = Platform.epochMillis() - t0
            val p = (ms.toFloat() / show.kind.ms).coerceIn(0f, 1f)
            val frame = (ms * 24 / 1000).toInt()
            val u = 4f
            Eggs.joltX = (h(frame, show.seed) - 0.5f) * u * 6f * crashHard(p)
            Eggs.joltY = (h(frame + 7, show.seed) - 0.5f) * u * 3f * crashHard(p)
            if (p >= 1f) break
        }
    }
    LaunchedEffect(Unit) {
        if (show.kind == Eggs.Kind.GLITCH) fx.shake(1.6f)
        if (show.kind == Eggs.Kind.SELF) fx.flash(C.hot)
        delay(show.kind.ms)
        Eggs.done(show.id)
    }
    val ink = EggInk(C.bg, C.panel, C.amber, C.hot, C.white, C.dim, C.blood, C.line)
    Canvas(Modifier.fillMaxSize().pointerInput(show.id) { detectTapGestures { Eggs.done(show.id) } }) {
        val p = (t * 1000f / show.kind.ms).coerceIn(0f, 1f)
        val txt = EggTxt(tm, font, cache, this)
        when (show.kind) {
            Eggs.Kind.LOGO -> logoEgg(show.style, t, p, show.seed, ink, txt)
            Eggs.Kind.GLITCH -> crash(t, p, show.seed, ink, txt)
            Eggs.Kind.SELF -> selfBow(t, p, show.seed, ink, txt)
            Eggs.Kind.LOVE -> love(t, p, show.seed, ink, txt)
            Eggs.Kind.MILESTONE -> milestone(t, p, show.seed, show.note, ink, txt)
        }
    }
}

private class EggInk(val bg: Color, val panel: Color, val amber: Color, val hot: Color, val white: Color, val dim: Color, val blood: Color, val line: Color)

/** Text for the egg canvases: laid out once per text and size, drawn centred. */
private class EggTxt(val tm: TextMeasurer, val font: FontFamily, val cache: HashMap<String, TextLayoutResult>, val d: DrawScope) {
    fun lay(s: String, px: Float, bold: Boolean = true): TextLayoutResult = cache.getOrPut("$s|${px.toInt()}|$bold") {
        tm.measure(s, TextStyle(fontFamily = font, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontSize = with(d) { px.toSp() }))
    }
    /** [s] centred on [c], in [color]; shrunk to fit 92 % of the screen's width when it is wider. */
    fun center(s: String, c: Offset, px: Float, color: Color, alpha: Float = 1f, bold: Boolean = true) {
        var l = lay(s, px, bold)
        val room = d.size.width * 0.92f
        if (l.size.width > room) l = lay(s, (px * room / l.size.width).coerceAtLeast(6f), bold)
        d.drawText(l, color, Offset(c.x - l.size.width / 2f, c.y - l.size.height / 2f), alpha.coerceIn(0f, 1f))
    }
    fun at(s: String, topLeft: Offset, px: Float, color: Color, alpha: Float = 1f, bold: Boolean = false) {
        d.drawText(lay(s, px, bold), color, topLeft, alpha.coerceIn(0f, 1f))
    }
}

/** 0 → 1 → 0 envelope: in over [a], out over the last [b] (shares of the whole). */
private fun env(p: Float, a: Float = 0.12f, b: Float = 0.18f): Float = min((p / a).coerceIn(0f, 1f), ((1f - p) / b).coerceIn(0f, 1f))

private fun easeOutBack(x: Float): Float { val c = 1.70158f; val y = x - 1f; return 1f + (c + 1f) * y * y * y + c * y * y }

private fun h(i: Int, salt: Int): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0xFFFFFF) / 16777215f
}

// ------------------------------------------------------------------------------------------------ the logo eggs

private fun DrawScope.logoEgg(style: Style, t: Float, p: Float, seed: Int, k: EggInk, txt: EggTxt) {
    val w = size.width; val hgt = size.height; val c = Offset(w / 2, hgt / 2); val u = min(w, hgt) / 100f
    val e = env(p)
    when (style) {
        Style.TERMINAL -> {
            // a "kernel panic": a hex dump floods up the screen, then a wink
            drawRect(k.bg.copy(alpha = 0.92f * e))
            val rows = (hgt / (u * 4.2f)).toInt() + 2
            val shown = (rows * min(1f, p * 2.2f)).toInt()
            for (r in 0 until shown) {
                val y = hgt - (r + 1) * u * 4.2f + (t * 60f % (u * 4.2f))
                val addr = (0x7f3a0000 + r * 16 + seed.and(0xff)).toString(16)
                val bytes = (0 until 8).joinToString(" ") { b -> ((h(r * 8 + b, seed) * 255).toInt()).toString(16).padStart(2, '0') }
                txt.at("0x$addr  $bytes", Offset(u * 3, y), u * 3.2f, k.amber, 0.75f * e)
            }
            if (p > 0.45f) {
                drawRect(k.bg.copy(alpha = 0.85f * e), Offset(0f, c.y - u * 14), Size(w, u * 28))
                txt.center("KERNEL PANIC", Offset(c.x, c.y - u * 6), u * 8f, k.hot, e)
                txt.center("just kidding. ♥", Offset(c.x, c.y + u * 6), u * 5f, k.amber, e * ((p - 0.55f) * 6f).coerceIn(0f, 1f))
            }
        }
        Style.PAPER -> {
            // EXTRA! EXTRA! the newspaper spins in, front page out
            drawRect(Color.Black.copy(alpha = 0.45f * e))
            val inP = (p / 0.35f).coerceIn(0f, 1f)
            val s = if (p < 0.82f) inP else 1f + (p - 0.82f) * 6f
            val a = (1f - inP) * 720f
            rotate(a, c) {
                scale(s, s, c) {
                    val pw = w * 0.82f; val ph = pw * 0.62f
                    val tl = Offset(c.x - pw / 2, c.y - ph / 2)
                    drawRect(Color(0xFFF3EBD9), tl, Size(pw, ph))
                    drawRect(Color(0xFF2A241B), tl, Size(pw, ph), style = Stroke(u * 0.6f))
                    txt.center("EXTRA! EXTRA!", Offset(c.x, tl.y + ph * 0.18f), u * 7f, Color(0xFF2A241B))
                    drawLine(Color(0xFF2A241B), Offset(tl.x + pw * 0.06f, tl.y + ph * 0.31f), Offset(tl.x + pw * 0.94f, tl.y + ph * 0.31f), u * 0.5f)
                    txt.center("READER TAPS LOGO", Offset(c.x, tl.y + ph * 0.47f), u * 5.6f, Color(0xFF2A241B))
                    txt.center("FIVE TIMES", Offset(c.x, tl.y + ph * 0.62f), u * 5.6f, Color(0xFF2A241B))
                    txt.center("\"we're touched,\" says the editor", Offset(c.x, tl.y + ph * 0.8f), u * 3.2f, Color(0xFF5B5246), bold = false)
                }
            }
        }
        Style.NEON -> {
            // fireworks over the grid
            drawRect(Color.Black.copy(alpha = 0.35f * e))
            for (i in 0 until 6) {
                val start = i * 0.11f
                val q = ((p - start) / 0.5f)
                if (q <= 0f || q >= 1f) continue
                val x = w * (0.15f + 0.7f * h(i, seed))
                val top = hgt * (0.2f + 0.25f * h(i + 9, seed))
                val col = hue(h(i, seed + 3) * 360f + t * 60f, 0.8f, 1f)
                if (q < 0.35f) {
                    val y = hgt - (hgt - top) * (q / 0.35f)
                    drawCircle(col, u * 0.8f, Offset(x, y))
                    drawLine(col.copy(alpha = 0.4f), Offset(x, y), Offset(x, y + u * 8), u * 0.5f)
                } else {
                    val b = (q - 0.35f) / 0.65f
                    for (j in 0 until 18) {
                        val a = j * 2f * PI.toFloat() / 18f
                        val r = u * 22f * (1f - (1f - b) * (1f - b))
                        val pt = Offset(x + cos(a) * r, top + sin(a) * r + b * b * u * 10f)
                        drawCircle(col.copy(alpha = 1f - b), u * 0.9f, pt)
                    }
                }
            }
            val flick = if (h((t * 12f).toInt(), seed) > 0.15f) 1f else 0.3f
            txt.center("HIGH SCORE!", Offset(c.x, hgt * 0.72f), u * 9f, hue(300f + 40f * sin(t * 3f), 0.7f, 1f), e * flick)
            txt.center("you found a secret", Offset(c.x, hgt * 0.8f), u * 4f, k.white, e)
        }
        Style.CLEAN -> {
            // confetti cannons from both bottom corners, a winner's card
            drawRect(Color.Black.copy(alpha = 0.25f * e))
            confetti(t, p, seed, u)
            val s = easeOutBack(((p - 0.1f) / 0.25f).coerceIn(0f, 1f))
            scale(s, s, c) {
                val cw = w * 0.74f; val ch = u * 30
                drawRoundRect(Color.White, Offset(c.x - cw / 2, c.y - ch / 2), Size(cw, ch), CornerRadius(u * 5, u * 5))
                txt.center("🎉", Offset(c.x, c.y - u * 7), u * 9f, Color.Black)
                txt.center("You found a secret!", Offset(c.x, c.y + u * 5), u * 5.4f, Color(0xFF1F2430))
            }
        }
        Style.PIXEL -> {
            // a treasure chest opens: SECRET FOUND!
            drawRect(Color.Black.copy(alpha = 0.55f * e))
            val b = u * 2.2f
            fun px(x: Int, y: Int, col: Color) = drawRect(col, Offset(c.x + x * b, c.y + y * b), Size(b, b))
            val wood = Color(0xFF8B5A2B); val dark = Color(0xFF4A2E12); val gold = Color(0xFFF2C230)
            for (x in -8..7) for (y in 0..6) px(x, y, if (y == 0 || y == 6 || x == -8 || x == 7) dark else wood)
            for (x in -1..0) for (y in 1..2) px(x, y, gold)
            val open = ((p - 0.15f) / 0.2f).coerceIn(0f, 1f)
            val lid = (-3 - (open * 6).toInt())
            for (x in -8..7) for (y in 0..2) px(x, lid + y, if (y == 0 || y == 2 || x == -8 || x == 7) dark else wood)
            if (open > 0f) {
                for (i in 0 until 14) {
                    val a = i * 2f * PI.toFloat() / 14f + t
                    val r = b * (4 + 10 * open + 2 * sin(t * 5 + i))
                    val tw = if ((t * 8 + i).toInt() % 2 == 0) gold else Color.White
                    drawRect(tw, Offset(c.x + cos(a) * r, c.y - b * 2 + sin(a) * r * 0.6f), Size(b * 0.8f, b * 0.8f))
                }
                txt.center("SECRET FOUND!", Offset(c.x, c.y - b * 16), u * 7f, gold, e)
                txt.center("+1 UP", Offset(c.x, c.y + b * 11), u * 5f, Color.White, e * open)
            }
        }
        Style.COMIC -> {
            // KA-BOOM!
            drawRect(Color.Black.copy(alpha = 0.3f * e))
            val s = easeOutBack((p / 0.22f).coerceIn(0f, 1f)) * (1f + 0.03f * sin(t * 30f) * (1f - p))
            val path = Path()
            val n = 16
            for (i in 0 until n * 2) {
                val a = i * PI.toFloat() / n
                val r = (if (i % 2 == 0) 40f else 26f) * u * (1f + 0.12f * h(i, seed))
                val pt = Offset(c.x + cos(a) * r, c.y + sin(a) * r)
                if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
            }
            path.close()
            scale(s, s, c) {
                drawPath(path, Color(0xFFFFD21F))
                drawPath(path, Color.Black, style = Stroke(u * 1.4f))
                for (i in 0 until 60) {
                    val a = h(i, seed) * 2f * PI.toFloat(); val r = h(i + 99, seed) * 24f * u
                    drawCircle(Color(0xFFE23B2E).copy(alpha = 0.35f), u * 0.9f, Offset(c.x + cos(a) * r, c.y + sin(a) * r))
                }
                rotate(-8f, c) {
                    txt.center("KA-BOOM!", Offset(c.x + u * 0.7f, c.y + u * 0.7f), u * 11f, Color.Black)
                    txt.center("KA-BOOM!", c, u * 11f, Color(0xFFE23B2E))
                }
            }
            txt.center("a secret, true believer!", Offset(c.x, hgt * 0.78f), u * 4.4f, Color.White, e)
        }
        Style.ABYSS -> {
            // a whale drifts by in the deep, with a song of bubbles
            drawRect(Color(0xFF00121F).copy(alpha = 0.55f * e))
            val x = -w * 0.6f + (w * 2.2f) * p
            val y = hgt * 0.45f + sin(t * 1.3f) * u * 3
            val body = Color(0xFF0B3550).copy(alpha = 0.92f)
            drawOval(body, Offset(x - u * 40, y - u * 11), Size(u * 70, u * 24))
            drawOval(body, Offset(x + u * 22, y - u * 7), Size(u * 18, u * 14))
            val tail = Path().apply { moveTo(x - u * 38, y); lineTo(x - u * 56, y - u * 12 + sin(t * 3f) * u * 4); lineTo(x - u * 52, y); lineTo(x - u * 56, y + u * 12 + sin(t * 3f) * u * 4); close() }
            drawPath(tail, body)
            drawCircle(Color(0xFF7FD8FF), u * 1.2f, Offset(x + u * 24, y - u * 3))
            drawOval(Color(0xFF7FD8FF).copy(alpha = 0.18f), Offset(x - u * 30, y + u * 2), Size(u * 52, u * 8))
            for (i in 0 until 16) {
                val q = ((t * 0.6f + h(i, seed)) % 1f)
                drawCircle(Color(0xFFBFF3FF).copy(alpha = 0.5f * (1f - q) * e), u * (0.6f + 1.2f * h(i + 7, seed)),
                    Offset(x + u * 26 + (h(i, seed + 1) - 0.5f) * u * 14, y - u * 10 - q * hgt * 0.35f))
            }
            txt.center("you found the deep one", Offset(c.x, hgt * 0.8f), u * 4.6f, Color(0xFF9FE6FF), e)
        }
        Style.METRO -> {
            // an express train shoots across: MIND THE EGG
            drawRect(Color.Black.copy(alpha = 0.3f * e))
            val y = hgt * 0.46f
            drawLine(k.line, Offset(0f, y + u * 9), Offset(w, y + u * 9), u * 0.8f)
            val x = -w * 1.2f + w * 2.6f * ((p - 0.05f) / 0.6f).coerceIn(0f, 1f)
            val cars = 4; val cw = u * 34; val ch = u * 14
            for (i in 0 until cars) {
                val l = x - i * (cw + u * 2)
                drawRoundRect(Color(0xFFFFD000), Offset(l, y - ch / 2), Size(cw, ch), CornerRadius(u * 3, u * 3))
                for (j in 0 until 4) drawRect(Color(0xFF1B1B1B), Offset(l + u * 3 + j * u * 8, y - ch * 0.3f), Size(u * 5, ch * 0.32f))
            }
            for (i in 0 until 10) {
                val ly = y - ch + h(i, seed) * ch * 2
                drawLine(k.white.copy(alpha = 0.4f), Offset(x - cars * cw - u * 10 - h(i + 3, seed) * u * 40, ly), Offset(x - cars * cw - u * 4, ly), u * 0.5f)
            }
            if (p > 0.45f) {
                val board = Offset(c.x, hgt * 0.72f)
                drawRect(Color(0xFF111111), Offset(board.x - u * 34, board.y - u * 7), Size(u * 68, u * 14))
                val msg = "MIND THE EGG"
                val shownTxt = msg.mapIndexed { i, ch -> if ((p - 0.45f) * 40f > i) ch else "ABCDEFGHIJKLMNOPQRSTUVWXYZ"[(h(i, (t * 20).toInt()) * 25).toInt()] }.joinToString("")
                txt.center(shownTxt, board, u * 6f, Color(0xFFFFD000), e)
            }
        }
        Style.JACKPOT -> {
            // three reels spin and land on 7 7 7, coins rain
            drawRect(Color.Black.copy(alpha = 0.5f * e))
            val rw = u * 22; val rh = u * 28
            for (i in 0..2) {
                val l = c.x - rw * 1.6f + i * rw * 1.1f
                drawRoundRect(Color.White, Offset(l, c.y - rh / 2), Size(rw, rh), CornerRadius(u * 2, u * 2))
                val stop = 0.25f + i * 0.12f
                val sym = if (p > stop) "7" else listOf("♠", "♦", "♣", "♥", "★", "7")[((t * 22f + i * 3).toInt()) % 6]
                txt.center(sym, Offset(l + rw / 2, c.y), u * 15f, if (sym == "7") Color(0xFFD4202A) else Color.Black)
                drawRoundRect(Color(0xFFF2C230), Offset(l, c.y - rh / 2), Size(rw, rh), CornerRadius(u * 2, u * 2), style = Stroke(u * 1.2f))
            }
            if (p > 0.5f) {
                for (i in 0 until 40) {
                    val q = ((p - 0.5f) * 2.2f + h(i, seed) * 0.6f) % 1.2f
                    val x = w * h(i + 50, seed)
                    val y = -u * 5 + q * (hgt + u * 10)
                    val sq = abs(cos(t * 6f + i))
                    drawOval(Color(0xFFF2C230), Offset(x - u * 2.2f * sq, y - u * 2.2f), Size(u * 4.4f * sq + 1f, u * 4.4f))
                }
                txt.center("JACKPOT!", Offset(c.x, c.y - rh), u * 10f, Color(0xFFF2C230), e)
            }
        }
        Style.CHAT -> {
            // hearts float up; a message arrives
            drawRect(Color.Black.copy(alpha = 0.18f * e))
            hearts(t, p, seed, u, Color(0xFFFF4D6D))
            val s = easeOutBack(((p - 0.12f) / 0.2f).coerceIn(0f, 1f))
            scale(s, s, Offset(w * 0.3f, hgt * 0.62f)) {
                val bw = w * 0.62f; val bh = u * 15
                drawRoundRect(Color(0xFFE9E9EE), Offset(w * 0.08f, hgt * 0.62f - bh / 2), Size(bw, bh), CornerRadius(u * 6, u * 6))
                txt.at("hey, you found me 👋 ♥", Offset(w * 0.12f, hgt * 0.62f - u * 3.4f), u * 4.4f, Color(0xFF1C1C1E))
            }
        }
        Style.DESKTOP -> {
            // a screensaver: the logo bounces round the screen, changing colour at every wall
            drawRect(Color.Black.copy(alpha = 0.85f * e))
            val bw = u * 40; val bh = u * 14
            val sx = w - bw; val sy = hgt - bh
            val vx = u * 70f; val vy = u * 52f
            fun fold(v: Float, span: Float): Pair<Float, Int> { val m = v % (2 * span); val bounces = (v / span).toInt(); return (if (m < span) m else 2 * span - m) to bounces }
            val (x, bx) = fold(t * vx + sx * h(1, seed), sx)
            val (y, by) = fold(t * vy + sy * h(2, seed), sy)
            val col = hue(((bx + by) * 67f) % 360f, 0.8f, 1f)
            drawRoundRect(col, Offset(x, y), Size(bw, bh), CornerRadius(u * 2, u * 2), style = Stroke(u * 0.8f))
            txt.center("CORE DUMP", Offset(x + bw / 2, y + bh / 2), u * 6f, col)
        }
        Style.ORACLE -> {
            // a card turns over: THE FOOL — you, tapping logos
            drawRect(Color(0xFF07061A).copy(alpha = 0.7f * e))
            for (i in 0 until 50) {
                val tw = 0.4f + 0.6f * abs(sin(t * (1f + h(i, seed) * 2f) + i))
                drawCircle(Color(0xFFFFF3C4).copy(alpha = tw * e), u * (0.3f + 0.5f * h(i + 1, seed)), Offset(w * h(i + 2, seed), hgt * h(i + 3, seed)))
            }
            val flip = ((p - 0.15f) / 0.25f).coerceIn(0f, 1f)
            val sxs = abs(cos(flip * PI.toFloat()))
            val cw = u * 46; val ch = u * 76
            scale(sxs.coerceAtLeast(0.02f), 1f, c) {
                val tl = Offset(c.x - cw / 2, c.y - ch / 2)
                if (flip < 0.5f) {
                    drawRoundRect(Color(0xFF231A4A), tl, Size(cw, ch), CornerRadius(u * 3, u * 3))
                    drawRoundRect(Color(0xFFD9B45A), tl, Size(cw, ch), CornerRadius(u * 3, u * 3), style = Stroke(u))
                    drawCircle(Color(0xFFD9B45A), u * 9, c, style = Stroke(u * 0.8f))
                } else {
                    drawRoundRect(Color(0xFFF4E8CC), tl, Size(cw, ch), CornerRadius(u * 3, u * 3))
                    drawRoundRect(Color(0xFFB08A3A), tl, Size(cw, ch), CornerRadius(u * 3, u * 3), style = Stroke(u))
                    txt.center("0", Offset(c.x, tl.y + u * 7), u * 5f, Color(0xFF3A2A10))
                    val sun = Offset(c.x, c.y - u * 4)
                    drawCircle(Color(0xFFB08A3A), u * 6, sun, style = Stroke(u * 0.8f))
                    drawCircle(Color(0xFFB08A3A), u * 1.4f, sun)
                    for (i in 0 until 12) {
                        val a = i * PI.toFloat() / 6f + t * 0.4f
                        drawLine(Color(0xFFB08A3A), Offset(sun.x + cos(a) * u * 8, sun.y + sin(a) * u * 8),
                            Offset(sun.x + cos(a) * u * 11, sun.y + sin(a) * u * 11), u * 0.6f, StrokeCap.Round)
                    }
                    txt.center("THE FOOL", Offset(c.x, tl.y + ch - u * 13), u * 5f, Color(0xFF3A2A10))
                    txt.center("you, tapping logos", Offset(c.x, tl.y + ch - u * 6), u * 3f, Color(0xFF6A5530), bold = false)
                }
            }
        }
    }
}

private fun DrawScope.confetti(t: Float, p: Float, seed: Int, u: Float) {
    for (i in 0 until 70) {
        val left = i % 2 == 0
        val q = (p * 1.6f - h(i, seed) * 0.3f).coerceIn(0f, 1f)
        if (q <= 0f) continue
        val vx = (0.25f + 0.45f * h(i + 1, seed)) * size.width * (if (left) 1f else -1f)
        val vy = -(0.9f + 0.6f * h(i + 2, seed)) * size.height
        val x = (if (left) 0f else size.width) + vx * q
        val y = size.height + vy * q + 1.4f * size.height * q * q
        val col = hue(h(i + 3, seed) * 360f, 0.75f, 1f)
        rotate(t * 400f * (h(i + 4, seed) - 0.5f), Offset(x, y)) {
            drawRect(col, Offset(x - u, y - u * 0.5f), Size(u * 2, u))
        }
    }
}

private fun DrawScope.hearts(t: Float, p: Float, seed: Int, u: Float, col: Color) {
    for (i in 0 until 28) {
        val q = ((p * 1.3f + h(i, seed)) % 1f)
        val x = size.width * (0.1f + 0.8f * h(i + 1, seed)) + sin(t * 2f + i) * u * 4
        val y = size.height * (1.05f - q * 1.1f)
        val s = u * (2.2f + 2.5f * h(i + 2, seed))
        heart(Offset(x, y), s, col.copy(alpha = (1f - q) * env(p)))
    }
}

private fun DrawScope.heart(c: Offset, s: Float, col: Color) {
    val path = Path().apply {
        moveTo(c.x, c.y + s * 0.9f)
        cubicTo(c.x - s * 1.4f, c.y - s * 0.1f, c.x - s * 0.7f, c.y - s * 1.2f, c.x, c.y - s * 0.4f)
        cubicTo(c.x + s * 0.7f, c.y - s * 1.2f, c.x + s * 1.4f, c.y - s * 0.1f, c.x, c.y + s * 0.9f)
        close()
    }
    drawPath(path, col)
}

// ------------------------------------------------------------------------------------------------ the others

/** How hard the crash shakes at [p] (0..1 of the egg): full, then easing off before the reboot. */
private fun crashHard(p: Float): Float = if (p < 0.55f) 1f else ((0.75f - p) / 0.2f).coerceIn(0f, 1f)

/** TERMINAL: the screen crashes — tearing bands, colour bars, the app jolts, "segmentation fault", reboot. */
private fun DrawScope.crash(t: Float, p: Float, seed: Int, k: EggInk, txt: EggTxt) {
    val w = size.width; val hgt = size.height; val u = min(w, hgt) / 100f
    val hard = crashHard(p)
    val frame = (t * 24f).toInt()
    // tearing bands of colour noise
    for (i in 0 until 14) {
        if (h(i + frame * 31, seed) > 0.55f * hard + 0.2f) continue
        val y = hgt * h(i + frame * 17, seed + 1)
        val bh = u * (0.6f + 5f * h(i + frame * 5, seed + 2))
        val col = when (i % 3) { 0 -> k.hot; 1 -> k.blood; else -> Color(0xFF00E5FF) }
        drawRect(col.copy(alpha = 0.35f * hard), Offset((h(i, frame) - 0.5f) * u * 20, y), Size(w, bh))
    }
    // block noise
    for (i in 0 until 40) {
        if (h(i + frame * 3, seed + 5) > hard * 0.7f) continue
        drawRect(if (i % 2 == 0) k.amber.copy(alpha = 0.5f) else k.bg, Offset(w * h(i, frame + seed), hgt * h(i + 1, frame + seed)), Size(u * 6 * h(i + 2, frame), u * 1.4f))
    }
    if (p > 0.18f) {
        val a = ((p - 0.18f) * 6f).coerceIn(0f, 1f) * env(p, 0.2f, 0.12f)
        drawRect(k.bg.copy(alpha = 0.8f * a), Offset(0f, hgt * 0.4f), Size(w, hgt * 0.2f))
        txt.center("Segmentation fault", Offset(w / 2 + Eggs.joltX, hgt * 0.47f), u * 7f, k.hot, a)
        txt.center("(core dumped)", Offset(w / 2, hgt * 0.54f), u * 4.6f, k.amber, a)
    }
    if (p > 0.72f) {
        // reboot: a bright line sweeps down and everything settles
        val y = hgt * ((p - 0.72f) / 0.28f)
        drawRect(k.amber.copy(alpha = 0.6f), Offset(0f, y), Size(w, u * 0.6f))
        drawRect(k.amber.copy(alpha = 0.08f), Offset(0f, 0f), Size(w, y))
        txt.center("rebooting… ♥", Offset(w / 2, hgt * 0.62f), u * 4f, k.amber, env(p, 0.75f, 0.1f))
    }
}

/** "Name a game" → "core dump": the app takes a bow — its core bursts into a ring of light. */
private fun DrawScope.selfBow(t: Float, p: Float, seed: Int, k: EggInk, txt: EggTxt) {
    val w = size.width; val hgt = size.height; val c = Offset(w / 2, hgt * 0.42f); val u = min(w, hgt) / 100f
    val e = env(p, 0.1f, 0.2f)
    drawRect(k.bg.copy(alpha = 0.62f * e))
    // the core: a sphere of dots that turns, swells and bursts
    val burst = ((p - 0.35f) / 0.3f).coerceIn(0f, 1f)
    val n = 140
    for (i in 0 until n) {
        val y = 1f - 2f * (i + 0.5f) / n
        val r0 = kotlin.math.sqrt(1f - y * y)
        val a = i * 2.399963f + t * 1.6f
        val x3 = cos(a) * r0; val z = sin(a) * r0
        val R = u * 16f * (1f + 0.08f * sin(t * 6f)) * (1f + burst * (2.5f + 2f * h(i, seed)))
        val pt = Offset(c.x + x3 * R, c.y + y * R)
        val depth = (z + 1f) / 2f
        drawCircle(hue(200f + 160f * depth + t * 40f, 0.7f, 1f, (0.35f + 0.65f * depth) * (1f - burst * 0.8f) * e), u * (0.5f + 0.7f * depth), pt)
    }
    if (burst > 0f) drawCircle(k.hot.copy(alpha = (1f - burst) * 0.6f), u * 16f * (1f + burst * 4f), c, style = Stroke(u * 1.2f * (1f - burst) + 1f))
    val a = ((p - 0.3f) * 5f).coerceIn(0f, 1f) * e
    txt.center("CORE DUMP? that's me!", Offset(w / 2, hgt * 0.68f), u * 6.4f, k.hot, a)
    txt.center("flattered ♥", Offset(w / 2, hgt * 0.75f), u * 4.4f, k.amber, a)
    txt.center("but no points for self-promotion", Offset(w / 2, hgt * 0.8f), u * 3.6f, k.amber, a, bold = false)
}

/** "<3": love, right back. */
private fun DrawScope.love(t: Float, p: Float, seed: Int, k: EggInk, txt: EggTxt) {
    val u = min(size.width, size.height) / 100f
    hearts(t, p, seed, u, Color(0xFFFF4D6D))
    val c = Offset(size.width / 2, size.height * 0.45f)
    val beat = 1f + 0.12f * abs(sin(t * 7f))
    val s = easeOutBack((p / 0.25f).coerceIn(0f, 1f)) * beat
    heart(c, u * 12f * s, Color(0xFFFF4D6D).copy(alpha = env(p)))
    txt.center("love you too ♥", Offset(c.x, c.y + u * 22), u * 6f, k.hot, env(p))
}

/** A milestone ("50 runs"): confetti and a thank-you. */
private fun DrawScope.milestone(t: Float, p: Float, seed: Int, note: String, k: EggInk, txt: EggTxt) {
    val w = size.width; val hgt = size.height; val u = min(w, hgt) / 100f
    drawRect(Color.Black.copy(alpha = 0.3f * env(p)))
    confetti(t, p, seed, u)
    val s = easeOutBack(((p - 0.08f) / 0.25f).coerceIn(0f, 1f))
    val c = Offset(w / 2, hgt * 0.45f)
    scale(s, s, c) {
        txt.center(note.uppercase(), Offset(c.x, c.y - u * 6), u * 10f, k.hot, env(p))
        txt.center("thank you for playing ♥", Offset(c.x, c.y + u * 6), u * 4.6f, k.amber, env(p))
    }
}
