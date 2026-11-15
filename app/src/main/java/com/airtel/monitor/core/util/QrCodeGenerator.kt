package com.airtel.monitor.core.util

import java.nio.charset.StandardCharsets

/**
 * Pure-Kotlin ISO/IEC 18004 QR Code encoder (Byte mode, Error Correction Level M, Versions 1–10)
 * plus Wi-Fi QR payload formatter (`WIFI:T:...;S:...;P:...;H:...;;`).
 */
object QrCodeGenerator {

    private const val MODE_BYTE = 0b0100
    private const val FORMAT_EC_M = 0b00 // L=01, M=00, Q=11, H=10
    private const val FORMAT_MASK = 0x5412

    private data class VersionSpec(
        val version: Int,
        val alignmentCenters: IntArray,
        val ecPerBlock: Int,
        val g1Blocks: Int,
        val g1DataBytes: Int,
        val g2Blocks: Int = 0,
        val g2DataBytes: Int = 0
    ) {
        val size: Int get() = 4 * version + 17
        val totalDataBytes: Int get() = g1Blocks * g1DataBytes + g2Blocks * g2DataBytes
        val charCountBits: Int get() = if (version <= 9) 8 else 16
        val maxByteCapacity: Int get() = (totalDataBytes * 8 - 4 - charCountBits) / 8
    }

    // Verified against ISO/IEC 18004 Error Correction Level M tables for Versions 1–10.
    private val VERSIONS = listOf(
        VersionSpec(1, intArrayOf(), ecPerBlock = 10, g1Blocks = 1, g1DataBytes = 16),
        VersionSpec(2, intArrayOf(6, 18), ecPerBlock = 16, g1Blocks = 1, g1DataBytes = 28),
        VersionSpec(3, intArrayOf(6, 22), ecPerBlock = 26, g1Blocks = 1, g1DataBytes = 44),
        VersionSpec(4, intArrayOf(6, 26), ecPerBlock = 18, g1Blocks = 2, g1DataBytes = 32),
        VersionSpec(5, intArrayOf(6, 30), ecPerBlock = 24, g1Blocks = 2, g1DataBytes = 43),
        VersionSpec(6, intArrayOf(6, 34), ecPerBlock = 16, g1Blocks = 4, g1DataBytes = 27),
        VersionSpec(7, intArrayOf(6, 22, 38), ecPerBlock = 18, g1Blocks = 4, g1DataBytes = 31),
        VersionSpec(8, intArrayOf(6, 24, 42), ecPerBlock = 22, g1Blocks = 2, g1DataBytes = 38, g2Blocks = 2, g2DataBytes = 39),
        VersionSpec(9, intArrayOf(6, 26, 46), ecPerBlock = 22, g1Blocks = 3, g1DataBytes = 36, g2Blocks = 2, g2DataBytes = 37),
        VersionSpec(10, intArrayOf(6, 28, 50), ecPerBlock = 26, g1Blocks = 4, g1DataBytes = 43, g2Blocks = 1, g2DataBytes = 44)
    )

    private val GF_EXP = IntArray(512)
    private val GF_LOG = IntArray(256)

    init {
        var v = 1
        for (i in 0 until 255) {
            GF_EXP[i] = v
            GF_LOG[v] = i
            v = v shl 1
            if (v and 0x100 != 0) v = v xor 0x11D
        }
        for (i in 255 until 512) {
            GF_EXP[i] = GF_EXP[i - 255]
        }
    }

    /** Formats a standard `WIFI:` URI string with ZXing backslash escaping. */
    fun formatWifiQrPayload(
        ssid: String,
        key: String,
        authenticationType: String,
        hidden: Boolean = false
    ): String {
        val security = if (authenticationType == "0" || key.isEmpty()) "nopass" else "WPA"
        val escapedSsid = escapeWifiField(ssid)
        val escapedKey = if (security == "nopass") "" else escapeWifiField(key)
        return buildString {
            append("WIFI:T:")
            append(security)
            append(";S:")
            append(escapedSsid)
            append(";P:")
            append(escapedKey)
            if (hidden) append(";H:true")
            append(";;")
        }
    }

