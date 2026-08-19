package com.lucasta.edit

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.Choreographer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a file may be and still be opened. Nothing about the editor holds a plain file in memory any
 * more — the document is the file where it lies plus the changes made to it — so this is no longer
 * a memory limit at all. What is left is patience: opening indexes the file for chunk boundaries at
 * roughly the speed it can be read, and everything is addressed with an `Int`, which is the real
 * ceiling at 2 GB. This sits below both, and moving it is one constant.
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
 * A file whose bytes only arrive in order, which is the one thing this document model cannot work
 * with: a piece of the document is a range of the file, read when it is looked at. Every provider
 * backed by a real file on the phone hands over a descriptor we can read out of order, which is
 * every file this app exists for.
 */
class NotSeekable(message: String) : Exception(message)

/**
 * Opens [uri] as a document: a descriptor held open on the file as it stands this instant, plus the
 * changes about to be made to it. The descriptor is the snapshot — see [Snapshot] — so nothing is
 * copied and nothing is held in memory that is proportional to the file.
 */
suspend fun openDocument(context: Context, uri: Uri): Result<Content> = withContext(Dispatchers.IO) {
    runCatching {
        val file = Snapshot.open(context, uri)
        var adopted = false
        try {
            val head = file.head(HEAD_BYTES)
            val format = if (looksEncrypted(head)) Format.Encrypted else Format.Plain
            if (file.size > format.maxBytes) {
                throw TooBig("${file.size.readable()}, over the ${format.maxBytes.readable()} limit")
            }
            if (format == Format.Encrypted) {
                readEncryptedDocument(context, uri).getOrThrow()
            } else {
                readPlainDocument(file, head).also { adopted = true }
            }
        } finally {
            if (!adopted) file.close()
        }
    }
}

suspend fun saveDocument(context: Context, uri: Uri, content: Content): Result<Unit> =
    when (content.format) {
        Format.Plain -> writePlainDocument(context, uri, content)
        Format.Encrypted -> writeEncryptedDocument(context, uri, content)
    }

/** What stands between the document and the file it was opened on. */
enum class Collision {
    /** The file is where we left it, byte for byte. */
    None,

    /**
     * Something replaced the file since it was opened — sync brought a newer version. Our own copy
     * is intact, because the descriptor pinned what it was opened on, so the only question is whose
     * version the file ends up holding, and that is the user's to answer.
     */
    Replaced,

    /**
     * Something rewrote the same file in place while we were reading it, which is the one case a
     * descriptor cannot cover: the bytes we have not read yet are already somebody else's. Saving
     * would write a document that is half old and half new.
     */
    Rewritten,
}

/**
 * Whether [uri] is still the file [content] was opened on. Costs one open and one `fstat` — where a
 * hash would cost a full read of the file to learn the same thing, and could still only ever say
 * that bytes are gone, never bring them back.
 */
suspend fun collision(context: Context, uri: Uri, content: Content): Collision =
    withContext(Dispatchers.IO) {
        when {
            content.rewritten() -> Collision.Rewritten
            content.stamp == null -> Collision.Replaced
            content.stamp != Snapshot.stamp(context, uri) -> Collision.Replaced
            else -> Collision.None
        }
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
