package com.lucasta.edit

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * A run of the document's bytes, decoded for the screen, and the map back.
 *
 * Crossing between bytes and characters is arithmetic wherever the bytes decode —
 * [Encoding.encodedLength] is exact — so the map carries nothing for ordinary text. [marks] holds a
 * pair of offsets, characters and bytes, only where the decoding was not a bijection: where a byte
 * sequence the encoding does not allow was shown as one replacement character. It always begins
 * with a pair at the start, so there is always something to count from.
 */
class Slice(
    val text: String,
    val bytes: Int,
    private val encoding: Encoding,
    private val marks: IntArray,
) {

    /** Where the character at [char] begins, as an offset into this slice's bytes. */
    fun byteAt(char: Int): Int {
        // Never answer from between the halves of a surrogate pair: the two are one character and
        // one place in the file, and counting the first half alone would name a byte inside it.
        val at = if (char in 1 until text.length &&
            text[char].isLowSurrogate() && text[char - 1].isHighSurrogate()
        ) char - 1 else char.coerceIn(0, text.length)
        val mark = markBefore { index -> marks[index * 2] <= at }
        return marks[mark * 2 + 1] + encoding.encodedLength(text, marks[mark * 2], at)
    }

    /** Which character the byte at [byte] belongs to. */
    fun charAt(byte: Int): Int {
        val at = byte.coerceIn(0, bytes)
        val mark = markBefore { index -> marks[index * 2 + 1] <= at }
        var char = marks[mark * 2]
        var seen = marks[mark * 2 + 1]
        while (char < text.length && seen < at) {
            // A surrogate pair has to be stepped over whole. Half of one is not a character, and
            // asking what half of one encodes to answers about the replacement it would become.
            val step = if (char + 1 < text.length &&
                text[char].isHighSurrogate() && text[char + 1].isLowSurrogate()
            ) 2 else 1
            seen += encoding.encodedLength(text, char, char + step)
            char += step
        }
        return char
    }

    /** The same slice with its last [chars] characters, and their bytes, left off. */
    fun dropLast(chars: Int): Slice {
        if (chars <= 0) return this
        val end = (text.length - chars).coerceAtLeast(0)
        val kept = byteAt(end)
        var count = marks.size
        while (count > 2 && marks[count - 2] > end) count -= 2
        return Slice(text.substring(0, end), kept, encoding, marks.copyOf(count))
    }

    /**
     * This slice with the characters between [from] and [to] replaced by [with], which took [bytes]
     * bytes to encode. The marks before the change are where they were, the ones inside it are gone
     * with the bytes they described, and the ones after it have merely moved — so a window that has
     * been typed into keeps an exact map without being read back off the document.
     */
    fun replacing(from: Int, to: Int, with: CharSequence, bytes: Int): Slice {
        val fromByte = byteAt(from)
        val chars = with.length - (to - from)
        val moved = bytes - (byteAt(to) - fromByte)
        val out = IntArray(marks.size)
        var count = 0
        // The first mark is the one every count starts from — character zero is byte zero — and it
        // stays true of the slice whatever happens to the rest, so it is written out rather than
        // moved along with the others.
        out[count++] = 0
        out[count++] = 0
        for (index in 2 until marks.size step 2) {
            if (marks[index] < from) {
                out[count++] = marks[index]
                out[count++] = marks[index + 1]
            } else if (marks[index] >= to) {
                out[count++] = marks[index] + chars
                out[count++] = marks[index + 1] + moved
            }
        }
        val text = StringBuilder(this.text.length + chars)
            .append(this.text, 0, from)
            .append(with)
            .append(this.text, to, this.text.length)
            .toString()
        return Slice(text, this.bytes + moved, encoding, out.copyOf(count))
    }

    /** The last mark that is still at or before somewhere, by whichever of the two [within] reads. */
    private inline fun markBefore(within: (Int) -> Boolean): Int {
        var low = 0
        var high = marks.size / 2 - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (within(middle)) low = middle else high = middle - 1
        }
        return low
    }
}

/**
 * Decodes a run of the document. Bytes the encoding does not allow are shown as one replacement
 * character each and recorded as a mark rather than refusing the file, since a save copies them out
 * untouched; what refuses a file is [notText], before any of this.
 */
