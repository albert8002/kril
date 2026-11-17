package app.coredump.sigil

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One camera frame: luminance, one byte per pixel (row [stride], first pixel at [offset]), taken at [tMs] (a
 * monotonic clock, milliseconds). Shared tools are computed on first use and kept for every reader of this frame:
 * the integral image, thresholded images at a few window sizes, blobs (connected components) in them.
 */
class SigilFrame(val lum: ByteArray, val w: Int, val h: Int, val stride: Int = w, val offset: Int = 0, val tMs: Long = 0L) {

    /** Luminance of pixel (x, y), 0..255 (no bounds check). */
    fun px(x: Int, y: Int): Int = lum[offset + y * stride + x].toInt() and 0xFF

    /** Bilinear luminance at (x, y) in pixels (clamped to the frame). */
    fun at(x: Float, y: Float): Float {
        val xc = x.coerceIn(0f, w - 1.001f); val yc = y.coerceIn(0f, h - 1.001f)
        val x0 = xc.toInt(); val y0 = yc.toInt()
        val fx = xc - x0; val fy = yc - y0
        val a = px(x0, y0); val b = px(x0 + 1, y0); val c = px(x0, y0 + 1); val d = px(x0 + 1, y0 + 1)
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    /** Mean luminance in a disc of radius [r] pixels round (x, y), from up to 21 taps (cheap, sub-pixel). */
    fun disc(x: Float, y: Float, r: Float): Float {
        if (r < 0.75f) return at(x, y)
        var s = at(x, y); var n = 1
        for (ring in 1..2) {
            val rr = r * ring / 2.5f
            val k = if (ring == 1) 6 else 14
            for (i in 0 until k) {
                val a = i * 6.2831855f / k + ring * 0.3f
                s += at(x + rr * kotlin.math.cos(a), y + rr * kotlin.math.sin(a)); n++
            }
        }
        return s / n
    }

    // ------------------------------------------------------------------ integral image

    /** Summed-area table, (w + 1) × (h + 1). */
    val integral: IntArray by lazy {
        val iw = w + 1
        val t = IntArray(iw * (h + 1))
        for (y in 0 until h) {
            var row = 0
            val base = offset + y * stride
            for (x in 0 until w) {
                row += lum[base + x].toInt() and 0xFF
                t[(y + 1) * iw + x + 1] = t[y * iw + x + 1] + row
            }
        }
        t
    }

    /** Mean luminance of the box [x0, x1) × [y0, y1) (clamped; 0 if empty). */
    fun boxMean(x0: Int, y0: Int, x1: Int, y1: Int): Float {
        val a = x0.coerceIn(0, w); val b = x1.coerceIn(0, w); val c = y0.coerceIn(0, h); val d = y1.coerceIn(0, h)
        if (b <= a || d <= c) return 0f
        val iw = w + 1
        val t = integral
        val s = t[d * iw + b] - t[c * iw + b] - t[d * iw + a] + t[c * iw + a]
        return s.toFloat() / ((b - a) * (d - c))
    }

    // ------------------------------------------------------------------ thresholds and blobs

    private val bins = HashMap<Int, ByteArray>()
    private val blobCache = HashMap<Int, List<Blob>>()

    /**
     * 1 where the pixel (as a 3×3 mean) is darker than the mean of a window min(w, h) / [div] wide round it, else 0.
     * Typical [div]: 6 (big shapes), 12, 20 (small marks). Cached per [div].
     */
    fun dark(div: Int): ByteArray = bins.getOrPut(div) {
        val out = ByteArray(w * h)
        val rad = max(4, min(w, h) / div / 2)
        val iw = w + 1
        val t = integral
        for (y in 0 until h) {
            val y0 = max(0, y - rad); val y1 = min(h, y + rad + 1)
            val sy0 = max(0, y - 1); val sy1 = min(h, y + 2)
            for (x in 0 until w) {
                val x0 = max(0, x - rad); val x1 = min(w, x + rad + 1)
                val sum = t[y1 * iw + x1] - t[y0 * iw + x1] - t[y1 * iw + x0] + t[y0 * iw + x0]
                val area = (y1 - y0) * (x1 - x0)
                val sx0 = max(0, x - 1); val sx1 = min(w, x + 2)
                val own = t[sy1 * iw + sx1] - t[sy0 * iw + sx1] - t[sy1 * iw + sx0] + t[sy0 * iw + sx0]
                val ownArea = (sy1 - sy0) * (sx1 - sx0)
                out[y * w + x] = if (own.toLong() * area < sum.toLong() * ownArea) 1 else 0
            }
        }
        out
    }

    /**
     * Connected components (8-neighbour) of the dark ([dark] = true) or light pixels of [dark] (div), at most
     * [limit] blobs, smallest ones ([minArea] pixels) dropped. Cached per (div, dark).
     */
    fun blobs(div: Int, dark: Boolean, minArea: Int = 3, limit: Int = 4000): List<Blob> = blobCache.getOrPut(div * 2 + (if (dark) 1 else 0)) {
        val bin = dark(div)
        val want: Byte = if (dark) 1 else 0
        val label = IntArray(w * h) { -1 }
        val out = ArrayList<Blob>()
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (bin[start] != want || label[start] >= 0) continue
            var sp = 0
            stack[sp++] = start
            label[start] = out.size
            var area = 0; var sx = 0L; var sy = 0L; var sl = 0L
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w; val y = p / w
                area++; sx += x; sy += y; sl += px(x, y)
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w || (dx == 0 && dy == 0)) continue
                        val q = yy * w + xx
                        if (bin[q] == want && label[q] < 0) { label[q] = out.size; stack[sp++] = q }
                    }
                }
            }
            if (area >= minArea) out.add(Blob(area, sx.toFloat() / area, sy.toFloat() / area, x0, y0, x1, y1, sl.toFloat() / area))
            else {
                // too small: keep the label number free for the next blob
                for (yy in y0..y1) for (xx in x0..x1) if (label[yy * w + xx] == out.size) label[yy * w + xx] = Int.MAX_VALUE
            }
            if (out.size >= limit) break
        }
        out
    }

    /** A frame [factor] times smaller (box average), e.g. to keep big camera frames cheap. */
    fun shrink(factor: Int): SigilFrame {
        if (factor <= 1) return this
        val nw = w / factor; val nh = h / factor
        val out = ByteArray(nw * nh)
        val n = factor * factor
        for (y in 0 until nh) for (x in 0 until nw) {
            var s = 0
            for (dy in 0 until factor) for (dx in 0 until factor) s += px(x * factor + dx, y * factor + dy)
            out[y * nw + x] = (s / n).toByte()
        }
        return SigilFrame(out, nw, nh, nw, 0, tMs)
    }
}

