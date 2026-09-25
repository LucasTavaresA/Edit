package com.lucasta.edit

import android.content.Context
import android.net.Uri
import android.system.Os
import java.io.FileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adopts an open file as a document, by copying it into a [Snapshot]. What encoding it is in and
 * whether it is text at all are decided from the [head] that has already been read, so a file that
 * is refused is refused before anything is copied. The copy stops at [size], what the file measured
 * when it was opened.
 *
 * There is no round trip to check any more, and no strict decode either. Both existed because the
 * whole file used to be decoded on the way in and re-encoded on the way out, which made every byte
 * the decoder could not name a byte that saving would destroy. A byte-addressed document copies out
 * every range nobody edited exactly as it came in, so a file with a few bad bytes in it — or in a
 * single-byte encoding nobody can detect — opens, reads, and is safe to edit somewhere else.
 */
fun readPlainDocument(context: Context, file: FileDescriptor, size: Long, head: ByteArray): Content {
    val encoding = sniffEncoding(head)
    if (notText(head, encoding)) throw NotText("not text")
    val copy = Snapshot.write(context) { out -> passes(file, size) { pass, count -> out.write(pass, 0, count) } }
    return Content.of(copy, encoding, Format.Plain)
}

/**
 * Writes the document over [uri], and answers [Collision.None] once the file holds it.
 *
 * The document is written into a snapshot of its own first and copied over the file from there, so
 * the file is only touched once there is a whole document to put in it, and the document starts
 * again from that snapshot afterwards. Unless [anyway], the file has to still hash the same as the
 * document's snapshot, or the answer is [Collision.Changed] and nothing is written; a file that
 * already holds the document is not written either. [anyway] is for a file the user has said to
 * write over whatever it holds, and for one just created to be written into.
 */
suspend fun writePlainDocument(context: Context, uri: Uri, content: Content, anyway: Boolean): Result<Collision> =
    withContext(Dispatchers.IO) {
        runCatching {
            val written = Snapshot.write(context) { content.writeTo(it) }
            var adopted = false
            try {
                // Read and write, because it does not truncate: "wt" would empty the file before
                // there was any looking at it.
                val out = checkNotNull(context.contentResolver.openFileDescriptor(uri, "rw")) { "nothing to write into" }
                out.use {
                    if (!anyway) {
                        // Through the descriptor it is written with, which holds the file it was
                        // opened on whatever the name leads to by then, so what is looked at is
                        // exactly what gets written over.
                        val found = hash(it.fileDescriptor)
                        if (!(found contentEquals content.hash)) return@runCatching Collision.Changed
                        if (found contentEquals written.hash) return@runCatching Collision.None
                    }
                    Os.ftruncate(it.fileDescriptor, 0)
                    written.writeTo(it.fileDescriptor)
                }
                content.rebase(written)
                adopted = true
                // Nothing on a phone makes another app wait for ours, so anything writing the file
                // while the copy ran leaves its bytes in it, and reading it back is what tells. Only
                // once the descriptor is closed, since a layer in between may still be passing the
                // bytes on to the file until then.
                if (readBack(context, uri) contentEquals written.hash) Collision.None else Collision.Changed
            } finally {
                if (!adopted) written.close()
            }
        }
    }

/** What [uri] holds now, read through a fresh descriptor, or null if it cannot be read at all. */
private fun readBack(context: Context, uri: Uri): ByteArray? = runCatching {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { hash(it.fileDescriptor) }
}.getOrNull()
