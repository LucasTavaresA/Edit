package com.lucasta.edit

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chunks either side of the cursor that are editable at once. The window is the only text a
 * keystroke has to lay out again, so this is the dial between how long a keystroke takes and how
 * far a single drag can select: at [CHUNK_TARGET] each, one chunk of slack is about 6 KB of text,
 * roughly a hundred lines, and roughly 25 ms of layout per keystroke.
 */
private const val WINDOW_CHUNKS = 1

/** How close to the edge of the window the cursor may get before the window moves with it. */
private const val WINDOW_GUARD = 256

/**
 * How much text the window may hold before it is cut back to size whatever the cursor is doing.
 *
 * A window normally covers [WINDOW_CHUNKS] chunks either side of the cursor, so it is a few
 * thousand characters and a keystroke lays out a few thousand characters. Text put in the middle of
 * it, though — a paste — moves neither edge nearer the cursor, so nothing else would ever ask it to
 * move, and the field would go on holding everything pasted into it and laying all of it out again
 * on every keystroke. That is what makes an editor slower the more has been pasted into it.
 */
private const val WINDOW_MAX = CHUNK_MAX * (2 * WINDOW_CHUNKS + 2)

/**
 * Documents at least this long are indexed on a background thread, so that opening one never
 * freezes the UI. Below it the hop off the main thread and back costs more than the scan does: a
 * 64 KB file indexes in 15-30 ms, where a cold dispatcher thread takes a few hundred milliseconds
 * to get going and hand the answer back.
 */
private const val INDEX_OFF_THREAD = 256 * 1024

/**
 * How long a stretch of typing may pause before what comes next is a change of its own.
 *
 * Every keystroke is recorded, so this decides only how many of them one press of undo walks back,
 * which is a question about what feels like one edit rather than about what the document knows. The
 * pause is measured from the last keystroke and not from the start of the run.
 */
private const val PAUSE_MILLIS = 1_000L

/** Blank space under the last line, so the end of a file is not stuck against the bottom edge. */
private val TAIL = 160.dp

/**
 * The document, plus the slice of it that is currently editable.
 *
 * The document is a [Content]: a copy of the file plus the changes made to it, addressed in
 * bytes. Nothing here holds the text of the file — a row is decoded when it is drawn and thrown
 * away when it scrolls off, and what is in memory measures the editing rather than the file.
 *
 * On top of it sits a window — a few chunks around the cursor, held in a [TextFieldState] — which
 * is what actually takes input. Every other chunk on screen is laid out read-only, and everything
 * off screen is not laid out at all. The window is committed back into the document and cut again
 * around the cursor whenever the cursor nears an edge of it or it has been filled past
 * [WINDOW_MAX], so no amount of typing, deleting or pasting is confined to where it started and
 * none of it makes the next keystroke slower.
 *
 * A selection that reaches outside the window cannot be held by the field at all — select-all on a
 * gigabyte is not a thing a text field can hold — so it is held here as [marked] instead: the rows
 * draw it, and the next edit made while it stands replaces the whole of it in the document.
 *
 * Every offset in here is a byte offset into the document. The window's own are characters, because
 * that is what a text field deals in, and [Slice] is what crosses between the two.
 */
@Stable
class DocumentState : Closeable {

    var content: Content by mutableStateOf(Content.empty())
        private set

    var chunks: IntArray by mutableStateOf(intArrayOf(0))
        private set

    /** Bumped on every load, for anything that has to start over rather than carry on. */
    var generation by mutableIntStateOf(0)
        private set

    /**
     * Bumped whenever the document changes. Nothing drawn from it is a value Compose can compare —
     * a row is a range of bytes and the bytes are behind a descriptor — so everything drawn from it
     * is keyed on this instead.
     */
    var version by mutableIntStateOf(0)
        private set

    /** Bumped whenever the window is cut again, which is when the field wants focus back. */
    var opened by mutableIntStateOf(0)
        private set

    val window = TextFieldState()

    /** Range of the document the window stands in for, or -1 when nothing is focused. */
    var windowStart by mutableIntStateOf(-1)
        private set

