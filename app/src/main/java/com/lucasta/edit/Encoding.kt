package com.lucasta.edit

import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * What a file is written in, and everything that follows from that.
 *
 * [unitBytes] is the size of a code unit, one for UTF-8 and two for UTF-16. It is what a line break
 * has to be looked for in — in UTF-16 a byte 0x0A is only sometimes a line break, and the other
 * times it is half of something else — and it is what a chunk's size target is scaled by, so a
 * chunk holds about as much text whatever the file is in.
 */
enum class Encoding(val charset: Charset, val bom: ByteArray, val unitBytes: Int) {
    Utf8(Charsets.UTF_8, byteArrayOf(), 1),
    Utf8Bom(Charsets.UTF_8, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), 1),
    Utf16Le(Charsets.UTF_16LE, byteArrayOf(0xFF.toByte(), 0xFE.toByte()), 2),
    Utf16Be(Charsets.UTF_16BE, byteArrayOf(0xFE.toByte(), 0xFF.toByte()), 2),
    ;

    /**
     * What [text] between [from] and [to] takes to encode, without encoding it.
     *
     * This is how a place in the text is turned into a place in the file, so it has to agree with
     * [encode] on every input rather than on well-formed input — including an unpaired surrogate,
     * which cannot be represented at all and which the encoder therefore replaces: with one byte in
     * UTF-8, and with one two-byte unit in UTF-16, which is what the arithmetic here says too.
     */
    fun encodedLength(text: CharSequence, from: Int, to: Int): Int {
        if (unitBytes == 2) return (to - from) * 2
        var total = 0
        var at = from
        while (at < to) {
            val code = text[at].code
            total += when {
                code < 0x80 -> 1
                code < 0x800 -> 2
                code in 0xD800..0xDBFF && at + 1 < to && text[at + 1].code in 0xDC00..0xDFFF -> {
                    at++
                    4
                }
                code in 0xD800..0xDFFF -> 1
                else -> 3
            }
            at++
        }
        return total
    }

    /**
     * How many characters [decode] makes of [bytes] between [from] and [to], without decoding them.
     *
     * The other way across from [encodedLength], and held to the same rule: it has to agree with the
     * decoder on every input. A unit the decoder cannot name where it stands is one replacement
     * character and the next unit is read afresh, so what decides the count is which sequences are
     * well formed, and that is Unicode's to say rather than any decoder's. In UTF-16 every unit is a
     * character, an unpaired surrogate included, and so is a last byte with no partner.
     */
    fun decodedLength(bytes: ByteArray, from: Int, to: Int): Int {
        if (unitBytes == 2) return (to - from + 1) / 2
        var total = 0
        var at = from
        while (at < to) {
            val length = utf8At(bytes, at, to)
            // Four bytes are one character outside the basic plane, which is two in UTF-16.
            total += if (length == 4) 2 else 1
            at += maxOf(length, 1)
        }
        return total
    }

    /**
     * [text] between [from] and [to] as bytes of this encoding. Never a byte order mark: a mark
     * belongs to the file and not to any of the text in it, so it is written once, when the file is.
     */
    fun encode(text: CharSequence, from: Int, to: Int): ByteArray {
        if (to <= from) return ByteArray(0)
        val encoder = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val encoded = encoder.encode(CharBuffer.wrap(text, from, to))
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }
}

/**
 * How long the well-formed UTF-8 sequence starting at [at] is, or 0 when the byte there starts none
 * before [to]. Well formed is Unicode's table of it: no overlong forms, no surrogates and nothing
 * past U+10FFFF, which is what the second byte's range is narrowed for after E0, ED, F0 and F4.
 */
private fun utf8At(bytes: ByteArray, at: Int, to: Int): Int {
    val lead = bytes[at].toInt() and 0xFF
    val length: Int
    var low = 0x80
    var high = 0xBF
    when (lead) {
        in 0x00..0x7F -> return 1
        in 0xC2..0xDF -> length = 2
        0xE0 -> { length = 3; low = 0xA0 }
        0xED -> { length = 3; high = 0x9F }
        in 0xE1..0xEF -> length = 3
        0xF0 -> { length = 4; low = 0x90 }
        0xF4 -> { length = 4; high = 0x8F }
        in 0xF1..0xF3 -> length = 4
        else -> return 0
    }
    if (at + length > to) return 0
    val second = bytes[at + 1].toInt() and 0xFF
    if (second < low || second > high) return 0
    for (next in at + 2 until at + length) if ((bytes[next].toInt() and 0xC0) != 0x80) return 0
    return length
}
