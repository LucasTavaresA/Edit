package com.lucasta.edit

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds
import android.graphics.Color as ArgbColor

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.auto(ArgbColor.TRANSPARENT, ArgbColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        // A rebuilt activity is handed the intent it was first started with, which no longer says
        // what is open. The file kept for the next start does, and handed nothing, that is opened.
        val opened = when (intent?.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
            else -> null
        }.takeIf { savedInstanceState == null }
        setContent {
            // The bar icons are set once, against the mode in force at the time, and the activity
            // is no longer rebuilt when that mode changes — so they are set again when it does.
            val dark = isSystemInDarkTheme()
            LaunchedEffect(dark) { enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars) }
            MaterialTheme(colorScheme = systemColorScheme(dark)) {
                Surface(Modifier.fillMaxSize()) { Editor(opened) }
            }
        }
    }
}

@Composable
private fun systemColorScheme(dark: Boolean = isSystemInDarkTheme()): ColorScheme {
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        val dynamic = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dynamic.accentedLikeTheSystem(dark)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    // The rule under the toolbar draws itself with outlineVariant, and Material's is a grey faint
    // enough to be lost against either ground. It wants the plain opposite of what it sits on.
    return if (dark) scheme.unlit() else scheme.copy(outlineVariant = Color.Black)
}

/**
 * The primary family taken off the tonal ramp the phone actually publishes. There are two sets of
 * system colours: the `system_accent1_*` ramp, and the colour roles API 34 added on top of it —
 * `system_primary_dark` and the rest. Compose prefers the roles where they exist, but One UI fills
 * in only the ramp and leaves the roles at the AOSP default, so the phone's accent is published and
 * not looked at. Reading the ramp and mapping the tones the way Material does puts it back.
 *
 * Only the primary family, since it is the accent the toolbar is drawn in. The neutrals have the
 * same split, but nothing here is drawn in them that would show it.
 */
@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun ColorScheme.accentedLikeTheSystem(dark: Boolean): ColorScheme {
    val resources = LocalContext.current.resources
    fun tone(id: Int) = Color(resources.getColor(id, null))
    return if (dark) copy(
        primary = tone(android.R.color.system_accent1_200),
        onPrimary = tone(android.R.color.system_accent1_800),
        primaryContainer = tone(android.R.color.system_accent1_700),
        onPrimaryContainer = tone(android.R.color.system_accent1_100),
        inversePrimary = tone(android.R.color.system_accent1_600),
    ) else copy(
        primary = tone(android.R.color.system_accent1_600),
        onPrimary = tone(android.R.color.system_accent1_0),
        primaryContainer = tone(android.R.color.system_accent1_100),
        onPrimaryContainer = tone(android.R.color.system_accent1_900),
        inversePrimary = tone(android.R.color.system_accent1_200),
    )
}

/**
 * The same scheme with every ground taken to pure black. On an OLED panel a black pixel is an
 * unlit one, where Material's dark greys light the whole screen up. What is drawn on the grounds
 * keeps its colours, so contrast goes up. [surfaceTint] goes black too, since elevation would use
 * it to lift a surface back off the black. [outlineVariant] is the one foreground that moves, for
 * the reason given in [systemColorScheme].
 */
private fun ColorScheme.unlit(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color.Black,
    surfaceTint = Color.Black,
    surfaceBright = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainerHigh = Color.Black,
    surfaceContainerHighest = Color.Black,
    outlineVariant = Color.White,
)

/** What a document that has never been saved is called, and what Save as offers for it. */
private const val UNTITLED = "untitled.txt"

/**
 * What the bar says while the document has no file behind it: at start, and after New. Save stays
 * off until Save as has said where it goes.
 */
private const val NO_FILE = "no file"

/**
 * How long something said keeps the bar that names the file on screen while the keyboard is up,
 * where it otherwise goes with the row above it to give the text the room.
 */
