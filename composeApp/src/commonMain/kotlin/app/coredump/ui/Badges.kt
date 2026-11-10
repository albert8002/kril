package app.coredump.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.core.Rank
import app.coredump.core.Tier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// Tier and rank badges are drawn in the shapes of the active theme (bracket, stamp, neon tube, sign, chip,
// reaction, bevel, …) with the tier's own colours. Motion is short and quiet: one entry move per theme,
// and only the rarest tiers keep a slow loop.

/** How a badge looks in each style. */
private enum class Kind { BRACKET, STAMP, TUBE, TINT, BLOCK, SLAB, GLOW, SIGN, CHIP, REACTION, BEVEL, ORNATE }

private fun kindOf(style: Style): Kind = when (style) {
    Style.TERMINAL -> Kind.BRACKET
    Style.PAPER -> Kind.STAMP
    Style.NEON -> Kind.TUBE
    Style.CLEAN -> Kind.TINT
    Style.PIXEL -> Kind.BLOCK
    Style.COMIC -> Kind.SLAB
    Style.ABYSS -> Kind.GLOW
    Style.METRO -> Kind.SIGN
    Style.JACKPOT -> Kind.CHIP
    Style.CHAT -> Kind.REACTION
    Style.DESKTOP -> Kind.BEVEL
    Style.ORACLE -> Kind.ORNATE
}

/** Kinds filled with the tier colours (text on top in a contrasting ink); the rest are outlined / tinted in [tint]. */
private val Kind.filled: Boolean get() = this == Kind.BLOCK || this == Kind.SLAB || this == Kind.SIGN || this == Kind.CHIP || this == Kind.BEVEL

/** Lowest heat (0..6) that keeps a slow loop running; 99 = never. */
private fun loopFrom(k: Kind): Int = when (k) {
    Kind.BRACKET -> 5
    Kind.TUBE -> 4
    Kind.TINT -> 5
    Kind.GLOW -> 4
    Kind.CHIP -> 5
    Kind.ORNATE -> 4
    Kind.SLAB -> 6
    else -> 99
}

private fun entrySpec(k: Kind): AnimationSpec<Float> = when (k) {
    Kind.BRACKET -> tween(360, easing = LinearEasing)
    Kind.STAMP -> tween(240, easing = FastOutSlowInEasing)
    Kind.TUBE -> tween(560, easing = LinearEasing)
    Kind.TINT -> spring(dampingRatio = 0.6f, stiffness = 500f)
    Kind.BLOCK -> tween(320, easing = LinearEasing)
    Kind.SLAB -> spring(dampingRatio = 0.45f, stiffness = 520f)
    Kind.GLOW -> tween(700, easing = FastOutSlowInEasing)
    Kind.SIGN -> tween(380, easing = FastOutSlowInEasing)
    Kind.CHIP -> tween(520, easing = FastOutSlowInEasing)
    Kind.REACTION -> spring(dampingRatio = 0.4f, stiffness = 600f)
    Kind.BEVEL -> tween(1)
    Kind.ORNATE -> tween(800, easing = FastOutSlowInEasing)
}

/** Neon "striking": dark, flash, dim, on. [e] 0..1 = entry progress. */
private fun tubeAlpha(e: Float): Float = when {
    e < 0.15f -> 0f
    e < 0.25f -> 0.9f
    e < 0.38f -> 0.25f
    e < 0.55f -> 1f
    e < 0.65f -> 0.55f
    else -> 1f
}

private fun Path.octagon(l: Float, t: Float, r: Float, b: Float, k: Float) {
    moveTo(l + k, t); lineTo(r - k, t); lineTo(r, t + k); lineTo(r, b - k)
    lineTo(r - k, b); lineTo(l + k, b); lineTo(l, b - k); lineTo(l, t + k); close()
}

/** Outline of a badge of kind [k], inset by [i] px. */
private fun DrawScope.shape(k: Kind, i: Float = 0f): Path {
    val w = size.width
    val h = size.height
    return Path().apply {
        when (k) {
            Kind.SLAB -> {
                val s = min(h * 0.3f, 8.dp.toPx())
                moveTo(s + i, i); lineTo(w - i, i); lineTo(w - s - i, h - i); lineTo(i, h - i); close()
            }
            Kind.TUBE, Kind.TINT, Kind.GLOW, Kind.CHIP, Kind.REACTION ->
                addRoundRect(RoundRect(i, i, w - i, h - i, CornerRadius((h - 2 * i) / 2f)))
            Kind.SIGN -> addRoundRect(RoundRect(i, i, w - i, h - i, CornerRadius(4.dp.toPx())))
            Kind.STAMP -> addRoundRect(RoundRect(i, i, w - i, h - i, CornerRadius(2.dp.toPx())))
            Kind.ORNATE -> octagon(i, i, w - i, h - i, min(h * 0.28f, 7.dp.toPx()))
            else -> addRect(Rect(i, i, w - i, h - i))
        }
    }
}

