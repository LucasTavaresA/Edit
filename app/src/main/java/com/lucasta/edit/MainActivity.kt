package com.lucasta.edit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import android.graphics.Color as ArgbColor

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.auto(ArgbColor.TRANSPARENT, ArgbColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        val files = Files.of(this)
        // A rebuilt activity is handed the intent it was first started with, which says nothing
        // about what is open by now.
        files.arrive(handedBy(intent).takeIf { savedInstanceState == null })
        // Closing Edit would throw every unsaved edit away, in every file, so Back only puts it in
        // the background.
        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }
        setContent {
            // The bar icons are set once, against the mode in force at the time, and the activity
            // is no longer rebuilt when that mode changes — so they are set again when it does.
            val dark = isSystemInDarkTheme()
            LaunchedEffect(dark) { enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars) }
            MaterialTheme(colorScheme = systemColorScheme(dark)) {
                Surface(Modifier.fillMaxSize()) { Editor(files) }
            }
        }
    }

    /** Edit is a single instance, so another app handing a file over reaches the running one here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Files.of(this).arrive(handedBy(intent))
    }
}

/** The file [intent] hands over to be opened, if it is one that does. */
private fun handedBy(intent: Intent?): Uri? = when (intent?.action) {
    Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
    else -> null
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

/** How long [Hold.Briefly] holds, and how long [Hold.UntilSeen] goes on holding once the user is back. */
private val HOLD_TIME = 2.seconds

/** Where how the text looks is written down: the size a pinch left it at, for every file alike. */
private const val LOOKS = "looks"

@Composable
private fun Editor(files: Files) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = files.current
    val document = checkNotNull(current.document) { "the file on screen is always opened" }
    val notice = files.bar
    var touches by remember { mutableIntStateOf(0) }
    val looks = remember { context.getSharedPreferences(LOOKS, Context.MODE_PRIVATE) }
    var scale by remember { mutableFloatStateOf(looks.getFloat("scale", 1f)) }

    /** Whether the list of files is up, over the bar that opened it. */
    var listing by remember { mutableStateOf(false) }

    /** Where the bar that names the file stands, which is where the list comes up. */
    var barAt by remember { mutableStateOf(Rect.Zero) }

    /** The file Save as was asked for, which is not always the one on screen by the time it answers. */
    var savingAs by remember { mutableStateOf<OpenFile?>(null) }

    /** Whether that Save as is the answer Save to closing a new file, which closes it once saved. */
    var closingAfter by remember { mutableStateOf(false) }

    LaunchedEffect(notice) {
        if (notice.holding) {
            if (notice.hold == Hold.UntilSeen) {
                // The next press anywhere on the screen, or the next thing the keyboard does to the
                // text — which reaches the field without passing through the screen's touches at
                // all.
                snapshotFlow {
                    val window = files.current.document?.window
                    Triple(touches, window?.text, window?.selection)
                }
                    .drop(1)
                    .first()
            }
            delay(HOLD_TIME)
        }
        files.settled(notice)
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        picked ?: return@rememberLauncherForActivityResult
        files.open(picked)
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { created ->
        val file = savingAs
        savingAs = null
        if (created != null && file != null) files.saveAs(file, created, close = closingAfter)
    }

    /**
     * Asks where [file] is to be saved as, and saves it there once that is answered, closing it
     * after if [close].
     */
    fun pickTarget(file: OpenFile, close: Boolean = false) {
        savingAs = file
        closingAfter = close
        saveAs.launch(file.name)
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
                    Tool("New") { files.new() }
                    Tool("Open") { open.launch(arrayOf("*/*")) }
                    Tool("Save as") { pickTarget(current) }
                }
            }
        }
        // The name and its rule stay over the keyboard for as long as [Hold] says. They push the
        // text down rather than cover it, so the cursor can never be under them.
        if (!typing || notice.holding) {
            // A pixel above and below and nothing else: it is something to read rather than a
            // control, so it is as tall as its sentence and aligned with the text below. Tapping
            // it brings the list of files up over it, which the mark at its end says.
            Row(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { barAt = it.boundsInRoot() }
                    .clickable { listing = true }
                    .padding(horizontal = 12.dp, vertical = pixel),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(notice.text, Modifier.weight(1f))
                // The file the bar is talking about, which is not always the one on screen.
                if (notice.about?.document?.unsaved == true) Unsaved()
                ListMark(files.list.count { it.document != null }, files.list.count { it.document?.unsaved == true })
            }
            // One physical pixel, not one dp: a dense panel puts nearly three pixels in a dp, and at
            // that width the rule reads as a bar across the screen rather than as a line under the
            // toolbar.
            HorizontalDivider(thickness = pixel)
        }
        DocumentEditor(
            state = document,
            modifier = Modifier.fillMaxWidth().weight(1f),
            margin = 12.dp,
            refuse = { files.say(current, it, Hold.UntilSeen) },
            scale = scale,
            rescale = {
                scale = it
                looks.edit().putFloat("scale", it).apply()
            },
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
                Op("Save", enabled = current.uri != null) { files.save(current) }
                Op("All") { document.selectAll() }
                Op("Undo", enabled = document.canUndo) { scope.launch { document.undo() } }
                Op("Redo", enabled = document.canRedo) { scope.launch { document.redo() } }
            }
        }
    }

    // Over everything, the bar it came from included: Editor is laid out in the box a Surface puts
    // its content in, so what comes after the column stacks on it.
    if (listing) FileList(files, barAt, pixel) { listing = false }

    // The file asked about may be any in the list, not only the one on screen, so the question
    // names it and its answers go to it.
    val asking = files.list.firstOrNull { it.asking }
    if (asking != null) {
        AlertDialog(
            onDismissRequest = { asking.asking = false },
            title = { Text("${asking.name} changed") },
            text = {
                Text(
                    "Something else changed it since it was opened or last saved. This document is " +
                        "still what the file held then, with your edits in it. Saving puts that over " +
                        "whatever is there now."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    asking.asking = false
                    files.overwrite(asking)
                }) { Text("Overwrite") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        asking.asking = false
                        pickTarget(asking)
                    }) { Text("Save as") }
                    TextButton(onClick = { asking.asking = false }) { Text("Cancel") }
                }
            },
        )
    }

    // Closing is asked from the list, so this comes up over it, and the list stays up behind it to
    // close more from.
    val closing = files.list.firstOrNull { it.closing }
    if (closing != null) {
        val doing = if (closing.forgetting) "removing it from the list" else "closing it"
        AlertDialog(
            onDismissRequest = { files.keepOpen(closing) },
            title = { Text("Do you want to save ${closing.name} before $doing?") },
            text = { Text("It has edits that are not saved. ${if (closing.forgetting) "Removing" else "Closing"} without saving throws them away.") },
            confirmButton = {
                TextButton(onClick = {
                    closing.closing = false
                    // A new file has nowhere to be saved yet, so Save asks where, as Save as does.
                    if (closing.uri != null) files.saveAndClose(closing) else pickTarget(closing, close = true)
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        closing.closing = false
                        files.discard(closing)
                    }) { Text(if (closing.forgetting) "Remove" else "Close") }
                    TextButton(onClick = { files.keepOpen(closing) }) { Text("Nevermind") }
                }
            },
        )
    }
}

