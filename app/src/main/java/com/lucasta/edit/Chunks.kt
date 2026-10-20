package com.lucasta.edit

/**
 * Somewhere to read the document's bytes from, so that indexing one neither knows nor cares whether
 * they are in the file or in the buffer holding what was typed.
 */
fun interface Bytes {
    fun read(from: Int, to: Int): ByteArray
}

/**
 * Layout chunks. Only the part of the document on screen is ever handed to a text layout, a couple
 * of KB at a time. Laying out text costs about 5 seconds per MB and nothing makes that smaller, so
 * the only way to open a large file quickly is to lay out almost none of it.
 *
 * A chunk covers whole lines wherever it can, so wrapping inside one is exactly what wrapping the
 * whole document would have produced. A single line longer than [CHUNK_MAX] is cut at a space near
 * the limit — the one case where a chunk boundary is visible, as a wrap a whole-document layout
 * would have put a few characters later.
 *
 * Everything here counts bytes, and both targets are scaled by [Encoding.unitBytes].
 */
const val CHUNK_TARGET = 2048

const val CHUNK_MAX = 4096

/**
 * How much of the document is read at a time to be scanned. Has to exceed [CHUNK_MAX], so that
 * everything one chunk boundary depends on is inside one window.
 */
private const val SCAN = 1 shl 16

private const val LINE_FEED = 0x0A

private const val SPACE = 0x20

/**
 * Byte offsets of the chunk starts covering [from] to [to], always beginning with [from]. An empty
 * range is one empty chunk.
 */
fun chunkStarts(source: Bytes, from: Int, to: Int, encoding: Encoding): IntArray {
    if (to <= from) return intArrayOf(from)
    val target = CHUNK_TARGET * encoding.unitBytes
    val max = CHUNK_MAX * encoding.unitBytes
    var starts = IntArray((to - from) / target + 2)
    var count = 0
    var window = ByteArray(0)
    var base = from
    var at = from
    do {
        if (count == starts.size) starts = starts.copyOf(starts.size * 2)
        starts[count++] = at
        if (at < base || (at + max > base + window.size && base + window.size < to)) {
            base = at
            window = source.read(base, minOf(base + SCAN, to))
        }
        at = chunkEnd(window, base, at, to, target, max, encoding)
    } while (at < to)
    return starts.copyOf(count)
}

/**
 * Where the chunk starting at [at] ends. [window] is the stretch of the document that begins at
 * [base], and [to] is where the document itself ends. Offsets in and out are the document's.
 */
private fun chunkEnd(
    window: ByteArray,
    base: Int,
    at: Int,
    to: Int,
    target: Int,
    max: Int,
    encoding: Encoding,
): Int {
    val limit = minOf(base + window.size, at + max)
    var end = at
    while (end - at < target) {
        val line = unitAfter(window, end - base, limit - base, encoding, LINE_FEED)
        if (line < 0) break
        end = base + line
    }
    if (end > at) return end

    // One line longer than a chunk, or the tail of a file that ends without a break. Cut it, on a
    // space if there is one to cut on, and never in the middle of a character.
    if (limit == to) return to
    val space = unitBefore(window, at - base, limit - base, encoding, SPACE)
    if (space >= 0 && base + space >= at + target) return base + space
    return base + boundaryAt(window, limit - base, at - base, encoding)
}

/** Where the first code unit equal to [value] between [from] and [to] ends, or -1. */
private fun unitAfter(window: ByteArray, from: Int, to: Int, encoding: Encoding, value: Int): Int {
    var at = from
    if (encoding.unitBytes == 1) {
        val byte = value.toByte()
        while (at < to) {
            if (window[at] == byte) return at + 1
            at++
        }
        return -1
    }
    val first = (if (encoding == Encoding.Utf16Le) value and 0xFF else value shr 8).toByte()
    val second = (if (encoding == Encoding.Utf16Le) value shr 8 else value and 0xFF).toByte()
    while (at + 2 <= to) {
        if (window[at] == first && window[at + 1] == second) return at + 2
        at += 2
    }
    return -1
}