    var windowEnd by mutableIntStateOf(-1)
        private set

    /**
     * How long the document is in the offsets the rows are drawn at, which is not how long it
     * actually is while the window is holding changes they have not been told about. Everything
     * outside the window is addressed in this, and [shifted] is the way back into the document's
     * own offsets.
     */
    val length: Int get() = content.length - recorded

    /** What the window has gained since [commit], as the document has it. */
    private val recorded: Int
        get() = if (windowStart < 0) 0 else windowSlice.bytes + windowBreak - (windowEnd - windowStart)

    /**
     * A selection reaching outside the window, in the document's bytes, or null when the field's
     * own selection is the whole of it. The field holds a few kilobytes and the document may hold a
     * gigabyte, so a selection that large is drawn by the rows and applied by [swallow] — the field
     * only ever stands in for it.
     */
    var marked: TextRange? by mutableStateOf(null)
        private set

    /** Whether there is anything to walk back, which is only ever what the change list holds. */
    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    /** What the window was given, which is what an edit to it is measured against. */
    private var windowSlice = Slice("", 0, Encoding.Utf8, intArrayOf(0, 0))

    /** The line break at the end of the window's range, in bytes, which the field does not hold. */
    private var windowBreak = 0

    /**
     * Whether the document is holding changes the rows have not been told about.
     *
     * Every keystroke goes into the document as it happens — see [record] — but everything typed
     * lands inside the window, so every row outside it is drawing exactly the text it was drawing
     * before and only its offsets have moved. Telling them is what costs: re-indexing chunks and
     * shifting every key after the cursor, per keystroke, on a document of any size. So the rows go
     * on addressing the document as it was when the window opened, and [commit] is what moves them
     * on — which is what already happens whenever the window is cut, saved or walked back.
     */
    private var behind = false

    /**
     * When the last change was recorded, and where it left the text standing. A change joins the
     * run before it when it arrives soon enough after it and starts where it ended, or ends where
     * it began — which is typing carried on, backspacing carried on, and nothing else.
     */
    private var lastAt = 0L

    private var lastFrom = -1

    private var lastTo = -1

    /**
     * Where the row at the top of the screen starts, and where the last one on it does, kept
     * current by [DocumentEditor].
     *
     * The list holds its scroll position by remembering the key of the top row and finding it again
     * after the rows change. A window that swallowed that row would leave it with nothing to hold
     * on to, and it would fall back on the row's old number — which by then points somewhere else,
     * because the window it just left expanded back into chunks and the one it opened collapsed a
     * few into one. So the window never starts above this row; when it has to, [reach] moves the
     * row instead, which is a nudge of a few lines and costs nothing after it.
     *
     * Not snapshot state: it changes with every scrolled pixel and nothing is drawn from it.
     */
    var visibleFrom = 0

    var visibleTo = 0

    /**
     * Which window the field is to take focus for, or -1 when nobody is waiting for it.
     *
     * Addressed to a window rather than kept as a bare flag, because cutting the window again
     * leaves two rows alive at once: the one being replaced re-runs its effects before the new one
     * has composed, so a flag would be taken by the row that is about to be disposed and go with
     * it — leaving the field that survives unfocused, which ends Compose's input session and shuts
     * the keyboard. The row that may have it is the one drawing the window it was set for.
     */
    private var focusFor = -1

    /** Set while the document is being written out, when it must not be changed. */
    private var writing = false

    /**
     * One chunk for the whole document, which is what encrypted files get: the window then covers
     * everything, every edit is an ordinary edit in one text field, and the layout is the whole
     * document's the way it was before any of this. It only works because such a document is
     * capped at [MAX_ENCRYPTED_BYTES].
     */
    private var whole = false

    /** The list the rows are drawn in, so the window can make room for itself before it moves. */
    private var list: LazyListState? = null

    /** The window's last text layout, which is the only thing that knows where the cursor is drawn. */
    private var layout: TextLayoutResult? = null

    fun drawnIn(list: LazyListState?) {
        this.list = list
    }

    fun laidOut(result: TextLayoutResult) {
        layout = result
    }

