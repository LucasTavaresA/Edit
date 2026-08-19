package com.lucasta.edit

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adopts an open file as a document. Nothing is read but the [head] that has already been read: the
 * document is the file where it lies, so opening it is finding out what encoding it is in and
 * deciding whether it is text at all.
 *
 * There is no round trip to check any more, and no strict decode either. Both existed because the
 * whole file used to be decoded on the way in and re-encoded on the way out, which made every byte
 * the decoder could not name a byte that saving would destroy. A byte-addressed document copies out
 * every range nobody edited exactly as it came in, so a file with a few bad bytes in it — or in a
 * single-byte encoding nobody can detect — opens, reads, and is safe to edit somewhere else.
 */
fun readPlainDocument(file: Snapshot, head: ByteArray): Content {
    val encoding = sniffEncoding(head)
    if (notText(head, encoding)) throw NotText("not text")
    return Content.of(file, encoding, Format.Plain)
}

/**
 * Writes the document over [uri].
 *
 * Saving is the one place a copy is unavoidable. Writing through the provider truncates the file
 * our own pieces still point into, so the output goes to a temporary of our own first and is copied
 * over the file afterwards. That is two passes and the document's size in space while it runs, and
 * it buys back that the file is only touched once there is a whole document to put in it.
 *
 * What is written is the document and nothing else: the ranges nobody edited are copied straight
 * out of the descriptor the file was opened on, which is what that file held the instant it was
 * opened, whatever has happened to it since.
 */
suspend fun writePlainDocument(context: Context, uri: Uri, content: Content): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            val temp = File.createTempFile("save", null, context.cacheDir)
            try {
                FileOutputStream(temp).use { content.writeTo(it) }
                checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "no output stream" }
                    .use { out -> FileInputStream(temp).use { it.copyTo(out, PASS) } }
                content.rebase(context, uri, temp)
            } finally {
                // Unlinks it. If the document was rebased onto its descriptor the bytes stay alive
                // and unreachable until it is closed, which is what an anonymous file is for.
                temp.delete()
            }
        }
    }

/**
 * Starts the document again from the file it has just been written to, so that the descriptor it
 * reads through is a snapshot of that file rather than of the one that was there before — and so
 * that the change list collapses and the typed buffer is let go.
 *
 * The size is checked because a re-open is not the same act as the write: if something replaced the
 * file in between, re-opening would quietly adopt somebody else's bytes as the document. When that
 * happens, or when the file cannot be opened at all any more, the temporary is kept as the snapshot
 * instead — it is provably what was just written — and the file is left unstamped, so the next save
 * asks rather than assumes.
 */
private fun Content.rebase(context: Context, uri: Uri, temp: File) {
    val expected = length.toLong() + encoding.bom.size
    val saved = runCatching { Snapshot.open(context, uri) }.getOrNull()
    if (saved != null && saved.size == expected) {
        rebase(saved, saved.stamp)
    } else {
        saved?.close()
        rebase(Snapshot.open(temp), null)
    }
}