/** Where the last code unit equal to [value] between [from] and [to] ends, or -1. */
private fun unitBefore(window: ByteArray, from: Int, to: Int, encoding: Encoding, value: Int): Int {
    val step = encoding.unitBytes
    var at = to - step
    val first = (if (step == 1 || encoding == Encoding.Utf16Le) value and 0xFF else value shr 8).toByte()
    val second = (if (encoding == Encoding.Utf16Le) value shr 8 else value and 0xFF).toByte()
    while (at >= from) {
        if (window[at] == first && (step == 1 || window[at + 1] == second)) return at + step
        at -= step
    }
    return -1
}

/**
 * The last place at or before [at] where a character begins, never earlier than [floor]. A cut
 * anywhere else would split a character in half and leave both halves undecodable, which is the one
 * thing chunking is not allowed to invent.
 */
private fun boundaryAt(window: ByteArray, at: Int, floor: Int, encoding: Encoding): Int {
    if (encoding.unitBytes == 2) {
        var cut = at - (at % 2)
        // The high half of a surrogate pair is a unit in 0xD800..0xDBFF, whichever way round the
        // two bytes of it are stored.
        if (cut - 2 >= floor) {
            val high = if (encoding == Encoding.Utf16Le) window[cut - 1] else window[cut - 2]
            if ((high.toInt() and 0xFC) == 0xD8) cut -= 2
        }
        return maxOf(cut, floor)
    }
    // A UTF-8 continuation byte is 0b10xxxxxx, and no character is more than four bytes long, so
    // three of them is as far back as a boundary can be.
    var cut = at
    var back = 0
    while (cut > floor && back < 3 && cut < window.size && (window[cut].toInt() and 0xC0) == 0x80) {
        cut--
        back++
    }
    return cut
}

/**
 * The chunk index after the bytes between [start] and [end] were replaced, changing the document's
 * length by [delta]. Only the chunks the change fell inside are indexed again: the ones before it
 * are untouched and the ones after it have merely moved, so committing an edit costs the size of
 * what was edited and not the size of the document.
 *
 * The span is widened to whole chunks first. What the window commits is the run of bytes it
 * actually changed, which starts and ends wherever the typing did, and re-indexing from there would
 * put a chunk boundary in the middle of a line.
 */
fun respan(
    chunks: IntArray,
    start: Int,
    end: Int,
    delta: Int,
    source: Bytes,
    encoding: Encoding,
    length: Int,
): IntArray {
    val was = length - delta
    val head = chunkIndexAt(chunks, start)
    val from = chunks[head]
    val tail = chunkIndexAt(chunks, maxOf(end, start).coerceAtMost(was))
    val to = chunkEndAt(chunks, tail, was)
    val middle = chunkStarts(source, from, to + delta, encoding)
    // Sized to the answer rather than to the index, because this runs on every commit and on a
    // document of any size the index is the largest thing around: a spare copy of it per keystroke
    // is megabytes of garbage for nothing.
    val after = if (to >= was) chunks.size else tail + 1
    val out = IntArray(head + middle.size + (chunks.size - after))
    var count = 0
    for (index in 0 until head) out[count++] = chunks[index]
    for (offset in middle) count = push(out, count, offset, length)
    for (index in after until chunks.size) count = push(out, count, chunks[index] + delta, length)
    if (count == 0) return intArrayOf(0)
    return if (count == out.size) out else out.copyOf(count)
}

/**
 * Chunk starts stay strictly increasing and stay inside the document, so that no chunk is empty: an
 * empty chunk is a row of no height that cannot be tapped, and two rows claiming the same offset.
 */
private fun push(out: IntArray, count: Int, offset: Int, length: Int): Int {
    if (count > 0 && (offset >= length || offset <= out[count - 1])) return count
    out[count] = offset
    return count + 1
}

/** The offset one past the last byte of chunk [index]. */
fun chunkEndAt(chunks: IntArray, index: Int, length: Int): Int =
    if (index + 1 < chunks.size) chunks[index + 1] else length

/** The chunk [offset] falls in. */
fun chunkIndexAt(chunks: IntArray, offset: Int): Int {
    val found = chunks.binarySearch(offset)
    return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
}

/**
 * Length of the line break that ends [slice], in characters. A chunk keeps the break that ends it
 * so the document can be put back together from its chunks, but the break must not be drawn: the
 * next chunk is already on the next line, and a text layout ending in a break puts an empty line
 * after it.
 */
fun trailingBreak(slice: CharSequence): Int = when {
    slice.endsWith("\r\n") -> 2
    slice.endsWith("\n") -> 1
    else -> 0
}
