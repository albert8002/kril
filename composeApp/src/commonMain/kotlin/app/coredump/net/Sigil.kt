package app.coredump.net

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sigil: CORE DUMP's own scannable join code. Every look has its own (a memory map, a halftone, a neon sun,
 * a honeycomb, a pixel gem, a starburst, a ball of glowing dots, a metro map, a roulette wheel, a chat spiral, a tile
 * board, a patch of night sky), and any phone reads any of them, because they share three things:
 *
 *  - a bullseye in the middle (disc, gap, ring: 1:1:3:1:1 across any line through the centre) — found in the camera
 *    frame like a QR finder, but the same from every direction; its edges give the tilt (an ellipse fit);
 *  - the look's emblem, alone just outside the code at [EMBLEM]: it says which way is up;
 *  - [CELLS] cells, each a mark or an empty spot, at the places its [layouts] entry gives (one layout per look):
 *    84 bits = 14 symbols of 6 bits, 8 of payload and 6 of Reed–Solomon parity over GF(64).
 *
 * The reader tries every layout (both ways round: a mirrored camera) and keeps the one whose parity checks out.
 * Payload (48 bits): version 0 (1), party (1), relay broker index (6), room code (40: base 31).
 * Lengths are in modules (u) from the centre; a drawing is 2 × [EXTENT] modules wide. Marks keep inside
 * [MARK_MIN]..[MARK_MAX] and at least a small gap from each other; between [MARK_MAX] and [EXTENT] nothing but the
 * emblem may stand out from the plate (soft decoration only).
 */
object Sigil {
    const val DISC = 1.5f
    const val GAP = 2.5f
    const val RING = 3.5f
    /** Cell centres lie between these radii; marks may reach [MARK_MIN]..[MARK_MAX]. */
    const val CELL_IN = 4.85f
    const val CELL_OUT = 8.65f
    const val MARK_MIN = 4.3f
    const val MARK_MAX = 9.15f
    const val OUTER_BG = 9.45f
    const val EMBLEM = 10.3f
    const val EMBLEM_R = 0.6f
    const val EXTENT = 11.4f
    const val CELLS = 84
    const val LAYOUTS = 12

    private const val N = 14
    private const val K = 8
    private const val NSYM = N - K
    /** Parity a camera read may spend (2·errors + erasures); the other 2 symbols only check. */
    const val BUDGET = 4

    /** The 84 bits of a code (true = mark), cell order of every layout. */
    fun encode(room: String, broker: Int, party: Boolean): BooleanArray {
        val r = room.uppercase().filter { it != '-' && it != ' ' }
        require(r.length == JoinTicket.ROOM_LEN && r.all { it in JoinTicket.ROOM_ALPHABET }) { "bad room code: $room" }
        require(broker in 0..63) { "broker index out of range: $broker" }
        var v = 0L
        for (c in r) v = v * JoinTicket.ROOM_ALPHABET.length + JoinTicket.ROOM_ALPHABET.indexOf(c)
        v = v or (broker.toLong() shl 40) or (if (party) 1L shl 46 else 0L)
        val msg = IntArray(K) { j -> ((v shr (6 * (K - 1 - j))) and 63L).toInt() }
        val cw = Rs.encode(msg)
        return BooleanArray(CELLS) { i -> ((cw[i / 6] xor WHITE[i / 6]) shr (5 - i % 6)) and 1 == 1 }
    }

    /** The payload of 14 read symbols (whitened, as drawn), after repair; null if it cannot be repaired. */
    internal fun decodeSymbols(sym: IntArray, erasures: List<Int>, budget: Int = BUDGET): ShortCode.Decoded? {
        val r = IntArray(N) { sym[it] xor WHITE[it] }
        val fixed = Rs.decode(r, erasures, budget) ?: return null
        var v = 0L
        for (j in 0 until K) v = (v shl 6) or fixed[NSYM + j].toLong()
        if (v shr 47 != 0L) return null
        val party = (v shr 46) and 1L == 1L
        val broker = ((v shr 40) and 63L).toInt()
        var roomV = v and ((1L shl 40) - 1)
        if (broker >= 36) return null
        val base = JoinTicket.ROOM_ALPHABET.length
        val chars = CharArray(JoinTicket.ROOM_LEN)
        for (i in JoinTicket.ROOM_LEN - 1 downTo 0) { chars[i] = JoinTicket.ROOM_ALPHABET[(roomV % base).toInt()]; roomV /= base }
        if (roomV != 0L) return null
        return ShortCode.Decoded(chars.concatToString(), broker, party)
    }

