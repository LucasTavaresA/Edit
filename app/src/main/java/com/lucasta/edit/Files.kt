package com.lucasta.edit

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** What a document that has never been saved is called, and what Save as offers for it. */
const val UNTITLED = "untitled.txt"

/**
 * What the bar says while the document has no file behind it: at start, and after New. Save stays
 * off until Save as has said where it goes.
 */
private const val NO_FILE = "no file"

/**
 * How long something said keeps the bar that names the file on screen while the keyboard is up,
 * where it otherwise goes with the row above it to give the text the room.
 */
enum class Hold {
    /** Not at all: a file opening or saving while it runs, and New. They wait for the keyboard to go. */
    Never,

    /** [HOLD_TIME] and no longer: a file just opened or just saved, long enough to catch which. */
    Briefly,

    /**
     * Until [HOLD_TIME] after the user next presses anywhere on the screen or changes the text,
     * which is what a warning gets. Waiting for the user rather than for a clock is what stops a
     * warning coming and going while nobody was looking, and the time after is what stops the touch
     * that noticed it from taking it away before it is read.
     */
    UntilSeen,
}

/**
 * What the bar that names the file is saying, and whether it is still holding the bar over the
 * keyboard to say it. A new one for every message, the same message again included, so that each
 * holds for its own time and ending one hold can never end the next.
 *
 * [about] is the file in the list it is about, or null for one that is not in it: a file being
 * opened, or one that could not be.
 */
class Notice(val text: String, val hold: Hold, val about: OpenFile?) {
    var holding by mutableStateOf(hold != Hold.Never)
}

/**
 * One file in the list, with everything that is its own. Unopened, with no [document], until the
 * user goes to it, which a file brought back by a start or closed ever is.
 */
class OpenFile(uri: Uri?, name: String, document: DocumentState? = null, said: String = name) {
    /** The file behind it, or null for one that has never been saved. */
    var uri by mutableStateOf(uri)

    var name by mutableStateOf(name)

    /** Once opened, it stays opened, private copy included, until it is closed or Edit stops. */
    var document by mutableStateOf(document)

    /** The last thing said about it, which is what the bar says when it is gone to. */
    var notice by mutableStateOf(Notice(said, Hold.Never, this))

    /**
     * Whether a save of it is waiting for its turn. Pressing Save again joins it rather than
     * queueing another behind it: a save writes the document as it stands when its turn comes, so
     * the one waiting already takes in everything done up to then.
     */
    var saveWaiting = false

    /** Whether a save found the file changed, and whose version wins is waiting on the user. */
    var asking by mutableStateOf(false)

    /** Whether closing it with edits not saved is waiting on the user: save, close or nevermind. */
    var closing by mutableStateOf(false)

    /** Whether that closing is for taking it out of the list, which it leaves once closed. */
    var forgetting by mutableStateOf(false)

    /**
     * Whether its access outlasts the process, so that unopened it can still be written down for
     * the next start. Not so for one another app handed over for the process alone, which closed
     * stays in the list only until Edit stops.
     */
    var lasting = false

    /** New and never touched: leaving the list loses nothing, not even something to undo. */
    val untouched: Boolean
        get() = uri == null && document.let { it == null || !it.canUndo && !it.canRedo }
}

/**
 * Opening a file, New and saving take turns: one at a time, in the order they were asked for, and
 * nothing is refused meanwhile. Two saves at once would each find the other's write in the file and
 * ask about it as somebody else's. The turns are the process's rather than one file's, so opening a
 * very large file makes a save in another wait.
 */
private val turns = Mutex()

/**
 * Where the list is written down for the next start: each file's link and name, in order, and
 * whether the first had focus. Never what is in them, so what comes back is each file as it stands
 * by then, and edits not saved before Edit stopped are not part of it.
 */
private const val KEPT = "files"

/**
 * Every file open in the process, as a stack: the one on screen first, then the rest from the one
 * most recently opened or gone to downwards. Never empty, and the first is always opened. The one
 * list there is, whatever happens to the activity, so another app handing a file over joins it.
 */
class Files private constructor(private val context: Context) {

    private val scope = MainScope()

    val list = mutableStateListOf(untitled())

    val current: OpenFile get() = list[0]

    /** What the bar says: the file on screen's last notice, or one about a file behind it. */
    var bar by mutableStateOf(list[0].notice)
        private set

    private var started = false