/**
 * The list of open files, brought up by tapping the bar that names the one on screen, which is the
 * first row and stands exactly where [bar], the bar's text, did, with a rule under it as the bar
 * has. The rest follow below it in the list's order. Every row says where its file lives under its
 * name, which is what tells two of the same name apart, and an unsaved one has the bar's mark right
 * after its name. Each opened one but the first ends in a button that
 * closes its file and leaves the list up; one not opened has nothing to close, and the one on
 * screen is not closed from here, so neither has a button. Holding any row offers to remove it from
 * the list. Going to a file, a tap anywhere off the list, or Back puts it away through [close].
 */
@Composable
private fun FileList(files: Files, bar: Rect, pixel: Dp, close: () -> Unit) {
    BackHandler(onBack = close)
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            // Dims what the list is over, so it reads as on top of the text rather than part of it.
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            .pointerInput(Unit) { detectTapGestures { close() } }
    ) {
        val top = bar.top - origin.y
        Column(
            Modifier
                .offset { IntOffset((bar.left - origin.x).roundToInt(), top.roundToInt()) }
                .width(with(density) { bar.width.toDp() })
                .heightIn(max = maxHeight - with(density) { top.toDp() })
                .background(MaterialTheme.colorScheme.surface)
                .border(pixel, MaterialTheme.colorScheme.outlineVariant)
                .verticalScroll(rememberScrollState())
        ) {
            files.list.forEachIndexed { at, file ->
                key(file) {
                    // The outline's mid grey rather than onSurfaceVariant, which on black is near
                    // enough white that a name drawn in it could not be told from one that is not.
                    val quiet = MaterialTheme.colorScheme.outline
                    // An unopened name a step brighter than that, so it still reads as a name
                    // rather than as the place under it.
                    val unopened = lerp(quiet, MaterialTheme.colorScheme.onSurface, 0.25f)
                    val closable = at > 0 && file.document != null
                    var holding by remember { mutableStateOf(false) }
                    // As tall as the name and place, so the close button can take the row's height.
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        Column(
                            Modifier
                                .weight(1f)
                                .combinedClickable(
                                    onLongClick = { holding = true },
                                    onLongClickLabel = "Remove from list",
                                ) {
                                    close()
                                    files.goTo(file)
                                }
                                // The name on the first row a pixel down, where the bar's was,
                                // and on the rest the same distance from the top as the place
                                // below it is from the bottom.
                                .padding(start = 12.dp, end = if (closable) 0.dp else 12.dp)
                                .padding(top = if (at == 0) pixel else 8.dp, bottom = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    file.name,
                                    // Only as wide as the name, so the mark sits right after it,
                                    // and cut short for it rather than pushing it off the row.
                                    Modifier.weight(1f, fill = false),
                                    // One not opened, as a file brought back by a start or closed
                                    // is until it is gone to, greyed.
                                    color = if (file.document == null) unopened else Color.Unspecified,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (file.document?.unsaved == true) Unsaved()
                            }
                            Text(
                                placeOf(file),
                                color = quiet,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                // A path says the most at its end, the folder the file is in.
                                overflow = TextOverflow.StartEllipsis,
                            )
                            // Under the row held, leaving the list up to remove more from. Edged
                            // as the list is, since on black ground it would read as a row of it.
                            DropdownMenu(
                                expanded = holding,
                                onDismissRequest = { holding = false },
                                shape = RectangleShape,
                                border = BorderStroke(pixel, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Remove from list") },
                                    onClick = {
                                        holding = false
                                        files.forget(file)
                                    },
                                )
                            }
                        }
                        if (closable) CloseButton { files.close(file) }
                    }
                    if (at == 0) HorizontalDivider(thickness = pixel)
                }
            }
        }
    }
}