    suspend fun load(value: Content, whole: Boolean = false) {
        val starts = when {
            whole -> intArrayOf(0)
            value.length < INDEX_OFF_THREAD -> timed("index") { chunkStarts(value, 0, value.length, value.encoding) }
            else -> withContext(Dispatchers.IO) { timed("index") { chunkStarts(value, 0, value.length, value.encoding) } }
        }
        if (value !== content) content.close()
        this.whole = whole
        windowStart = -1
        windowEnd = -1
        focusFor = -1
        behind = false
        lastFrom = -1
        windowBreak = 0
        windowSlice = Slice("", 0, value.encoding, intArrayOf(0, 0))
        marked = null
        visibleFrom = 0
        visibleTo = 0
        content = value
        chunks = starts
        generation++
        changed()
    }

    /**
     * Runs [write] against the document as it stands, window edits included. Nothing may change it
     * while [write] runs: the window keeps taking keystrokes, but it cannot be committed or moved
     * until the write is done.
     */
    suspend fun <T> withContent(write: suspend (Content) -> T): T {
        commit()
        writing = true
        return try {
            write(content)
        } finally {
            writing = false
            // A save ends the history — the document starts again from what it has just written —
            // so what the buttons say about it has to be asked again.
            track()
        }
    }

    /** Puts the cursor at [offset], moving the window there. Both offsets are the document's bytes. */
    suspend fun focus(offset: Int, selection: TextRange? = null) {
        if (writing) return
        val at = shifted(offset)
        val range = selection?.let { TextRange(shifted(it.start), shifted(it.end)) }
        marked = null
        commit()
        val to = at.coerceIn(0, content.length)
        reach(chunkIndexAt(chunks, to))
        open(to, range)
        changed()
    }

    /**
     * Selects the whole document.
     *
     * The field can only ever hold the window, so what it gets is its own text selected and the
     * rest is [marked]: the rows draw it, and whatever is typed, pasted or deleted next replaces
     * all of it at once. Nothing is decoded, laid out or copied to do this, so it costs the same on
     * a gigabyte as on a line.
     */
    suspend fun selectAll() {
        if (writing) return
        marked = null
        // Before the emptiness test, not after: a document typed from new holds everything it has
        // in the field, and asking the document how long it is before that is written back answers
        // for a document nobody has typed into.
        commit()
        if (content.length == 0) return
        val at = visibleFrom.coerceIn(0, content.length)
        reach(chunkIndexAt(chunks, at))
        open(at)
        window.edit { selection = TextRange(0, length) }
        marked = TextRange(0, content.length)
        changed()
    }

    /** Walks the last change back, or forward again, and puts the window where it happened. */
    suspend fun undo() = step(back = true)

    suspend fun redo() = step(back = false)

    private suspend fun step(back: Boolean) {
        if (writing) return
        marked = null
        commit()
        val span = (if (back) content.undo() else content.redo()) ?: return
        val delta = span.now - span.was
        chunks = if (whole) intArrayOf(0)
        else respan(chunks, span.at, span.at + span.was, delta, content, content.encoding, content.length)
        val seen = span.at in visibleFrom..visibleTo
        if (visibleFrom > span.at && visibleFrom >= span.at + span.was) visibleFrom += delta
        else if (visibleFrom > span.at) visibleFrom = span.at
        windowStart = -1
        reach(chunkIndexAt(chunks, span.at.coerceIn(0, content.length)))
        open(span.at + span.now)
        // A change walked back somewhere off screen would look like nothing happening at all.
        if (!seen) list?.scrollToItem(rowsNow().rowAt(windowStart))
        changed()
    }