/** A connected group of pixels: [area] pixels, centroid ([cx], [cy]), bounding box, mean luminance [lum]. */
class Blob(val area: Int, val cx: Float, val cy: Float, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val lum: Float) {
    val bw get() = x1 - x0 + 1
    val bh get() = y1 - y0 + 1
    /** Share of the bounding box it fills (1 = a full rectangle, ~0.785 = a disc). */
    val fill get() = area.toFloat() / (bw * bh)
}

/** Small geometry and statistics helpers for readers. */
object Fit {
    /** Least-squares circle through points: [cx, cy, r] or null. */
    fun circle(xs: FloatArray, ys: FloatArray, n: Int = xs.size): FloatArray? {
        if (n < 3) return null
        // x² + y² + D x + E y + F = 0
        val m = Array(3) { DoubleArray(4) }
        for (i in 0 until n) {
            val x = xs[i].toDouble(); val y = ys[i].toDouble()
            val row = doubleArrayOf(x, y, 1.0); val rhs = -(x * x + y * y)
            for (p in 0 until 3) { for (q in 0 until 3) m[p][q] += row[p] * row[q]; m[p][3] += row[p] * rhs }
        }
        val s = solve(m) ?: return null
        val cx = -s[0] / 2; val cy = -s[1] / 2
        val r2 = cx * cx + cy * cy - s[2]
        if (r2 <= 0) return null
        return floatArrayOf(cx.toFloat(), cy.toFloat(), sqrt(r2).toFloat())
    }

    /** Solves the augmented n × (n + 1) system in place (partial pivoting); null if singular. */
    fun solve(m: Array<DoubleArray>): DoubleArray? {
        val n = m.size
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            for (r in 0 until n) if (r != col) {
                val f = m[r][col] / m[col][col]
                if (f != 0.0) for (k in col..n) m[r][k] -= f * m[col][k]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    /**
     * Homography mapping 4 source points to 4 destination points (x0, y0, … x3, y3): 3×3 row-major with h[8] = 1,
     * or null. Use [apply] to map a point.
     */
    fun homography(src: FloatArray, dst: FloatArray): DoubleArray? {
        val m = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = src[2 * i].toDouble(); val y = src[2 * i + 1].toDouble()
            val u = dst[2 * i].toDouble(); val v = dst[2 * i + 1].toDouble()
            m[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            m[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        val s = solve(m) ?: return null
        return doubleArrayOf(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], 1.0)
    }

    /** [h] applied to (x, y): writes the mapped point into [out] (2 floats). */
    fun apply(h: DoubleArray, x: Float, y: Float, out: FloatArray) {
        val d = h[6] * x + h[7] * y + h[8]
        out[0] = ((h[0] * x + h[1] * y + h[2]) / d).toFloat()
        out[1] = ((h[3] * x + h[4] * y + h[5]) / d).toFloat()
    }

    /** Otsu's threshold of [v] (values any scale): the split value and the between-class share of variance (0..1). */
    fun otsu(v: FloatArray, n: Int = v.size): FloatArray {
        if (n < 2) return floatArrayOf(0f, 0f)
        val s = v.copyOf(n); s.sort()
        var total = 0.0; var sq = 0.0
        for (x in s) { total += x; sq += x.toDouble() * x }
        val mean = total / n
        val varT = sq / n - mean * mean
        if (varT <= 1e-9) return floatArrayOf(s[0], 0f)
        var best = -1.0; var split = s[0]
        var left = 0.0
        for (k in 1 until n) {
            left += s[k - 1]
            val w0 = k.toDouble() / n; val w1 = 1 - w0
            val m0 = left / k; val m1 = (total - left) / (n - k)
            val b = w0 * w1 * (m0 - m1) * (m0 - m1)
            if (b > best) { best = b; split = (s[k - 1] + s[k]) / 2 }
        }
        return floatArrayOf(split, (best / varT).toFloat())
    }
}