/** Soft band of light crossing the shape; [t] 0..1 is its position. */
private fun DrawScope.shine(path: Path, t: Float, alpha: Float) {
    clipPath(path) {
        val w = size.width
        val x = -w * 0.6f + (w * 2.2f) * t
        drawRect(Brush.linearGradient(
            0f to Color.Transparent, 0.42f to Color.White.copy(alpha = 0f), 0.5f to Color.White.copy(alpha = alpha),
            0.58f to Color.White.copy(alpha = 0f), 1f to Color.Transparent,
            start = Offset(x, 0f), end = Offset(x + w * 0.6f, size.height),
        ))
    }
}

/** Four-point twinkle star. */
private fun DrawScope.twinkle(c: Offset, r: Float, color: Color) {
    if (r <= 0.3f) return
    val p = Path().apply {
        moveTo(c.x, c.y - r); quadraticTo(c.x, c.y, c.x + r, c.y); quadraticTo(c.x, c.y, c.x, c.y + r)
        quadraticTo(c.x, c.y, c.x - r, c.y); quadraticTo(c.x, c.y, c.x, c.y - r); close()
    }
    drawPath(p, color)
}

private fun mid(colors: List<Color>): Color = colors[colors.size / 2]

private fun Color.isDark(): Boolean = (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f

/**
 * One badge. colors: fill gradient, darkest first. tint: readable colour of the tier on the current panels.
 * heat: 0..6. big: rank plaque (display font, more padding). [key] restarts the entry move.
 */
@Composable
private fun Badge(text: String, colors: List<Color>, tint: Color, heat: Int, textSize: TextUnit, big: Boolean, animate: Boolean, key: Any,
                  modifier: Modifier = Modifier, icon: String? = null) {
    val style = Th.style
    val k = kindOf(style)
    val motion = animate && !LocalLook.current.reduceMotion
    val enter = remember(key) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(key, motion) { if (motion) enter.animateTo(1f, entrySpec(k)) }
    val looping = motion && heat >= loopFrom(k)
    val beat: State<Float> = if (looping) {
        rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(
            tween(if (k == Kind.GLOW || k == Kind.ORNATE) 3200 else 2400, easing = LinearEasing), RepeatMode.Restart))
    } else remember { mutableFloatStateOf(0f) }

    val fill = if (k == Kind.BLOCK) listOf(C.amber, C.amber) else colors
    val ink = when {
        k == Kind.BLOCK -> C.bg
        k.filled -> onColor(mid(fill))
        else -> tint
    }
    val label = when (k) {
        Kind.BRACKET -> "[ $text ]"
        Kind.ORNATE -> if (big) "✦  $text  ✦" else text
        else -> text
    }
    val padH: Dp = if (big) 18.dp else 9.dp
    val padV: Dp = if (big) 8.dp else 4.dp
    val darkPanel = Th.pal.dark

    Box(
        modifier
            .graphicsLayer {
                val e = enter.value
                when (k) {
                    Kind.BRACKET -> { alpha = if (e < 1f && ((e * 6f).toInt() % 2 == 1)) 0.25f else 1f }
                    Kind.STAMP -> { val s = 1.35f - 0.35f * e; scaleX = s; scaleY = s; alpha = e.coerceIn(0f, 1f); rotationZ = -3f }
                    Kind.TUBE -> { alpha = tubeAlpha(e) }
                    Kind.TINT, Kind.REACTION -> { val s = (if (k == Kind.TINT) 0.8f + 0.2f * e else e).coerceAtLeast(0f); scaleX = s; scaleY = s; alpha = e.coerceIn(0f, 1f) }
                    Kind.BLOCK -> { alpha = if (e < 1f && ((e * 4f).toInt() % 2 == 1)) 0f else 1f }
                    Kind.SLAB -> {
                        val wob = if (looping) sin(beat.value * 2f * PI.toFloat()) * 1.2f else 0f
                        val s = (0.3f + 0.7f * e).coerceAtLeast(0f); scaleX = s; scaleY = s; rotationZ = (1f - e) * -12f + wob
                    }
                    Kind.GLOW -> { translationY = (1f - e) * -8.dp.toPx(); alpha = e.coerceIn(0f, 1f) }
                    Kind.SIGN -> { rotationX = (1f - e) * -90f; cameraDistance = 10f * density }
                    Kind.CHIP -> { rotationY = (1f - e) * 90f; cameraDistance = 10f * density }
                    Kind.BEVEL -> {}
                    Kind.ORNATE -> { val s = 1.06f - 0.06f * e; scaleX = s; scaleY = s; alpha = e.coerceIn(0f, 1f) }
                }
                transformOrigin = TransformOrigin.Center
            }
            .drawWithContent {
                val px = 1.dp.toPx()
                val path = shape(k)
                val b = beat.value
                when (k) {
                    Kind.BRACKET -> drawPath(path, tint.copy(alpha = 0.10f + if (looping) 0.10f * (0.5f + 0.5f * sin(b * 2f * PI.toFloat())) else 0f))
                    Kind.STAMP -> {
                        drawPath(path, tint, style = Stroke(1.5f * px))
                        drawPath(shape(k, 2.8f * px), tint.copy(alpha = 0.75f), style = Stroke(0.8f * px))
                    }
                    Kind.TUBE -> {
                        val g = if (looping) 0.5f + 0.5f * sin(b * 2f * PI.toFloat()) else 0.5f
                        drawPath(path, tint.copy(alpha = 0.10f + 0.10f * g), style = Stroke(6f * px))
                        drawPath(path, tint.copy(alpha = 0.10f))
                        drawPath(path, tint, style = Stroke(1.5f * px))
                    }
                    Kind.TINT -> {
                        drawPath(path, tint.copy(alpha = if (darkPanel) 0.20f else 0.13f))
                        if (looping) shine(path, b, 0.22f)
                    }
                    Kind.BLOCK -> drawBox(Style.PIXEL, C.amber, C.amber, 1.dp.toPx())
                    Kind.SLAB -> {
                        val o = 3f * px
                        drawPath(Path().apply { addPath(path, Offset(o, o)) }, Color.Black.copy(alpha = 0.55f))
                        drawPath(path, Brush.linearGradient(fill, start = Offset.Zero, end = Offset(size.width, size.height)))
                        drawPath(path, C.line, style = Stroke(1.5f * px))
                    }
                    Kind.GLOW -> {
                        val g = if (looping) 0.6f + 0.4f * sin(b * 2f * PI.toFloat()) else 0.6f
                        drawPath(path, tint.copy(alpha = 0.05f * g), style = Stroke(9f * px))
                        drawPath(path, tint.copy(alpha = 0.09f * g), style = Stroke(5f * px))
                        drawPath(path, tint.copy(alpha = 0.10f))
                        drawPath(path, tint.copy(alpha = 0.85f), style = Stroke(1f * px))
                    }
                    Kind.SIGN -> {
                        drawPath(path, mid(fill))
                        drawCircle(ink, 3f * px, Offset(8f * px, size.height / 2f))
                    }
                    Kind.CHIP -> {
                        drawPath(path, Brush.verticalGradient(listOf(fill.last(), mid(fill), fill.first())))
                        drawPath(path, C.line, style = Stroke(1.5f * px))
                        drawPath(shape(k, 3f * px), Color.White.copy(alpha = 0.55f),
                            style = Stroke(1f * px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * px, 3f * px))))
                        if (looping) shine(path, b, 0.3f)
                    }
                    Kind.REACTION -> {
                        drawPath(path, tint.copy(alpha = if (darkPanel) 0.20f else 0.14f))
                        drawPath(path, tint.copy(alpha = 0.35f), style = Stroke(1f * px))
                    }
                    Kind.BEVEL -> {
                        drawRect(Brush.horizontalGradient(fill))
                        val w = size.width
                        val h = size.height
                        drawRect(Th.pal.white, Offset.Zero, Size(w, px)); drawRect(Th.pal.white, Offset.Zero, Size(px, h))
                        drawRect(Color.Black.copy(alpha = 0.75f), Offset(0f, h - px), Size(w, px))
                        drawRect(Color.Black.copy(alpha = 0.75f), Offset(w - px, 0f), Size(px, h))
                    }
                    Kind.ORNATE -> {
                        drawPath(path, tint.copy(alpha = 0.08f))
                        drawPath(path, tint, style = Stroke(1f * px))
                        drawPath(shape(k, 2.5f * px), tint.copy(alpha = 0.45f), style = Stroke(0.7f * px))
                        if (looping) shine(path, b, 0.22f)
                    }
                }
                drawContent()
            }
            .padding(start = padH + if (k == Kind.SIGN) 8.dp else 0.dp, end = padH, top = padV, bottom = padV),
        contentAlignment = Alignment.Center,
    ) {
        val shadow = if (k.filled && !ink.isDark() && k != Kind.BLOCK) Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 1.5f), 2f) else null
        val upper = k == Kind.STAMP || k == Kind.ORNATE || k == Kind.SIGN
        val base = if (big) TextStyle(
            color = ink, fontSize = textSize, fontWeight = FontWeight.Bold, fontFamily = LocalDisplay.current,
            letterSpacing = if (upper) 2.sp else 1.sp, textAlign = TextAlign.Center,
        ) else termStyle(size = textSize, color = ink, weight = if (k == Kind.REACTION || k == Kind.TINT) FontWeight.SemiBold else FontWeight.Bold,
            glow = if (k == Kind.TUBE || k == Kind.GLOW) 0.8f else 0f, spacing = if (upper) 1.2.sp else 0.5.sp)
        val text0 = @Composable { BasicText(label, style = if (shadow != null) base.copy(shadow = shadow) else base, maxLines = if (big) 2 else 1) }
        if (icon != null && CustomLooks.icons[icon] != null) {
            // the player's own icon in front of the name
            val d = with(LocalDensity.current) { (textSize * (if (big) 1.25f else 1.45f)).toDp() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CustomIcon(icon, d, animated = animate)
                Spacer(Modifier.width(if (big) 8.dp else 5.dp))
                text0()
            }
        } else text0()
    }
}