    /**
     * Keeps the window around the cursor and keeps it small: cut again whenever the cursor comes
     * within [WINDOW_GUARD] of an edge, or whenever more than [WINDOW_MAX] has been put into it.
     *
     * Called for every change to the field, and not only for every move of the cursor. Deleting
     * forwards does not move the cursor and pasting does not move it towards an edge, so a window
     * that only followed the cursor would never follow either of them — it would be eaten from the
     * inside until it ran out, and an edit would be confined to the few kilobytes it opened in.
     *
     * Left alone while a selection is being dragged, since moving the window under a drag would
     * fight the gesture.
     */
    suspend fun follow() {
        if (writing || windowStart < 0) return
        if (swallow()) return
        // Before anything that suspends, so that what the field holds reaches the change list while
        // it is still what the field holds. This flow conflates, so keystrokes arriving faster than
        // a frame are recorded as one change — which is what a run of typing is grouped into
        // anyway, and nothing is lost by it.
        record()
        track()
        // The layout that says where the cursor is drawn is a frame behind whatever was just typed,
        // and both of the things below read it. Waiting for it also means that however fast input
        // arrives, this runs once a frame rather than once a keystroke.
        withFrameNanos { }
        if (writing || windowStart < 0) return
        showCursor()
        val selection = window.selection
        if (!selection.collapsed || whole) return
        val text = window.text
        val nearStart = selection.min < WINDOW_GUARD && windowStart > 0
        val nearEnd = selection.max > text.length - WINDOW_GUARD && windowEnd < length
        val tooLong = text.length > WINDOW_MAX
        if (!nearStart && !nearEnd && !tooLong) return
        // Where the cursor is, closely enough to decide which chunks the window should cover. It
        // has to be answered before committing, or every keystroke near an edge would commit; the
        // exact offset is taken afterwards, off a window slice that has caught up with the field.
        val char = selection.min
        val near = (windowStart + content.encoding.encodedLength(text, 0, char)).coerceIn(0, content.length)
        val index = chunkIndexAt(chunks, near)
        reach(index)
        if (!tooLong && chunks[windowFirst(index)] == windowStart && windowLast(index) == windowEnd) return
        commit()
        open(windowStart + windowSlice.byteAt(char.coerceAtMost(windowSlice.text.length)))
        changed()
    }

    /**
     * Whether the row drawing [start] is the one being asked to take focus: true once per cutting
     * of the window, so the field takes focus when it appears but not every time scrolling brings
     * it back. A row left over from the window's last position does not match, and so cannot take
     * the answer away with it.
     *
     * Only asks. What answers is [focusTaken], once the field really holds focus — because the row
     * that asks may be disposed, or its effect cancelled by the next cut, before it ever gets
     * there, and a flag cleared on the way out would be a keyboard nobody reopens.
     */
    fun wantsFocus(start: Int) = focusFor >= 0 && focusFor == start

    /** The field is focused, so nothing is waiting for it any more. */
    fun focusTaken(start: Int) {
        if (focusFor == start) focusFor = -1
    }

    /**
     * Applies an edit made while a selection reached outside the window, and answers whether it
     * did. What the field was holding stood in for the whole of [marked], so replacing the whole of
     * the field replaces the whole of the mark — which is select-all and type, select-all and
     * paste, and select-all and delete, none of which the field could have done itself.
     *
     * Anything smaller than the whole field is the field being edited normally, and drops the mark.
     */
    private fun swallow(): Boolean {
        val mark = marked ?: return false
        val now = window.text
        if (change(windowSlice.text, now) == null) {
            // Nothing typed yet. The mark stands for as long as the field is still wholly selected,
            // which is also what says that an edit arriving later replaced the whole of it.
            val selection = window.selection
            if (selection.min != 0 || selection.max != windowSlice.text.length) marked = null
            return false
        }
        // The field's whole text stood in for the mark, so whatever it holds now is what the whole
        // of the mark becomes — and not the run a diff would name, which for a replacement that
        // happens to begin or end the way the old text did would leave part of the mark standing.
        marked = null
        val typed = content.encoding.encode(now, 0, now.length)
        val from = minOf(mark.min, windowStart)
        val to = maxOf(mark.max, windowStart + windowSlice.bytes)
        val delta = content.replace(from, to, typed)
        chunks = if (whole) intArrayOf(0)
        else respan(chunks, from, to, delta, content, content.encoding, content.length)
        visibleFrom = from
        windowStart = -1
        open(from + typed.size)
        list?.requestScrollToItem(rowsNow().rowAt(windowStart))
        changed()
        return true
    }

