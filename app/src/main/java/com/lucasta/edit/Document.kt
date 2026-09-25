package com.lucasta.edit

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.Choreographer
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a file may be and still be opened. Nothing about the editor holds a plain file in memory any
 * more — the document is a copy of the file on disk plus the changes made to it — so this is no
 * longer a memory limit at all. What is left is patience: opening copies the file and indexes it for
 * chunk boundaries, each at roughly the speed it can be read, and everything is addressed with an
 * `Int`, which is the real ceiling at 2 GB. This sits below both, and moving it is one constant.
 */
const val MAX_FILE_BYTES = Int.MAX_VALUE.toLong()

/**
 * Encrypted files stay on the path everything used to be on: read whole, decrypted whole, held
 * whole in memory and laid out whole in a single text field rather than in chunks. Nothing about
 * the plaintext of an encrypted file should ever be inferable from how it is drawn, and a layout
 * that measures only the part you are looking at is one more thing that would have to be reasoned
 * about to be sure of that. Whole-document layout costs about 5 seconds per MB, so the cap is the
 * size that still appears in about a second, leaving the rest of that second for the key derivation.
 */
const val MAX_ENCRYPTED_BYTES = 128L * 1024

enum class Format(val maxBytes: Long) {
    Plain(MAX_FILE_BYTES),
    Encrypted(MAX_ENCRYPTED_BYTES),
}

/**
 * How much of the front of a file is looked at before opening it: enough to see a format's magic,
 * and enough of the text to tell prose from a JPEG.
 */
private const val HEAD_BYTES = 64 * 1024

internal fun Long.readable(): String =
    if (this >= 1024 * 1024) "${this / (1024 * 1024)} MB" else "${this / 1024} KB"

/** Temporary, while the open path is being made fast. `adb logcat -s edit.perf`. */
internal inline fun <T> timed(label: String, block: () -> T): T {
    val start = SystemClock.elapsedRealtimeNanos()
    val result = block()
    Log.i("edit.perf", "%s %.1f ms".format(label, (SystemClock.elapsedRealtimeNanos() - start) / 1e6))
    return result
}

/**
 * Time to the end of the next frame, which is the frame that composes and lays out whatever was
 * just handed to the UI. Also temporary.
 */
internal fun frameAfterNext(label: String) {
    val start = SystemClock.elapsedRealtimeNanos()
    Choreographer.getInstance().postFrameCallback {
        Choreographer.getInstance().postFrameCallback {
            Log.i("edit.perf", "%s %.1f ms".format(label, (SystemClock.elapsedRealtimeNanos() - start) / 1e6))
        }
    }
}

/** Temporary, alongside the other perf logging: what the heap is doing around a load. */
internal fun heap(label: String) {
    val runtime = Runtime.getRuntime()
    val used = runtime.totalMemory() - runtime.freeMemory()
    Log.i("edit.perf", "%s heap %d / %d MB".format(label, used / (1024 * 1024), runtime.maxMemory() / (1024 * 1024)))
}

class NotText(message: String) : Exception(message)

class NotSupported(message: String) : Exception(message)

class TooBig(message: String) : Exception(message)

/**
 * A file whose bytes only arrive in order, as a stream. Every provider backed by a real file on the
 * phone hands over the file itself, which is every file this app exists for; a stream has no size
 * to copy it by, and nothing a save could write back into.
 */
class NotSeekable(message: String) : Exception(message)

/**
 * Opens [uri] as a document: a copy of the file as it stands this instant — see [Snapshot] — plus
 * the changes about to be made to it.
 */
suspend fun openDocument(context: Context, uri: Uri): Result<Content> = withContext(Dispatchers.IO) {
    runCatching {
        val file = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw NotSeekable("nothing behind it to read")
        file.use {
            val stat = Os.fstat(it.fileDescriptor)
            if (!OsConstants.S_ISREG(stat.st_mode)) throw NotSeekable("it arrives as a stream, not as a file")
            val head = head(it.fileDescriptor, HEAD_BYTES)
            val format = if (looksEncrypted(head)) Format.Encrypted else Format.Plain
            if (stat.st_size > format.maxBytes) {
                throw TooBig("${stat.st_size.readable()}, over the ${format.maxBytes.readable()} limit")
            }
            if (format == Format.Encrypted) {
                readEncryptedDocument(context, uri).getOrThrow()
            } else {
                readPlainDocument(context, it.fileDescriptor, stat.st_size, head)
            }
        }
    }
}

/** The first [count] bytes of the file behind [fd], or all of it if it is shorter. */
private fun head(fd: FileDescriptor, count: Int): ByteArray {
    val head = ByteArrayOutputStream()
    passes(fd, count.toLong()) { pass, read -> head.write(pass, 0, read) }
    return head.toByteArray()
}

/**
 * Writes [content] over [uri] and answers [Collision.None], or answers what it found instead. Unless
 * [anyway], the file has to still hold what the document was last in step with.
 */
suspend fun saveDocument(context: Context, uri: Uri, content: Content, anyway: Boolean): Result<Collision> =
    when (content.format) {
        Format.Plain -> writePlainDocument(context, uri, content, anyway)
        Format.Encrypted -> writeEncryptedDocument(context, uri, content, anyway)
    }

/** What a save found in the file it was to write over. */
enum class Collision {
    /** Nothing in the way: the file now holds the document. */
    None,

    /**
     * Something else wrote the file since the document was last in step with it — sync brought a
     * newer version, or another app wrote into it, before the save or while it ran. The document is
     * still exactly what was to be saved, so the only question is whose version the file ends up
     * holding, and that is the user's to answer.
     */
    Changed,
}

internal fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

internal fun sniffEncoding(bytes: ByteArray): Encoding =
    Encoding.entries.firstOrNull { it.bom.isNotEmpty() && bytes.startsWith(it.bom) } ?: Encoding.Utf8

fun displayName(context: Context, uri: Uri): String {
    val name = context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
    return name ?: uri.lastPathSegment ?: uri.toString()
}