fun decode(bytes: ByteArray, encoding: Encoding): Slice {
    val decoder = encoding.charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val input = ByteBuffer.wrap(bytes)
    // Never more characters than bytes: the shortest thing either encoding can spell is one byte,
    // and every byte that spells nothing becomes one replacement character.
    val out = CharBuffer.allocate(bytes.size + 1)
    var marks = IntArray(8)
    var count = 0
    fun mark(char: Int, byte: Int) {
        if (count == marks.size) marks = marks.copyOf(count * 2)
        marks[count++] = char
        marks[count++] = byte
    }
    mark(0, 0)
    var at = 0
    var decoded = false
    while (at < bytes.size) {
        // Reset here rather than after handling an error, so that whenever this loop ends the last
        // thing the decoder did was decode — which is the only state it can be flushed from.
        decoder.reset()
        input.position(at)
        decoded = true
        if (!decoder.decode(input, out, true).isError) break

        // Where the good part ended, worked out from the characters it produced rather than from
        // where the decoder left the buffer. The two decoders disagree about that: on the phone the
        // buffer comes back already past the bytes it objected to, on a desktop JVM it comes back
        // pointing at them, and neither says how many were consumed in a way the other agrees with.
        // A strict decode is a bijection, so the characters themselves say exactly how many bytes
        // they came from, and that answer is the same everywhere.
        val run = String(out.array(), marks[count - 2], out.position() - marks[count - 2])
        val bad = marks[count - 1] + encoding.encodedLength(run, 0, run.length)
        mark(out.position(), bad)
        out.put('\uFFFD')

        // One unit past it and no further. A file in a single-byte encoding nobody can name is text
        // with a bad byte every few words, and a decoder reporting a bad run past its first byte
        // takes readable characters off the screen with it.
        at = minOf(bad + encoding.unitBytes, bytes.size)
        mark(out.position(), at)
    }
    if (decoded) decoder.flush(out)
    out.flip()
    return Slice(out.toString(), bytes.size, encoding, marks.copyOf(count))
}

/**
 * Whether a file should be opened at all, from its first stretch. The rule is one zero unit: text
 * does not contain one and everything that is not text is full of them, and it is a rule about the
 * file rather than about the decoder, so a file in some single-byte encoding we cannot name still
 * opens and still reads back byte for byte.
 */
fun notText(head: ByteArray, encoding: Encoding): Boolean {
    val unit = encoding.unitBytes
    var at = encoding.bom.size
    while (at + unit <= head.size) {
        var zero = true
        for (byte in at until at + unit) if (head[byte] != 0.toByte()) zero = false
        if (zero) return true
        at += unit
    }
    return false
}

/**
 * The run of characters one text has where another has a different run: [from] to [was] of the one,
 * standing in for [from] to [now] of the other.
 */
class Change(val from: Int, val was: Int, val now: Int)

/**
 * What changed between what a window was given and what it holds now, or null if nothing did.
 *
 * The answer is the run the typing actually touched and not the whole window. A window is a few
 * thousand bytes of which almost none was typed, and committing all of it would fill the typed
 * buffer with text nobody typed and leave the file's own bytes for that run behind.
 *
 * Neither end may fall between the halves of a surrogate pair, since either half on its own encodes
 * to a replacement rather than to itself.
 */
fun change(was: CharSequence, now: CharSequence): Change? {
    val shortest = minOf(was.length, now.length)
    var prefix = 0
    while (prefix < shortest && was[prefix] == now[prefix]) prefix++
    if (prefix == was.length && was.length == now.length) return null
    if (prefix in 1 until was.length && was[prefix].isLowSurrogate() && was[prefix - 1].isHighSurrogate()) prefix--
    var suffix = 0
    while (suffix < shortest - prefix && was[was.length - 1 - suffix] == now[now.length - 1 - suffix]) suffix++
    if (suffix > 0 && was.length - suffix > 0 &&
        was[was.length - suffix].isLowSurrogate() && was[was.length - suffix - 1].isHighSurrogate()
    ) suffix--
    return Change(prefix, was.length - suffix, now.length - suffix)
}