    /**
     * Writes whatever the field has changed into the document, as a change of its own.
     *
     * Called for every change to the field, so that every state the text passes through is in the
     * change list. The keyboard's own undo is not the only thing that can take text back, and text
     * the document never saw cannot be brought back by anything — which is the whole of why this
     * no longer waits for the window to have to move.
     *
     * What goes in is the run the typing actually touched and not the whole window — the window is
     * a few thousand bytes and almost none of it was typed, and writing all of it back would fill
     * the typed buffer with text nobody typed and leave the file's own bytes for that run behind,
     * which is exactly what byte pieces exist to avoid.
     *
     * The rows are not told. See [behind].
     */
    private fun record() {
        if (windowStart < 0) return
        val now = window.text
        val change = change(windowSlice.text, now) ?: return
        val from = windowStart + windowSlice.byteAt(change.from)
        val to = windowStart + windowSlice.byteAt(change.was)
        val typed = content.encoding.encode(now, change.from, change.now)
        val at = SystemClock.elapsedRealtime()
        val joins = lastFrom >= 0 && at - lastAt < PAUSE_MILLIS && (from == lastTo || to == lastFrom)
        content.replace(from, to, typed, joins)
        windowSlice = windowSlice.replacing(change.from, change.was, now.subSequence(change.from, change.now), typed.size)
        lastAt = at
        lastFrom = from
        lastTo = from + typed.size
        behind = true
    }

    /**
     * Brings the rows up to what the document holds, without closing the window: the field keeps
     * its text, its selection and its focus, and only the offsets around it move.
     *
     * The whole of the window's range is re-indexed rather than the run that was typed, because by
     * now that may be a hundred separate changes and they are all inside it anyway.
     */
    private fun commit() {
        record()
        if (windowStart < 0 || !behind) return
        val to = windowEnd
        val delta = recorded
        windowEnd = to + delta
        // Strictly past the window, not merely at it: text inserted at the top row's start belongs
        // to that row rather than above it, and the window's own start is where such text lands.
        if (visibleFrom > windowStart && visibleFrom >= to) visibleFrom += delta
        chunks = if (whole) intArrayOf(0)
        else respan(chunks, windowStart, to, delta, content, content.encoding, content.length)
        behind = false
        version++
    }

    /**
     * Puts the window around [offset]. It takes [WINDOW_CHUNKS] chunks either side of the one the
     * cursor is in, except that it never reaches back past [visibleFrom] — see [reach], which is
     * what makes room for it to.
     */
    @OptIn(ExperimentalFoundationApi::class)
    private fun open(offset: Int, selection: TextRange? = null) {
        val at = offset.coerceIn(0, content.length)
        val index = chunkIndexAt(chunks, at)
        windowStart = chunks[windowFirst(index)]
        windowEnd = windowLast(index)
        val full = content.slice(windowStart, windowEnd)
        val slice = full.dropLast(trailingBreak(full.text))
        windowBreak = full.bytes - slice.bytes
        windowSlice = slice
        val local = if (selection == null) {
            TextRange(slice.charAt(at - windowStart))
        } else {
            TextRange(
                slice.charAt(selection.start - windowStart),
                slice.charAt(selection.end - windowStart),
            )
        }
        window.edit {
            replace(0, length, slice.text)
            this.selection = local
        }
        // The field keeps its own history of what it held, which after a cut is somebody else's
        // text at somebody else's offsets. The document's own history is what undo walks, and the
        // keys that ask for one are taken before the field ever sees them — see [undoKey].
        window.undoState.clearHistory()
        behind = false
        // Nothing typed after the window moved carries on from anything typed before it.
        lastFrom = -1
        focusFor = windowStart
        opened++
    }

