package com.lucasta.edit

/**
 * Layout chunks. The document stays one flat run of characters; what changes is that only the part
 * of it that is on screen is ever handed to the text layout, a couple of KB at a time. Laying out
 * text costs about 5 seconds per MB and nothing makes that per-character cost smaller, so the only
 * way to open a large file quickly is to lay out almost none of it.
 *
 * A chunk covers whole lines wherever it can, so its boundaries fall where a line break already is
 * and wrapping inside a chunk is exactly what wrapping the whole document would have produced. A
 * single line longer than [CHUNK_MAX] has no break to fall on, so it is cut at a space near the
 * limit — the one case where a chunk boundary is visible, as a wrap that a whole-document layout
 * would have put a few characters later.
 *
 * Everything here takes a `CharSequence` and returns an `IntArray`, so that indexing a document
 * neither copies it nor boxes a single offset.
 */
const val CHUNK_TARGET = 2048

const val CHUNK_MAX = 4096

/**
 * How much of the document is copied out at a time to be scanned. Has to exceed [CHUNK_MAX], so
 * that everything one chunk boundary depends on is inside one window.
 */
private const val SCAN = 1 shl 16

/**
 * Offsets of the chunk starts, always beginning with 0. Empty text is one empty chunk.
 *
 * The scan runs over Strings copied out of the document a window at a time, rather than over the
 * document itself, because `String.indexOf` is an intrinsic and `CharSequence.get` is an interface
 * call that never gets hot enough to be compiled: the scanning loop here is entered a few thousand
 * times but only runs a couple of thousand iterations each time, which is under the threshold for
 * both the method and the loop. Reading the characters through the interface measured 500 ms on a
 * 16 MB file and did not get faster on a second run in the same process. Copying 64 KB at a time
 * and letting the intrinsic do the looking costs about 7% extra copying and is worth it several
 * times over.
 */
fun chunkStarts(text: CharSequence): IntArray {
    val length = text.length
    if (length == 0) return intArrayOf(0)
    var starts = IntArray(length / CHUNK_TARGET + 2)
    var count = 0
    var window = ""
    var base = 0
    var at = 0
    do {
        if (count == starts.size) starts = starts.copyOf(starts.size * 2)
        starts[count++] = at
        if (at < base || (at + CHUNK_MAX > base + window.length && base + window.length < length)) {
            base = at
            window = text.substring(base, minOf(base + SCAN, length))
        }
        at = chunkEnd(window, at - base, base, length)
    } while (at < length)
    return starts.copyOf(count)
}

/**
 * Where the chunk starting at [start] of [window] ends, as an offset into the whole document.
 * [base] is where the window sits in the document and [length] is the document's own length.
 */
private fun chunkEnd(window: String, start: Int, base: Int, length: Int): Int {
    val limit = minOf(window.length, start + CHUNK_MAX)
    var end = start
    while (end - start < CHUNK_TARGET) {
        val br = window.indexOf('\n', end)
        if (br < 0 || br >= limit) break
        end = br + 1
    }
    if (end > start) return base + end

    // One line longer than a chunk, or the tail of a file that ends without a break. Cut it, on a
    // space if there is one to cut on, and never between the halves of a surrogate pair.
    if (limit == window.length && base + limit == length) return length
    val space = window.lastIndexOf(' ', limit - 1)
    if (space >= start + CHUNK_TARGET) return base + space + 1
    var cut = limit
    if (base + cut < length && window[cut].isLowSurrogate() && window[cut - 1].isHighSurrogate()) cut--
    return base + cut
}

/**
 * The chunk index after the span between [start] and [end] of the old text was replaced, changing
 * its length by [delta]. Only the replaced span is chunked again: the chunks before it are
 * untouched and the ones after it have merely moved, so committing an edit costs the size of the
 * span and not the size of the document.
 */
fun respan(chunks: IntArray, start: Int, end: Int, text: CharSequence, delta: Int): IntArray {
    val middle = chunkStarts(text.subSequence(start, end + delta))
    val out = IntArray(chunks.size + middle.size + 1)
    var count = 0
    for (offset in chunks) {
        if (offset >= start) break
        out[count++] = offset
    }
    for (offset in middle) count = push(out, count, start + offset, text.length)
    for (offset in chunks) {
        if (offset >= end) count = push(out, count, offset + delta, text.length)
    }
    if (count == 0) out[count++] = 0
    return out.copyOf(count)
}

/**
 * Chunk starts stay strictly increasing and stay inside the text, so that no chunk is empty: an
 * empty chunk is a row of no height that cannot be tapped, and two rows claiming the same offset.
 */
private fun push(out: IntArray, count: Int, offset: Int, length: Int): Int {
    if (count > 0 && (offset >= length || offset <= out[count - 1])) return count
    out[count] = offset
    return count + 1
}

/** The offset one past the last character of chunk [index]. */
fun chunkEndAt(chunks: IntArray, index: Int, length: Int): Int =
    if (index + 1 < chunks.size) chunks[index + 1] else length

/** The chunk [offset] falls in. */
fun chunkIndexAt(chunks: IntArray, offset: Int): Int {
    val found = chunks.binarySearch(offset)
    return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
}

/**
 * Length of the line break that ends [slice], if any. A chunk keeps the break that ends it so the
 * document can be put back together from its chunks, but the break must not be drawn: the next
 * chunk is already on the next line, and a text layout ending in a break puts an empty line after
 * it.
 */
fun trailingBreak(slice: CharSequence): Int = when {
    slice.endsWith("\r\n") -> 2
    slice.endsWith("\n") -> 1
    else -> 0
}