    /**
     * What came with the activity, or with an intent handed to it later: [handed] is a file another
     * app handed over, or null. The first time, the list kept at the last start comes back, and
     * only the file handed over, or else the one that had focus, is opened.
     */
    fun arrive(handed: Uri?) {
        if (started) {
            handed?.let { open(it) }
            return
        }
        started = true
        val focused = restore()
        inTurn {
            when {
                handed != null -> reach(handed)
                focused && list.size > 1 -> reach(list[1])
            }
            trim()
            // Only once those have had their turn: a file found gone has left the list by then.
            scope.launch {
                snapshotFlow { list.map { Triple(it.uri, it.name, it.document == null) } }.collect { keep() }
            }
        }
    }

    /** Puts [message] in the bar, held over the keyboard as [hold] says, and as [about]'s last word. */
    fun say(about: OpenFile?, message: String, hold: Hold = Hold.Never) {
        // One about a file behind the one on screen gets the bar briefly at least, and then hands
        // it back — see [settled] — so the bar never goes on naming a file that is not on screen.
        val behind = about != null && about !== current
        val notice = Notice(message, if (behind && hold == Hold.Never) Hold.Briefly else hold, about)
        about?.notice = notice
        bar = notice
    }

    /**
     * [notice] has held as long as it was going to. Unless it was about the file on screen, the bar
     * goes back to that file's own, so it never goes on naming another file or none, or hiding the
     * mark for edits not saved. "Opening", about no file and never held, stays: it is always
     * followed by what came of it.
     */
    fun settled(notice: Notice) {
        notice.holding = false
        if (bar === notice && notice.about !== current && notice.hold != Hold.Never) bar = current.notice
    }

    /** Opens [uri], or goes to it if it is open already. */
    fun open(uri: Uri) = inTurn { reach(uri) }

    /** Goes to [file], which has to wait its turn only if it has to be opened first. */
    fun goTo(file: OpenFile) {
        if (file.document != null) top(file) else inTurn { reach(file) }
    }

    /**
     * A new empty document with no file behind it. Nothing is created on disk: a new file is one
     * that has never been saved, and where it goes is asked for by Save as when there is something
     * to put in it. An untouched one on screen already is that document.
     */
    fun new() = inTurn {
        val file = current.takeIf { it.untouched } ?: untitled().also { top(it) }
        say(file, NO_FILE)
    }

    /**
     * Which file, and what goes into it, are both taken when the save's turn comes rather than when
     * Save was pressed: a Save as in between has moved it to another file by then.
     */
    fun save(file: OpenFile) {
        if (file.saveWaiting) return
        file.saveWaiting = true
        inTurn {
            file.saveWaiting = false
            file.uri?.let { write(file, it, anyway = false) }
        }
    }

    /** Saves [file] into [target], and then closes it if [close] and the save left nothing unsaved. */
    fun saveAs(file: OpenFile, target: Uri, close: Boolean = false) = inTurn {
        write(file, target, anyway = true)
        if (close) closeIfSaved(file)
    }

    /**
     * The close button on [file]'s row. Closing loses whatever was not saved, so a file with edits
     * not saved asks first, through [OpenFile.closing]; one without closes straight away.
     */
    fun close(file: OpenFile) {
        file.forgetting = false
        if (file.document?.unsaved == true) file.closing = true else discard(file)
    }

    /**
     * Takes [file] out of the list, the file itself untouched; its access is let go with it. One
     * opened is closed first, asking as [close] does if it has edits not saved.
     */
    fun forget(file: OpenFile) {
        file.forgetting = true
        if (file.document?.unsaved == true) file.closing = true else discard(file)
    }

    /** The answer Nevermind: [file] stays as it is, opened and in the list. */
    fun keepOpen(file: OpenFile) {
        file.closing = false
        file.forgetting = false
    }

    /** Closes [file] whatever it holds, the answer Close. */
    fun discard(file: OpenFile) = inTurn { unload(file) }

    /**
     * Saves [file] where it already lives and closes it, the answer Save for a file that has one. A
     * save that finds the file changed asks about that instead and leaves it open, as does one
     * that fails, and so does typing that lands while it runs.
     */
    fun saveAndClose(file: OpenFile) = inTurn {
        file.uri?.let { write(file, it, anyway = false) }
        closeIfSaved(file)
    }

    private suspend fun closeIfSaved(file: OpenFile) {
        if (file.document?.unsaved == false && !file.asking) unload(file)
    }

    /** The answer to [OpenFile.asking]: the document goes over whatever the file holds now. */
    fun overwrite(file: OpenFile) = inTurn { file.uri?.let { write(file, it, anyway = true) } }

    /** Runs [task] once every file operation asked for before it has finished. */
    private fun inTurn(task: suspend () -> Unit) {
        scope.launch { turns.withLock { task() } }
    }