    /**
     * Gives the window room to reach back over the row the list is holding its place by.
     *
     * The list finds its place again by the key of the top row, so a window that covered that row
     * would leave it with nothing to find. Scrolling up by the part of that row which is already
     * above the screen, and one pixel more, makes the row before it the one being held — which the
     * window may then start at, keeping the key alive. It is a nudge of a few lines, because the
     * cursor is by then near the top of that row and the screen is showing the top of it too, and
     * after it nothing jumps: the window draws that row's text exactly where the row drew it.
     *
     * Refused when the row is scrolled far enough off the top that the nudge would not be a nudge —
     * tapping into the last visible line of a long row. The window then keeps the reach it has, and
     * gets the rest the moment an edit brings the cursor near its start.
     */
    private suspend fun reach(index: Int) {
        val list = this.list ?: return
        if (chunks[(index - WINDOW_CHUNKS).coerceAtLeast(0)] >= visibleFrom) return
        val offset = list.firstVisibleItemScrollOffset
        val viewport = list.layoutInfo.viewportSize.height
        if (viewport <= 0 || offset > viewport / 3) return
        list.scrollBy(-(offset + 1).toFloat())
        noteVisible()
    }

    /**
     * Scrolls until the cursor is on screen.
     *
     * Nothing else does it. The field lays out inside a list item and the list is what scrolls, so
     * a cursor walking off the top of the screen is invisible to both — which is what deleting
     * backwards does, since the text below the cursor moves up and the cursor does not.
     *
     * It is also what keeps [reach] able to work at all: the nudge it makes is the part of the top
     * row that is already above the screen, and that is only small while the cursor is near the top
     * of the screen as well as near the start of the window.
     */
    private suspend fun showCursor() {
        val list = this.list ?: return
        val layout = this.layout ?: return
        val viewport = list.layoutInfo.viewportSize.height
        if (viewport <= 0) return
        val line = layout.getLineForOffset(window.selection.max.coerceIn(0, layout.layoutInput.text.length))
        val top = layout.getLineTop(line).toInt()
        val bottom = layout.getLineBottom(line).toInt()
        val row = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == windowStart }
        if (row == null) {
            // Not on screen at all, so there is nothing to scroll by — go to it instead.
            list.scrollToItem(rowsNow().rowAt(windowStart), (top - viewport / 3).coerceAtLeast(0))
        } else if (row.offset + top < 0) {
            list.scrollBy((row.offset + top - viewport / 4).toFloat())
        } else if (row.offset + bottom > viewport) {
            list.scrollBy((row.offset + bottom - viewport + viewport / 4).toFloat())
        } else {
            return
        }
        noteVisible()
    }

    /** Where the list is now, which every programmatic scroll has to leave true. */
    private fun noteVisible() {
        val visible = list?.layoutInfo?.visibleItemsInfo ?: return
        (visible.firstOrNull()?.key as? Int)?.let { visibleFrom = it }
        (visible.lastOrNull { it.key is Int }?.key as? Int)?.let { visibleTo = it }
    }

    /** First chunk of a window holding the cursor's chunk [index], clamped as [reach] describes. */
    private fun windowFirst(index: Int): Int {
        val anchor = chunkIndexAt(chunks, visibleFrom.coerceIn(0, length))
        return (index - WINDOW_CHUNKS).coerceIn(minOf(anchor, index), index)
    }

    /**
     * Where a window holding the cursor's chunk [index] ends. In the rows' own offsets, like the
     * chunk index it reads: asked before a commit it has to answer about the window standing there
     * now, and after one the two are the same thing anyway.
     */
    private fun windowLast(index: Int): Int =
        chunkEndAt(chunks, (index + WINDOW_CHUNKS).coerceAtMost(chunks.size - 1), length)

    private fun rowsNow() = Rows(chunks, length, windowStart, windowEnd)

    private fun changed() {
        version++
        track()
    }

    /** What the buttons say, which is now only ever what the document says: see [record]. */
    private fun track() {
        canUndo = content.canUndo
        canRedo = content.canRedo
    }

    /** An offset past an open window has moved by whatever the window's own length has changed by. */
    private fun shifted(offset: Int): Int {
        if (windowStart < 0 || offset < windowEnd) return offset
        val text = window.text
        val now = content.encoding.encodedLength(text, 0, text.length) + windowBreak
        return offset + now - (windowEnd - windowStart)
    }

    override fun close() = content.close()
}

