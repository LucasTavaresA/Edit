package com.lucasta.edit

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PushbackInputStream
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Characters moved per pass, both ways. Small enough to stay cheap, large enough to be rare. */
private const val PASS = 64 * 1024

/** Spare room left in a freshly read buffer, so that ordinary typing never makes it grow. */
private const val SLACK = 1024L * 1024

/**
 * Reads the file straight into the buffer the editor will hold, decoding as the bytes arrive.
 *
 * Nothing whole-file is allocated but the buffer itself. Reading the bytes into an array, decoding
 * that into a char buffer and turning that into a String held four copies of the file at once and
 * was what actually capped the size we could open — 64 MB died on a 256 MB heap while 32 MB
 * survived, which had nothing to do with what the editor could then do with the text.
 */
suspend fun readPlainDocument(context: Context, uri: Uri): Result<TextDocument> = withContext(Dispatchers.IO) {
    runCatching {
        val size = fileSize(context, uri)
        checkNotNull(context.contentResolver.openInputStream(uri)) { "no input stream" }.use { raw ->
            val input = PushbackInputStream(Capped(raw, MAX_FILE_BYTES), MAX_BOM_BYTES)
            val head = input.readAtMost(MAX_BOM_BYTES)
            val encoding = sniffEncoding(head)
            input.unread(head, encoding.bom.size, head.size - encoding.bom.size)

            // The strict decode below is the whole guarantee. UTF-8 and UTF-16 are both
            // bijections on well-formed input: every sequence the decoder accepts re-encodes to
            // the identical bytes, and everything that would not — overlong forms, unpaired
            // surrogates, truncated sequences — is exactly what REPORT rejects. So re-encoding
            // and comparing proved nothing that decoding had not already proved, and it cost
            // more than the read and the decode together.
            //
            // TODO: offer to open anyway. A mostly-fine file with a few bad bytes is still worth
            // reading, and sometimes worth editing. That has to be a deliberate choice by the
            // user, not a default, because saving will rewrite the bytes we could not represent
            // and the original is then gone. Whatever the wording ends up being, it has to say
            // plainly that a backup must exist somewhere before saving over the file.
            val text = try {
                timed("read") { input.decodeInto(encoding, size) }
            } catch (e: TooBig) {
                throw e
            } catch (e: Exception) {
                throw NotText("not valid text")
            }
            TextDocument(text, encoding, Format.Plain)
        }
    }
}

/**
 * Writes the document as it stands, encoding as it goes. The text is whatever the editor is
 * holding, never a copy of it: a copy of a 64 MB document is the thing that would fail.
 */
suspend fun writePlainDocument(context: Context, uri: Uri, document: TextDocument): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "no output stream" }
                .use { out ->
                    out.write(document.encoding.bom)
                    val encoder = document.encoding.charset.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    // Closing the writer is what flushes the encoder, including a surrogate left
                    // over from the last pass, so it has to close before the stream does.
                    OutputStreamWriter(out, encoder).use { writer ->
                        val text = document.text
                        var at = 0
                        while (at < text.length) {
                            val end = minOf(at + PASS, text.length)
                            writer.append(text, at, end)
                            at = end
                        }
                    }
                }
        }
    }

/**
 * Decodes everything left in the stream into a fresh buffer, sized up front from the file's own
 * size so it never has to grow and copy itself.
 */
private fun InputStream.decodeInto(encoding: Encoding, size: Long?): StringBuilder {
    val decoder = encoding.charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    // Sized up front from the file's own size, with room to type in: a StringBuilder that runs out
    // of room doubles, and doubling a buffer this size is what would not fit.
    val expected = ((size ?: 0L) / encoding.bytesPerChar + SLACK).coerceIn(SLACK, MAX_FILE_BYTES)
    val text = StringBuilder(expected.toInt())
    val pass = CharArray(PASS)
    val reader = InputStreamReader(BufferedInputStream(this, PASS), decoder)
    while (true) {
        val count = reader.read(pass)
        if (count < 0) break
        text.append(pass, 0, count)
    }
    return text
}
