package com.lucasta.edit

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent { Editor() }
    }
}

@Composable
private fun Editor() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val text = rememberTextFieldState()
    var file by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf("untitled.txt") }
    var status by remember { mutableStateOf("no file") }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        picked ?: return@rememberLauncherForActivityResult
        scope.launch {
            readText(context, picked)
                .onSuccess {
                    text.setTextAndPlaceCursorAtEnd(it)
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
            writeText(context, created, text.text.toString())
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
            writeText(context, target, text.text.toString())
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
        )
    }
}