    private fun escapeWifiField(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            if (ch == '\\' || ch == ';' || ch == ',' || ch == ':' || ch == '"') {
                append('\\')
            }
            append(ch)
        }
    }

    fun encode(content: String): List<List<Boolean>> {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        val spec = VERSIONS.firstOrNull { bytes.size <= it.maxByteCapacity }
            ?: throw IllegalArgumentException("QR payload exceeds 213-byte capacity (${bytes.size} bytes)")

        val codewords = buildFinalCodewords(bytes, spec)
        val size = spec.size
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        placeFunctionPatterns(spec, modules, isFunction)
        placeDataBits(codewords, size, modules, isFunction)

        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0 until 8) {
            val candidate = applyMask(modules, isFunction, size, mask)
            writeFormatBits(candidate, size, mask)
            val penalty = computePenalty(candidate, size)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
            }
        }

        val finalMatrix = applyMask(modules, isFunction, size, bestMask)
        writeFormatBits(finalMatrix, size, bestMask)
        return List(size) { r -> List(size) { c -> finalMatrix[r][c] } }
    }

    private fun buildFinalCodewords(bytes: ByteArray, spec: VersionSpec): IntArray {
        val bits = ArrayList<Boolean>(spec.totalDataBytes * 8)
        fun appendBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                bits.add(((value ushr i) and 1) == 1)
            }
        }

        appendBits(MODE_BYTE, 4)
        appendBits(bytes.size, spec.charCountBits)
        for (b in bytes) {
            appendBits(b.toInt() and 0xFF, 8)
        }

        val totalDataBits = spec.totalDataBytes * 8
        val terminator = minOf(4, totalDataBits - bits.size)
        repeat(terminator) { bits.add(false) }
        while (bits.size % 8 != 0) bits.add(false)

        val padBytes = intArrayOf(0xEC, 0x11)
        var padIdx = 0
        while (bits.size < totalDataBits) {
            appendBits(padBytes[padIdx % 2], 8)
            padIdx++
        }

        val dataBytes = IntArray(spec.totalDataBytes) { idx ->
            var v = 0
            for (b in 0 until 8) {
                if (bits[idx * 8 + b]) v = v or (1 shl (7 - b))
            }
            v
        }

        val blocks = ArrayList<IntArray>(spec.g1Blocks + spec.g2Blocks)
        val ecBlocks = ArrayList<IntArray>(spec.g1Blocks + spec.g2Blocks)
        val divisor = rsGeneratorPoly(spec.ecPerBlock)
        var offset = 0
        repeat(spec.g1Blocks) {
            val block = dataBytes.copyOfRange(offset, offset + spec.g1DataBytes)
            offset += spec.g1DataBytes
            blocks.add(block)
            ecBlocks.add(rsComputeRemainder(block, divisor))
        }
        repeat(spec.g2Blocks) {
            val block = dataBytes.copyOfRange(offset, offset + spec.g2DataBytes)
            offset += spec.g2DataBytes
            blocks.add(block)
            ecBlocks.add(rsComputeRemainder(block, divisor))
        }

        val totalOut = spec.totalDataBytes + blocks.size * spec.ecPerBlock
        val out = IntArray(totalOut)
        var outIdx = 0
        val maxDataLen = maxOf(spec.g1DataBytes, spec.g2DataBytes)
        for (i in 0 until maxDataLen) {
            for (b in blocks) {
                if (i < b.size) out[outIdx++] = b[i]
            }
        }
        for (i in 0 until spec.ecPerBlock) {
            for (ec in ecBlocks) {
                out[outIdx++] = ec[i]
            }
        }
        return out
    }

    private fun gfMul(x: Int, y: Int): Int =
        if (x == 0 || y == 0) 0 else GF_EXP[GF_LOG[x] + GF_LOG[y]]

    private fun rsGeneratorPoly(degree: Int): IntArray {
        var result = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(result.size + 1)
            val root = GF_EXP[i]
            for (j in result.indices) {
                next[j] = next[j] xor result[j]
                next[j + 1] = next[j + 1] xor gfMul(result[j], root)
            }
            result = next
        }
        return result
    }

    private fun rsComputeRemainder(data: IntArray, divisor: IntArray): IntArray {
        val degree = divisor.size - 1
        val rem = IntArray(degree)
        for (b in data) {
            val factor = b xor rem[0]
            System.arraycopy(rem, 1, rem, 0, degree - 1)
            rem[degree - 1] = 0
            for (j in 0 until degree) {
                rem[j] = rem[j] xor gfMul(divisor[j + 1], factor)
            }
        }
        return rem
    }

    private fun placeFunctionPatterns(
        spec: VersionSpec,
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>
    ) {
        val size = spec.size
        fun setFunc(r: Int, c: Int, black: Boolean) {
            if (r in 0 until size && c in 0 until size) {
                modules[r][c] = black
                isFunction[r][c] = true
            }
        }

        // Finder patterns + 1-module white separator
        val finderOrigins = arrayOf(0 to 0, 0 to (size - 7), (size - 7) to 0)
        for ((r0, c0) in finderOrigins) {
            for (dr in -1..7) {
                for (dc in -1..7) {
                    val dist = maxOf(kotlin.math.abs(dr - 3), kotlin.math.abs(dc - 3))
                    val dark = dr in 0..6 && dc in 0..6 && (dist == 3 || dist <= 1)
                    setFunc(r0 + dr, c0 + dc, dark)
                }
            }
        }

        // Alignment patterns (skip corners overlapping finders)
        val centers = spec.alignmentCenters
        for (rCenter in centers) {
            for (cCenter in centers) {
                val overlapsFinder =
                    (rCenter == centers.first() && cCenter == centers.first()) ||
                        (rCenter == centers.first() && cCenter == centers.last()) ||
                        (rCenter == centers.last() && cCenter == centers.first())
                if (overlapsFinder) continue
                for (dr in -2..2) {
                    for (dc in -2..2) {
                        val dist = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc))
                        setFunc(rCenter + dr, cCenter + dc, dist != 1)
                    }
                }
            }
        }

        // Timing patterns
        for (i in 8 until size - 8) {
            if (!isFunction[6][i]) setFunc(6, i, i % 2 == 0)
            if (!isFunction[i][6]) setFunc(i, 6, i % 2 == 0)
        }

        // Reserve format areas + dark module
        for (i in 0..8) {
            if (!isFunction[8][i]) setFunc(8, i, false)
            if (!isFunction[i][8]) setFunc(i, 8, false)
        }
        for (i in size - 8 until size) {
            if (!isFunction[8][i]) setFunc(8, i, false)
        }
        for (i in size - 7 until size) {
            if (!isFunction[i][8]) setFunc(i, 8, false)
        }
        setFunc(size - 8, 8, true)

        // Reserve and write Version info blocks for V7+
        if (spec.version >= 7) {
            val bits = computeVersionBits(spec.version)
            for (i in 0 until 18) {
                val bit = ((bits ushr i) and 1) == 1
                val a = size - 11 + (i % 3)
                val b = i / 3
                setFunc(a, b, bit)
                setFunc(b, a, bit)
            }
        }
    }

    private fun placeDataBits(
        codewords: IntArray,
        size: Int,
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>
    ) {
        var bitIdx = 0
        val totalBits = codewords.size * 8
        var up = true
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right--
            for (vert in 0 until size) {
                val r = if (up) size - 1 - vert else vert
                for (dx in 0..1) {
                    val c = right - dx
                    if (!isFunction[r][c]) {
                        val dark = if (bitIdx < totalBits) {
                            ((codewords[bitIdx ushr 3] ushr (7 - (bitIdx and 7))) and 1) == 1
                        } else {
                            false
                        }
                        modules[r][c] = dark
                        bitIdx++
                    }
                }
            }
            up = !up
            right -= 2
        }
    }

    private fun applyMask(
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
        size: Int,
        mask: Int
    ): Array<BooleanArray> {
        return Array(size) { r ->
            BooleanArray(size) { c ->
                if (isFunction[r][c]) {
                    modules[r][c]
                } else {
                    val invert = when (mask) {
                        0 -> (r + c) % 2 == 0
                        1 -> r % 2 == 0
                        2 -> c % 3 == 0
                        3 -> (r + c) % 3 == 0
                        4 -> (r / 2 + c / 3) % 2 == 0
                        5 -> (r * c) % 2 + (r * c) % 3 == 0
                        6 -> ((r * c) % 2 + (r * c) % 3) % 2 == 0
                        else -> ((r + c) % 2 + (r * c) % 3) % 2 == 0
                    }
                    modules[r][c] xor invert
                }
            }
        }
    }

    private fun writeFormatBits(modules: Array<BooleanArray>, size: Int, mask: Int) {
        val data = (FORMAT_EC_M shl 3) or mask
        var rem = data
        repeat(10) {
            rem = (rem shl 1) xor (((rem ushr 9) and 1) * 0x537)
        }
        val bits = ((data shl 10) or rem) xor FORMAT_MASK

        // Top-left copy: MSB (bit 14) at (8, 0) -> LSB (bit 0) at (0, 8)
        for (i in 0..5) modules[8][i] = ((bits ushr (14 - i)) and 1) == 1
        modules[8][7] = ((bits ushr 8) and 1) == 1
        modules[8][8] = ((bits ushr 7) and 1) == 1
        modules[7][8] = ((bits ushr 6) and 1) == 1
        for (i in 9..14) modules[14 - i][8] = ((bits ushr (14 - i)) and 1) == 1

        // Split copy: MSB (bit 14..8) at bottom-left col 8; bits 7..0 at top-right row 8
        for (i in 0..6) modules[size - 1 - i][8] = ((bits ushr (14 - i)) and 1) == 1
        for (i in 7..14) modules[8][size - 15 + i] = ((bits ushr (14 - i)) and 1) == 1
        modules[size - 8][8] = true
    }

    private fun computeVersionBits(version: Int): Int {
        var rem = version
        repeat(12) {
            rem = (rem shl 1) xor (((rem ushr 11) and 1) * 0x1F25)
        }
        return (version shl 12) or rem
    }

    private fun computePenalty(modules: Array<BooleanArray>, size: Int): Int {
        var penalty = 0

        // Rule 1: 5+ consecutive same-color modules in row / col
        for (r in 0 until size) {
            var runColor = modules[r][0]
            var runLen = 1
            for (c in 1 until size) {
                if (modules[r][c] == runColor) {
                    runLen++
                } else {
                    if (runLen >= 5) penalty += 3 + (runLen - 5)
                    runColor = modules[r][c]
                    runLen = 1
                }
            }
            if (runLen >= 5) penalty += 3 + (runLen - 5)
        }
        for (c in 0 until size) {
            var runColor = modules[0][c]
            var runLen = 1
            for (r in 1 until size) {
                if (modules[r][c] == runColor) {
                    runLen++
                } else {
                    if (runLen >= 5) penalty += 3 + (runLen - 5)
                    runColor = modules[r][c]
                    runLen = 1
                }
            }
            if (runLen >= 5) penalty += 3 + (runLen - 5)
        }

        // Rule 2: 2x2 same-color blocks
        for (r in 0 until size - 1) {
            for (c in 0 until size - 1) {
                val color = modules[r][c]
                if (color == modules[r][c + 1] && color == modules[r + 1][c] && color == modules[r + 1][c + 1]) {
                    penalty += 3
                }
            }
        }

        // Rule 3: 1:1:3:1:1 finder-like ratio with 4 light modules on either side
        fun checkPatternPenalty(getBit: (Int) -> Boolean): Int {
            var p = 0
            for (i in 0..size - 11) {
                val a = getBit(i) && !getBit(i + 1) && getBit(i + 2) && getBit(i + 3) && getBit(i + 4) &&
                    !getBit(i + 5) && getBit(i + 6) && !getBit(i + 7) && !getBit(i + 8) && !getBit(i + 9) && !getBit(i + 10)
                val b = !getBit(i) && !getBit(i + 1) && !getBit(i + 2) && !getBit(i + 3) && getBit(i + 4) &&
                    !getBit(i + 5) && getBit(i + 6) && getBit(i + 7) && getBit(i + 8) && !getBit(i + 9) && getBit(i + 10)
                if (a || b) p += 40
            }
            return p
        }
        for (r in 0 until size) penalty += checkPatternPenalty { c -> modules[r][c] }
        for (c in 0 until size) penalty += checkPatternPenalty { r -> modules[r][c] }

        // Rule 4: dark module proportion deviation from 50%
        var darkCount = 0
        for (r in 0 until size) {
            for (c in 0 until size) if (modules[r][c]) darkCount++
        }
        val total = size * size
        val percent = darkCount * 100 / total
        val prev5 = kotlin.math.abs(percent - (percent % 5) - 50) / 5
        val next5 = kotlin.math.abs(percent + (5 - percent % 5) - 50) / 5
        penalty += minOf(prev5, next5) * 10

        return penalty
    }
}