/**
 * Which rows the list draws: every chunk, except that the ones the window covers are one row.
 *
 * Worked out from the chunk index rather than held as a list of rows. A list is one object per
 * chunk, built again on every edit, and a chunk is two kilobytes — so on a large document that is
 * hundreds of thousands of objects and megabytes of garbage per keystroke, for an answer that is
 * two comparisons and an addition.
 */
private class Rows(
    private val chunks: IntArray,
    private val length: Int,
    private val windowStart: Int,
    private val windowEnd: Int,
) {

    /** The chunk the window starts at, and how many rows after it the window stands in for. */
    private val first = if (windowStart < 0) -1 else chunkIndexAt(chunks, windowStart)

    private val covered =
        if (windowStart < 0) 0
        else chunkIndexAt(chunks, (windowEnd - 1).coerceAtLeast(windowStart)) - first

    val count = chunks.size - covered

    fun editable(row: Int) = row == first

    fun start(row: Int) = if (row == first) windowStart else chunks[chunkOf(row)]

    fun end(row: Int) = if (row == first) windowEnd else chunkEndAt(chunks, chunkOf(row), length)

    /** Which row the byte at [offset] is drawn in. */
    fun rowAt(offset: Int): Int {
        val index = chunkIndexAt(chunks, offset.coerceIn(0, length))
        return when {
            first < 0 || index <= first -> index
            index <= first + covered -> first
            else -> index - covered
        }
    }

    private fun chunkOf(row: Int) = if (first < 0 || row <= first) row else row + covered
}

@Composable
fun DocumentEditor(state: DocumentState, modifier: Modifier = Modifier) {
    val rows = remember(state.version, state.chunks, state.windowStart, state.windowEnd) {
        Rows(state.chunks, state.length, state.windowStart, state.windowEnd)
    }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    DisposableEffect(state, list) {
        state.drawnIn(list)
        onDispose { state.drawnIn(null) }
    }
    LaunchedEffect(state.generation) { list.scrollToItem(0) }
    LaunchedEffect(list, state) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo }
            .collect { visible ->
                (visible.firstOrNull()?.key as? Int)?.let { state.visibleFrom = it }
                (visible.lastOrNull { it.key is Int }?.key as? Int)?.let { state.visibleTo = it }
            }
    }
    // Every change to the field, and not only every move of the cursor: deleting forwards moves no
    // cursor and a paste moves it nowhere near an edge, and both still have to move the window.
    // Collected here rather than in the row, so it goes on running while the row is recomposed.
    LaunchedEffect(state) {
        snapshotFlow { state.window.text to state.window.selection }.collect { state.follow() }
    }
    // The ground under the rows, which is the rest of the screen whenever the document is shorter
    // than one — a document just opened, or a new one, where it is nearly all of it. A row and the
    // field each consume their own taps and the list consumes a drag, so what is left to arrive
    // here is a tap that landed on nothing, and the end of the document is what lies under it.
    val onGround = Modifier.pointerInput(state) {
        detectTapGestures { scope.launch { state.focus(state.length) } }
    }
    // Every row is keyed on where it starts, the window included, so a key always means the same
    // place in the document. Keying the window on itself instead would let the list follow it
    // across the file: with the window at the top of the screen, tapping anything below would take
    // the anchor along and land the tap wherever the window went.
    LazyColumn(modifier.then(onGround), state = list) {
        items(rows.count, key = { rows.start(it) }) { index ->
            if (rows.editable(index)) WindowRow(state, rows.start(index))
            else ChunkRow(state, rows.start(index), rows.end(index), scope)
        }
        // Room to scroll the last line clear of the bottom edge. It takes no taps of its own: it is
        // ground like the rest, and the list behind it is what answers for all of it.
        item(key = "tail") {
            Spacer(Modifier.fillMaxWidth().height(TAIL))
        }
    }
}

