package com.lucasta.edit

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * How much a copy may put on the clipboard, in the document's bytes. What is copied crosses into
 * the system and back out to whoever pastes it in single binder transactions, and each process has
 * about a megabyte for everything it has in flight at once. Text crosses as UTF-8 or as UTF-16
 * depending on who is carrying it, and either way takes at most two bytes for each of the
 * document's, so this keeps every crossing within half of that megabyte; only a byte the encoding
 * could not name, carried as a replacement, takes three. Anything larger is refused, and cutting it
 * is too, rather than cut short: a clipboard holding part of what was asked for looks exactly like
 * one holding all of it, until it is pasted.
 *
 * Measured on a phone, twice this is already at the edge: the system hands a clip on to the
 * vendor's clipboard at two bytes a character. A megabyte is taken in and cannot be read back out.
 */
private const val CLIP_MAX = 256 * 1024

/** How near the top or bottom edge a drag has to come before the text scrolls under it, at most. */
private val EDGE = 40.dp

/** How big a handle is drawn, the size of the platform's own. */
private val HANDLE = 25.dp

/** How far past a handle a touch still takes hold of it, on every side. */
private val GRAB = 12.dp

/**
 * Everything a touch does to the text: tapping, scrolling, long-pressing a word, and dragging the
 * ends of a selection. It lies over the rows and takes every touch before they can, the window's
 * field included, which only ever hears from the keyboard.
 *
 * A selection belongs to the document rather than to a text field — see [DocumentState.marked] —
 * so the platform's handles, magnifier and menu, which belong to a field and reach no further than
 * one, are replaced here: the handles are drawn over the rows, the magnifier is the platform's own
 * widget pointed at them, and the menu is the platform's floating toolbar, asked for directly.
 * A long press on a link or an email address adds opening it and selecting the whole of it to the
 * menu; [refuse] says so when nothing on the phone opens it.
 *
 * The list scrolls under it through [scrollable], which is what the list itself would have done
 * with the touch, and with the list's own [overscroll]. Two fingers are a pinch, handed to [zoom]
 * as how much each move spread them or drew them together and the point between them.
 */
