package com.lucasta.edit

/** One run of the document: a range of the file, or a range of the buffer holding what was typed. */
class Piece(val at: Int, val length: Int, val typed: Boolean)

/** What a change covered: the [was] bytes at [at] are the [now] bytes standing there instead. */
class Span(val at: Int, val was: Int, val now: Int)

/**
 * How many changes are kept to be walked back. A change costs the pieces it took out — three or
 * four objects and not one byte of text — so what this bounds is not the size of a change but the
 * one thing that would otherwise grow for as long as the app is open.
 */
private const val STEPS = 1024

/**
 * The document as a sequence of pieces, which is the whole of what an edit changes, and the changes
 * themselves. Nothing here touches a byte: typing rewrites the sequence and the bytes it names stay
 * where they already were, in the file or on the end of the typed buffer. Coordinates are the
 * document's own bytes.
 *
 * Undo lives here rather than above because it is the same fact from the other side. A splice knows
 * exactly which pieces it took out, and a piece names bytes that never move, so remembering a
 * change is remembering three or four objects — no text is copied and nothing is held twice.
 */
class Pieces {

    private val list = ArrayList<Piece>(8)

    /** One change, as what it put in and what it took out, which is all that undoing one needs. */
    private class Step(val at: Int, val added: Int, val removed: MutableList<Piece>)

    private val undos = ArrayDeque<Step>()

    private val redos = ArrayDeque<Step>()

    var length = 0
        private set

    val canUndo: Boolean get() = undos.isNotEmpty()

    val canRedo: Boolean get() = redos.isNotEmpty()

    /** Back to one piece, or to nothing, which is what opening and saving both leave behind. */
    fun reset(piece: Piece?) {
        list.clear()
        undos.clear()
        redos.clear()
        length = if (piece != null && piece.length > 0) piece.length else 0
        if (length > 0) list.add(piece!!)
    }

    /**
     * Replaces what stands between [from] and [to] with [added], remembers the change, and answers
     * how much longer the document got.
     */
    fun replace(from: Int, to: Int, added: List<Piece>): Int {
        val start = from.coerceIn(0, length)
        val end = to.coerceIn(start, length)
        val was = length
        var count = 0
        for (piece in added) if (piece.length > 0) count += piece.length
        val removed = splice(start, end, added)
        undos.addLast(Step(start, count, removed))
        while (undos.size > STEPS) undos.removeFirst()
        redos.clear()
        return length - was
    }

    /** Walks the last change back, and says what it covered, or null when there is none left. */
    fun undo(): Span? = walk(undos, redos)

    fun redo(): Span? = walk(redos, undos)

    private fun walk(from: ArrayDeque<Step>, to: ArrayDeque<Step>): Span? {
        val step = from.removeLastOrNull() ?: return null
        var back = 0
        for (piece in step.removed) back += piece.length
        to.addLast(Step(step.at, back, splice(step.at, step.at + step.added, step.removed)))
        while (to.size > STEPS) to.removeFirst()
        return Span(step.at, step.added, back)
    }

    /**
     * The splice itself, without remembering it. The pieces the change falls inside are cut down to
     * the parts of them that survive; every piece either side is the same object it already was,
     * so an edit costs what it touched and not the length of the sequence.
     */
    private fun splice(from: Int, to: Int, added: List<Piece>): MutableList<Piece> {
        var first = 0
        var before = 0
        while (first < list.size && before + list[first].length <= from) {
            before += list[first].length
            first++
        }
        val removed = ArrayList<Piece>(2)
        var head: Piece? = null
        var tail: Piece? = null
        var last = first
        var at = before
        // Runs while there is anything left to cover, which for an insertion inside a piece is one
        // turn: nothing comes out of it, but it still has to be cut in two.
        while (last < list.size && at < to) {
            val piece = list[last]
            val stop = at + piece.length
            if (at < from) head = Piece(piece.at, from - at, piece.typed)
            val cut = maxOf(at, from)
            val until = minOf(stop, to)
            if (until > cut) {
                removed.add(
                    if (cut == at && until == stop) piece
                    else Piece(piece.at + (cut - at), until - cut, piece.typed)
                )
            }
            if (stop > to) tail = Piece(piece.at + (to - at), stop - to, piece.typed)
            at = stop
            last++
        }
        if (last > first) list.subList(first, last).clear()
        var write = first
        if (head != null) write = put(write, head)
        for (piece in added) write = put(write, piece)
        if (tail != null) put(write, tail)
        var count = 0
        for (piece in added) if (piece.length > 0) count += piece.length
        length += count - (to - from)
        return removed
    }

