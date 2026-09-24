package com.lucasta.edit

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.Closeable
import java.io.File
import java.io.OutputStream

/** Bytes moved per pass whenever the whole document has to go somewhere. */
internal const val PASS = 64 * 1024

/**
 * How large the buffer holding what was typed has to get before it is worth looking for parts of it
 * nothing names any more. Typing never reaches this; pasting and deleting over and over is what
 * does, and that is the case it exists for.
 */
private const val COMPACT_BYTES = 1 shl 20

/**
 * What a file was, closely enough to tell whether it is still the same one.
 *
 * [modified] is in nanoseconds. In whole seconds, a tool rewriting the file in place within the
 * second it was stamped in, at the same size, would leave it looking untouched; what is left of that
 * is the clock the filesystem stamps writes with, which ticks every few milliseconds. Before API 27
 * there are only whole seconds to be had.
 */
data class Stamp(val device: Long, val inode: Long, val size: Long, val modified: Long) {
    constructor(stat: StructStat) : this(stat.st_dev, stat.st_ino, stat.st_size, stat.modified)
}

private val StructStat.modified: Long
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        st_mtim.tv_sec * 1_000_000_000 + st_mtim.tv_nsec
    } else {
        st_mtime * 1_000_000_000
    }

/**
 * The file as it was the instant it was opened, and the only thing a document ever reads it
 * through.
 *
 * A descriptor pins the file it was opened on and not the name. A tool that replaces the file —
 * writing a temporary and renaming over it, which is what Syncthing and rclone do — leaves what we
 * are reading alive and intact until we close it, whatever the name now points at. That is a real
 * snapshot for the price of one open descriptor, and it is what lets the document be the file plus
 * a list of changes instead of a copy of the file in memory. The bytes stay on disk rather than in
 * the heap, so it costs the space of the replaced file until the document is closed.
 *
 * The one case it cannot cover is a tool that rewrites the same file in place, which changes the
 * bytes under us. [rewritten] catches that by reading size and modification time back off our own
 * descriptor, for nothing.
 */
class Snapshot private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val opened: Stamp,
) : Closeable {

    val size: Long get() = opened.size

    val stamp: Stamp get() = opened

    companion object {

        fun open(context: Context, uri: Uri): Snapshot {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw NotSeekable("nothing behind it to read")
            return adopt(descriptor)
        }

        fun open(file: File): Snapshot =
            adopt(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))

        /** What [uri] points at right now, or null if it cannot be opened at all any more. */
        fun stamp(context: Context, uri: Uri): Stamp? = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")
                ?.use { Stamp(Os.fstat(it.fileDescriptor)) }
        }.getOrNull()

        private fun adopt(descriptor: ParcelFileDescriptor): Snapshot {
            val stat = try {
                Os.fstat(descriptor.fileDescriptor)
            } catch (e: Exception) {
                descriptor.close()
                throw NotSeekable("it cannot be looked at as a file")
            }
            // A pipe or a socket is a provider streaming its content rather than a file on the
            // phone. Refused here rather than half way through, because a document reads the parts
            // of a file nobody has edited straight out of it, in whatever order they are looked at.
            if (!OsConstants.S_ISREG(stat.st_mode)) {
                descriptor.close()
                throw NotSeekable("it arrives as a stream, not as a file")
            }
            return Snapshot(descriptor, Stamp(stat))
        }
    }

    /**
     * [length] bytes from [at]. A read that comes up short means the file has been truncated under
     * us, which is the in-place rewrite [rewritten] reports; the rest is left zeroed rather than
     * failing, so that scrolling over the hole shows something and saving is what refuses.
     */
    fun read(at: Long, into: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val read = Os.pread(descriptor.fileDescriptor, into, offset + done, length - done, at + done)
            if (read <= 0) break
            done += read
        }
    }

    /** The first [count] bytes, or the whole file if it is shorter. */
    fun head(count: Int): ByteArray {
        val bytes = ByteArray(minOf(count.toLong(), size).toInt())
        read(0, bytes, 0, bytes.size)
        return bytes
    }

    /** Whether the file we are reading has been rewritten in place since it was opened. */
    fun rewritten(): Boolean = runCatching {
        val now = Stamp(Os.fstat(descriptor.fileDescriptor))
        now.size != opened.size || now.modified != opened.modified
    }.getOrDefault(true)

    override fun close() = descriptor.close()
}

/**
 * A document: the file where it lies, plus the changes made to it.
 *
 * It behaves as a whole copy of the file taken the instant it was opened. What a save writes is
 * that copy with our edits in it and not one byte from anywhere else — never something another tool
 * put there in the meantime. The change list is only how a copy that large is afforded.
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

    /** The file this document was last in step with, or null if it has never been in step with one. */
    var stamp: Stamp? = file?.stamp
        private set

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

    /** Whether the file underneath has been rewritten in place since it was opened. */
    fun rewritten(): Boolean = file?.rewritten() ?: false

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
     * Starts again from a file that already holds exactly this document, which is what a save has
     * just made true. The change list collapses back to one piece and the typed buffer is let go,
     * so a long session does not accumulate.
     */
    fun rebase(file: Snapshot, stamp: Stamp?) {
        this.file?.close()
        this.file = file
        this.stamp = stamp
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