    private fun untitled() = OpenFile(null, UNTITLED, DocumentState(context.cacheDir), NO_FILE)

    /**
     * Puts [file], which has to be opened, on screen and on top. An untouched new one it takes the
     * place of leaves the list rather than piling up under it.
     */
    private fun top(file: OpenFile) {
        val left = current
        if (left === file) return
        list.remove(file)
        if (left.untouched) list.remove(left)
        list.add(0, file)
        bar = file.notice
    }

    /**
     * Lets [file]'s document go, private copy included, and leaves it in the list unopened where it
     * was, so the list stays the files most recently gone to. A new one has nothing to come back
     * as and leaves the list. The file on screen gives way to the next in the list that opens, and
     * to a new one if none does. Run in turn, so no save of it is running and none waits behind.
     * One being forgotten leaves the list too, opened or not.
     */
    private suspend fun unload(file: OpenFile) {
        if (file !in list) return
        if (file.document == null) {
            if (file.forgetting) list.remove(file)
            if (bar.about === file) bar = current.notice
            return
        }
        if (file === current) {
            for (next in list.drop(1)) {
                if (current !== file) break
                reach(next)
            }
            if (current === file) top(untitled())
        }
        if (file.uri == null || file.forgetting) list.remove(file)
        if (bar.about === file) bar = current.notice
        val document = file.document ?: return
        file.document = null
        // Not while the screen may still be drawing it: the switch away reaches the editor at the
        // next frame, so the document goes the frame after.
        awaitFrame()
        awaitFrame()
        document.close()
    }

    /**
     * Takes every unopened file that is gone out of the list, without a word: done at a start, with
     * the list not yet on screen, so no row goes from under a finger reaching for it. One deleted
     * while Edit runs is found when the user goes to it, and so is one a provider still answers for.
     */
    private suspend fun trim() {
        for (file in list.toList()) {
            val uri = file.uri ?: continue
            if (file.document == null && context.seemsGone(uri)) list.remove(file)
        }
    }

    /** Goes to the file at [uri], opening it unless it is in the list already. */
    private suspend fun reach(uri: Uri) {
        list.firstOrNull { it.uri == uri }?.let { return reach(it) }
        val name = displayName(context, uri)
        val document = read(uri, name) ?: return
        val file = OpenFile(uri, name, document)
        top(file)
        say(file, file.name, Hold.Briefly)
    }

    /**
     * Goes to [file], opening it as it stands now if it has not been. A file that is gone leaves the
     * list, the one way a file with a link ever does besides [trim] and [forget]; one that fails to open for
     * any other reason stays, unopened, and the screen stays where it was.
     */
    private suspend fun reach(file: OpenFile) {
        if (file !in list) return
        if (file.document == null) {
            val uri = checkNotNull(file.uri) { "only a file with a link is ever unopened" }
            if (context.isGone(uri)) {
                list.remove(file)
                say(null, "could not open ${file.name}: it is gone", Hold.UntilSeen)
                return
            }
            file.document = read(uri, file.name) ?: return
            file.name = displayName(context, uri)
            file.notice = Notice(file.name, Hold.Briefly, file)
        }
        top(file)
    }

    /** The file at [uri], called [name], read into a document, or null after saying why it is not. */
    private suspend fun read(uri: Uri, name: String): DocumentState? {
        heap("before")
        // Opening copies the file and indexes it for chunk boundaries, two passes over the whole
        // thing: seconds for a file of a gigabyte. Say so rather than look like nothing happened.
        say(null, "opening $name")
        return timed("open") {
            openDocument(context, uri).fold(
                onSuccess = { content ->
                    DocumentState(context.cacheDir).also {
                        timed("setText") { it.load(content, whole = content.format == Format.Encrypted) }
                        frameAfterNext("layout")
                        heap("after")
                    }
                },
                onFailure = {
                    say(null, "could not open $name: ${it.message}", Hold.UntilSeen)
                    null
                },
            )
        }
    }

    /**
     * Writes [file]'s document into [target]. Unless [anyway], only while the file still holds what
     * the document was last in step with — see [saveDocument].
     */
    private suspend fun write(file: OpenFile, target: Uri, anyway: Boolean) {
        val document = file.document ?: return
        val called = displayName(context, target)
        say(file, "saving $called")
        document.save { saveDocument(context, target, it, anyway) }
            .onSuccess { found ->
                // Whatever was found, the file in question is now the one it was saved as.
                file.uri = target
                file.name = called
                when (found) {
                    Collision.None -> say(file, "saved $called", Hold.Briefly)
                    // Something else wrote the file, found before anything was written or arriving
                    // while it was. The document is still what was to be saved, so the question is
                    // whose version the file keeps.
                    Collision.Changed -> {
                        say(file, called)
                        file.asking = true
                    }
                }
            }
            .onFailure { say(file, "could not save $called: ${it.message}", Hold.UntilSeen) }
    }

