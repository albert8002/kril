package app.coredump.qr

import kotlin.math.abs

/**
 * Minimal QR Code encoder: byte mode, error correction level M, versions 1..10.
 * Enough for a join link (~100 bytes). Follows ISO/IEC 18004.
 */
class QrMatrix(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
}

object QrEncoder {
    // ECC level M, index = version (0 unused)
    private val ECC_PER_BLOCK = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
    private val NUM_BLOCKS = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
    private const val FORMAT_BITS_M = 0 // L=1, M=0, Q=3, H=2

    fun encode(text: String): QrMatrix {
        val data = text.encodeToByteArray()
        var version = -1
        for (v in 1..10) {
            val capBits = numDataCodewords(v) * 8
            val need = 4 + charCountBits(v) + data.size * 8
            if (need <= capBits) { version = v; break }
        }
        require(version > 0) { "QR payload too long: ${data.size} bytes" }

        // ---- bit stream ----
        val bits = BitBuffer()
        bits.append(0x4, 4)                       // byte mode
        bits.append(data.size, charCountBits(version))
        for (b in data) bits.append(b.toInt() and 0xFF, 8)
        val capacity = numDataCodewords(version) * 8
        bits.append(0, minOf(4, capacity - bits.size))   // terminator
        bits.append(0, (8 - bits.size % 8) % 8)           // byte align
        var pad = 0xEC
        while (bits.size < capacity) { bits.append(pad, 8); pad = pad xor (0xEC xor 0x11) }
        val codewords = ByteArray(bits.size / 8)
        for (i in 0 until bits.size) if (bits[i]) {
            val idx = i ushr 3
            codewords[idx] = (codewords[idx].toInt() or (1 shl (7 - (i and 7)))).toByte()
        }

        val all = addEccAndInterleave(codewords, version)
        val q = Builder(version)
        q.drawFunctionPatterns()
        q.drawCodewords(all)

        // choose mask with lowest penalty
        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        for (m in 0 until 8) {
            q.applyMask(m)
            q.drawFormatBits(m)
            val p = q.penalty()
            if (p < bestPenalty) { bestPenalty = p; bestMask = m }
            q.applyMask(m) // undo (xor)
        }
        q.applyMask(bestMask)
        q.drawFormatBits(bestMask)
        return QrMatrix(q.size, q.modules)
    }

    private fun charCountBits(ver: Int) = if (ver <= 9) 8 else 16

    private fun numRawDataModules(ver: Int): Int {
        var result = (16 * ver + 128) * ver + 64
        if (ver >= 2) {
            val numAlign = ver / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if (ver >= 7) result -= 36
        }
        return result
    }