@Composable
internal fun SelectionLayer(
    state: DocumentState,
    list: LazyListState,
    overscroll: OverscrollEffect?,
    clip: Clip,
    zoom: (Float, Offset) -> Unit,
    refuse: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pinched by rememberUpdatedState(zoom)
    val refusal by rememberUpdatedState(refuse)
    val handles = LocalTextSelectionColors.current.handleColor
    val context = LocalContext.current
    val view = LocalView.current
    val toolbar = remember(view) { TextMenu(view) }
    val haptics = LocalHapticFeedback.current
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var pressed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    // The window's cursor as it was when the menu that offers to paste was asked for at it, and
    // null when it was not, or the cursor has moved since.
    var cursorMenu by remember { mutableStateOf<TextRange?>(null) }
    // The link the last long press landed on, which the menu offers for as long as the selection
    // stays inside it, or the cursor that press left stays where it was.
    var link by remember { mutableStateOf<TextLink?>(null) }

    LaunchedEffect(state) {
        snapshotFlow { state.window.selection }.collect { if (it != cursorMenu) cursorMenu = null }
    }
    // The menu is shown for as long as there is something for it to act on and nothing is moving
    // under it: it is put away while a finger is down or the list is scrolling, and comes back
    // where the selection has got to once they stop. A selection the keyboard made or moved has
    // none; see [DocumentState.touched].
    LaunchedEffect(state, list, toolbar) {
        snapshotFlow {
            val mark = state.marked
            val near = link?.takeIf { if (mark != null) mark.min >= it.range.min && mark.max <= it.range.max else cursorMenu != null }
            when {
                pressed || list.isScrollInProgress -> null
                mark != null -> if (state.touched) selectionRect(state, list)?.let { Triple(it, true, near) } else null
                cursorMenu != null -> state.cursorCaret()?.let { Triple(it, false, near) }
                else -> null
            }
        }.collect { menu ->
            val where = placed[0]?.takeIf { it.isAttached }
            if (menu == null || where == null) {
                toolbar.hide()
                return@collect
            }
            val (rect, selection, found) = menu
            val root = Rect(where.localToRoot(rect.topLeft), rect.size)
            val items = mutableListOf<Pair<String, () -> Unit>>()
            if (found != null) {
                items += "Open ${found.kind}" to {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(found.url)))
                    } catch (e: ActivityNotFoundException) {
                        refusal("could not open ${found.url}: nothing on the phone opens it")
                    }
                }
                if (state.marked != found.range) items += "Select ${found.kind}" to {
                    cursorMenu = null
                    state.mark(found.range)
                }
            }
            if (selection) items += context.getString(android.R.string.copy) to { if (clip.copy()) state.deselect() }
            if (clip.hasText) items += context.getString(android.R.string.paste) to { clip.paste(); cursorMenu = null }
            if (selection) items += context.getString(android.R.string.cut) to { clip.cut() }
            items += context.getString(android.R.string.selectAll) to {
                cursorMenu = null
                state.selectAll()
            }
            toolbar.show(root, items)
        }
    }
    DisposableEffect(toolbar) { onDispose { toolbar.hide() } }

    Box(
        modifier
            .onPlaced { placed[0] = it }
            .magnifier(
                sourceCenter = {
                    val end = state.marked?.end
                    val caret = if (dragging && end != null) state.caretAt(end) else null
                    if (caret == null || caret.top < 0f) Offset.Unspecified else Offset(caret.left, caret.center.y)
                },
            )
            .drawBehind {
                val mark = state.marked?.takeIf { state.touched } ?: return@drawBehind
                state.caretAt(mark.min)?.takeIf { it.bottom > 0f && it.top < size.height }?.let { handle(it, true, handles) }
                state.caretAt(mark.max)?.takeIf { it.bottom > 0f && it.top < size.height }?.let { handle(it, false, handles) }
            }
            // A finger moves with the text, not with the viewport, which is the other way round to
            // how the list counts, as it would have told its own scrollable.
            .scrollable(list, Orientation.Vertical, overscroll, reverseDirection = true)
            // After the scrollable, so that it sees every touch first and takes the ones that are
            // a handle or a long press before the scrollable can take them for a scroll.
            .pointerInput(state, list) {
                coroutineScope {
                    val scope = this
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        try {
                            val grip = gripAt(state, down.position, size.height.toFloat())
                            if (grip != null) {
                                down.consume()
                                dragging = true
                                carry(down, scope, list) { finger ->
                                    val spot = state.spotAt(finger + grip.offset) ?: return@carry
                                    val at = state.offsetOf(spot)
                                    if (at != grip.fixed && at != state.marked?.end) {
                                        state.mark(TextRange(grip.fixed, at), park = false)
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                                state.park()
                                return@awaitEachGesture
                            }
                            when (press(down)) {
                                // A second finger can come down after the first has started
                                // scrolling, and that is a pinch too.
                                Press.Scroll -> if (another()) pinch(pinched)
                                Press.Pinch -> pinch(pinched)
                                Press.Tap -> {
                                    link = null
                                    val spot = state.spotAt(down.position) ?: return@awaitEachGesture
                                    // Tapping the cursor where it already is asks for the menu that
                                    // offers to paste, as in any text field, and tapping it again
                                    // puts the menu away.
                                    val again = spot.slice == null && state.marked == null &&
                                        state.window.selection == TextRange(spot.char)
                                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                        state.tap(spot)
                                        cursorMenu = if (again && cursorMenu == null) state.window.selection else null
                                    }
                                }
                                Press.Long -> {
                                    link = null
                                    val spot = state.spotAt(down.position) ?: return@awaitEachGesture
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    link = linkAt(spot.text, spot.char)?.let { (range, url) ->
                                        TextLink(TextRange(state.offsetOf(spot, range.start), state.offsetOf(spot, range.end)), url)
                                    }
                                    val word = wordAt(spot.text, spot.char)
                                    if (word.collapsed) {
                                        // Nothing to select: the cursor goes there, with the menu
                                        // that offers to paste.
                                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                            state.tap(spot)
                                            cursorMenu = state.window.selection
                                        }
                                        return@awaitEachGesture
                                    }
                                    val anchor = TextRange(state.offsetOf(spot, word.start), state.offsetOf(spot, word.end))
                                    state.mark(anchor)
                                    dragging = true
                                    // Dragging on from a long press carries the selection a word at
                                    // a time, and keeps the word it started on.
                                    carry(down, scope, list) { finger ->
                                        val at = state.spotAt(finger) ?: return@carry
                                        val near = wordAt(at.text, at.char)
                                        val from = state.offsetOf(at, near.start)
                                        val to = state.offsetOf(at, near.end)
                                        val range = when {
                                            to > anchor.max -> TextRange(anchor.min, to)
                                            from < anchor.min -> TextRange(anchor.max, from)
                                            else -> anchor
                                        }
                                        if (range != state.marked) {
                                            state.mark(range, park = false)
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                    state.park()
                                }
                            }
                        } finally {
                            pressed = false
                            dragging = false
                        }
                    }
                }
            },
    )
}

