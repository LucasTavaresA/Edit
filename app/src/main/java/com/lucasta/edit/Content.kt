package com.lucasta.edit

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Bytes moved per pass whenever the whole document has to go somewhere. */
internal const val PASS = 64 * 1024

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
 */
class Snapshot private constructor(
    private val descriptor: ParcelFileDescriptor,
    val size: Long,
    /** What it holds hashes to. */
    val hash: ByteArray,
) : Closeable {

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

    /** All of it, written into the file behind [out] from wherever that stands. */
    fun writeTo(out: FileDescriptor) = passes(descriptor.fileDescriptor, size) { pass, count ->
        var done = 0
        while (done < count) done += Os.write(out, pass, done, count - done)
    }

    override fun close() = descriptor.close()
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
 */
internal fun hash(fd: FileDescriptor): ByteArray {
    val digest = digest()
    passes(fd) { pass, count -> digest.update(pass, 0, count) }
    return digest.digest()
}

private fun digest(): MessageDigest = MessageDigest.getInstance("SHA-256")

/**
 * A document: a [Snapshot] of the file, plus the changes made to it.
 *
 * What a save writes is that copy with our edits in it and not one byte from anywhere else — never
 * something another tool put there in the meantime.
 *
 * Coordinates are bytes, on both sides. A piece is a byte range whichever it points at, so a range
 * nobody edited is copied to the output exactly as it came in and only the parts that were touched
 * are ever decoded or encoded. That is what makes a save incapable of corrupting what was not
 * edited, and what makes a file no decoder round-trips openable and editable. The cost is that
 * everything looking for a line break has to look in code units and not in bytes, since in UTF-16 a
 * byte 0x0A is only sometimes a line break.
 *
 * A document with no file behind it — a new one, or a decrypted one — is the same thing with every
 * piece pointing at the typed buffer. There is nothing else to be: the encrypted path holds its
 * plaintext whole, in memory, and puts no piece of it into a file anywhere.
 */
class Content private constructor(
    private var file: Snapshot?,
    val encoding: Encoding,
    val format: Format,
    private var typed: ByteArray,
    private var typedLength: Int,
) : Bytes, Closeable {

    private val pieces = Pieces()

    private var compactAt = COMPACT_BYTES

    /**
     * What this document's snapshot hashes to, or null if it has none. A save writes over its file
     * without asking only while the file still hashes the same.
     */
    val hash: ByteArray? get() = file?.hash

    val length: Int get() = pieces.length

    companion object {

        /**
         * The whole of [file] as one piece. The document starts after the byte order mark, which
         * belongs to the file rather than to the text in it and is written back on the way out.
         */
        fun of(file: Snapshot, encoding: Encoding, format: Format = Format.Plain): Content {
            val from = encoding.bom.size
            val length = (file.size - from).coerceAtLeast(0).toInt()
            return Content(file, encoding, format, ByteArray(0), 0).apply {
                pieces.reset(Piece(from, length, typed = false))
            }
        }

        /** A document that is only what is in memory: a new one, or a decrypted one. */
        fun of(bytes: ByteArray, encoding: Encoding, format: Format = Format.Plain): Content =
            Content(null, encoding, format, bytes, bytes.size).apply {
                pieces.reset(Piece(0, bytes.size, typed = true))
            }

        fun empty(encoding: Encoding = Encoding.Utf8, format: Format = Format.Plain): Content =
            of(ByteArray(0), encoding, format)
    }

    override fun read(from: Int, to: Int): ByteArray {
        val out = ByteArray((to - from).coerceAtLeast(0))
        if (out.isEmpty()) return out
        var filled = 0
        pieces.forEach(from, to) { typedPiece, at, count ->
            if (typedPiece) System.arraycopy(typed, at, out, filled, count)
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
        val delta = pieces.replace(from, to, listOfNotNull(append(bytes)), continues)
        settle()
        return delta
    }

    val canUndo: Boolean get() = pieces.canUndo

    val canRedo: Boolean get() = pieces.canRedo

    /** Walks the last run of changes back, and says what it covered, or null when there is none. */
    fun undo(): Span? = pieces.undo()

    fun redo(): Span? = pieces.redo()

    /**
     * Lets go of whatever the typed buffer is holding that no piece names any more, which is what a
     * session of pasting and deleting leaves behind. Only ever looked for once the buffer is large
     * enough for it to matter, and the next look is put off until it has doubled again, so a buffer
     * that is all still in use is not copied over and over to learn that.
     */
    private fun settle() {
        if (typedLength < compactAt) return
        typed = pieces.compact(typed)
        typedLength = typed.size
        compactAt = maxOf(COMPACT_BYTES, minOf(typedLength, Int.MAX_VALUE / 2) * 2)
    }

    private fun append(bytes: ByteArray): Piece? {
        if (bytes.isEmpty()) return null
        if (typedLength + bytes.size > typed.size) {
            val room = maxOf(typedLength + bytes.size, maxOf(typed.size * 2, PASS))
            typed = typed.copyOf(room)
        }
        System.arraycopy(bytes, 0, typed, typedLength, bytes.size)
        val piece = Piece(typedLength, bytes.size, typed = true)
        typedLength += bytes.size
        return piece
    }

    /** The document as bytes, in order, byte order mark first. */
    fun writeTo(out: OutputStream) {
        out.write(encoding.bom)
        val pass = ByteArray(PASS)
        pieces.forEach(0, length) { typedPiece, at, length ->
            if (typedPiece) {
                out.write(typed, at, length)
            } else {
                var done = 0
                while (done < length) {
                    val count = minOf(PASS, length - done)
                    file?.read((at + done).toLong(), pass, 0, count)
                    out.write(pass, 0, count)
                    done += count
                }
            }
        }
    }

    /**
     * Starts again from a snapshot that holds exactly this document, which is what a save has just
     * written. The change list collapses back to one piece and the typed buffer is let go, so a long
     * session does not accumulate.
     */
    fun rebase(file: Snapshot) {
        this.file?.close()
        this.file = file
        pieces.reset(Piece(encoding.bom.size, length, typed = false))
        typed = ByteArray(0)
        typedLength = 0
        compactAt = COMPACT_BYTES
    }

    override fun close() {
        file?.close()
        file = null
    }
}