// ------------------------------------------------------------------ tier badge

/** Tier of one answer, in the active theme's badge shape. [animate] = false for long lists (tables). */
@Composable
fun TierBadge(tier: Tier, size: TextUnit = 13.sp, animate: Boolean = true) {
    Badge(tier.label, tierLook(tier).gradient, tierColor(tier), tier.heat, size, big = false, animate = animate, key = tier,
        icon = if (tier.isValid) customKey(tier) else null)
}

// ------------------------------------------------------------------ rank badge

/** Readable colour of a rank on the current panels (lightest stop on dark panels, darkest on light ones). */
fun rankColor(r: Rank): Color {
    CustomLooks.color(customKey(r))?.let { c -> return if (Th.pal.dark) CustomLooks.onDark(c) else CustomLooks.onLight(c) }
    if (Th.style == Style.PIXEL) return C.amber
    val g = rankGradient(r)
    return if (Th.pal.dark) g.last() else g.first()
}

/** End-of-run title as a plaque in the theme's badge shape; higher ranks keep a slow loop. */
@Composable
fun RankBadge(rank: Rank, modifier: Modifier = Modifier, size: TextUnit = 24.sp) {
    Badge(rank.title, rankGradient(rank), rankColor(rank), rank.ordinal, size, big = true, animate = true, key = rank, modifier = modifier,
        icon = customKey(rank))
}

