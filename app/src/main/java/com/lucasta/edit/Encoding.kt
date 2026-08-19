package com.lucasta.edit

import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * What a file is written in, and everything that follows from that. Pure Kotlin with no Android in
 * it, along with [Pieces], [Chunks] and [Slice]: between them they are where a mistake corrupts a
 * file quietly rather than crashing, so they are kept free of anything Android.
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
     * Being exact everywhere is what lets the map from characters to bytes carry no entry at all
     * for ordinary text and mark only the places where the file itself would not decode.
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