/** What a touch that is not on a handle turns out to be. */
private enum class Press { Tap, Long, Scroll, Pinch }

/**
 * Lifted soon enough is a tap, held still long enough is a long press, and moved first is a
 * scroll, which is the list's: nothing is consumed here, so the scrollable takes it as its own.
 * Another finger coming down before any of that is a pinch.
 */
private suspend fun AwaitPointerEventScope.press(down: PointerInputChange): Press {
    var press = Press.Long
    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } > 1) {
                press = Press.Pinch
                break
            }
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                press = Press.Scroll
                break
            }
            if (change.changedToUpIgnoreConsumed()) {
                press = Press.Tap
                break
            }
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                press = Press.Scroll
                break
            }
        }
    }
    return press
}

/**
 * Waits out a touch the list is scrolling, and says whether another finger came down before the
 * last one lifted.
 */
private suspend fun AwaitPointerEventScope.another(): Boolean {
    while (true) {
        val down = awaitPointerEvent().changes.count { it.pressed }
        if (down > 1) return true
        if (down == 0) return false
    }
}

/**
 * Follows the fingers until the last of them lifts, handing [zoom] each move made with two or more
 * down. Every move is taken, so the list never sees one as a scroll, and a scroll already under way
 * stops; once a finger lifts the one left down is still taken, rather than setting the text
 * scrolling out from under a pinch.
 */
private suspend fun AwaitPointerEventScope.pinch(zoom: (Float, Offset) -> Unit) {
    while (true) {
        val event = awaitPointerEvent()
        val down = event.changes.count { it.pressed }
        if (down == 0) break
        if (down > 1) {
            val by = event.calculateZoom()
            if (by != 1f) zoom(by, event.calculateCentroid())
        }
        event.changes.forEach { it.consume() }
    }
}

/**
 * Follows the pointer that went [down] until it lifts, handing [follow] each place it moves to,
 * and scrolls the text under it while it is held near the top or bottom edge, handing [follow] the
 * same place again as the text moves. Every move is taken, so the list never sees one as a scroll.
 */
private suspend fun AwaitPointerEventScope.carry(
    down: PointerInputChange,
    scope: CoroutineScope,
    list: LazyListState,
    follow: (Offset) -> Unit,
) {
    var finger = down.position
    val edge = EDGE.toPx()
    val slop = viewConfiguration.touchSlop
    val scrolling = scope.launch {
        var since = 0L
        // An edge scrolls only once the finger has been carried into it, never because the drag
        // began there: a long press on the first or last line on screen would otherwise set the
        // text moving under a finger that has not moved at all.
        var top = false
        var bottom = false
        while (true) {
            val now = withFrameMillis { it }
            val height = size.height.toFloat()
            // Landscape with the keyboard up leaves a few lines, which would otherwise be mostly edge.
            val zone = minOf(edge, height / 5)
            if (finger.y >= zone || finger.y < down.position.y - slop) top = true
            if (finger.y <= height - zone || finger.y > down.position.y + slop) bottom = true
            val beyond = when {
                top && finger.y < zone -> finger.y - zone
                bottom && finger.y > height - zone -> finger.y - (height - zone)
                else -> 0f
            }
            if (beyond == 0f) {
                since = 0L
                continue
            }
            if (since == 0L) since = now
            // Faster the deeper into the edge, and faster again the longer it is held there, so
            // that going a long way is not a long wait.
            val held = (1 + (now - since) / 1000f).coerceAtMost(10f)
            list.scrollBy((beyond / zone).coerceIn(-3f, 3f) * zone / 2 * held)
            follow(finger)
        }
    }
    try {
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (change.changedToUpIgnoreConsumed()) break
            change.consume()
            finger = change.position
            follow(finger)
        }
    } finally {
        scrolling.cancel()
    }
}