    /**
     * Puts [piece] at [index], or onto the end of the piece before it when the two are one run of
     * the same place. Typing that carries straight on from what was typed last is exactly that, so
     * a long run of typing stays one piece instead of becoming one per commit — which is what keeps
     * an edit from costing more the longer the session has been going.
     */
    private fun put(index: Int, piece: Piece): Int {
        if (piece.length <= 0) return index
        val prior = if (index > 0) list[index - 1] else null
        if (prior != null && prior.typed == piece.typed && prior.at + prior.length == piece.at) {
            list[index - 1] = Piece(prior.at, prior.length + piece.length, piece.typed)
            return index
        }
        list.add(index, piece)
        return index + 1
    }

    /**
     * Rebuilds [typed] so that it holds only what some piece still names, and rewrites every piece
     * against it. Answers the new buffer.
     *
     * Typing appends to that buffer and nothing ever takes anything out of it, so a session that
     * pastes and deletes over and over goes on holding every byte it ever held. The pieces the
     * remembered changes are standing on are counted in as well: they name bytes the document no
     * longer shows, and walking one of those changes back has to find them where it left them.
     */
    fun compact(typed: ByteArray): ByteArray {
        var runs = LongArray(list.size + 8)
        var marks = 0
        fun mark(piece: Piece) {
            if (!piece.typed || piece.length <= 0) return
            if (marks == runs.size) runs = runs.copyOf(marks * 2)
            runs[marks++] = (piece.at.toLong() shl 32) or (piece.at + piece.length).toLong()
        }
        for (piece in list) mark(piece)
        for (step in undos) for (piece in step.removed) mark(piece)
        for (step in redos) for (piece in step.removed) mark(piece)
        runs.sort(0, marks)

        // The runs still named, merged where they touch, and where each of them is moving to.
        val map = IntArray(marks * 3)
        var entries = 0
        var kept = 0
        var index = 0
        while (index < marks) {
            val from = (runs[index] ushr 32).toInt()
            var to = (runs[index] and 0xFFFFFFFFL).toInt()
            index++
            while (index < marks && (runs[index] ushr 32).toInt() <= to) {
                val end = (runs[index] and 0xFFFFFFFFL).toInt()
                if (end > to) to = end
                index++
            }
            map[entries * 3] = from
            map[entries * 3 + 1] = to
            map[entries * 3 + 2] = kept
            kept += to - from
            entries++
        }
        val out = ByteArray(kept)
        for (entry in 0 until entries) {
            val from = map[entry * 3]
            System.arraycopy(typed, from, out, map[entry * 3 + 2], map[entry * 3 + 1] - from)
        }

        fun moved(piece: Piece): Piece {
            if (!piece.typed || piece.length <= 0 || entries == 0) return piece
            var low = 0
            var high = entries - 1
            while (low < high) {
                val middle = (low + high + 1) / 2
                if (map[middle * 3] <= piece.at) low = middle else high = middle - 1
            }
            return Piece(map[low * 3 + 2] + (piece.at - map[low * 3]), piece.length, true)
        }
        for (at in list.indices) list[at] = moved(list[at])
        for (step in undos) for (at in step.removed.indices) step.removed[at] = moved(step.removed[at])
        for (step in redos) for (at in step.removed.indices) step.removed[at] = moved(step.removed[at])
        return out
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
