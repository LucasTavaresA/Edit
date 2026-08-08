package com.lucasta.edit

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class Encoding(val charset: Charset, val bom: ByteArray) {
    Utf8(Charsets.UTF_8, byteArrayOf()),
    Utf8Bom(Charsets.UTF_8, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),
    Utf16Le(Charsets.UTF_16LE, byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
    Utf16Be(Charsets.UTF_16BE, byteArrayOf(0xFE.toByte(), 0xFF.toByte())),
}

data class TextDocument(val text: String, val encoding: Encoding)

class NotText(message: String) : Exception(message)

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun decode(bytes: ByteArray, encoding: Encoding): String =
    encoding.charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, encoding.bom.size, bytes.size - encoding.bom.size))
        .toString()

private fun encode(document: TextDocument): ByteArray =
    document.encoding.bom + document.text.toByteArray(document.encoding.charset)

suspend fun readDocument(context: Context, uri: Uri): Result<TextDocument> = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = checkNotNull(context.contentResolver.openInputStream(uri)) { "no input stream" }
            .use { it.readBytes() }

        val encoding = Encoding.entries
            .firstOrNull { it.bom.isNotEmpty() && bytes.startsWith(it.bom) }
            ?: Encoding.Utf8

        val text = try {
            decode(bytes, encoding)
        } catch (e: Exception) {
            throw NotText("not valid text")
        }

        // Decoding strictly is not quite enough on its own. Re-encoding and
        // comparing proves this exact file can be written back byte for byte,
        // so saving can never turn it into something else.
        //
        // TODO: offer to open anyway. A mostly-fine file with a few bad bytes
        // is still worth reading, and sometimes worth editing. That has to be
        // a deliberate choice by the user, not a default, because saving will
        // rewrite the bytes we could not represent and the original is then
        // gone. Whatever the wording ends up being, it has to say plainly that
        // a backup must exist somewhere before saving over the file. The same
        // offer applies to the strict decode failure above — a single corrupt
        // byte fails there, not here.
        val document = TextDocument(text, encoding)
        if (!encode(document).contentEquals(bytes)) throw NotText("cannot be saved back unchanged")
        document
    }
}

suspend fun writeDocument(context: Context, uri: Uri, document: TextDocument): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            // "wt" truncates. Plain "w" leaves any trailing bytes of a longer
            // previous version behind on some providers.
            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "no output stream" }
                .use { it.write(encode(document)) }
        }
    }

fun displayName(context: Context, uri: Uri): String {
    val name = context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
    return name ?: uri.lastPathSegment ?: uri.toString()
}
