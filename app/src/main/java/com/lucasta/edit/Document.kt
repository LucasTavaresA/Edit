package com.lucasta.edit

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.Choreographer
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class Encoding(val charset: Charset, val bom: ByteArray, val bytesPerChar: Int) {
    Utf8(Charsets.UTF_8, byteArrayOf(), 1),
    Utf8Bom(Charsets.UTF_8, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), 1),
    Utf16Le(Charsets.UTF_16LE, byteArrayOf(0xFF.toByte(), 0xFE.toByte()), 2),
    Utf16Be(Charsets.UTF_16BE, byteArrayOf(0xFE.toByte(), 0xFF.toByte()), 2),
}

/** Enough to hold the longest BOM, which is UTF-8's three bytes. */
internal val MAX_BOM_BYTES = Encoding.entries.maxOf { it.bom.size }

/**
 * What caps a plain file is the buffer it is held in, and nothing else any more: reading streams,
 * writing streams, and laying out only ever touches what is on screen. A Java char is two bytes, so
 * the buffer is twice the file, and on a 256 MB heap, a common `dalvik.vm.heapgrowthlimit`, that
 * puts a hard ceiling somewhere near 96 MB whatever else is done.
 *
 * The cap sits well below that ceiling for a second reason: a `StringBuilder` that runs out of room
 * doubles, and the doubling allocates the new array while the old one is still alive. At 32 MB that
 * is 64 MB of buffer growing to 128 MB with the old 64 MB still held — tight but survivable. At
 * 64 MB it is not survivable, so a file that size could be opened and read but not typed into
 * indefinitely. 64 MB was measured opening in 1.6 s at 153 MB of heap, so raising this is one
 * constant and a decision about that risk, not more work.
 */
const val MAX_FILE_BYTES = 32L * 1024 * 1024

/**
 * Encrypted files stay on the path everything used to be on: read whole, decrypted whole, and laid
 * out whole in a single text field rather than in chunks. Nothing about the plaintext of an
 * encrypted file should ever be inferable from how it is drawn, and a layout that measures only the
 * part you are looking at is one more thing that would have to be reasoned about to be sure of
 * that. Whole-document layout costs about 5 seconds per MB, so the cap is the size that still
 * appears in about a second, leaving the rest of that second for the key derivation.
 */
const val MAX_ENCRYPTED_BYTES = 128L * 1024

enum class Format(val maxBytes: Long) {
    Plain(MAX_FILE_BYTES),
    Encrypted(MAX_ENCRYPTED_BYTES),
}

/**
 * A document on its way in or out. [text] is handed over, not shared: on the way in it becomes the
 * editor's buffer as it stands, and on the way out it is the editor's buffer, so nothing may write
 * to it while a `TextDocument` holding it is still in use.
 */
data class TextDocument(val text: CharSequence, val encoding: Encoding, val format: Format = Format.Plain)

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

suspend fun openDocument(context: Context, uri: Uri): Result<TextDocument> {
    val format = inspect(context, uri).getOrElse { return Result.failure(it) }
    return when (format) {
        Format.Plain -> readPlainDocument(context, uri)
        Format.Encrypted -> readEncryptedDocument(context, uri)
    }
}

suspend fun saveDocument(context: Context, uri: Uri, document: TextDocument): Result<Unit> =
    when (document.format) {
        Format.Plain -> writePlainDocument(context, uri, document)
        Format.Encrypted -> writeEncryptedDocument(context, uri, document)
    }

/**
 * Format and size both, from the first bytes and a query, before anything reads the whole file.
 * The format comes first because the two have different limits: what an encrypted file may cost is
 * not what a plain one may cost.
 */
private suspend fun inspect(context: Context, uri: Uri): Result<Format> =
    withContext(Dispatchers.IO) {
        runCatching {
            val header = checkNotNull(context.contentResolver.openInputStream(uri)) { "no input stream" }
                .use { it.readAtMost(ENCRYPTED_HEADER_BYTES) }
            val format = if (looksEncrypted(header)) Format.Encrypted else Format.Plain
            val size = fileSize(context, uri)
            if (size != null && size > format.maxBytes) {
                throw TooBig("${size.readable()}, over the ${format.maxBytes.readable()} limit")
            }
            format
        }
    }

internal fun InputStream.readAtMost(count: Int): ByteArray {
    val buffer = ByteArray(count)
    var filled = 0
    while (filled < count) {
        val read = read(buffer, filled, count - filled)
        if (read <= 0) break
        filled += read
    }
    return buffer.copyOf(filled)
}

/**
 * Holds the limit against the bytes as they arrive, which is the only way to hold it at all for a
 * provider that reports no size — and a second line for one that reports the wrong size.
 */
internal class Capped(stream: InputStream, private val limit: Long) : FilterInputStream(stream) {

    private var total = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) count(1)
        return value
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        count(super.read(bytes, offset, length))

    private fun count(read: Int): Int {
        if (read > 0) {
            total += read
            if (total > limit) throw TooBig("over the ${limit.readable()} limit")
        }
        return read
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

/** Null when the provider does not report a size, which is not the same as an empty file. */
fun fileSize(context: Context, uri: Uri): Long? =
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
