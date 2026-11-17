package app.coredump.sigil

import app.coredump.net.JoinTicket
import app.coredump.net.ShortCode
import kotlin.math.max
import kotlin.math.min

/**
 * What every look's animated code carries: 48 bits (version 0, party, relay broker index 0..35, room code in base 31)
 * plus Reed–Solomon parity over GF(64): 14 symbols of 6 bits = [BITS] bits. Symbol s holds bits 6s..6s+5, most
 * significant first. The symbols are whitened (a fixed mask), so about half the bits are 1 whatever the room.
 *
 * Readers repair up to [BUDGET] of the parity (2 × wrong symbols + unreadable ones); the 2 symbols left over only
 * check. A read that needed no repair ([Read.fixes] == 0) is as good as certain; others want a second agreeing read
 * ([SigilScanner] does that).
 */
object SigilPayload {
    const val BITS = 84
    const val SYMBOLS = 14
    const val BUDGET = 4
    private const val N = 14
    private const val K = 8
    private const val NSYM = N - K

    /** The 14 whitened symbols of a code. */
    fun symbols(room: String, broker: Int, party: Boolean): IntArray {
        val r = room.uppercase().filter { it != '-' && it != ' ' }
        require(r.length == JoinTicket.ROOM_LEN && r.all { it in JoinTicket.ROOM_ALPHABET }) { "bad room code: $room" }
        require(broker in 0..35) { "broker index out of range: $broker" }
        var v = 0L
        for (c in r) v = v * JoinTicket.ROOM_ALPHABET.length + JoinTicket.ROOM_ALPHABET.indexOf(c)
        v = v or (broker.toLong() shl 40) or (if (party) 1L shl 46 else 0L)
        val msg = IntArray(K) { j -> ((v shr (6 * (K - 1 - j))) and 63L).toInt() }
        val cw = Rs.encode(msg)
        return IntArray(N) { cw[it] xor WHITE[it] }
    }

    /** The 84 bits of a code (true = 1), symbol by symbol, most significant bit first. */
    fun bits(room: String, broker: Int, party: Boolean): BooleanArray {
        val s = symbols(room, broker, party)
        return BooleanArray(BITS) { i -> (s[i / 6] shr (5 - i % 6)) and 1 == 1 }
    }

    fun bits(d: ShortCode.Decoded): BooleanArray = bits(d.room, d.broker, d.party)

    /**
     * The code in 14 read symbols ([erasures]: positions known to be unreadable), or null. Never spends more than
     * [budget] (2·errors + erasures).
     */
    fun decode(symbols: IntArray, erasures: List<Int> = emptyList(), budget: Int = BUDGET): Read? {
        if (symbols.size != N) return null
        val r = IntArray(N) { (symbols[it] and 63) xor WHITE[it] }
        val fixed = Rs.decode(r, erasures.distinct().filter { it in 0 until N }, budget) ?: return null
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
        var fixes = 0
        for (i in 0 until N) if (fixed[i] != r[i]) fixes++
        return Read(ShortCode.Decoded(chars.concatToString(), broker, party), max(fixes, erasures.size))
    }

    /**
     * The code in 84 read bits, [sure] (optional, same length): how sure each bit is (any scale, bigger = surer).
     * Tries no erasures, then the 2 and 4 least sure symbols as erasures.
     */
    fun decodeBits(bits: BooleanArray, sure: FloatArray? = null): Read? {
        if (bits.size != BITS) return null
        val sym = IntArray(N)
        for (i in 0 until BITS) if (bits[i]) sym[i / 6] = sym[i / 6] or (1 shl (5 - i % 6))
        decode(sym)?.let { return it }
        if (sure == null) return null
        val symSure = FloatArray(N) { s -> var m = Float.MAX_VALUE; for (b in 0 until 6) m = min(m, sure[6 * s + b]); m }
        val order = (0 until N).sortedBy { symSure[it] }
        for (n in intArrayOf(2, 4)) decode(sym, order.take(n))?.let { return it }
        return null
    }

    /** Fixed whitening of the 14 symbols. */
    private val WHITE = IntArray(N) { s -> ((s * 0x2B + 0x15) xor (s * s * 7) xor 0x2A) and 63 }

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

/** A decoded code and how much of the parity it needed. */
class Read(val code: ShortCode.Decoded, val fixes: Int)
