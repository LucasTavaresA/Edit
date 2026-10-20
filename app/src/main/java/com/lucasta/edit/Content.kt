package com.lucasta.edit

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * How large the buffer holding what was typed has to get before it is worth looking for parts of it
 * nothing names any more. Typing never reaches this; pasting and deleting over and over is what
 * does, and that is the case it exists for.
 */
private const val COMPACT_BYTES = 1 shl 20

/**
 * A private copy of a file, and the only thing a document ever reads a file's bytes through.
 *
 * It lives in the app's own storage, unlinked from the moment it is open, so nothing but the
 * descriptor held on it can reach it: no tool writing the file itself — appending, rewriting it in
 * place or replacing it — can change the document under us. It costs the file's size in storage for
 * as long as the document holds it.
 *
 * It stays open for as long as anything holds it — the document, and any save still writing from
 * it — and the last of them to let go closes it. Back can close the screen and the document with it
 * in the middle of a save, and a save that has started is never cut short.
 */
class Snapshot private constructor(
    private val descriptor: ParcelFileDescriptor,
    val size: Long,
    /** What it holds hashes to. */
    val hash: ByteArray,
) : Closeable {

    private var holders = 1

    companion object {

        /** A snapshot of whatever [fill] writes into it. */
        fun write(context: Context, fill: (OutputStream) -> Unit): Snapshot {
            val file = File.createTempFile("snapshot", null, context.cacheDir)
            // Unlinked before anything goes into it, so a copy the process dies halfway through
            // leaves nothing behind. The stream is not closed: the descriptor is the snapshot's.
            val descriptor = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
            } finally {
                file.delete()
            }
            try {
                val digest = digest()
                fill(DigestOutputStream(FileOutputStream(descriptor.fileDescriptor), digest))
                return Snapshot(descriptor, Os.fstat(descriptor.fileDescriptor).st_size, digest.digest())
            } catch (e: Throwable) {
                descriptor.close()
                throw e
            }
        }
    }

    /** [length] bytes from [at]. */
    fun read(at: Long, into: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val read = Os.pread(descriptor.fileDescriptor, into, offset + done, length - done, at + done)
            if (read <= 0) break
            done += read
        }
    }

    /** Keeps it open for one more holder, who lets go with [close]. */
    fun hold(): Snapshot = synchronized(this) {
        holders++
        this
    }

    override fun close() {
        if (synchronized(this) { --holders == 0 }) descriptor.close()
    }
}

/**
 * Hands [block] the file behind [fd] a pass at a time, from its start to its end or to [limit],
 * whichever comes first. It reads by position, so the descriptor's offset stays where it was.
 */
internal inline fun passes(fd: FileDescriptor, limit: Long = Long.MAX_VALUE, block: (ByteArray, Int) -> Unit) {
    val pass = ByteArray(PASS)
    var at = 0L
    while (at < limit) {
        val count = Os.pread(fd, pass, 0, minOf(PASS.toLong(), limit - at).toInt(), at)
        if (count <= 0) break
        block(pass, count)
        at += count
    }
}

/**
 * What the whole of the file behind [fd] hashes to. Two files hash the same only if they hold the
 * same bytes, which is how a save tells whether anything else has written its file: whatever was
 * written and however, while nothing that leaves the bytes as they were — a new time on the file, or
 * the same contents put back — counts as a change.
 *
 * Nothing the file reports about itself can stand in for it. A file picked through the external
 * storage provider is written through a layer that stamps its own time, when the copy loop ended
 * rather than when the bytes reached the file, seconds apart on a large one; and a tool that
 * empties the file partway through a save and writes something shorter leaves it at exactly the
 * size we wrote.
 */
internal fun hash(fd: FileDescriptor): ByteArray {
    val digest = digest()
    passes(fd) { pass, count -> digest.update(pass, 0, count) }
    return digest.digest()
}

private fun digest(): MessageDigest = MessageDigest.getInstance("SHA-256")