/** Where [file] lives, told under its name; a new file says it is new, having nowhere yet. */
@Composable
private fun placeOf(file: OpenFile): String {
    val context = LocalContext.current
    val uri = file.uri
    return remember(uri) { uri?.let { whereIs(context, it) }?.ifEmpty { null } ?: "new" }
}

/**
 * The close button at the end of a row in the list: a cross drawn as the chevron is, on a target
 * the row's full height and wide enough for a thumb, apart from the rest of the row, which goes to
 * the file.
 */
@Composable
private fun CloseButton(onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.fillMaxHeight().width(48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) {
            val stroke = 1.5.dp.toPx()
            drawLine(color, Offset.Zero, Offset(size.width, size.height), stroke, StrokeCap.Round)
            drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
        }
    }
}

/**
 * The mark for a file with edits not saved: a dot in the accent, at the end of the bar's text and
 * right after a name in the list.
 */
@Composable
private fun Unsaved() {
    Box(
        Modifier
            .padding(start = 8.dp)
            .size(8.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
    )
}

/**
 * The end of the bar, saying it opens the list of files: once more than one is [opened], how many
 * of them have edits not saved, in the unsaved mark's accent, and how many are opened, since the
 * list is recent files and its length says nothing; then a chevron, drawn rather than a glyph, so
 * no font decides how it looks.
 */
@Composable
private fun ListMark(opened: Int, unsaved: Int) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelMedium
    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (opened > 1) {
            if (unsaved > 0) {
                Text("$unsaved", Modifier.padding(end = 8.dp), MaterialTheme.colorScheme.primary, style = style)
            }
            Text("$opened", color = color, style = style)
        }
        Canvas(Modifier.padding(start = 6.dp).size(width = 10.dp, height = 6.dp)) {
            val chevron = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width / 2, size.height)
                lineTo(size.width, 0f)
            }
            drawPath(chevron, color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
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
