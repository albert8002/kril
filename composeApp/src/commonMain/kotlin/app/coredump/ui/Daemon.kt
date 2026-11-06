package app.coredump.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.core.Tier
import app.coredump.core.hexAddr
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * The daemon: a 16×16 pixel imp (this game's krill). Legend:
 * A body · D shadow · E eye · R horn/tail · W wing · . empty
 */
private val SPRITE = arrayOf(
    "..R..........R..",
    "..RR........RR..",
    "...RAAAAAAAAR...",
    "...AAAAAAAAAA...",
    "..AAEEAAAAEEAA..",
    "..AAEEAAAAEEAA..",
    "..AAAAAAAAAAAA..",
    "..AAAADDDDAAAA..",
    "...AAAADDAAAA...",
    "....AAAAAAAA....",
    ".WWAAAAAAAAAAWW.",
    "W.W.AAAAAAAA.W.W",
    "....AA....AA....",
    "....AA....AA..RR",
    "...DDD....DDDR..",
    "................",
)
private val TAIL_ALT = arrayOf(
    "....AA....AA....",
    "....AA....AA.R..",
    "...DDD....DDDRR.",
)

/** Draws the sprite with its top-left at [origin]. px = size of one sprite pixel. */
fun DrawScope.drawDaemon(
    origin: Offset,
    px: Float,
    body: Color,
    eye: Color,
    blink: Boolean,
    tailAlt: Boolean,
    wingUp: Boolean,
) {
    for (y in SPRITE.indices) {
        val row = if (tailAlt && y >= 12 && y <= 14) TAIL_ALT[y - 12] else SPRITE[y]
        for (x in row.indices) {
            var ch = row[x]
            if (ch == 'W' && wingUp) ch = if (y == 10) '.' else ch
            val c = when (ch) {
                'A' -> body
                'D' -> body.copy(alpha = 0.55f)
                'E' -> if (blink) body else eye
                'R' -> C.blood
                'W' -> body.copy(alpha = 0.7f)
                else -> null
            } ?: continue
            drawRect(c, Offset(origin.x + x * px, origin.y + y * px), Size(px + 0.5f, px + 0.5f))
        }
    }
    if (wingUp) {
        // raised wings while falling
        drawRect(body.copy(alpha = 0.7f), Offset(origin.x, origin.y + 8 * px), Size(px * 2, px))
        drawRect(body.copy(alpha = 0.7f), Offset(origin.x + 14 * px, origin.y + 8 * px), Size(px * 2, px))
    }
}

/** Idle daemon: bobs, blinks, flicks its tail. For menus. */
@Composable
fun DaemonIdle(modifier: Modifier, px: Float = 3f) {
    val t = rememberInfiniteTransition()
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)))
    var blink by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2400L + Random.nextLong(2400)); blink = 1; delay(130); blink = 0 } }
    Canvas(modifier) {
        val p = px * density
        val w = 16 * p
        val y = sin(bob * 2 * PI).toFloat() * 3f * density
        drawDaemon(Offset((size.width - w) / 2f, (size.height - w) / 2f + y), p, C.amber, C.white,
            blink == 1, tailAlt = bob > 0.5f, wingUp = false)
    }
}

/**
 * Vertical shaft of memory addresses with the daemon sinking through it.
 * depthFrames: current depth (1 point = 10 frames). Every increase animates a fall.
 * lastTier: tier of the last accepted answer (ZERO_DAY smashes through layers).
 * missTick: increments on each wrong attempt (daemon glitches).
 * markers: (depth, label) pins drawn on the wall, e.g. per-round gains on the summary.
 */
