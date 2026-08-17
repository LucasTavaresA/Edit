package com.lucasta.edit

import android.graphics.Color
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            MaterialTheme(colorScheme = systemColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Editor() }
            }
        }
    }
}

/** The system light or dark scheme, taking the wallpaper palette where the platform offers one. */
@Composable
private fun systemColorScheme(dark: Boolean = isSystemInDarkTheme()): ColorScheme {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    return if (dark) darkColorScheme() else lightColorScheme()
}

@Composable
private fun Editor() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val text = rememberTextFieldState()
    var file by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf("untitled.txt") }
    var encoding by remember { mutableStateOf(Encoding.Utf8) }
    var status by remember { mutableStateOf("no file") }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        picked ?: return@rememberLauncherForActivityResult
        scope.launch {
            readDocument(context, picked)
                .onSuccess {
                    text.setTextAndPlaceCursorAtEnd(it.text)
                    encoding = it.encoding
                    file = picked
                    name = displayName(context, picked)
                    status = name
                }
                .onFailure { status = "could not open: ${it.message}" }
        }
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { created ->
        created ?: return@rememberLauncherForActivityResult
        scope.launch {
            writeDocument(context, created, TextDocument(text.text.toString(), encoding))
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
            writeDocument(context, target, TextDocument(text.text.toString(), encoding))
                .onSuccess { status = "saved $name" }
                .onFailure { status = "could not save: ${it.message}" }
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { open.launch(arrayOf("*/*")) }) { Text("Open") }
            TextButton(onClick = { save() }, enabled = file != null) { Text("Save") }
            TextButton(onClick = { saveAs.launch(name) }) { Text("Save as") }
        }
        Text(status, Modifier.padding(horizontal = 12.dp))
        BasicTextField(
            state = text,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(12.dp),
            textStyle = LocalTextStyle.current.copy(color = LocalContentColor.current),
            cursorBrush = SolidColor(LocalContentColor.current),
        )
    }
}
