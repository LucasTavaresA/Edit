package com.lucasta.edit

import android.content.Context
import android.net.Uri
import android.system.Os
import java.io.File
import java.io.FileDescriptor
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
 * Writes the document over [uri], and answers [Collision.None] once the file holds it.
 *
 * Saving is the one place a copy is unavoidable. Writing into the file truncates the file our own
 * pieces still point into, so the output goes to a temporary of our own first and is copied over
 * the file afterwards. That is two passes and the document's size in space while it runs, and it
 * buys back that the file is only touched once there is a whole document to put in it.
 *
 * What is written is the document and nothing else: the ranges nobody edited are copied straight
 * out of the descriptor the file was opened on, which is what that file held the instant it was
 * opened. Something that rewrote that file in place before the temporary was finished got its bytes
 * into it, so the descriptor is asked again once it has been read to the end, and if it was rewritten
 * the answer is [Collision.Rewritten] and the file is not touched.
 *
 * The file is looked at once the descriptor it is written through is open, which is what keeps the
 * look from going stale: a descriptor writes into the file it was opened on and not into the name,
 * so whatever replaces the file after the look cannot be written over. Unless [anyway], that file
 * has to be the one the document was last in step with, exactly as it was left, or the answer is
 * [Collision.Replaced] and it is not touched either. [anyway] is for a file the user has said to
 * write over whatever it holds, and for one just created to be written into.
 */
suspend fun writePlainDocument(context: Context, uri: Uri, content: Content, anyway: Boolean): Result<Collision> =
    withContext(Dispatchers.IO) {
        runCatching {
            val temp = File.createTempFile("save", null, context.cacheDir)
            try {
                FileOutputStream(temp).use { content.writeTo(it) }
                writeOver(context, uri, content, temp, anyway)
            } finally {
                // Unlinks it. If the document was rebased onto its descriptor the bytes stay alive
                // and unreachable until it is closed, which is what an anonymous file is for.
                temp.delete()
            }
        }
    }

/**
 * Copies [temp] over [uri] if the file is still what [writePlainDocument] says it has to be.
 *
 * The descriptor written through is asked only which file it is. A provider can hand it out through
 * a different layer from the one it reads through — the external storage one does, putting writes
 * through the storage layer every app shares and reads straight off the disk — and then it reports
 * the same inode under a different device, and once written to, its own time for the write rather
 * than the one the file ends up with. How the file stands is asked of a fresh read instead, in the
 * same terms every other look at it was taken in.
 */
private fun writeOver(context: Context, uri: Uri, content: Content, temp: File, anyway: Boolean): Collision {
    // Read and write, because it does not truncate: "wt" would empty the file before there was any
    // looking at it.
    val out = checkNotNull(context.contentResolver.openFileDescriptor(uri, "rw")) { "nothing to write into" }
    val into = out.use {
        val into = Os.fstat(out.fileDescriptor).st_ino
        if (content.rewritten()) return Collision.Rewritten
        if (!anyway && (into != content.stamp?.inode || Snapshot.stamp(context, uri) != content.stamp)) {
            return Collision.Replaced
        }
        copy(temp, out.fileDescriptor)
        into
    }
    // Closed before anything looks at the result: a layer in between may still be passing the bytes
    // on to the file until then.
    return content.rebase(context, uri, temp, into)
}

/** Puts [temp] into the file behind [out] in place of whatever it held. */
private fun copy(temp: File, out: FileDescriptor) {
    Os.ftruncate(out, 0)
    FileInputStream(temp).use { input ->
        val pass = ByteArray(PASS)
        while (true) {
            val count = input.read(pass)
            if (count < 0) break
            var done = 0
            while (done < count) done += Os.write(out, pass, done, count - done)
        }
    }
}

/**
 * Starts the document again from the file it has just been written to, so that the descriptor it
 * reads through is a snapshot of that file rather than of the one that was there before — and so
 * that the change list collapses and the typed buffer is let go.
 *
 * Only if [uri] still leads to the file that was written, [into], and at the size it was written to.
 * The write went into the file the descriptor was opened on, wherever the name led by the end:
 * something that replaced the file while the copy ran leaves ours where nobody can reach it and
 * theirs under the name, and re-opening would quietly adopt theirs as the document, whatever its
 * size. Anything else keeps the temporary as the snapshot instead — it is provably what was just
 * written — leaves the file unstamped so no later save assumes, and answers [Collision.Replaced] so
 * that this one asks right away rather than saying it saved.
 */
private fun Content.rebase(context: Context, uri: Uri, temp: File, into: Long): Collision {
    val saved = runCatching { Snapshot.open(context, uri) }.getOrNull()
    if (saved != null && saved.stamp.inode == into && saved.size == temp.length()) {
        rebase(saved, saved.stamp)
        return Collision.None
    }
    saved?.close()
    rebase(Snapshot.open(temp), null)
    return Collision.Replaced
}