private enum class Hold {
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

/** How long [Hold.Briefly] holds, and how long [Hold.UntilSeen] goes on holding once the user is back. */
private val HOLD_TIME = 2.seconds

/**
 * What the bar that names the file is saying, and whether it is still holding the bar over the
 * keyboard to say it. A new one for every message, the same message again included, so that each
 * holds for its own time and ending one hold can never end the next.
 */
private class Notice(val text: String, val hold: Hold) {
    var holding by mutableStateOf(hold != Hold.Never)
}

/**
 * Opening a file, New and saving take turns: one at a time, in the order they were asked for, and
 * nothing is refused meanwhile. Two saves at once would each find the other's write in the file and
 * ask about it as somebody else's, and a save ends by moving the base and naming the file it wrote,
 * which an open or New in between would already have replaced.
 *
 * The turns are the process's rather than one screen's, since another app handing a file over can
 * start a second copy of Edit beside the first.
 */
private val turns = Mutex()

/**
 * Where the file kept for the next start is written down. Only its link and its name are, so what
 * comes back is the file as it stands by then, and edits not saved before Edit stopped are not
 * part of it.
 */
private const val LAST = "last"

/**
 * The file that was open when Edit last stopped, and what it was called. The name is kept because
 * it cannot be asked for once the file's access has gone, which is exactly when it needs saying.
 */
private class LastFile(val uri: Uri, val name: String)

/** The file that was open when Edit last stopped, or null if none was or it could not be kept. */
private fun lastFile(context: Context): LastFile? {
    val last = context.getSharedPreferences(LAST, Context.MODE_PRIVATE)
    val uri = last.getString("file", null) ?: return null
    return LastFile(Uri.parse(uri), last.getString("name", null) ?: uri)
}

/**
 * Keeps [uri], called [name], as the file the next start opens, or nothing when there is no file or
 * its access cannot outlive the process. Open and Save as hand over access that can be kept;
 * another app handing a file over usually does not, and then the next start opens nothing rather
 * than a file older than the last one. The only grant held is the kept file's, so the one before
 * is let go.
 */
private fun keepLastFile(context: Context, uri: Uri?, name: String) {
    val resolver = context.contentResolver
    val kept = uri?.takeIf { resolver.keepAccess(it) }
    for (held in resolver.persistedUriPermissions) {
        if (held.uri == kept) continue
        val read = if (held.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0
        val write = if (held.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        // One that will not let go is only one more grant held, and never worth stopping for.
        runCatching { resolver.releasePersistableUriPermission(held.uri, read or write) }
    }
    context.getSharedPreferences(LAST, Context.MODE_PRIVATE).edit()
        .putString("file", kept?.toString())
        .putString("name", kept?.let { name })
        .apply()
}

/**
 * Whether the access kept to [uri] is still held. A provider lets go of it once the file is
 * deleted, and after that the file cannot be opened or even named.
 */
private fun ContentResolver.holdsAccess(uri: Uri): Boolean = persistedUriPermissions.any { it.uri == uri }

/**
 * Keeps access to [uri] past the process, writing included where that was given, and says whether
 * it could. Asking for more than was given is refused outright, hence reading alone after.
 */
private fun ContentResolver.keepAccess(uri: Uri): Boolean {
    val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    return listOf(read or write, read).any { runCatching { takePersistableUriPermission(uri, it) }.isSuccess }
}

@Composable
private fun Editor(opened: Uri? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val document = remember { DocumentState(context.cacheDir) }
    var file by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf(UNTITLED) }
    var notice by remember { mutableStateOf(Notice(NO_FILE, Hold.Never)) }
    var touches by remember { mutableIntStateOf(0) }
    var asking by remember { mutableStateOf(false) }

    /**
     * Whether a save is waiting for its turn. Pressing Save again joins it rather than queueing
     * another behind it: a save writes the document as it stands when its turn comes, so the one
     * waiting already takes in everything done up to then.
     */
    var saveWaiting by remember { mutableStateOf(false) }

    // The document holds its copy of the file open for as long as it is the open document, so this
    // is where it lets go. A save still writing from it holds it too, and whichever of the two lets
    // go last closes it.
    DisposableEffect(document) { onDispose { document.close() } }

    /** Puts [message] in the bar that names the file, held over the keyboard as [hold] says. */
    fun say(message: String, hold: Hold = Hold.Never) {
        notice = Notice(message, hold)
    }

    LaunchedEffect(notice) {
        val said = notice
        if (!said.holding) return@LaunchedEffect
        if (said.hold == Hold.UntilSeen) {
            // The next press anywhere on the screen, or the next thing the keyboard does to the
            // text — which reaches the field without passing through the screen's touches at all.
            snapshotFlow { Triple(touches, document.window.text, document.window.selection) }
                .drop(1)
                .first()
        }
        delay(HOLD_TIME)
        said.holding = false
    }

    /** Runs [task] once every file operation asked for before it has finished. */
    fun inTurn(task: suspend () -> Unit) {
        scope.launch { turns.withLock { task() } }
    }

    suspend fun load(uri: Uri) {
        heap("before")
        // Opening copies the file and indexes it for chunk boundaries, two passes over the whole
        // thing: seconds for a file of a gigabyte. Say so rather than look like nothing happened.
        say("opening ${displayName(context, uri)}")
        timed("open") {
            openDocument(context, uri)
                .onSuccess {
                    timed("setText") { document.load(it, whole = it.format == Format.Encrypted) }
                    frameAfterNext("layout")
                    heap("after")
                    file = uri
                    name = displayName(context, uri)
                    say(name, Hold.Briefly)
                }
                .onFailure { say("could not open: ${it.message}", Hold.UntilSeen) }
        }
    }

    /**
     * Writes the document into [target]. Unless [anyway], only while the file still holds what
     * the document was last in step with — see [saveDocument].
     */
    suspend fun write(target: Uri, anyway: Boolean) {
        say("saving ${displayName(context, target)}")
        document.save { saveDocument(context, target, it, anyway) }
            .onSuccess { found ->
                when (found) {
                    Collision.None -> {
                        file = target
                        name = displayName(context, target)
                        say("saved $name", Hold.Briefly)
                    }
                    // Something else wrote the file, found before anything was written or arriving
                    // while it was. The document is still what was to be saved, so the question is
                    // whose version the file keeps — and after Save as, the file in question is the
                    // one it was saved as.
                    Collision.Changed -> {
                        file = target
                        name = displayName(context, target)
                        say(name)
                        asking = true
                    }
                }
            }
            .onFailure { say("could not save: ${it.message}", Hold.UntilSeen) }
    }

    /**
     * Back to an empty document with no file behind it. Nothing is created on disk: a new file is
     * one that has never been saved, and where it goes is asked for by Save as when there is
     * something to put in it.
     */
    fun new() {
        inTurn {
            document.load(Content.empty(context.cacheDir))
            document.focus(0)
            file = null
            name = UNTITLED
            say(NO_FILE)
        }
    }

    // A file handed over is opened, and otherwise the one open when Edit last stopped. Once that has
    // had its turn, the file kept follows whichever is open, so one that could not be opened again
    // is forgotten rather than tried at every start, and New leaves nothing to come back to.
    LaunchedEffect(opened) {
        turns.withLock {
            val last = if (opened == null) lastFile(context) else null
            when {
                opened != null -> load(opened)
                last == null -> {}
                !context.contentResolver.holdsAccess(last.uri) ->
                    say("could not open ${last.name}: it is gone", Hold.UntilSeen)
                else -> load(last.uri)
            }
        }
        snapshotFlow { file to name }.collect { (uri, called) -> keepLastFile(context, uri, called) }
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        picked ?: return@rememberLauncherForActivityResult
        inTurn { load(picked) }
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { created ->
        created ?: return@rememberLauncherForActivityResult
        inTurn { write(created, anyway = true) }
    }

    /**
     * Which file, and what goes into it, are both taken when the save's turn comes rather than when
     * Save was pressed: a file opened in between is the document by then, and writing it into the
     * file it replaced would be worse than saving nothing.
     */
    fun save() {
        if (saveWaiting) return
        saveWaiting = true
        inTurn {
            saveWaiting = false
            file?.let { write(it, anyway = false) }
        }
    }

    val safe = WindowInsets.safeDrawing
    val pixel = with(LocalDensity.current) { 1.toDp() }
    val typing = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // The column pays the top and the sides, and the bottom row pays the bottom, keyboard included.
    // `safeDrawing` counts the IME, and the file row takes its height from what is left of the
    // screen, so padding the whole column with it would shrink the row the instant the keyboard
    // came up.
    Column(
        Modifier
            .fillMaxSize()
            // Every press on the screen, seen on its way down to whatever it lands on and taken
            // from nobody. A warning waits for one before it starts to go.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) touches++
                    }
                }
            }
            .windowInsetsPadding(safe.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
    ) {
        // What a file is — which one is open, opening one, saving one — is not what anyone needs
        // mid-sentence, so the row, the name and the rule under them go while the keyboard is up.
        // Asking the IME inset rather than whether a field holds focus makes them follow the
        // keyboard itself: dismissing it with Back brings them back without touching the cursor.
        //
        // The edge is measured here rather than asked of the configuration, so the padding above
        // has already taken its share in landscape and beside a cutout; the shorter one, so the row
        // is not a quarter of the screen in landscape.
        if (!typing) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().height(minOf(maxWidth, maxHeight) / ROW_SHARE)) {
                    Tool("New") { new() }
                    Tool("Open") { open.launch(arrayOf("*/*")) }
                    Tool("Save as") { saveAs.launch(name) }
                }
            }
        }
        // The name and its rule stay over the keyboard for as long as [Hold] says. They push the
        // text down rather than cover it, so the cursor can never be under them.
        if (!typing || notice.holding) {
            // A pixel above and below and nothing else: it is something to read rather than a
            // control, so it is as tall as its sentence and aligned with the text below.
            Text(notice.text, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = pixel))
            // One physical pixel, not one dp: a dense panel puts nearly three pixels in a dp, and at
            // that width the rule reads as a bar across the screen rather than as a line under the
            // toolbar.
            HorizontalDivider(thickness = pixel)
        }
        DocumentEditor(
            state = document,
            modifier = Modifier.fillMaxWidth().weight(1f),
            margin = 12.dp,
            refuse = { say(it, Hold.UntilSeen) },
        )
        HorizontalDivider(thickness = pixel)
        // What these undo is the sentence being typed, and saving ends it, so this row rides on top
        // of the keyboard and pays the bottom inset.
        //
        // Material pads every button out to a 48dp touch target, which in landscape with the
        // keyboard up comes straight out of the few lines of text left. Turning it off lets
        // [OP_HEIGHT] be the whole of the row.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(safe.only(WindowInsetsSides.Bottom)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Op("All") { document.selectAll() }
                Op("Undo", enabled = document.canUndo) { scope.launch { document.undo() } }
                Op("Redo", enabled = document.canRedo) { scope.launch { document.redo() } }
                Op("Save", enabled = file != null) { save() }
            }
        }
    }

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("$name changed") },
            text = {
                Text(
                    "Something else changed it since it was opened or last saved. This document is " +
                        "still what the file held then, with your edits in it. Saving puts that over " +
                        "whatever is there now."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    inTurn { file?.let { write(it, anyway = true) } }
                }) { Text("Overwrite") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        asking = false
                        saveAs.launch(name)
                    }) { Text("Save as") }
                    TextButton(onClick = { asking = false }) { Text("Cancel") }
                }
            },
        )
    }
}

