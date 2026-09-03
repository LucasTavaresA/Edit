package com.lucasta.edit

/** One run of the document: a range of the file, or a range of the buffer holding what was typed. */
class Piece(val at: Int, val length: Int, val typed: Boolean)

/** What a change covered: the [was] bytes at [at] are the [now] bytes standing there instead. */
class Span(val at: Int, val was: Int, val now: Int)

/**
 * How many changes are kept to be walked back. A change costs the pieces it took out — three or
 * four objects and not one byte of text — so what this bounds is not the size of a change but the
 * one thing that would otherwise grow for as long as the app is open.
 *
 * It is counted in keystrokes rather than in commits: every state the document passes through is
 * recorded as it happens, so a change is now one press of a key. A save collapses the document and
 * ends the history, so this only ever has to cover one stretch of editing between two saves.
 */
private const val STEPS = 16384

/**
 * The document as a sequence of pieces, which is the whole of what an edit changes, and the changes
 * themselves. Nothing here touches a byte: typing rewrites the sequence and the bytes it names stay
 * where they already were, in the file or on the end of the typed buffer. Coordinates are the
 * document's own bytes.
 *
 * Undo lives here rather than above because it is the same fact from the other side. A splice knows
 * exactly which pieces it took out, and a piece names bytes that never move, so remembering a
 * change is remembering three or four objects — no text is copied and nothing is held twice.
 *
 * Every change is recorded, and how much one press of undo walks back is a separate question: a
 * change carries whether it belongs to the run of changes before it, and a walk takes a whole run.
 */
class Pieces {

    private val list = ArrayList<Piece>(8)

    /**
     * One change, as what it put in and what it took out, which is all that undoing one needs.
     * [continues] says it belongs to the run the change before it is in — a stretch of typing
     * between two pauses — which is what one press of undo walks back.
     */
    private class Step(
        val at: Int,
        val added: Int,
        val removed: MutableList<Piece>,
        val continues: Boolean,
    )

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
     * how much longer the document got. [continues] joins it to the change before it, so that one
     * press of undo walks both of them back.
     */
    fun replace(from: Int, to: Int, added: List<Piece>, continues: Boolean = false): Int {
        val start = from.coerceIn(0, length)
        val end = to.coerceIn(start, length)
        val was = length
        var count = 0
        for (piece in added) if (piece.length > 0) count += piece.length
        val joins = continues && undos.isNotEmpty()
        val removed = splice(start, end, added)
        undos.addLast(Step(start, count, removed, joins))
        trim()
        redos.clear()
        return length - was
    }

    /**
     * Drops the oldest changes past [STEPS], and never leaves half a run of them at the front: a
     * run is what one press of undo walks, so one whose first change has gone would walk into
     * nothing. Only the changes taken are ever trimmed — the ones waiting to be walked forward
     * came out of that same deque and were cleared the moment anything else was typed, so the two
     * together can never hold more than it did.
     */
    private fun trim() {
        if (undos.size <= STEPS) return
        while (undos.size > STEPS) undos.removeFirst()
        while (undos.isNotEmpty() && undos.first().continues) undos.removeFirst()
    }

    /**
     * Walks the last run of changes back, and says what it covered, or null when there is none
     * left. Every change is recorded as it happens, so how much one press of undo takes is decided
     * by the run each change was put in and not by how much of the typing the document had seen.
     */
    fun undo(): Span? = walk(undos, redos, backwards = true)

    fun redo(): Span? = walk(redos, undos, backwards = false)

    /**
     * A run in one direction. Going back, the change that ends it is the one that does not continue
     * the change before it; going forward, that same change is the one the run starts at, so it is
     * the next one along that says whether there is more of it.
     */
    private fun walk(from: ArrayDeque<Step>, to: ArrayDeque<Step>, backwards: Boolean): Span? {
        if (from.isEmpty()) return null
        // What the run covered, in the coordinates the document had before any of it was walked.
        // Each change is expressed in the document the one before it left, so each is mapped back
        // through what the run has done so far — which is one splice, because what it has covered
        // is one range: below [low] both frames agree, and above it they differ by [delta].
        var low = -1
        var high = -1
        var delta = 0
        while (true) {
            val step = from.removeLast()
            var back = 0
            for (piece in step.removed) back += piece.length
            to.addLast(Step(step.at, back, splice(step.at, step.at + step.added, step.removed), step.continues))
            val end = step.at + step.added
            val at = when {
                low < 0 || step.at <= low -> step.at
                step.at >= high + delta -> step.at - delta
                else -> low
            }
            val stop = when {
                low < 0 || end <= low -> end
                end >= high + delta -> end - delta
                else -> high
            }
            low = if (low < 0) at else minOf(low, at)
            high = if (high < 0) stop else maxOf(high, stop)
            delta += back - step.added
            if (if (backwards) !step.continues else from.lastOrNull()?.continues != true) break
        }
        return Span(low, high - low, high - low + delta)
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
