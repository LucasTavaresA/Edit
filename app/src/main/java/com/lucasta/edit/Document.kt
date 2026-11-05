package com.lucasta.edit

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
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
 * What a plain file may be and still be opened. The document is a copy of the file on disk plus its
 * changes, so this is not a memory limit: everything is addressed with an `Int`, which is the
 * ceiling at 2 GB.
 */
const val MAX_FILE_BYTES = Int.MAX_VALUE.toLong()

/**
 * Encrypted files are read, decrypted and held whole, and laid out whole in a single text field
 * rather than in chunks, so nothing about the plaintext can be inferred from how it is drawn.
 * Whole-document layout costs about 5 seconds per MB, so the cap is the size that still appears in
 * about a second, leaving the rest of that second for the key derivation.
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
 * Writes [take] over [uri] and answers [Collision.None], or answers what it found instead. Unless
 * [anyway], the file has to still hold what the document was last in step with.
 */
suspend fun saveDocument(context: Context, uri: Uri, take: Take, anyway: Boolean): Result<Collision> =
    when (take.format) {
        Format.Plain -> writePlainDocument(context, uri, take, anyway)
        Format.Encrypted -> writeEncryptedDocument(context, uri, take, anyway)
    }

/** What a save found in the file it was to write over. */
enum class Collision {
    /** Nothing in the way: the file now holds the document. */
    None,

    /**
     * Something else wrote the file since the document was last in step with it — sync, or another
     * app — before the save or while it ran. The document is still what was to be saved, so whose
     * version the file ends up holding is the user's question to answer.
     */
    Changed,
}

internal fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

internal fun sniffEncoding(bytes: ByteArray): Encoding =
    Encoding.entries.firstOrNull { it.bom.isNotEmpty() && bytes.startsWith(it.bom) } ?: Encoding.Utf8

/**
 * What [uri] is called. Asking the provider can fail outright, for a file whose access has gone
 * among others, and a name is never worth failing for: the link's own last segment stands in.
 */
fun displayName(context: Context, uri: Uri): String {
    val name = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
    return name ?: uri.lastPathSegment ?: uri.toString()
}

/** The phone's own storage, whose document ids are a volume and a path on it. */
private const val STORAGE = "com.android.externalstorage.documents"

/**
 * Where [uri] lives: the folder, when the document id is a path or MediaStore says, and the app
 * that provides it, unless that is the phone's own storage, MediaStore, or Downloads with a path on
 * the phone's storage, whose folders say it all.
 * It is what tells two files of the same name apart in the list.
 */
fun whereIs(context: Context, uri: Uri): String {
    val authority = uri.authority.orEmpty()
    mediaFolder(context, uri)?.let { return it }
    val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
    val folder = when {
        id == null -> null
        authority == STORAGE -> {
            val volume = id.substringBefore(':')
            val path = id.substringAfter(':', "").substringBeforeLast('/', "")
            storageFolder(volume.takeIf { it != "primary" }, path)
        }
        // Downloads hands out ids like `raw:/storage/emulated/0/Download/a.txt`, and providers
        // backed by a plain directory, Termux's among them, the absolute path itself.
        id.removePrefix("raw:").startsWith('/') -> {
            val path = id.removePrefix("raw:").substringBeforeLast('/').ifEmpty { "/" }
            // Downloads is the system's own, so a path on the phone's storage says it all, as one
            // from the storage provider does, and is told the same way.
            if (authority in MAPPED) onStorage(path)?.let { return it }
            path
        }
        else -> null
    }
    val app = if (authority == STORAGE) null else runCatching {
        val packages = context.packageManager
        packages.resolveContentProvider(authority, 0)?.loadLabel(packages)?.toString()
    }.getOrNull() ?: authority
    return listOfNotNull(app, folder).joinToString(" · ")
}

/**
 * A folder on the phone's storage as the storage provider names it: [path] on the phone's own
 * storage when [volume] is null, otherwise on that volume, a card say.
 */
private fun storageFolder(volume: String?, path: String): String = when {
    volume != null -> "$volume/$path".trimEnd('/')
    path.isEmpty() -> "internal storage"
    else -> path
}

/**
 * The folder at the absolute [path] as [storageFolder] tells it, if it is on the phone's storage:
 * `/storage/emulated/<user>` for its own, `/storage/<volume>` for any other.
 */
private fun onStorage(path: String): String? {
    val parts = path.removePrefix("/storage/").takeIf { it != path }?.split('/') ?: return null
    return when {
        parts[0] == "emulated" && parts.size >= 2 -> storageFolder(null, parts.drop(2).joinToString("/"))
        parts[0].isNotEmpty() && parts[0] != "emulated" && parts[0] != "self" ->
            storageFolder(parts[0], parts.drop(1).joinToString("/"))
        else -> null
    }
}

/**
 * The providers behind the picker's Documents and Downloads views, whose links MediaStore can map to
 * its own. Only these are asked: mapping is a call into the provider, made on the main thread as
 * the list comes up, and these are the system's own, where any other is an app of its own whose
 * process may have to start, or be busy, before it answers.
 */
private val MAPPED = setOf("com.android.providers.media.documents", "com.android.providers.downloads.documents")

/** The folder MediaStore keeps [uri] in, for a MediaStore link or one it can map to its own. */
private fun mediaFolder(context: Context, uri: Uri): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val media = when (uri.authority) {
        MediaStore.AUTHORITY -> uri
        in MAPPED -> runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull() ?: return null
        else -> return null
    }
    val path = runCatching {
        context.contentResolver
            .query(media, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: return null
    return path.trimEnd('/').ifEmpty { "internal storage" }
}
