package com.lucasta.edit

/** One run of the document: a range of the file, or a range of the buffer holding what was typed. */
class Piece(val at: Int, val length: Int, val typed: Boolean)

/**
 * The document as a sequence of pieces, which is the whole of what an edit changes. Nothing here
 * touches a byte: typing rewrites the sequence and the bytes it names stay where they already were,
 * in the file or on the end of the typed buffer. Coordinates are the document's own bytes.
 */
class Pieces {

    private var list = ArrayList<Piece>(4)

    var length = 0
        private set

    /** Back to one piece, or to nothing, which is what opening and saving both leave behind. */
    fun reset(piece: Piece?) {
        list = ArrayList(4)
        if (piece != null && piece.length > 0) list.add(piece)
        length = piece?.length ?: 0
    }

    /**
     * Replaces what stands between [from] and [to] with [added], and answers how much longer the
     * document got. The pieces the change falls inside are cut down to the parts of them that
     * survive; everything either side is the same piece it was.
     */
    fun replace(from: Int, to: Int, added: Piece?): Int {
        val out = ArrayList<Piece>(list.size + 2)
        var at = 0
        var placed = false
        for (piece in list) {
            val end = at + piece.length
            if (at < from) out.add(Piece(piece.at, minOf(end, from) - at, piece.typed))
            if (!placed && end >= from) {
                if (added != null) out.add(added)
                placed = true
            }
            if (end > to) {
                val skip = maxOf(at, to) - at
                out.add(Piece(piece.at + skip, piece.length - skip, piece.typed))
            }
            at = end
        }
        if (!placed && added != null) out.add(added)
        list = out
        val delta = (added?.length ?: 0) - (to - from)
        length += delta
        return delta
    }

    /**
     * Hands [block] each run of bytes between [from] and [to], in order, as the piece it belongs to
     * and how much of that piece is wanted.
     */
    inline fun forEach(from: Int, to: Int, block: (typed: Boolean, at: Int, length: Int) -> Unit) {
        var at = 0
        for (index in 0 until count) {
            val piece = pieceAt(index)
            val end = at + piece.length
            if (end > from && at < to) {
                val start = maxOf(at, from)
                block(piece.typed, piece.at + (start - at), minOf(end, to) - start)
            }
            at = end
            if (at >= to) break
        }
    }

    /** Only for [forEach], which has to be inline and so cannot reach [list] itself. */
    val count: Int get() = list.size

    /** Only for [forEach]. */
    fun pieceAt(index: Int): Piece = list[index]
}