    /** Fixed whitening of the 14 symbols (about half the cells are marks, whatever the room). */
    private val WHITE = IntArray(N) { s -> ((s * 0x2B + 0x15) xor (s * s * 7) xor 0x2A) and 63 }

    // ------------------------------------------------------------------ layouts (one per look, Style order)

    /**
     * Layout [id]: x0, y0, x1, y1 … (modules, emblem along +x), 84 cells in reading order (round from the emblem,
     * then outwards: 6 neighbouring cells make one symbol). Fixed tables ([SigilLayouts]), so every phone and every
     * app version places the cells exactly alike.
     */
    val layouts: Array<FloatArray> get() = SigilLayouts.all

    /** Names, for logs. */
    val layoutNames = listOf("grid", "halftone", "stripes", "honeycomb", "diamond", "spokes", "sunflower", "metro", "roulette",
        "spiral", "tiles", "sky")

    /** Reed–Solomon over GF(64) (x^6 + x + 1), n = 14, k = 8, generator roots α^1..α^6, parity first. */
    internal object Rs {
        private val exp = IntArray(126)
        private val log = IntArray(64)

        init {
            var x = 1
            for (i in 0 until 63) {
                exp[i] = x; log[x] = i
                x = x shl 1
                if (x and 64 != 0) x = x xor 0x43
            }
            for (i in 63 until 126) exp[i] = exp[i - 63]
        }

        fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
        fun inv(a: Int) = exp[63 - log[a]]
        fun pow(i: Int) = exp[((i % 63) + 63) % 63]

        /** p(x) = Σ p[i] x^i at x. */
        fun eval(p: IntArray, x: Int): Int {
            var y = 0
            for (i in p.indices.reversed()) y = mul(y, x) xor p[i]
            return y
        }

        fun polyMul(a: IntArray, b: IntArray): IntArray {
            val r = IntArray(a.size + b.size - 1)
            for (i in a.indices) for (j in b.indices) r[i + j] = r[i + j] xor mul(a[i], b[j])
            return r
        }

        private val gen: IntArray by lazy {
            var g = intArrayOf(1)
            for (i in 1..NSYM) g = polyMul(g, intArrayOf(pow(i), 1))
            g
        }

        /** Codeword: parity at 0 until [NSYM], the message after it (symbol j at NSYM + j). */
        fun encode(msg: IntArray): IntArray {
            val c = IntArray(N)
            for (j in 0 until K) c[NSYM + j] = msg[j]
            // remainder of m(x)·x^NSYM divided by g(x) (g is monic, degree NSYM)
            val rem = c.copyOf()
            for (i in N - 1 downTo NSYM) {
                val f = rem[i]
                if (f != 0) for (j in 0..NSYM) rem[i - NSYM + j] = rem[i - NSYM + j] xor mul(f, gen[j])
            }
            for (i in 0 until NSYM) c[i] = rem[i]
            return c
        }