@Composable
private fun ChunkRow(state: DocumentState, start: Int, end: Int, scope: CoroutineScope) {
    val slice = remember(state.version, start, end) {
        val full = state.content.slice(start, end)
        full.dropLast(trailingBreak(full.text))
    }
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val ground = LocalTextSelectionColors.current.backgroundColor
    // A selection reaching across the document is drawn here, because the field holding the window
    // is only ever holding part of it. Nothing is built for the rows it does not cover, which on a
    // document of any size is nearly all of them.
    val marked = state.marked
    val shown = remember(slice, marked, ground) {
        val mark = marked
        if (mark == null || mark.max <= start || mark.min >= end) null
        else buildAnnotatedString {
            append(slice.text)
            addStyle(SpanStyle(background = ground), slice.charAt(mark.min - start), slice.charAt(mark.max - start))
        }
    }
    val style = LocalTextStyle.current.copy(color = LocalContentColor.current)
    val onLayout = { result: TextLayoutResult -> layout.value = result }
    val modifier = Modifier
        .fillMaxWidth()
        .pointerInput(slice) {
            detectTapGestures(
                onTap = { at ->
                    layout.value?.let {
                        scope.launch { state.focus(start + slice.byteAt(it.getOffsetForPosition(at))) }
                    }
                },
                onLongPress = { at ->
                    layout.value?.let {
                        val char = it.getOffsetForPosition(at)
                        val word = wordAt(slice.text, char)
                        scope.launch {
                            state.focus(
                                start + slice.byteAt(char),
                                TextRange(start + slice.byteAt(word.start), start + slice.byteAt(word.end)),
                            )
                        }
                    }
                },
            )
        }
    if (shown == null) {
        BasicText(text = slice.text, modifier = modifier, style = style, onTextLayout = onLayout)
    } else {
        BasicText(text = shown, modifier = modifier, style = style, onTextLayout = onLayout)
    }
}

@Composable
private fun WindowRow(state: DocumentState, start: Int) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.opened, start) {
        if (state.wantsFocus(start)) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
            // Asking for focus raises the keyboard only where the field did not already have it,
            // which is most of the time: the window is keyed on where it starts, so moving it
            // composes a new field. A document of one empty line is the case where it does not
            // move at all, and there a tap would put the cursor back with no keyboard under it.
            keyboard?.show()
        }
    }
    BasicTextField(
        state = state.window,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged { if (it.isFocused) state.focusTaken(start) }
            .onPreviewKeyEvent { event -> undoKey(event, state, scope) },
        textStyle = LocalTextStyle.current.copy(color = LocalContentColor.current),
        onTextLayout = { result -> result()?.let { state.laidOut(it) } },
        cursorBrush = SolidColor(LocalContentColor.current),
    )
}

/**
 * The keyboard's own undo and redo, taken before the field can answer them.
 *
 * They arrive as ordinary key events — ctrl+Z and ctrl+Y — and a text field answers them out of a
 * history of its own: scratch that every cut of the window throws away, and that the document knows
 * nothing about. Where the two met, typing was destroyed for good, because text the field took back
 * had never been written into the document and the field's way forward went with the next cut. So
 * they belong to the change list exactly as the toolbar's own buttons do, and the field is left with
 * no history to answer out of.
 *
 * Both directions are consumed whichever way they arrive, key up included, so that nothing of them
 * reaches the field. Ctrl+shift+Z is taken as a redo alongside ctrl+Y: it is what the other half of
 * the world's keyboards send, and it means nothing here otherwise.
 */
private fun undoKey(event: KeyEvent, state: DocumentState, scope: CoroutineScope): Boolean {
    if (!event.isCtrlPressed) return false
    val forward = when (event.key) {
        Key.Y -> true
        Key.Z -> event.isShiftPressed
        else -> return false
    }
    if (event.type == KeyEventType.KeyDown) {
        scope.launch { if (forward) state.redo() else state.undo() }
    }
    return true
}

/** What a long press selects: the run of word characters under it, or nothing if there is none. */
private fun wordAt(text: CharSequence, offset: Int): TextRange {
    fun word(char: Char) = char.isLetterOrDigit() || char == '_'
    var start = offset
    var end = offset
    while (start > 0 && word(text[start - 1])) start--
    while (end < text.length && word(text[end])) end++
    return TextRange(start, end)
}