    private fun numDataCodewords(ver: Int): Int =
        numRawDataModules(ver) / 8 - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver]

    private fun addEccAndInterleave(data: ByteArray, ver: Int): ByteArray {
        val numBlocks = NUM_BLOCKS[ver]
        val blockEccLen = ECC_PER_BLOCK[ver]
        val rawCodewords = numRawDataModules(ver) / 8
        val numShortBlocks = numBlocks - rawCodewords % numBlocks
        val shortBlockLen = rawCodewords / numBlocks
        val divisor = rsDivisor(blockEccLen)
        val blocks = ArrayList<ByteArray>()
        var k = 0
        for (i in 0 until numBlocks) {
            val datLen = shortBlockLen - blockEccLen + (if (i < numShortBlocks) 0 else 1)
            val dat = data.copyOfRange(k, k + datLen)
            k += datLen
            val block = dat.copyOf(shortBlockLen + 1)
            val ecc = rsRemainder(dat, divisor)
            ecc.copyInto(block, block.size - blockEccLen)
            blocks += block
        }
        val result = ByteArray(rawCodewords)
        var r = 0
        for (i in blocks[0].indices) {
            for (j in blocks.indices) {
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) result[r++] = blocks[j][i]
            }
        }
        return result
    }

    private fun gfMul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    private fun rsDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = gfMul(result[j], root)
                if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
            }
            root = gfMul(root, 0x02)
        }
        return result
    }

    private fun rsRemainder(data: ByteArray, divisor: IntArray): ByteArray {
        val result = IntArray(divisor.size)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor result[0]
            for (i in 0 until result.size - 1) result[i] = result[i + 1]
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor gfMul(divisor[i], factor)
        }
        return ByteArray(result.size) { result[it].toByte() }
    }

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val size get() = bits.size
        operator fun get(i: Int) = bits[i]
        fun append(value: Int, len: Int) {
            for (i in len - 1 downTo 0) bits += ((value ushr i) and 1) != 0
        }
    }

    private class Builder(val version: Int) {
        val size = version * 4 + 17
        val modules = BooleanArray(size * size)
        val isFunction = BooleanArray(size * size)

        private fun set(x: Int, y: Int, dark: Boolean) { modules[y * size + x] = dark }
        private fun get(x: Int, y: Int) = modules[y * size + x]
        private fun setF(x: Int, y: Int, dark: Boolean) { set(x, y, dark); isFunction[y * size + x] = true }

        fun drawFunctionPatterns() {
            for (i in 0 until size) { setF(6, i, i % 2 == 0); setF(i, 6, i % 2 == 0) }
            drawFinder(3, 3); drawFinder(size - 4, 3); drawFinder(3, size - 4)
            val pos = alignmentPositions()
            val n = pos.size
            for (i in 0 until n) for (j in 0 until n) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
                drawAlignment(pos[i], pos[j])
            }
            drawFormatBits(0)
            drawVersion()
        }

        private fun drawFinder(x: Int, y: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val dist = maxOf(abs(dx), abs(dy))
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until size && yy in 0 until size) setF(xx, yy, dist != 2 && dist != 4)
            }
        }

        private fun drawAlignment(x: Int, y: Int) {
            for (dy in -2..2) for (dx in -2..2) setF(x + dx, y + dy, maxOf(abs(dx), abs(dy)) != 1)
        }

        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val numAlign = version / 7 + 2
            val step = (version * 8 + numAlign * 3 + 5) / (numAlign * 4 - 4) * 2
            val result = IntArray(numAlign)
            result[0] = 6
            var pos = size - 7
            for (i in numAlign - 1 downTo 1) { result[i] = pos; pos -= step }
            return result
        }

        fun drawFormatBits(mask: Int) {
            val data = (FORMAT_BITS_M shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = ((bits ushr i) and 1) != 0
            for (i in 0..5) setF(8, i, bit(i))
            setF(8, 7, bit(6)); setF(8, 8, bit(7)); setF(7, 8, bit(8))
            for (i in 9 until 15) setF(14 - i, 8, bit(i))
            for (i in 0 until 8) setF(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) setF(8, size - 15 + i, bit(i))
            setF(8, size - 8, true)
        }

        private fun drawVersion() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val b = ((bits ushr i) and 1) != 0
                val a = size - 11 + i % 3
                val c = i / 3
                setF(a, c, b); setF(c, a, b)
            }
        }

        fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0..1) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!isFunction[y * size + x] && i < data.size * 8) {
                            val bit = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                            set(x, y, bit)
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                if (isFunction[y * size + x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert) set(x, y, !get(x, y))
            }
        }

        /** Simplified penalty (rules 1, 2, 4): enough to avoid bad masks. */
        fun penalty(): Int {
            var result = 0
            for (y in 0 until size) {
                var run = 1
                for (x in 1 until size) {
                    if (get(x, y) == get(x - 1, y)) { run++; if (run == 5) result += 3 else if (run > 5) result++ }
                    else run = 1
                }
            }
            for (x in 0 until size) {
                var run = 1
                for (y in 1 until size) {
                    if (get(x, y) == get(x, y - 1)) { run++; if (run == 5) result += 3 else if (run > 5) result++ }
                    else run = 1
                }
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = get(x, y)
                if (c == get(x + 1, y) && c == get(x, y + 1) && c == get(x + 1, y + 1)) result += 3
            }
            var dark = 0
            for (b in modules) if (b) dark++
            val total = size * size
            val k = (abs(dark * 20 - total * 10) + total - 1) / total - 1
            result += k.coerceAtLeast(0) * 10
            return result
        }
    }
}