/**
 * A document: a [Snapshot] of the file, plus the changes made to it. What a save writes is that
 * copy with our edits in it and not one byte from anywhere else.
 *
 * Coordinates are bytes, on both sides. A piece is a byte range whichever it points at, so a range
 * nobody edited is copied out exactly as it came in and only what was touched is ever decoded or
 * encoded. That is what keeps a save from corrupting anything not edited, and what lets a file no
 * decoder round-trips be opened and edited. The cost is that a line break has to be looked for in
 * code units and not in bytes — see [Encoding].
 *
 * The copy and the typed buffer are kept for as long as the document is open, and the history
 * points into them and never into the file, so nothing done to the file can reach it.
 *
 * A document with no file behind it — a new one, or a decrypted one — is the same thing with every
 * piece pointing at the typed buffer, which for a decrypted one never moves onto disk.
 */
class Content private constructor(
    private val file: Snapshot?,
    val encoding: Encoding,
    val format: Format,
    private var typed: Typed,
    start: Piece?,
) : Bytes, Closeable {

    private val pieces = Pieces(start)

    private var compactAt = COMPACT_BYTES

    /**
     * Where the file was last in step with the document — opened, or saved — as a place on the line
     * of changes, and what the file held then. A save moves it, undo and redo do not, and neither
     * touches the other.
     */
    private var base = pieces.place

    private var baseHash: ByteArray? = file?.hash

    private var closed = false

    /**
     * Whether the document has edits the file does not hold, which is exactly that it stands
     * somewhere on the line other than the base: undoing back to what was saved is saved.
     */
    val unsaved: Boolean get() = pieces.place != base

    val length: Int get() = pieces.length

    companion object {

        /**
         * The whole of [file] as one piece. The document starts after the byte order mark, which
         * belongs to the file rather than to the text in it and is written back on the way out.
         * [spill] is where its typed buffer goes once it is too large for memory.
         */
        fun of(file: Snapshot, encoding: Encoding, spill: File?, format: Format = Format.Plain): Content {
            val from = encoding.bom.size
            val length = (file.size - from).coerceAtLeast(0).toInt()
            return Content(file, encoding, format, typed(format, spill), Piece(from, length, typed = false))
        }

        /** A document that is only what is in memory: a new one, or a decrypted one. */
        fun of(bytes: ByteArray, encoding: Encoding, format: Format = Format.Plain, spill: File? = null): Content {
            val typed = typed(format, spill)
            return Content(null, encoding, format, typed, Piece(typed.append(bytes), bytes.size, typed = true))
        }

        fun empty(spill: File? = null, encoding: Encoding = Encoding.Utf8, format: Format = Format.Plain): Content =
            of(ByteArray(0), encoding, format, spill)

        /** A typed buffer that may move onto disk only if the document is plain, wherever it is asked. */
        private fun typed(format: Format, spill: File?) = Typed(if (format == Format.Plain) spill else null)
    }

    override fun read(from: Int, to: Int): ByteArray {
        val out = ByteArray((to - from).coerceAtLeast(0))
        if (out.isEmpty()) return out
        var filled = 0
        pieces.forEach(from, to) { typedPiece, at, count ->
            if (typedPiece) typed.read(at, out, filled, count)
            else file?.read(at.toLong(), out, filled, count)
            filled += count
        }
        return out
    }

    /** [from] to [to] decoded for the screen, with the map back to where each character came from. */
    fun slice(from: Int, to: Int): Slice = decode(read(from, to), encoding)

    /**
     * Replaces the bytes between [from] and [to] with [bytes], and answers how much longer the
     * document got. Nothing is copied but the pieces themselves: [bytes] goes on the end of the
     * typed buffer and the sequence is rewritten around it.
     *
     * [continues] says this change belongs to the run the one before it is in, which is what one
     * press of undo walks back. Every change is recorded either way.
     */
    fun replace(from: Int, to: Int, bytes: ByteArray, continues: Boolean = false): Int {
        val added = if (bytes.isEmpty()) null else Piece(typed.append(bytes), bytes.size, typed = true)
        val delta = pieces.replace(from, to, listOfNotNull(added), continues)
        settle()
        return delta
    }

    val canUndo: Boolean get() = pieces.canUndo

    val canRedo: Boolean get() = pieces.canRedo

    /** Walks the last run of changes back, and says what it covered, or null when there is none. */
    fun undo(): Span? = pieces.undo().also { settle() }

    fun redo(): Span? = pieces.redo().also { settle() }

    /**
     * Keeps the pieces under their limit, which undoing a change can pass as well as making one,
     * and lets go of what the typed buffer holds that no piece names any more. That is looked for
     * only once the buffer is large enough to matter, and not again until it has doubled, so a
     * buffer that is all still in use is not copied over and over.
     */
    private fun settle() {
        pieces.collapse(::merged)
        if (typed.length < compactAt) return
        val kept = pieces.compact(typed)
        // A save still writing from the old one holds its own view of it, so this lets go of it
        // only as far as the document goes.
        typed.close()
        typed = kept
        compactAt = maxOf(COMPACT_BYTES, minOf(typed.length, Int.MAX_VALUE / 2) * 2)
    }

    /** The bytes [run] names, put on the end of the typed buffer as one piece. */
    private fun merged(run: List<Piece>): Piece {
        var total = 0
        for (piece in run) total += piece.length
        val bytes = ByteArray(total)
        var filled = 0
        for (piece in run) {
            if (piece.typed) typed.read(piece.at, bytes, filled, piece.length)
            else file?.read(piece.at.toLong(), bytes, filled, piece.length)
            filled += piece.length
        }
        return Piece(typed.append(bytes), total, typed = true)
    }

    /** Everything a save writes, taken in one step with nothing that waits — see [Take]. */
    fun take(): Take = Take(
        encoding, format, pieces.toArray(), file?.hold(), typed.view(), baseHash, pieces.place, !unsaved,
    )

    /**
     * The file holds [take] now, read back and confirmed: the base moves to where the document stood
     * when it was taken. Nothing else changes — no piece, nothing in the history, no copy — so
     * whatever was typed while the save ran is unsaved after it.
     */
    fun saved(take: Take) {
        base = take.place
        baseHash = take.hash
    }

    override fun close() {
        if (closed) return
        closed = true
        file?.close()
        typed.close()
    }
}