/**
 * A handle taken hold of: the end of the selection that stays put while it is dragged, and how far
 * the place it points at is from the finger, which keeps it pointing there rather than under the
 * finger, where nobody could see it.
 */
private class Grip(val fixed: Int, val offset: Offset)

/**
 * The handle a touch at [at] takes hold of, or null; the nearer one when they overlap. Only a
 * handle drawn on screen, which is [height] tall, can be taken hold of.
 */
private fun Density.gripAt(state: DocumentState, at: Offset, height: Float): Grip? {
    val mark = state.marked?.takeIf { state.touched } ?: return null
    var grip: Grip? = null
    var nearest = Float.MAX_VALUE
    for (start in booleanArrayOf(true, false)) {
        val caret = state.caretAt(if (start) mark.min else mark.max)
            ?.takeIf { it.bottom > 0f && it.top < height } ?: continue
        val area = handleArea(caret, start).inflate(GRAB.toPx())
        val distance = (area.center - at).getDistance()
        if (!area.contains(at) || distance >= nearest) continue
        nearest = distance
        grip = Grip(if (start) mark.max else mark.min, Offset(caret.left, caret.center.y) - at)
    }
    return grip
}

/**
 * Where a handle hangs from the bottom of [caret], pointing up at it: the start of a selection
 * hangs to the left of where it starts, the end to the right of where it ends.
 */
private fun Density.handleArea(caret: Rect, start: Boolean): Rect {
    val size = HANDLE.toPx()
    val left = if (start) caret.left - size else caret.left
    return Rect(left, caret.bottom, left + size, caret.bottom + size)
}

/** The platform's teardrop: a circle with the corner nearest the text squared off to point at it. */
private fun DrawScope.handle(caret: Rect, start: Boolean, color: Color) {
    val area = handleArea(caret, start)
    val radius = area.width / 2
    drawCircle(color, radius, area.center)
    drawRect(color, Offset(if (start) area.center.x else area.left, area.top), Size(radius, radius))
}

/**
 * Where the menu for the selection goes: around the part of it on screen, in the list's own
 * coordinates, or null when none of it is. An end that is off screen counts as the edge it is
 * beyond.
 */
private fun selectionRect(state: DocumentState, list: LazyListState): Rect? {
    val mark = state.marked ?: return null
    val size = list.layoutInfo.viewportSize
    val height = size.height.toFloat()
    val first = state.caretAt(mark.min)
    val last = state.caretAt(mark.max)
    val top = (first?.top ?: if (mark.min < state.visibleFrom) 0f else return null).coerceAtLeast(0f)
    val bottom = (last?.bottom ?: if (mark.max > state.visibleFrom) height else return null).coerceAtMost(height)
    if (top >= bottom) return null
    return if (first != null && last != null && first.top == last.top) Rect(first.left, top, last.left, bottom)
    else Rect(0f, top, size.width.toFloat(), bottom)
}

/** What a long press selects: the run of word characters under it, or nothing if there is none. */
private fun wordAt(text: CharSequence, offset: Int): TextRange {
    var start = offset
    var end = offset
    while (start > 0 && isWord(text[start - 1])) start--
    while (end < text.length && isWord(text[end])) end++
    return TextRange(start, end)
}

/** What a word is made of, for a long press and for a step a word at a time alike. */
internal fun isWord(char: Char) = char.isLetterOrDigit() || char == '_'

