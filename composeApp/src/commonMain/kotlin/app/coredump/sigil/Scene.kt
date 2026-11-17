package app.coredump.sigil

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One frame of a look's animated code, as plain shapes in the unit square (x right, y down, 0..1), so the app draws
 * exactly what the lab's camera simulator sees ([Scene.ops]). Colours are 0xAARRGGBB ints. Lengths (radius, width,
 * stroke) are in the same unit-square units. Stroke 0 = filled.
 *
 * The app fills the whole square with [Ink.plate] first (rounded corners per look), then draws the ops in order.
 */
class Scene {
    sealed class Op {
        class Circle(val x: Float, val y: Float, val r: Float, val color: Int, val stroke: Float) : Op()
        /** A rectangle centred on (x, y), [rot] degrees clockwise about its centre, [corner] radius. */
        class Rect(val x: Float, val y: Float, val w: Float, val h: Float, val color: Int, val rot: Float, val corner: Float, val stroke: Float) : Op()
        /** A closed polygon: x0, y0, x1, y1 … */
        class Poly(val pts: FloatArray, val color: Int, val stroke: Float) : Op()
        class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float, val color: Int, val round: Boolean) : Op()
        /** A stroked arc of a circle: start and sweep in degrees, clockwise from +x. */
        class Arc(val x: Float, val y: Float, val r: Float, val start: Float, val sweep: Float, val width: Float, val color: Int) : Op()
        /** A soft round glow: [color] in the middle fading to transparent at [r]. */
        class Glow(val x: Float, val y: Float, val r: Float, val color: Int) : Op()
        /** An ellipse centred on (x, y) with radii [rx], [ry], turned [rot] degrees. */
        class Oval(val x: Float, val y: Float, val rx: Float, val ry: Float, val rot: Float, val color: Int, val stroke: Float) : Op()
    }

    val ops = ArrayList<Op>(256)

    fun clear() = ops.clear()
    fun circle(x: Float, y: Float, r: Float, color: Int, stroke: Float = 0f) { ops.add(Op.Circle(x, y, r, color, stroke)) }
    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int, rot: Float = 0f, corner: Float = 0f, stroke: Float = 0f) {
        ops.add(Op.Rect(x, y, w, h, color, rot, corner, stroke))
    }
    fun poly(pts: FloatArray, color: Int, stroke: Float = 0f) { ops.add(Op.Poly(pts, color, stroke)) }
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int, round: Boolean = true) {
        ops.add(Op.Line(x1, y1, x2, y2, width, color, round))
    }
    fun arc(x: Float, y: Float, r: Float, start: Float, sweep: Float, width: Float, color: Int) { ops.add(Op.Arc(x, y, r, start, sweep, width, color)) }
    fun glow(x: Float, y: Float, r: Float, color: Int) { ops.add(Op.Glow(x, y, r, color)) }
    fun oval(x: Float, y: Float, rx: Float, ry: Float, color: Int, rot: Float = 0f, stroke: Float = 0f) {
        ops.add(Op.Oval(x, y, rx, ry, rot, color, stroke))
    }
}

/**
 * The colours a look's code may use. [main] and [alt] stand far enough from [plate] in lightness for a cheap camera
 * (data must live in that difference); [soft] and [faint] are close to the plate (decoration only, never data).
 */
class Ink(val plate: Int, val main: Int, val alt: Int, val soft: Int, val faint: Int) {
    /** The plate is dark (marks are lighter than the plate). */
    val dark: Boolean get() = Colors.light(plate) < 0.5f

    companion object {
        /** The two candidates furthest from [plate] in lightness (at least 0.38 apart), else black or white. */
        fun of(plate: Int, candidates: List<Int>): Ink {
            val lp = Colors.light(plate)
            val far = if (lp > 0.5f) 0xFF111111.toInt() else 0xFFF6F6F6.toInt()
            val good = candidates.filter { abs(Colors.light(it) - lp) >= 0.38f }.sortedByDescending { abs(Colors.light(it) - lp) }
            val main = good.firstOrNull() ?: far
            val alt = good.getOrNull(1) ?: main
            return Ink(plate or (0xFF shl 24), main, alt, Colors.mix(plate, main, 0.22f), Colors.mix(plate, main, 0.11f))
        }
    }
}

/** Colour helpers on 0xAARRGGBB ints. */
object Colors {
    fun a(c: Int) = (c ushr 24) and 0xFF
    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF
    fun argb(a: Int, r: Int, g: Int, b: Int) = (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** [c] with alpha [alpha] (0..1). */
    fun alpha(c: Int, alpha: Float) = (c and 0xFFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24)

    /** Straight mix of two colours (alpha too). */
    fun mix(x: Int, y: Int, f: Float): Int {
        val k = f.coerceIn(0f, 1f)
        fun ch(p: Int, q: Int) = (p + (q - p) * k).roundToInt()
        return argb(ch(a(x), a(y)), ch(r(x), r(y)), ch(g(x), g(y)), ch(b(x), b(y)))
    }

    private fun lin(v: Int): Float { val s = v / 255f; return if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f) }

    /** Relative luminance (0..1). */
    fun luminance(c: Int) = 0.2126f * lin(r(c)) + 0.7152f * lin(g(c)) + 0.0722f * lin(b(c))

    /** Perceived lightness (√luminance, 0..1): what the camera's luma roughly follows. */
    fun light(c: Int) = sqrt(luminance(c))

    /** A colour from hue (degrees), saturation, value (0..1). */
    fun hsv(h: Float, s: Float, v: Float, alpha: Float = 1f): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val i = hh.toInt(); val f = hh - i
        val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
        val (r, g, b) = when (i) { 0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t); 3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q) }
        return argb((alpha * 255).roundToInt(), (r * 255).roundToInt(), (g * 255).roundToInt(), (b * 255).roundToInt())
    }

    /**
     * [c] turned [deg] round the colour wheel, then pulled to the lightness of [c] again (so a hue animation keeps the
     * camera's contrast).
     */
    fun hueShift(c: Int, deg: Float): Int {
        val rr = r(c) / 255f; val gg = g(c) / 255f; val bb = b(c) / 255f
        val mx = max(rr, max(gg, bb)); val mn = min(rr, min(gg, bb))
        val d = mx - mn
        var h = when {
            d == 0f -> 0f
            mx == rr -> 60f * (((gg - bb) / d) % 6f)
            mx == gg -> 60f * ((bb - rr) / d + 2f)
            else -> 60f * ((rr - gg) / d + 4f)
        }
        if (h < 0) h += 360f
        val s = if (mx == 0f) 0f else d / mx
        var out = hsv(h + deg, s, mx, a(c) / 255f)
        // keep the lightness
        val want = light(c)
        repeat(6) {
            val have = light(out)
            if (abs(have - want) < 0.01f) return out
            out = if (have < want) mix(out, 0xFFFFFFFF.toInt(), min(1f, (want - have) * 1.5f)) else mix(out, 0xFF000000.toInt(), min(1f, (have - want) * 1.5f))
        }
        return out
    }
}
