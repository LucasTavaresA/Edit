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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    return if (dark) scheme.unlit() else scheme
}

/**
 * The same scheme with every ground it paints taken to pure black. On an OLED panel a black
 * pixel is an unlit pixel: dark mode costs nothing but the text drawn on it, and the dark greys
 * Material would otherwise use light the whole screen up. Only the grounds move — what is drawn on
 * them keeps the colours the scheme chose, so contrast goes up rather than down. [surfaceTint] goes
 * black as well, since that is the colour elevation would use to lift a surface back off the black.
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
    var encoding by remember { mutableStateOf(Encoding.Utf8) }
    var format by remember { mutableStateOf(Format.Plain) }
    var status by remember { mutableStateOf(NO_FILE) }

    suspend fun load(uri: Uri) {
        heap("before")
        timed("open") {
            openDocument(context, uri)
                .onSuccess {
                    timed("setText") { document.load(it.text, whole = it.format == Format.Encrypted) }
                    frameAfterNext("layout")
                    heap("after")
                    encoding = it.encoding
                    format = it.format
                    file = uri
                    name = displayName(context, uri)
                    status = name
                }
                .onFailure { status = "could not open: ${it.message}" }
        }
    }

    /**
     * Back to an empty document with no file behind it. Nothing is created on disk: a new file is
     * one that has never been saved, and where it goes is asked for by Save as when there is
     * something to put in it.
     */
    fun new() {
        scope.launch {
            document.load(StringBuilder())
            document.focus(0)
            file = null
            name = UNTITLED
            encoding = Encoding.Utf8
            format = Format.Plain
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
        scope.launch {
            document.withText { saveDocument(context, created, TextDocument(it, encoding, format)) }
                .onSuccess {
                    file = created
                    name = displayName(context, created)
                    status = "saved $name"
                }
                .onFailure { status = "could not save: ${it.message}" }
        }
    }

    fun save() {
        val target = file ?: return
        scope.launch {
            document.withText { saveDocument(context, target, TextDocument(it, encoding, format)) }
                .onSuccess { status = "saved $name" }
                .onFailure { status = "could not save: ${it.message}" }
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { new() }) { Text("New") }
            TextButton(onClick = { open.launch(arrayOf("*/*")) }) { Text("Open") }
            TextButton(onClick = { save() }, enabled = file != null) { Text("Save") }
            TextButton(onClick = { saveAs.launch(name) }) { Text("Save as") }
        }
        Text(status, Modifier.padding(horizontal = 12.dp))
        DocumentEditor(
            state = document,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
        )
    }
}