        /**
         * Repairs [r] (errors and the [erasures] positions); null if it cannot, or if it would spend more than
         * [budget] of the parity (2·errors + erasures): what is left over is what tells a misread from a code.
         */
        fun decode(r: IntArray, erasures: List<Int>, budget: Int = NSYM): IntArray? {
            val f = erasures.size
            if (f > budget) return null
            val s = IntArray(NSYM) { eval(r, pow(it + 1)) }
            if (s.all { it == 0 }) return r.copyOf()
            var gamma = intArrayOf(1)
            for (p in erasures) gamma = polyMul(gamma, intArrayOf(1, pow(p)))
            // Berlekamp–Massey started from the erasure locator (Blahut)
            var lam = gamma.copyOf()
            var b = gamma.copyOf()
            var l = f
            for (k in f + 1..NSYM) {
                var delta = 0
                for (j in lam.indices) { val si = k - 1 - j; if (si >= 0) delta = delta xor mul(lam[j], s[si]) }
                val xb = IntArray(b.size + 1).also { for (i in b.indices) it[i + 1] = b[i] }
                val next = IntArray(max(lam.size, xb.size))
                for (i in lam.indices) next[i] = lam[i]
                for (i in xb.indices) next[i] = next[i] xor mul(delta, xb[i])
                if (delta != 0 && 2 * l <= k + f - 1) {
                    val di = inv(delta)
                    b = IntArray(lam.size) { mul(lam[it], di) }
                    l = k + f - l
                } else b = xb
                lam = next
            }
            var deg = lam.size - 1
            while (deg > 0 && lam[deg] == 0) deg--
            val e = deg - f
            if (deg == 0 || 2 * e + f > budget) return null
            val psi = lam.copyOf(deg + 1)
            val pos = ArrayList<Int>()
            for (i in 0 until N) if (eval(psi, pow(-i)) == 0) pos.add(i)
            if (pos.size != deg) return null
            // Ω = S(x)·Ψ(x) mod x^NSYM; Forney: e = Ω(X⁻¹) / Ψ'(X⁻¹) (b = 1)
            val om = IntArray(NSYM)
            for (i in 0 until NSYM) for (j in 0..min(i, deg)) om[i] = om[i] xor mul(s[i - j], psi[j])
            val d = IntArray(deg) { j -> if ((j + 1) % 2 == 1) psi[j + 1] else 0 }
            val out = r.copyOf()
            for (p in pos) {
                val xi = pow(-p)
                val den = eval(d, xi)
                if (den == 0) return null
                out[p] = out[p] xor mul(eval(om, xi), inv(den))
            }
            for (i in 0 until NSYM) if (eval(out, pow(i + 1)) != 0) return null
            return out
        }
    }
}

/**
 * Reads sigils in camera frames (luminance, one byte per pixel). One reader per scanner: it keeps its buffers, and
 * [scan] only answers when two frames out of the last few agree (a single misread frame never joins a wrong room).
 */
class SigilReader {
    companion object { internal var PASSES = intArrayOf(14, 9, 20) }
    private var w = 0
    private var h = 0
    private var integ = IntArray(0)
    private var bin = ByteArray(0)
    private var lum = ByteArray(0)
    private var stride = 0
    private var offset = 0

    private val recent = ArrayDeque<String>()

    /** Debug of the last [read] (finder, fit, timing …), for tests. */
    var trace: String = ""
        private set

    /** The short code ("CD1:…") once two of the last four frames agree, else null. */
    fun scan(lum: ByteArray, w: Int, h: Int, stride: Int = w, offset: Int = 0): String? {
        val d = read(lum, w, h, stride, offset)
        val code = d?.let { ShortCode.encode(it.room, it.broker, it.party) }
        recent.addLast(code ?: "")
        while (recent.size > 4) recent.removeFirst()
        return if (code != null && recent.count { it == code } >= 2) code else null
    }

    /** One frame: the code in it, or null. */
    fun read(lum: ByteArray, w: Int, h: Int, stride: Int = w, offset: Int = 0): ShortCode.Decoded? {
        if (w < 40 || h < 40) return null
        this.lum = lum; this.w = w; this.h = h; this.stride = stride; this.offset = offset
        trace = ""
        // three threshold windows: about 1/14 of the frame, then 1/9 (big codes, soft light), then 1/20 (small, blurred)
        for ((pass, div) in PASSES.withIndex()) {
            binarize(div)
            val found = finders()
            trace += (if (pass > 0) " | " else "") + "finders=${found.size}"
            for (c in found.take(4)) {
                val d = attempt(c)
                if (d != null) return d
            }
        }
        return null
    }

    private fun px(x: Int, y: Int): Int = lum[offset + y * stride + x].toInt() and 0xFF