/**
 * How much of the shorter edge the file row is tall: a seventh, about 58dp on a phone and over the
 * 48dp the platform asks for. A fraction rather than a share per button, so the row's height does
 * not depend on how many buttons it holds.
 */
private const val ROW_SHARE = 7

/**
 * One button on the file row: an equal share of the width, however many there are. A weight is a
 * fixed constraint, so Material's minimum button width never applies.
 *
 * Squared off rather than Material's pill, since the ripple is cut to the shape and a round ripple
 * inside a rectangular target reads as a smaller target than it is.
 *
 * Two lines are allowed rather than clipped, so a system font turned right up wraps and stays
 * readable.
 */
@Composable
private fun RowScope.Tool(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f).fillMaxHeight(),
        shape = RectangleShape,
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * How tall the row over the keyboard is. Under the 48dp Material asks for, because in landscape the
 * keyboard leaves about three lines of text and the row's height comes out of them; 36dp is still a
 * comfortable target for a thumb, and the buttons are wide, so a tap near one lands on it.
 */
private val OP_HEIGHT = 36.dp

/**
 * One button on the row that stays, at the bottom over the keyboard. These are ordinary text
 * buttons rather than the squares of the file row: the row is on screen while typing, so every
 * pixel of height it takes is taken from the text.
 */
@Composable
private fun Op(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(OP_HEIGHT),
        shape = RectangleShape,
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
}