// ------------------------------------------------------------------ reward burst

/** Shape of the burst per theme. */
private enum class Burst { SQUARES, INK, SPARKS, CONFETTI, PIXELS, ACTION, BUBBLES, STREAKS, COINS, HEARTS, ZOOM, STARS }

private fun burstOf(style: Style): Burst = when (style) {
    Style.TERMINAL -> Burst.SQUARES
    Style.PAPER -> Burst.INK
    Style.NEON -> Burst.SPARKS
    Style.CLEAN -> Burst.CONFETTI
    Style.PIXEL -> Burst.PIXELS
    Style.COMIC -> Burst.ACTION
    Style.ABYSS -> Burst.BUBBLES
    Style.METRO -> Burst.STREAKS
    Style.JACKPOT -> Burst.COINS
    Style.CHAT -> Burst.HEARTS
    Style.DESKTOP -> Burst.ZOOM
    Style.ORACLE -> Burst.STARS
}

private class Particle(val a: Float, val b: Float, val c: Float, val size: Float, val color: Int)

private fun DrawScope.heart(c: Offset, s: Float, color: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y + s * 0.9f)
        cubicTo(c.x - s * 1.4f, c.y - s * 0.1f, c.x - s * 0.6f, c.y - s * 1.1f, c.x, c.y - s * 0.35f)
        cubicTo(c.x + s * 0.6f, c.y - s * 1.1f, c.x + s * 1.4f, c.y - s * 0.1f, c.x, c.y + s * 0.9f)
        close()
    }
    drawPath(p, color)
}

