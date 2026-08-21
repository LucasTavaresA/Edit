package com.lucasta.edit

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import android.graphics.Color as ArgbColor

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.auto(ArgbColor.TRANSPARENT, ArgbColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        val opened = when (intent?.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
            else -> null
        }
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
 * The primary family taken off the tonal ramp the phone actually publishes, because the scheme it
 * came from has the wrong accent on One UI. There are two sets of system colours: the old
 * `system_accent1_*` ramp, and the colour roles API 34 added on top of it — `system_primary_dark`
 * and the rest. Compose prefers the roles where they exist, but One UI fills in only the ramp and
 * leaves the roles at the AOSP default, so the phone's accent is published and simply not looked
 * at. Reading the ramp and mapping the tones the way Material does puts it back.
 *
 * Only the primary family is corrected: it is the accent the toolbar is drawn in, and the rest of
 * the app is text on a ground. The neutrals have the same split and are left alone for the same
 * reason — nothing here is drawn in them that would show the difference.
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
 * The same scheme with every ground it paints taken to pure black. On an OLED panel a black
 * pixel is an unlit pixel: dark mode costs nothing but the text drawn on it, and the dark greys
 * Material would otherwise use light the whole screen up. Only the grounds move — what is drawn on
 * them keeps the colours the scheme chose, so contrast goes up rather than down. [surfaceTint] goes
 * black as well, since that is the colour elevation would use to lift a surface back off the black.
 * [outlineVariant] is the one foreground that moves, for the reason given where it is set light.
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
 * A document with no file behind it, which is what the app starts with and what New goes back to.
 * There is nowhere to write it, so Save does nothing at all until Save as has been through and said
 * where it goes; [UNTITLED] is only the name that dialog is offered, not a file that exists.
 */
private const val NO_FILE = "no file"

@Composable
private fun Editor(opened: Uri? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val document = remember { DocumentState() }
    var file by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf(UNTITLED) }
    var status by remember { mutableStateOf(NO_FILE) }
    var asking by remember { mutableStateOf(false) }

    // The document holds a descriptor open on the file it was opened on, for as long as it is the
    // open document. Nothing else is holding it, so this is where it is let go.
    DisposableEffect(document) { onDispose { document.close() } }

    suspend fun load(uri: Uri) {
        heap("before")
        // Opening no longer reads the file, but it does index it for chunk boundaries, and that is
        // a pass over the whole thing: seconds for a file of a gigabyte. Say so rather than look
        // like nothing happened.
        status = "opening ${displayName(context, uri)}"
        timed("open") {
            openDocument(context, uri)
                .onSuccess {
                    timed("setText") { document.load(it, whole = it.format == Format.Encrypted) }
                    frameAfterNext("layout")
                    heap("after")
                    file = uri
                    name = displayName(context, uri)
                    status = name
                }
                .onFailure { status = "could not open: ${it.message}" }
        }
    }

    suspend fun write(target: Uri) {
        status = "saving ${displayName(context, target)}"
        document.withContent { saveDocument(context, target, it) }
            .onSuccess {
                file = target
                name = displayName(context, target)
                status = "saved $name"
            }
            .onFailure { status = "could not save: ${it.message}" }
    }

    /**
     * Back to an empty document with no file behind it. Nothing is created on disk: a new file is
     * one that has never been saved, and where it goes is asked for by Save as when there is
     * something to put in it.
     */
    fun new() {
        scope.launch {
            document.load(Content.empty())
            document.focus(0)
            file = null
            name = UNTITLED
            status = NO_FILE
        }
    }

    LaunchedEffect(opened) { opened?.let { load(it) } }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        picked ?: return@rememberLauncherForActivityResult
        scope.launch { load(picked) }
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { created ->
        created ?: return@rememberLauncherForActivityResult
        scope.launch { write(created) }
    }

    /**
     * Saving asks first if the file is no longer the one that was opened. The document itself is
     * fine either way — the descriptor kept it — so the only question is whose version the file
     * ends up holding, and that is the user's to answer and not ours.
     */
    fun save() {
        val target = file ?: return
        scope.launch {
            when (collision(context, target, document.content)) {
                Collision.None -> write(target)
                Collision.Replaced -> asking = true
                Collision.Rewritten ->
                    status = "$name was rewritten while it was open, so this document is no longer all of it"
            }
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        // Scrollable, because seven text buttons is a little wider than a phone in portrait and
        // a toolbar that clips its last button is worse than one that can be pushed along.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { new() }) { Text("New") }
            TextButton(onClick = { open.launch(arrayOf("*/*")) }) { Text("Open") }
            TextButton(onClick = { save() }, enabled = file != null) { Text("Save") }
            TextButton(onClick = { saveAs.launch(name) }) { Text("Save as") }
            // Select all works on the document and not on the window, which is the only way it
            // could work: the field holds a few kilobytes of whatever is open.
            TextButton(onClick = { scope.launch { document.selectAll() } }) { Text("All") }
            TextButton(
                onClick = { scope.launch { document.undo() } },
                enabled = document.canUndo,
            ) { Text("Undo") }
            TextButton(
                onClick = { scope.launch { document.redo() } },
                enabled = document.canRedo,
            ) { Text("Redo") }
        }
        Text(status, Modifier.padding(horizontal = 12.dp))
        // One physical pixel, not one dp: a dense panel puts nearly three pixels in a dp, and at that
        // width the rule reads as a bar across the screen rather than as a line under the toolbar.
        HorizontalDivider(thickness = with(LocalDensity.current) { 1.toDp() })
        DocumentEditor(
            state = document,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
        )
    }

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("$name changed") },
            text = {
                Text(
                    "Something else replaced it since it was opened. This document is still what " +
                        "the file held then, with your edits in it. Saving puts that over whatever " +
                        "is there now."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    file?.let { target -> scope.launch { write(target) } }
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