    /**
     * Puts the list kept at the last start under the new document on screen, every file unopened,
     * and says whether the first of them had focus.
     */
    private fun restore(): Boolean {
        val kept = context.getSharedPreferences(KEPT, Context.MODE_PRIVATE)
        val files = runCatching { JSONArray(kept.getString("files", null) ?: "[]") }.getOrElse { JSONArray() }
        for (i in 0 until files.length()) {
            val file = files.optJSONObject(i) ?: continue
            val uri = file.optString("uri").takeIf { it.isNotEmpty() } ?: continue
            list.add(OpenFile(Uri.parse(uri), file.optString("name", uri)).also { it.lasting = true })
        }
        return kept.getBoolean("focused", false)
    }

    /**
     * Writes the list down for the next start, keeping access to every file in it, writing included
     * where that was given. A file whose access ends with the process, as another app handing one
     * over usually gives, is left out, and so is a new one, which has nothing to come back as. One
     * unopened whose access was kept is written down whatever its access now, and its access having
     * gone is said when the user goes to it. Every grant held for a file not in the list is let go.
     */
    private fun keep() {
        val resolver = context.contentResolver
        val kept = list.filter { file ->
            val uri = file.uri ?: return@filter false
            file.lasting = file.document == null && file.lasting || resolver.keepAccess(uri)
            file.lasting
        }
        val links = kept.mapTo(HashSet()) { it.uri }
        for (held in resolver.persistedUriPermissions) {
            if (held.uri in links) continue
            val read = if (held.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0
            val write = if (held.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
            // One that will not let go is only one more grant held, and never worth stopping for.
            runCatching { resolver.releasePersistableUriPermission(held.uri, read or write) }
        }
        val files = JSONArray()
        for (file in kept) files.put(JSONObject().put("uri", file.uri.toString()).put("name", file.name))
        context.getSharedPreferences(KEPT, Context.MODE_PRIVATE).edit()
            .putString("files", files.toString())
            .putBoolean("focused", kept.firstOrNull() === current)
            .apply()
    }

    companion object {
        private var made: Files? = null

        /** The process's list, made the first time anything asks for it. Main thread only. */
        fun of(context: Context): Files = made ?: Files(context.applicationContext).also { made = it }
    }
}

/**
 * Whether the file at [uri] is gone: its access let go, as a provider does once it deletes the
 * file, or nothing there to open, as when something deleted it behind the provider's back, which
 * leaves the access held. Not found is taken at its word, so a file on storage that is not mounted
 * counts as gone too.
 */
private suspend fun Context.isGone(uri: Uri): Boolean = !holdsAccess(uri) || withContext(Dispatchers.IO) {
    try {
        contentResolver.openFileDescriptor(uri, "r")?.close()
        false
    } catch (e: FileNotFoundException) {
        true
    } catch (e: Exception) {
        // Anything else is a failure to open, which is said when the user goes to it.
        false
    }
}

/**
 * [isGone] without opening the file, for every file in the list at once: a provider fetching from
 * elsewhere would answer an open by fetching the file, and a start reads nothing. It asks for the
 * file's name instead, which a documents provider answers with nothing for a file it cannot find.
 * One that still answers for a file deleted behind its back, as MediaStore can, says it is there.
 */
private suspend fun Context.seemsGone(uri: Uri): Boolean = !holdsAccess(uri) || withContext(Dispatchers.IO) {
    try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { !it.moveToFirst() } ?: true
    } catch (e: FileNotFoundException) {
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * Whether access to [uri] is still held: kept past the process, or given for the process alone to
 * a file closed since. A provider lets go of it once the file is deleted, and after that the file
 * cannot be opened or even named.
 */
private fun Context.holdsAccess(uri: Uri): Boolean =
    contentResolver.persistedUriPermissions.any { it.uri == uri } ||
        checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Keeps access to [uri] past the process, writing included where that was given, and says whether
 * it could. Asking for more than was given is refused outright, hence reading alone after.
 */
private fun ContentResolver.keepAccess(uri: Uri): Boolean {
    val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    return listOf(read or write, read).any { runCatching { takePersistableUriPermission(uri, it) }.isSuccess }
}