/**
 * Short burst for an accepted answer, shaped like the theme (bubbles, coins, hearts, window outlines…), in the tier's
 * colours; rarer tiers get a few more pieces. The points float up once. Plays once per [key].
 */
@Composable
fun RewardBurst(tier: Tier, points: Int, key: Any, modifier: Modifier = Modifier) {
    if (!tier.isValid || LocalLook.current.reduceMotion) return
    val style = Th.style
    val kind = burstOf(style)
    val p = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { p.animateTo(1f, tween(if (kind == Burst.BUBBLES || kind == Burst.STARS) 1700 else 1200, easing = LinearEasing)) }
    val heat = tier.heat
    val hyper = LocalHyper.current.on
    val shift = remember(key) { Random.nextFloat() * 360f }
    val colors = if (hyper) rainbow(shift, 6).dropLast(1) else when (kind) {
        Burst.PIXELS -> listOf(C.amber, C.dim)
        Burst.COINS -> listOf(Color(0xFFFFD45A), Color(0xFFE3A51A), Color(0xFFFFF1B8))
        Burst.INK -> listOf(tierColor(tier), C.amber)
        else -> tierLook(tier).gradient
    }
    val parts = remember(key) {
        val n = intArrayOf(0, 5, 7, 10, 14, 20, 28)[heat.coerceIn(0, 6)] * (if (hyper) 2 else 1)
        List(n) { Particle(Random.nextFloat(), Random.nextFloat(), Random.nextFloat(), 3f + Random.nextFloat() * (2f + heat * 0.6f), Random.nextInt(colors.size)) }
    }
    val t = p.value
    if (t >= 1f) return
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val o = Offset(w / 2f, h * 0.42f)
            val reach = max(w, h) * 0.38f
            val ease = FastOutSlowInEasing.transform(t)
            val fade = (1f - t).coerceIn(0f, 1f)
            when (kind) {
                Burst.ZOOM -> {
                    // old-desktop "window opening" outlines growing from the centre
                    for (i in 0 until 3 + heat / 2) {
                        val q = (ease - i * 0.12f).coerceIn(0f, 1f)
                        if (q <= 0f) continue
                        val rw = w * 0.15f + w * 0.7f * q
                        val rh = h * 0.08f + h * 0.36f * q
                        drawRect(colors[i % colors.size].copy(alpha = fade * 0.8f), Offset(o.x - rw / 2, o.y - rh / 2), Size(rw, rh),
                            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))))
                    }
                }
                Burst.STREAKS -> {
                    for (q in parts) {
                        val y = h * (0.25f + 0.4f * q.a)
                        val len = w * (0.15f + 0.25f * q.b)
                        val x = -len + (w + len * 2) * FastOutSlowInEasing.transform((t * (1.1f + q.c * 0.5f)).coerceAtMost(1f))
                        drawLine(colors[q.color].copy(alpha = fade * 0.7f), Offset(x - len, y), Offset(x, y), strokeWidth = (1.5f + q.c * 2f).dp.toPx())
                    }
                }
                Burst.SPARKS -> {
                    if (heat >= 4) drawCircle(colors.last().copy(alpha = (1f - t * 1.6f).coerceIn(0f, 1f) * 0.45f),
                        reach * FastOutSlowInEasing.transform(min(1f, t * 1.6f)), o, style = Stroke(2.dp.toPx()))
                    for (q in parts) {
                        val ang = q.a * 2f * PI.toFloat()
                        val d = reach * (0.35f + 0.65f * q.b) * ease
                        val x = o.x + cos(ang) * d
                        val y = o.y + sin(ang) * d
                        val l = q.size.dp.toPx() * 1.6f
                        drawLine(colors[q.color].copy(alpha = fade), Offset(x - cos(ang) * l, y - sin(ang) * l), Offset(x, y), strokeWidth = 1.5.dp.toPx())
                    }
                }
                Burst.ACTION -> {
                    // radial speed lines that shoot out and vanish
                    for ((i, q) in parts.withIndex()) {
                        val ang = (i.toFloat() / parts.size + q.a * 0.05f) * 2f * PI.toFloat()
                        val d0 = reach * (0.25f + 0.6f * ease)
                        val d1 = d0 + reach * 0.22f * (1f - t)
                        drawLine(C.line.copy(alpha = fade), Offset(o.x + cos(ang) * d0, o.y + sin(ang) * d0),
                            Offset(o.x + cos(ang) * d1, o.y + sin(ang) * d1), strokeWidth = (2f + q.c * 2f).dp.toPx())
                    }
                }
                else -> parts.forEach { q ->
                    val c = colors[q.color].copy(alpha = fade)
                    val s = q.size.dp.toPx()
                    when (kind) {
                        Burst.BUBBLES -> {
                            val x = o.x + (q.a - 0.5f) * w * 0.6f + sin((t * 3f + q.b) * 2f * PI.toFloat()) * 6.dp.toPx()
                            val y = h * 0.62f - h * 0.5f * t * (0.6f + q.c * 0.6f)
                            drawCircle(c.copy(alpha = fade * 0.8f), s * 0.9f, Offset(x, y), style = Stroke(1.dp.toPx()))
                        }
                        Burst.COINS -> {
                            val x = o.x + (q.a - 0.5f) * w * 0.7f
                            val y = -s + (h * 0.75f) * (t * (0.8f + q.c * 0.5f)).coerceAtMost(1f) * (0.5f + 0.5f * q.b)
                            val squash = abs(cos((t * 4f + q.b) * 2f * PI.toFloat()))
                            drawOval(c, Offset(x - s * squash, y - s), Size(2 * s * squash.coerceAtLeast(0.15f), 2 * s))
                        }
                        Burst.HEARTS -> {
                            val x = w * (0.62f + 0.3f * q.a) + sin((t * 2f + q.b) * 2f * PI.toFloat()) * 8.dp.toPx()
                            val y = h * 0.86f - h * 0.55f * t * (0.6f + q.c * 0.5f)
                            heart(Offset(x, y), s, c)
                        }
                        Burst.STARS -> {
                            val x = o.x + (q.a - 0.5f) * w * 0.8f
                            val y = o.y + (q.b - 0.3f) * h * 0.25f - h * 0.12f * t
                            val tw = sin((t + q.c) * PI.toFloat() * 3f).let { it * it }
                            twinkle(Offset(x, y), s * (0.6f + tw), c)
                        }
                        Burst.PIXELS -> {
                            val st = (t * 6f).toInt() / 6f
                            val ang = q.a * 2f * PI.toFloat()
                            val d = reach * (0.3f + 0.6f * q.b) * st
                            val g = 4.dp.toPx()
                            val x = ((o.x + cos(ang) * d) / g).toInt() * g
                            val y = ((o.y + sin(ang) * d) / g).toInt() * g
                            drawRect(c, Offset(x, y), Size(g, g))
                        }
                        Burst.SQUARES -> {
                            val ang = q.a * 2f * PI.toFloat()
                            val d = reach * (0.3f + 0.6f * q.b) * ease
                            drawRect(c, Offset(o.x + cos(ang) * d, o.y + sin(ang) * d), Size(s * 0.6f, s * 0.6f))
                        }
                        Burst.INK -> {
                            val ang = q.a * 2f * PI.toFloat()
                            val d = reach * 0.45f * (0.3f + 0.7f * q.b) * FastOutSlowInEasing.transform(min(1f, t * 3f))
                            drawCircle(c.copy(alpha = fade * 0.8f), s * (0.3f + 0.4f * q.c), Offset(o.x + cos(ang) * d, o.y + sin(ang) * d))
                        }
                        else -> {   // CONFETTI
                            val ang = q.a * 2f * PI.toFloat()
                            val d = reach * (0.3f + 0.7f * q.b) * ease
                            val x = o.x + cos(ang) * d
                            val y = o.y + sin(ang) * d + 180f * t * t
                            rotate(q.c * 360f * (1f + t), Offset(x, y)) {
                                drawRect(c, Offset(x - s / 2, y - s * 0.3f), Size(s, s * 0.6f))
                            }
                        }
                    }
                }
            }
        }
        if (points > 0) {
            TText(
                "+$points", Modifier.align(Alignment.Center).graphicsLayer {
                    val up = FastOutSlowInEasing.transform(min(1f, t * 1.3f))
                    translationY = -110f * up
                    val s = 0.7f + 0.5f * min(1f, t * 4f) - 0.2f * t
                    scaleX = s; scaleY = s
                    alpha = (1f - (t - 0.5f).coerceAtLeast(0f) * 2f).coerceIn(0f, 1f)
                },
                size = (24 + heat * 3).sp, color = if (style == Style.PIXEL) C.amber else tierColor(tier),
                weight = FontWeight.Bold, glow = 1.2f, align = TextAlign.Center,
            )
        }
    }
}