@Composable
fun DaemonShaft(
    depthFrames: Int,
    modifier: Modifier,
    lastTier: Tier? = null,
    missTick: Int = 0,
    markers: List<Pair<Int, String>> = emptyList(),
    fallMs: Int = 1400,
    startFrom: Int? = null,
) {
    val motion = !LocalLook.current.reduceMotion
    val cam = remember { Animatable((startFrom ?: depthFrames).toFloat()) }
    var landTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(depthFrames) {
        val target = depthFrames.toFloat()
        if (target != cam.value) {
            if (motion) cam.animateTo(target, tween(fallMs, easing = FastOutSlowInEasing)) else cam.snapTo(target)
            landTick++
        }
    }
    val squash = remember { Animatable(1f) }
    LaunchedEffect(landTick) {
        if (landTick == 0 || !motion) return@LaunchedEffect
        squash.snapTo(0.72f)
        squash.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
    }
    val shards = remember { Animatable(1f) }
    LaunchedEffect(landTick) {
        if (landTick == 0 || lastTier != Tier.ZERO_DAY || !motion) return@LaunchedEffect
        shards.snapTo(0f)
        shards.animateTo(1f, tween(900))
    }
    var glitch by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(missTick) {
        if (missTick == 0 || !motion) return@LaunchedEffect
        repeat(6) { glitch = Random.nextFloat() * 2f - 1f; delay(40) }
        glitch = 0f
    }
    val t = rememberInfiniteTransition()
    val idle by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)))
    var blink by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2600L + Random.nextLong(2200)); blink = 1; delay(120); blink = 0 } }
    val measurer = rememberTextMeasurer()
    val mono = LocalMono.current
    val debris = remember { List(26) { Triple(Random.nextFloat(), Random.nextFloat(), 0.4f + Random.nextFloat()) } }

    Canvas(modifier) {
        val pxPerFrame = 0.6f * density
        val c = cam.value
        val falling = abs(depthFrames - c) > 2f
        val speed = if (falling) ((depthFrames - c) / 900f).coerceIn(0.15f, 1f) else 0f
        val deep = (c / 7000f).coerceIn(0f, 1f)
        val wall = lerp(C.amber, C.blood, deep)

        // shaft background: darker and redder with depth
        drawRect(Brush.verticalGradient(listOf(C.bg, lerp(C.ghost, C.bloodDim, deep).copy(alpha = 0.55f), C.bg)))
        // walls
        val wallW = 2.dp.toPx()
        drawRect(wall.copy(alpha = 0.35f), Offset(0f, 0f), Size(wallW, size.height))
        drawRect(wall.copy(alpha = 0.35f), Offset(size.width - wallW, 0f), Size(wallW, size.height))

        val daemonY = size.height * 0.36f
        // ticks: every 250 frames, labelled every 1000 frames
        val step = 250
        val topFrame = c - daemonY / pxPerFrame
        val first = (floor(topFrame / step) * step).toInt()
        var f = first
        val label = TextStyle(fontFamily = mono, fontSize = 8.sp, color = wall.copy(alpha = 0.6f))
        while (true) {
            val y = daemonY + (f - c) * pxPerFrame
            if (y > size.height) break
            if (f >= 0) {
                val major = f % 1000 == 0
                val crack = major && lastTier == Tier.ZERO_DAY && falling
                drawRect(
                    wall.copy(alpha = if (major) 0.55f else 0.18f),
                    Offset(if (major) 0f else size.width * 0.62f, y),
                    Size(if (major) (if (crack) size.width * 0.4f else size.width) else size.width * 0.38f, if (major) 2f else 1f),
                )
                if (crack) drawRect(wall.copy(alpha = 0.55f), Offset(size.width * 0.6f, y + 3f), Size(size.width * 0.4f, 2f))
                if (major) drawText(measurer, hexAddr(f), Offset(4f, y + 3f), label)
            }
            f += step
        }
        // markers (summary)
        for ((d, text) in markers) {
            val y = daemonY + (d - c) * pxPerFrame
            if (y < -20f || y > size.height + 20f) continue
            drawRect(C.hot.copy(alpha = 0.7f), Offset(size.width - 8f, y - 1f), Size(8f, 3f))
            drawText(measurer, text, Offset(4f, y - 12f), TextStyle(fontFamily = mono, fontSize = 8.sp, color = C.hot))
        }
        // debris streaks rising while falling, drifting slowly when idle
        for ((i, d) in debris.withIndex()) {
            val (dx, dy, sz) = d
            val y = ((dy * size.height - c * pxPerFrame * (0.3f + sz * 0.4f)) % size.height + size.height) % size.height
            val len = if (falling) 4f + 26f * speed * sz else 1.5f
            drawRect(wall.copy(alpha = 0.12f + 0.2f * sz), Offset(6f + dx * (size.width - 12f), y), Size(1.2f * density, len))
            if (i > 18 && !falling) continue
        }

        // daemon
        val px = (size.width * 0.5f / 16f).coerceAtMost(3.2f * density)
        val w = 16 * px
        val bob = if (falling) 0f else sin(idle * 2 * PI).toFloat() * 2.5f * density
        val sq = squash.value
        val stretchY = if (falling) 1f + 0.25f * speed else sq
        val stretchX = if (falling) 1f - 0.12f * speed else 1f + (1f - sq) * 0.6f
        val cx = size.width / 2f + glitch * 6f
        val cy = daemonY + bob
        if (glitch != 0f) {
            withTransform({ translate(cx - w / 2f - 3f, cy - w / 2f) }) {
                drawDaemon(Offset.Zero, px, C.blood.copy(alpha = 0.6f), C.blood, false, false, falling)
            }
        }
        withTransform({
            scale(stretchX, stretchY, Offset(cx, cy + w / 2f))
            translate(cx - w / 2f, cy - w / 2f)
        }) {
            drawDaemon(Offset.Zero, px, lerp(C.amber, C.hot, speed), C.white, blink == 1 || falling, idle > 0.5f, falling)
        }
        // ZERO_DAY shards
        val sh = shards.value
        if (sh < 1f) {
            for (k in 0 until 14) {
                val a = k / 14f * 2 * PI
                val r = sh * size.width * 0.9f
                val sx = cx + cos(a).toFloat() * r
                val sy = cy + w / 2f + sin(a).toFloat() * r * 0.6f
                drawRect(C.white.copy(alpha = 1f - sh), Offset(sx, sy), Size(3f * density, 1.5f * density))
            }
        }
        // depth readout at the bottom
        drawText(measurer, "${(c.toInt())}fr", Offset(4f, size.height - 14f * density), TextStyle(fontFamily = mono, fontSize = 9.sp, color = wall))
    }
}
