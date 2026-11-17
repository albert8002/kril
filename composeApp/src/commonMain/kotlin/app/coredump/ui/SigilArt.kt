package app.coredump.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import app.coredump.net.ShortCode
import app.coredump.sigil.Ink
import app.coredump.sigil.Scene
import app.coredump.sigil.SigilPayload
import app.coredump.sigil.SigilSchemes
import kotlin.math.min

/**
 * The self-test's handle on sigils: it asks for one to be shown over everything ([show]) and reads back the pixels
 * the last sigil drew ([layer]) to decode them.
 */
object SigilProbe {
    /** Shown full-screen by the app root while set (self-test only). */
    var show by mutableStateOf<ShortCode.Decoded?>(null)
    /** While true, sigils also record what they draw into [layer]. */
    var armed = false
    var layer: GraphicsLayer? = null
}

/**
 * The host's join code, as the active look's own animated sigil ([SigilSchemes]: a particle ball, punched tape, a
 * synthwave sun, buzzer lamps, a rune circle, a starburst, a sonar, a loop train, a chip stack, a voice note, a
 * defragmenter, a constellation). The scheme draws plain shapes ([Scene]) 30 times a second; this draws them.
 * It always moves (even with reduced motion): some looks send their code over time.
 */
@Composable
fun ThemedSigil(room: String, broker: Int, party: Boolean, modifier: Modifier = Modifier) {
    val bits = remember(room, broker, party) { SigilPayload.bits(room, broker, party) }
    val style = Th.style
    val scheme = SigilSchemes.forLook(style.ordinal)
    val plate = C.panel
    val ink = remember(plate, C.amber, C.hot, C.white, C.line) {
        Ink.of(plate.toArgb(), listOf(C.amber.toArgb(), C.hot.toArgb(), C.white.toArgb(), C.line.toArgb()))
    }
    val clock = rememberClock(true, fps = 30)
    val layer = rememberGraphicsLayer()
    DisposableEffect(layer) {
        SigilProbe.layer = layer
        onDispose { if (SigilProbe.layer === layer) SigilProbe.layer = null }
    }
    val scene = remember { Scene() }
    Spacer(
        modifier.aspectRatio(1f).drawWithCache {
            val side = min(size.width, size.height)
            val corner = min(style.corner.toPx(), side * 0.06f)
            onDrawBehind {
                val t = clock.value
                scene.clear()
                scheme.draw(bits, t, ink, scene)
                if (SigilProbe.armed) {
                    layer.record { drawScene(scene, ink, side, corner) }
                    drawLayer(layer)
                } else {
                    drawScene(scene, ink, side, corner)
                }
            }
        },
    )
}

/** Draws a [Scene] (unit square) at [side] px, on the plate. */
internal fun DrawScope.drawScene(scene: Scene, ink: Ink, side: Float, corner: Float) {
    drawRoundRect(Color(ink.plate), Offset.Zero, Size(side, side), CornerRadius(corner, corner))
    for (op in scene.ops) {
        when (op) {
            is Scene.Op.Circle -> {
                val c = Offset(op.x * side, op.y * side)
                if (op.stroke > 0f) drawCircle(Color(op.color), op.r * side, c, style = Stroke(op.stroke * side))
                else drawCircle(Color(op.color), op.r * side, c)
            }
            is Scene.Op.Oval -> {
                val c = Offset(op.x * side, op.y * side)
                rotate(op.rot, c) {
                    val tl = Offset(c.x - op.rx * side, c.y - op.ry * side)
                    val sz = Size(2 * op.rx * side, 2 * op.ry * side)
                    if (op.stroke > 0f) drawOval(Color(op.color), tl, sz, style = Stroke(op.stroke * side))
                    else drawOval(Color(op.color), tl, sz)
                }
            }
            is Scene.Op.Rect -> {
                val c = Offset(op.x * side, op.y * side)
                rotate(op.rot, c) {
                    val tl = Offset(c.x - op.w * side / 2, c.y - op.h * side / 2)
                    val sz = Size(op.w * side, op.h * side)
                    val cr = CornerRadius(op.corner * side, op.corner * side)
                    if (op.stroke > 0f) drawRoundRect(Color(op.color), tl, sz, cr, style = Stroke(op.stroke * side))
                    else drawRoundRect(Color(op.color), tl, sz, cr)
                }
            }
            is Scene.Op.Poly -> {
                val n = op.pts.size / 2
                if (n >= 2) {
                    val p = Path()
                    for (i in 0 until n) {
                        val x = op.pts[2 * i] * side; val y = op.pts[2 * i + 1] * side
                        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    }
                    p.close()
                    if (op.stroke > 0f) drawPath(p, Color(op.color), style = Stroke(op.stroke * side, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    else drawPath(p, Color(op.color))
                }
            }
            is Scene.Op.Line -> drawLine(Color(op.color), Offset(op.x1 * side, op.y1 * side), Offset(op.x2 * side, op.y2 * side),
                op.width * side, if (op.round) StrokeCap.Round else StrokeCap.Butt)
            is Scene.Op.Arc -> drawArc(Color(op.color), op.start, op.sweep, false,
                Offset((op.x - op.r) * side, (op.y - op.r) * side), Size(2 * op.r * side, 2 * op.r * side), style = Stroke(op.width * side))
            is Scene.Op.Glow -> if (op.r > 0f) {
                val c = Offset(op.x * side, op.y * side)
                val col = Color(op.color)
                drawCircle(Brush.radialGradient(listOf(col, col.copy(alpha = 0f)), c, op.r * side), op.r * side, c)
            }
        }
    }
}