/**
 * The document as a save takes it: the pieces as they stood, the typed buffer as far as it went, a
 * hold on the private copy, and where the base and the document stood. Taken on the main thread and
 * written from on another, and nothing done to the document meanwhile can reach it — the pieces are
 * a copy, the typed buffer is only ever written past where it went, and the private copy never
 * changes and stays open until the save lets go of it, whether or not the document has let go first.
 */
class Take internal constructor(
    private val encoding: Encoding,
    val format: Format,
    private val pieces: Array<Piece>,
    private val file: Snapshot?,
    private val typed: Typed.View,
    /** What the file held when it was last in step with the document, or null if it never was. */
    val base: ByteArray?,
    internal val place: Long,
    /** Whether this is exactly what the file held then, with nothing changed since. */
    val clean: Boolean,
) : Closeable {

    /** What this hashes to, once that is known: the base's when [clean], and otherwise once it is written. */
    var hash: ByteArray? = if (clean) base else null
        private set

    private var closed = false

    /** Writes the whole of it into [out], byte order mark first, and learns its [hash] on the way. */
    fun writeTo(out: OutputStream) {
        val digest = digest()
        // Buffered, because a piece of typing can be one byte and every write under it is a call into
        // the file.
        val sink = BufferedOutputStream(DigestOutputStream(out, digest), PASS)
        sink.write(encoding.bom)
        val pass = ByteArray(PASS)
        for (piece in pieces) {
            var done = 0
            while (done < piece.length) {
                val count = minOf(PASS, piece.length - done)
                if (piece.typed) typed.read(piece.at + done, pass, 0, count)
                else file?.read((piece.at + done).toLong(), pass, 0, count)
                sink.write(pass, 0, count)
                done += count
            }
        }
        sink.flush()
        hash = digest.digest()
    }

    override fun close() {
        if (closed) return
        closed = true
        file?.close()
        typed.close()
    }
}