/** A link in the document: where it is, and the address it opens, scheme and all. */
private class TextLink(val range: TextRange, val url: String) {
    /** What the menu calls it. */
    val kind get() = if (url.startsWith("mailto:")) "email" else "link"
}

/**
 * The web or email address [offset] is in or next to, and what it opens, found the way a text field
 * finds the links it makes tappable. A bare address is given the scheme it would open with.
 */
private fun linkAt(text: CharSequence, offset: Int): Pair<TextRange, String>? {
    val spans = SpannableString(text)
    if (!Linkify.addLinks(spans, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES)) return null
    val span = spans.getSpans(offset, offset, URLSpan::class.java).firstOrNull() ?: return null
    return TextRange(spans.getSpanStart(span), spans.getSpanEnd(span)) to span.url
}

/**
 * The platform's floating menu over [view], shown the way Compose's own text toolbar shows it.
 * That one offers only its fixed four items and a link wants two more, so the menu is asked of the
 * view directly. Showing it while it is up moves it and gives it the new items; picking one runs
 * it and puts the menu away.
 */
private class TextMenu(private val view: View) : ActionMode.Callback2() {
    private var mode: ActionMode? = null
    private val rect = android.graphics.Rect()
    private var items: List<Pair<String, () -> Unit>> = emptyList()

    /** Shows [items] around [rect], in the coordinates of the root, which is [view]. */
    fun show(rect: Rect, items: List<Pair<String, () -> Unit>>) {
        this.rect.set(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        this.items = items
        mode?.invalidate() ?: run { mode = view.startActionMode(this, ActionMode.TYPE_FLOATING) }
    }

    fun hide() {
        mode?.finish()
        mode = null
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu) = onPrepareActionMode(mode, menu)

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.clear()
        items.forEachIndexed { i, (title, _) -> menu.add(Menu.NONE, i, i, title) }
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        items.getOrNull(item.itemId)?.second?.invoke()
        mode.finish()
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        if (mode == this.mode) this.mode = null
    }

    override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
        outRect.set(rect)
    }
}

/**
 * The clipboard, as the editor's selection uses it. Copying and cutting refuse a selection over
 * [CLIP_MAX], and say so through [refuse]; [typing] says whether the keyboard is up, which a cut or
 * a paste keeps it.
 */
internal class Clip(
    private val state: DocumentState,
    private val context: Context,
    private val refuse: (String) -> Unit,
    private val typing: () -> Boolean,
) {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    /** Whether there is text to paste, asked without reading it, which the system would announce. */
    val hasText: Boolean
        get() = clipboard?.primaryClipDescription?.hasMimeType("text/*") == true

    /** Copies the selection, and answers whether it did. */
    fun copy(): Boolean = put("copy")

    /** Copies the selection and takes it out, or does neither. */
    fun cut() {
        if (put("cut")) state.cut(typing())
    }

    fun paste() {
        val clip = try {
            clipboard?.primaryClip
        } catch (e: RuntimeException) {
            // Another app can put more on the clipboard than the system can hand back to anyone,
            // and asking for it then fails as if the system had died.
            refuse("could not paste: the clipboard holds more than it can hand over")
            null
        }?.takeIf { it.itemCount > 0 } ?: return
        val text = clip.getItemAt(0).coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return
        state.paste(text, typing())
    }

    private fun put(verb: String): Boolean {
        val clipboard = clipboard ?: return false
        val bytes = state.marked?.length ?: return false
        if (bytes > CLIP_MAX) {
            refuse("could not $verb: ${bytes.toLong().readable()}, over the ${CLIP_MAX.toLong().readable()} limit")
            return false
        }
        val text = state.markedText() ?: return false
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
            true
        } catch (e: RuntimeException) {
            // The system refusing a clip too large to carry, which the limit is there to prevent.
            refuse("could not $verb: the clipboard would not take ${bytes.toLong().readable()}")
            false
        }
    }
}

@Composable
internal fun rememberClip(state: DocumentState, refuse: (String) -> Unit): Clip {
    val context = LocalContext.current
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val refusal by rememberUpdatedState(refuse)
    return remember(state, context, ime, density) {
        Clip(state, context, { refusal(it) }) { ime.getBottom(density) > 0 }
    }
}