    /** Dark = 1, against the mean of a window of about a fifth of the frame. */
    private fun binarize(div: Int) {
        val iw = w + 1
        if (integ.size < iw * (h + 1)) integ = IntArray(iw * (h + 1))
        if (bin.size < w * h) bin = ByteArray(w * h)
        for (y in 0 until h) {
            var row = 0
            val base = offset + y * stride
            for (x in 0 until w) {
                row += lum[base + x].toInt() and 0xFF
                integ[(y + 1) * iw + x + 1] = integ[y * iw + x + 1] + row
            }
        }
        val rad = max(8, min(w, h) / div)
        for (y in 0 until h) {
            val y0 = max(0, y - rad); val y1 = min(h, y + rad + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - rad); val x1 = min(w, x + rad + 1)
                val sum = integ[y1 * iw + x1] - integ[y0 * iw + x1] - integ[y1 * iw + x0] + integ[y0 * iw + x0]
                val area = (y1 - y0) * (x1 - x0)
                // the pixel itself as a 3×3 mean (noise), against the window's mean
                val sx0 = max(0, x - 1); val sx1 = min(w, x + 2); val sy0 = max(0, y - 1); val sy1 = min(h, y + 2)
                val own = integ[sy1 * iw + sx1] - integ[sy0 * iw + sx1] - integ[sy1 * iw + sx0] + integ[sy0 * iw + sx0]
                val ownArea = (sy1 - sy0) * (sx1 - sx0)
                bin[y * w + x] = if (own.toLong() * area < sum.toLong() * ownArea) 1 else 0
            }
        }
    }

    private fun b(x: Int, y: Int): Int = bin[y * w + x].toInt()

    private class Cand(var x: Float, var y: Float, var unit: Float, val mark: Int, var hits: Int = 1)

    /** 1:1:3:1:1 runs (both colours) in rows, checked down the column and both diagonals; clustered. */
    private fun finders(): List<Cand> {
        val out = ArrayList<Cand>()
        val runs = IntArray(w + 1)
        val cols = IntArray(w + 1)
        val step = if (h > 360) 2 else 1
        var y = 0
        while (y < h) {
            var n = 0
            var x = 0
            while (x < w) {
                val c = b(x, y)
                var e = x
                while (e < w && b(e, y) == c) e++
                runs[n] = e - x; cols[n] = c; n++
                x = e
            }
            var start = 0
            for (i in 0 until n) {
                if (i >= 4) {
                    val r0 = runs[i - 4]; val r1 = runs[i - 3]; val r2 = runs[i - 2]; val r3 = runs[i - 1]; val r4 = runs[i]
                    if (ratioOk(r0, r1, r2, r3, r4)) {
                        val sx = start - r3 - r2   // start of the centre run (start = where run i begins)
                        val cx = sx + r2 / 2f
                        val mark = cols[i - 2]
                        confirm(cx, y.toFloat(), (r0 + r1 + r2 + r3 + r4) / 7f, mark)?.let { add(out, it) }
                    }
                }
                start += runs[i]
            }
            y += step
        }
        out.sortByDescending { it.hits * 1000 + it.unit }
        return out
    }

    private fun ratioOk(r0: Int, r1: Int, r2: Int, r3: Int, r4: Int): Boolean {
        val total = r0 + r1 + r2 + r3 + r4
        if (total < 12) return false
        val u = total / 7f
        val v = u * 0.6f
        return abs(r0 - u) < v && abs(r1 - u) < v && abs(r3 - u) < v && abs(r4 - u) < v && abs(r2 - 3 * u) < 3 * v
    }

    private fun confirm(cx: Float, cy: Float, unit: Float, mark: Int): Cand? {
        val x = cx.toInt(); val y = cy.toInt()
        if (x !in 0 until w || y !in 0 until h || b(x, y) != mark) return null
        val v = cross(x, y, 0, 1, mark, unit) ?: return null
        val ny = (y + v[0]).roundToInt().coerceIn(0, h - 1)
        val hz = cross(x, ny, 1, 0, mark, unit) ?: return null
        val nx = (x + hz[0]).roundToInt().coerceIn(0, w - 1)
        if (b(nx, ny) != mark) return null
        val d1 = cross(nx, ny, 1, 1, mark, unit) ?: return null
        val d2 = cross(nx, ny, 1, -1, mark, unit) ?: return null
        val u = (v[1] + hz[1] + (d1[1] + d2[1]) * 1.4142f) / 4f / 7f
        return Cand(nx.toFloat(), ny.toFloat(), u, mark)
    }

    /** Runs through (x, y) along (dx, dy): [offset of the centre run's middle, total length in steps] or null. */
    private fun cross(x0: Int, y0: Int, dx: Int, dy: Int, mark: Int, unit: Float): FloatArray? {
        val limit = (unit * 12 / (if (dx != 0 && dy != 0) 1.4142f else 1f)).toInt() + 4
        val r = IntArray(5)
        var x = x0; var y = y0
        var back = 0
        while (inside(x, y) && b(x, y) == mark && back < limit) { back++; x -= dx; y -= dy }
        if (!inside(x, y)) return null
        while (inside(x, y) && b(x, y) != mark && r[1] < limit) { r[1]++; x -= dx; y -= dy }
        if (!inside(x, y)) return null
        while (inside(x, y) && b(x, y) == mark && r[0] < limit) { r[0]++; x -= dx; y -= dy }
        x = x0 + dx; y = y0 + dy
        var fwd = 0
        while (inside(x, y) && b(x, y) == mark && fwd < limit) { fwd++; x += dx; y += dy }
        if (!inside(x, y)) return null
        while (inside(x, y) && b(x, y) != mark && r[3] < limit) { r[3]++; x += dx; y += dy }
        if (!inside(x, y)) return null
        while (inside(x, y) && b(x, y) == mark && r[4] < limit) { r[4]++; x += dx; y += dy }
        r[2] = back + fwd
        if (!ratioOk(r[0], r[1], r[2], r[3], r[4])) return null
        return floatArrayOf((fwd - back + 1) / 2f, (r[0] + r[1] + r[2] + r[3] + r[4]).toFloat())
    }

    private fun inside(x: Int, y: Int) = x in 0 until w && y in 0 until h

    private fun add(list: MutableList<Cand>, c: Cand) {
        for (o in list) {
            if (o.mark == c.mark && abs(o.x - c.x) < o.unit * 1.2f && abs(o.y - c.y) < o.unit * 1.2f) {
                val n = o.hits
                o.x = (o.x * n + c.x) / (n + 1); o.y = (o.y * n + c.y) / (n + 1); o.unit = (o.unit * n + c.unit) / (n + 1)
                o.hits = n + 1
                return
            }
        }
        list.add(c)
    }

    // ---- one candidate: ellipse fit, levels, timing, data

    /** Image point of the code point at radius [r] (modules), angle [t]: centre + L·(r cos t, r sin t). */
    private var ox = 0.0; private var oy = 0.0
    private var l00 = 0.0; private var l01 = 0.0; private var l11 = 0.0

    private fun ix(r: Float, t: Double) = ox + l00 * r * cos(t) + l01 * r * sin(t)
    private fun iy(r: Float, t: Double) = oy + l01 * r * cos(t) + l11 * r * sin(t)

    /** Bilinear luminance at an image point (clamped). */
    private fun lumAt(x: Double, y: Double): Float {
        val xc = x.coerceIn(0.0, w - 1.001); val yc = y.coerceIn(0.0, h - 1.001)
        val x0 = xc.toInt(); val y0 = yc.toInt()
        val fx = (xc - x0).toFloat(); val fy = (yc - y0).toFloat()
        val a = px(x0, y0); val bq = px(x0 + 1, y0); val c = px(x0, y0 + 1); val d = px(x0 + 1, y0 + 1)
        return (a * (1 - fx) + bq * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    /** Mean luminance round the code point (r, t): five taps, [dr] modules apart radially, [dt] radians round. */
    private fun tap(r: Float, t: Double, dr: Float = 0.12f, dt: Double = 0.0): Float {
        // [dr] modules out and in, the same distance round
        val da = if (dt != 0.0) dt else (dr / r).toDouble()
        return (lumAt(ix(r, t), iy(r, t)) + lumAt(ix(r + dr, t), iy(r + dr, t)) + lumAt(ix(r - dr, t), iy(r - dr, t)) +
            lumAt(ix(r, t + da), iy(r, t + da)) + lumAt(ix(r, t - da), iy(r, t - da))) / 5f
    }

    private val sectors = 24
    private val mk = FloatArray(sectors)
    private val bgIn = FloatArray(sectors)
    private val bgOut = FloatArray(sectors)

    /**
     * Markness at the code point (r, t): 0 = background, 1 = mark, against the local levels round the code (mark on
     * the bullseye ring, background in the gap, between ring and cells, and outside the cells; glare as additive).
     * [quick]: one tap instead of five.
     */
    private fun levelAt(t: Double, r: Float, quick: Boolean = false): Float {
        var a = (t / (2 * PI) * sectors) % sectors
        if (a < 0) a += sectors
        val i0 = a.toInt() % sectors; val i1 = (i0 + 1) % sectors; val f = (a - a.toInt()).toFloat()
        val m = mk[i0] * (1 - f) + mk[i1] * f
        val bi = bgIn[i0] * (1 - f) + bgIn[i1] * f
        val bo = bgOut[i0] * (1 - f) + bgOut[i1] * f
        val k = ((r - 3.0f) / (Sigil.OUTER_BG - 3.0f)).coerceIn(0f, 1f)
        val bg = bi + (bo - bi) * k
        val mark = m + (bg - bi)
        val span = mark - bg
        if (abs(span) < 6f) return 0.5f
        // quick (the turn search): a wide footprint, so a cell read off-centre gives an in-between value even in a
        // sharp picture, and the right turn stands out
        val v = if (quick) tap(r, t, 0.3f) else tap(r, t)
        return (v - bg) / span
    }

    private fun attempt(c: Cand): ShortCode.Decoded? {
        if (!fit(c)) return null
        for (i in 0 until sectors) {
            val t = 2 * PI * i / sectors
            mk[i] = tap(3.0f, t)
            bgIn[i] = (tap(2.0f, t) + tap(4.05f, t)) / 2f
            bgOut[i] = tap(Sigil.OUTER_BG, t)
        }
        val inner = mk.average().toFloat()
        val bgAvg = bgIn.average().toFloat()
        val contrast = inner - bgAvg
        if (abs(contrast) < 12f) { trace += " lowcontrast=${contrast.toInt()}"; return null }
        // a stray mark outside the cells: that sector takes the median background
        val outMed = bgOut.sortedArray()[sectors / 2]
        for (i in 0 until sectors) if (abs(bgOut[i] - outMed) > abs(contrast) * 0.35f) bgOut[i] = outMed
        fun level(t: Double, r: Float) = levelAt(t, r)

        // the emblem: the one mark just outside the code, which way is up
        val steps = 360
        val ring = FloatArray(steps) { j ->
            val t = 2 * PI * j / steps
            (level(t, Sigil.EMBLEM - 0.22f) + level(t, Sigil.EMBLEM) + level(t, Sigil.EMBLEM + 0.22f)) / 3f
        }
        val sm = FloatArray(steps) { j -> var a = 0f; for (d in -3..3) a += ring[(j + d + steps) % steps]; a / 7f }
        var peak = 0
        for (j in 1 until steps) if (sm[j] > sm[peak]) peak = j
        val med = sm.sortedArray()[steps / 2]
        if (sm[peak] < 0.2f || sm[peak] - med < 0.15f) { trace += " emblem=${(sm[peak] * 100).toInt()}/${(med * 100).toInt()}"; return null }
        var cx = 0.0; var cy = 0.0
        for (d in -9..9) {
            val j = (peak + d + steps) % steps
            val wgt = (ring[j] - med).coerceAtLeast(0f).toDouble()
            val t = 2 * PI * j / steps
            cx += wgt * cos(t); cy += wgt * sin(t)
        }
        val key = atan2(cy, cx)

        // every layout, both ways round, each turned a little either side of the emblem: the best fit first
        class Fit(val layout: Int, val dir: Int, val turn: Double, val score: Float)
        val fits = ArrayList<Fit>()
        val vals = FloatArray(Sigil.CELLS)
        val deg = PI / 180
        for (id in 0 until Sigil.LAYOUTS) {
            val cells = Sigil.layouts[id]
            for (dir in intArrayOf(1, -1)) {
                var best = -1f; var bestTurn = 0.0
                var d = -3.5
                while (d <= 3.51) {
                    sampleCells(cells, key + d * deg, dir, vals, quick = true)
                    val sc = separation(vals)
                    if (sc > best) { best = sc; bestTurn = d }
                    d += 0.5
                }
                fits.add(Fit(id, dir, bestTurn, best))
            }
        }
        fits.sortByDescending { it.score }
        for (f in fits) {
            if (f.score < 0.2f) break
            // a finer turn round the best one, then the full read
            var best = f.score; var turn = f.turn
            for (d in doubleArrayOf(f.turn - 0.25, f.turn + 0.25)) {
                sampleCells(Sigil.layouts[f.layout], key + d * deg, f.dir, vals, quick = true)
                val sc = separation(vals)
                if (sc > best) { best = sc; turn = d }
            }
            sampleCells(Sigil.layouts[f.layout], key + turn * deg, f.dir, vals, quick = false)
            // rescale between the two clusters (blur lowers small marks more than the bullseye ring)
            var lo = 0f; var hi = 1f
            repeat(8) {
                val mid = (lo + hi) / 2
                var sa = 0f; var na = 0; var sb = 0f; var nb = 0
                for (v in vals) if (v < mid) { sa += v; na++ } else { sb += v; nb++ }
                if (na > 0) lo = sa / na
                if (nb > 0) hi = sb / nb
            }
            val l = lo.coerceIn(-0.2f, 0.35f); val h = hi.coerceIn(0.45f, 1.1f)
            val sym = IntArray(14)
            val sure = FloatArray(14) { 9f }
            for (i in 0 until Sigil.CELLS) {
                val m = if (h - l > 0.25f) (vals[i] - l) / (h - l) else vals[i]
                if (m >= 0.5f) sym[i / 6] = sym[i / 6] or (1 shl (5 - i % 6))
                sure[i / 6] = min(sure[i / 6], abs(m - 0.5f))
            }
            val order = (0 until 14).sortedBy { sure[it] }
            Sigil.decodeSymbols(sym, emptyList())?.let { trace += " ok ${Sigil.layoutNames[f.layout]} dir=${f.dir}"; return it }
            for (n in intArrayOf(2, 4)) {
                val er = order.take(n).filter { sure[it] < 0.3f }
                if (er.isEmpty()) continue
                Sigil.decodeSymbols(sym, er)?.let { trace += " ok ${Sigil.layoutNames[f.layout]} dir=${f.dir} er=${er.size}"; return it }
            }
        }
        trace += " rs-fail(${fits.take(3).joinToString(",") { Sigil.layoutNames[it.layout] + ":" + (it.score * 100).toInt() }})"
        return null
    }

    /** Markness of every cell of a layout turned to [rot] (radians, emblem direction), read [dir]-wise round. */
    private fun sampleCells(cells: FloatArray, rot: Double, dir: Int, out: FloatArray, quick: Boolean) {
        for (i in 0 until Sigil.CELLS) {
            val x = cells[2 * i]; val y = cells[2 * i + 1]
            val r = sqrt(x * x + y * y)
            val t = rot + dir * atan2(y.toDouble(), x.toDouble())
            out[i] = levelAt(t, r, quick)
        }
    }

    /** How well the values split into marks and gaps: between-cluster share of the variance × the gap. */
    private fun separation(v: FloatArray): Float {
        val sorted = v.sortedArray()
        val n = sorted.size
        var total = 0.0; var sq = 0.0
        for (x in sorted) { total += x; sq += x * x }
        val mean = total / n
        val varT = sq / n - mean * mean
        if (varT <= 1e-6) return 0f
        var best = 0.0; var gap = 0.0
        var left = 0.0
        for (k in 1 until n) {
            left += sorted[k - 1]
            val w0 = k.toDouble() / n; val w1 = 1 - w0
            if (w0 < 0.15 || w1 < 0.15) continue
            val m0 = left / k; val m1 = (total - left) / (n - k)
            val b = w0 * w1 * (m0 - m1) * (m0 - m1)
            if (b > best) { best = b; gap = m1 - m0 }
        }
        return ((best / varT) * min(1.0, gap)).toFloat()
    }

    /**
     * Ellipse of the bullseye: along 64 rays from the finder, the middle of the gap (2.0 u) and of the ring (3.0 u)
     * (edge midpoints: the threshold's bias cancels); fitted as (d − t)ᵀM(d − t) = r², then L = M^(−1/2).
     */
    private fun fit(c: Cand): Boolean {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val rs = ArrayList<Double>()
        val maxR = c.unit * 6f
        val rays = 64
        for (i in 0 until rays) {
            val t = 2 * PI * i / rays
            val dx = cos(t); val dy = sin(t)
            var s = 0.0
            var state = 0
            val edges = DoubleArray(3)
            var prev = c.mark
            while (s < maxR && state < 3) {
                s += 0.5
                val x = (c.x + dx * s).roundToInt(); val y = (c.y + dy * s).roundToInt()
                if (!inside(x, y)) break
                val v = b(x, y)
                if (v != prev) { edges[state] = s - 0.25; state++; prev = v }
            }
            if (state < 3) continue
            val e1 = edges[0]; val e2 = edges[1]; val e3 = edges[2]
            if (e1 < 1.0) continue
            val q2 = e2 / e1; val q3 = e3 / e1
            if (q2 < 1.25 || q2 > 2.2 || q3 < 1.7 || q3 > 3.1) continue
            val g = (e1 + e2) / 2; val rr = (e2 + e3) / 2
            xs.add(dx * g); ys.add(dy * g); rs.add(2.0)
            xs.add(dx * rr); ys.add(dy * rr); rs.add(3.0)
        }
        if (xs.size < 24) { trace += " rays=${xs.size / 2}"; return false }
        var keep = BooleanArray(xs.size) { true }
        var sol: DoubleArray? = null
        for (round in 0 until 2) {
            sol = solveConic(xs, ys, rs, keep) ?: return false
            val a = sol[0]; val bb = sol[1]; val cc = sol[2]
            val det = a * cc - bb * bb
            if (a <= 0 || det <= 0) return false
            val tx = -(cc * sol[3] - bb * sol[4]) / (2 * det)
            val ty = -(a * sol[4] - bb * sol[3]) / (2 * det)
            var dropped = 0
            for (i in xs.indices) {
                val dx = xs[i] - tx; val dy = ys[i] - ty
                val r = sqrt(a * dx * dx + 2 * bb * dx * dy + cc * dy * dy)
                val ok = abs(r - rs[i]) < 0.18 * rs[i]
                if (!ok && keep[i]) dropped++
                keep[i] = ok
            }
            if (keep.count { it } < 20) return false
            if (dropped == 0) break
        }
        val s = sol!!
        val a = s[0]; val bb = s[1]; val cc = s[2]
        val det = a * cc - bb * bb
        val tx = -(cc * s[3] - bb * s[4]) / (2 * det)
        val ty = -(a * s[4] - bb * s[3]) / (2 * det)
        // M^(-1/2) of the symmetric 2×2 [a b; b c]
        val tr = a + cc
        val disc = sqrt(((a - cc) / 2) * ((a - cc) / 2) + bb * bb)
        val ev1 = tr / 2 + disc; val ev2 = tr / 2 - disc
        if (ev2 <= 0) return false
        if (ev1 / ev2 > 9.0) { trace += " tilt"; return false }
        val (vx, vy) = if (abs(bb) > 1e-12) Pair(ev1 - cc, bb) else if (a >= cc) Pair(1.0, 0.0) else Pair(0.0, 1.0)
        val n = sqrt(vx * vx + vy * vy)
        val ux = vx / n; val uy = vy / n
        val s1 = 1 / sqrt(ev1); val s2 = 1 / sqrt(ev2)
        l00 = s1 * ux * ux + s2 * uy * uy
        l01 = (s1 - s2) * ux * uy
        l11 = s1 * uy * uy + s2 * ux * ux
        ox = c.x + tx; oy = c.y + ty
        trace += " u=${(sqrt(s1 * s2) * 10).roundToInt() / 10.0}"
        return true
    }

    /** Least squares for a x² + 2b xy + c y² + p x + q y + k = r² → [a, b, c, p, q, k]. */
    private fun solveConic(xs: List<Double>, ys: List<Double>, rs: List<Double>, keep: BooleanArray): DoubleArray? {
        val m = Array(6) { DoubleArray(7) }
        for (i in xs.indices) {
            if (!keep[i]) continue
            val x = xs[i]; val y = ys[i]
            val row = doubleArrayOf(x * x, 2 * x * y, y * y, x, y, 1.0)
            val rhs = rs[i] * rs[i]
            for (p in 0 until 6) {
                for (q in 0 until 6) m[p][q] += row[p] * row[q]
                m[p][6] += row[p] * rhs
            }
        }
        for (col in 0 until 6) {
            var piv = col
            for (r in col + 1 until 6) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            for (r in 0 until 6) if (r != col) {
                val f = m[r][col] / m[col][col]
                if (f != 0.0) for (k in col..6) m[r][k] -= f * m[col][k]
            }
        }
        return DoubleArray(6) { m[it][6] / m[it][it] }
    }
}
