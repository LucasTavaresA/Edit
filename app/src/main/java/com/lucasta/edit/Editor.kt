package com.lucasta.edit

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
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
 * Documents at least this long are indexed on a background thread, so that opening one never
 * freezes the UI. Below it the hop off the main thread and back costs more than the scan does: a
 * 64 KB file indexes in 15-30 ms, where a cold dispatcher thread takes a few hundred milliseconds
 * to get going and hand the answer back.
 */
private const val INDEX_OFF_THREAD = 256 * 1024

/** Blank space under the last line, so the end of a file is not stuck against the bottom edge. */
private val TAIL = 160.dp

/**
 * The document, plus the slice of it that is currently editable.
 *
 * The document is a [Content]: the file where it lies plus the changes made to it, addressed in
 * bytes. Nothing here holds the text of the file — a row is decoded when it is drawn and thrown
 * away when it scrolls off, and what is in memory measures the editing rather than the file.
 *
 * On top of it sits a window — a few chunks around the cursor, held in a [TextFieldState] — which
 * is what actually takes input. Every other chunk on screen is laid out read-only, and everything
 * off screen is not laid out at all. The window is committed back into the document whenever it
 * moves, so an edit is never confined to where it started: only one drag-selection is, and only at
 * the window's edges.
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

    val window = TextFieldState()

    /** Range of the document the window stands in for, or -1 when nothing is focused. */
    var windowStart by mutableIntStateOf(-1)
        private set

    var windowEnd by mutableIntStateOf(-1)
        private set

    /** What the window was given, which is what an edit to it is measured against. */
    private var windowSlice = Slice("", 0, Encoding.Utf8, intArrayOf(0, 0))

    /** The line break at the end of the window's range, in bytes, which the field does not hold. */
    private var windowBreak = 0

    /**
     * Where the row at the top of the screen starts, kept current by [DocumentEditor].
     *
     * The list holds its scroll position by remembering the key of that row and finding it again
     * after the rows change. A window that swallowed that row would leave it with nothing to hold
     * on to, and it would fall back on the row's old number — which by then points somewhere else,
     * because the window it just left expanded back into chunks and the one it opened collapsed a
     * few into one. That is the jump: tap near the top of the screen and the text lurches by a
     * couple of chunks, and then the cursor is dragged back into view at whichever edge it went
     * out of. So the window is never allowed to start above this row. See [open].
     *
     * Not a snapshot state: it changes with every scrolled pixel and nothing is drawn from it.
     */
    var visibleFrom = 0

    private var focusWanted = false

    /** Set while the document is being written out, when it must not be changed. */
    private var writing = false

    /**
     * One chunk for the whole document, which is what encrypted files get: the window then covers
     * everything, every edit is an ordinary edit in one text field, and the layout is the whole
     * document's the way it was before any of this. It only works because such a document is
     * capped at [MAX_ENCRYPTED_BYTES].
     */
    private var whole = false

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
        windowBreak = 0
        windowSlice = Slice("", 0, value.encoding, intArrayOf(0, 0))
        visibleFrom = 0
        content = value
        chunks = starts
        generation++
        version++
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
        }
    }

    /** Puts the cursor at [offset], moving the window there. Both offsets are the document's bytes. */
    fun focus(offset: Int, selection: TextRange? = null) {
        if (writing) return
        val at = shifted(offset)
        val range = selection?.let { TextRange(shifted(it.start), shifted(it.end)) }
        commit()
        open(at, range)
    }

    /**
     * Keeps the cursor clear of the window edges, so that typing, deleting and moving never run out
     * of editable text. Left alone while a selection is being dragged, since moving the window
     * under a drag would fight the gesture, and left alone when the window is already where it
     * would reopen — against the top of the screen it has nowhere to go, and reopening it there
     * would rewrite the field's text under the cursor on every keystroke for nothing.
     */
    fun follow() {
        if (writing || windowStart < 0) return
        val selection = window.selection
        if (!selection.collapsed) return
        val nearStart = selection.min < WINDOW_GUARD && windowStart > 0
        val nearEnd = selection.max > window.text.length - WINDOW_GUARD && windowEnd < content.length
        if (!nearStart && !nearEnd) return
        // Where the cursor is, closely enough to decide which chunks the window should cover. It
        // has to be answered before committing, or every keystroke near an edge would commit; the
        // exact offset is taken afterwards, off a window slice that has caught up with the field.
        val char = selection.min
        val near = windowStart + content.encoding.encodedLength(window.text, 0, char)
        if (chunks[windowFirst(chunkIndexAt(chunks, near))] == windowStart) return
        commit()
        open(windowStart + windowSlice.byteAt(char.coerceAtMost(windowSlice.text.length)))
    }

    /**
     * True once per opening of the window, so the field takes focus when it appears but not every
     * time scrolling brings it back.
     */
    fun consumeFocus(): Boolean {
        val wanted = focusWanted
        focusWanted = false
        return wanted
    }

    /**
     * Writes what the window changed back into the document without closing it: the field keeps its
     * text, its selection and its focus, and only the offsets around it move.
     *
     * What goes in is the run the typing actually touched and not the whole window — the window is
     * a few thousand bytes and almost none of it was typed, and committing all of it would fill the
     * typed buffer with text nobody typed and leave the file's own bytes for that run behind, which
     * is exactly what byte pieces exist to avoid.
     */
    private fun commit() {
        if (windowStart < 0) return
        val now = window.text
        val change = change(windowSlice.text, now) ?: return
        val from = windowStart + windowSlice.byteAt(change.from)
        val to = windowStart + windowSlice.byteAt(change.was)
        val typed = content.encoding.encode(now, change.from, change.now)
        val delta = content.replace(from, to, typed)

        windowEnd += delta
        if (visibleFrom >= to) visibleFrom += delta
        windowSlice = windowSlice.replacing(change.from, change.was, now.subSequence(change.from, change.now), typed.size)
        chunks = if (whole) intArrayOf(0) else respan(chunks, from, to, delta, content, content.encoding, content.length)
        version++
    }

    /**
     * Puts the window around [offset]. It takes [WINDOW_CHUNKS] chunks either side of the one the
     * cursor is in, except that it never reaches back past [visibleFrom]: the row the list is
     * anchored on has to survive the move, and a row the window covers stops being a row. Giving up
     * chunks before the cursor costs backward slack — see the note on [visibleFrom] for what is
     * being bought with it — and the ones after the cursor are untouched, so a window that opens
     * against the top of the screen still has its full reach forward.
     */
    private fun open(offset: Int, selection: TextRange? = null) {
        val at = offset.coerceIn(0, content.length)
        val index = chunkIndexAt(chunks, at)
        val first = windowFirst(index)
        val last = (index + WINDOW_CHUNKS).coerceAtMost(chunks.size - 1)
        windowStart = chunks[first]
        windowEnd = chunkEndAt(chunks, last, content.length)
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
        focusWanted = true
    }

    /** First chunk of a window holding the cursor's chunk [index], clamped as [open] describes. */
    private fun windowFirst(index: Int): Int {
        val anchor = chunkIndexAt(chunks, visibleFrom.coerceIn(0, content.length))
        return (index - WINDOW_CHUNKS).coerceIn(minOf(anchor, index), index)
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

/** One row of the list: a read-only chunk, or the editable window standing in for several. */
private class Row(val start: Int, val end: Int, val editable: Boolean)

private fun rowsOf(chunks: IntArray, length: Int, windowStart: Int, windowEnd: Int): List<Row> {
    val rows = ArrayList<Row>(chunks.size)
    for (index in chunks.indices) {
        val start = chunks[index]
        when {
            // Tested before the read-only case, and not after it, because an empty document is a
            // window of no length: it starts where it ends, and asking first whether the chunk is
            // outside the window would put it outside its own.
            windowStart >= 0 && start == windowStart -> rows.add(Row(windowStart, windowEnd, true))
            windowStart < 0 || start < windowStart || start >= windowEnd ->
                rows.add(Row(start, chunkEndAt(chunks, index, length), false))
        }
    }
    return rows
}

@Composable
fun DocumentEditor(state: DocumentState, modifier: Modifier = Modifier) {
    val rows = remember(state.version, state.chunks, state.windowStart, state.windowEnd) {
        rowsOf(state.chunks, state.content.length, state.windowStart, state.windowEnd)
    }
    val list = rememberLazyListState()
    LaunchedEffect(state.generation) { list.scrollToItem(0) }
    LaunchedEffect(list, state) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.firstOrNull()?.key }
            .collect { key -> (key as? Int)?.let { state.visibleFrom = it } }
    }
    // Every row is keyed on where it starts, the window included, so a key always means the same
    // place in the document. Keying the window on itself instead would let the list follow it
    // across the file: with the window at the top of the screen, tapping anything below would take
    // the anchor along and land the tap wherever the window went.
    LazyColumn(modifier, state = list) {
        items(rows.size, key = { rows[it].start }) { index ->
            val row = rows[index]
            if (row.editable) WindowRow(state) else ChunkRow(state, row)
        }
        item(key = "tail") {
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(TAIL)
                    .pointerInput(state) { detectTapGestures { state.focus(state.content.length) } }
            )
        }
    }
}

@Composable
private fun ChunkRow(state: DocumentState, row: Row) {
    val slice = remember(state.version, row.start, row.end) {
        val full = state.content.slice(row.start, row.end)
        full.dropLast(trailingBreak(full.text))
    }
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    BasicText(
        text = slice.text,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(slice) {
                detectTapGestures(
                    onTap = { at ->
                        layout.value?.let {
                            state.focus(row.start + slice.byteAt(it.getOffsetForPosition(at)))
                        }
                    },
                    onLongPress = { at ->
                        layout.value?.let {
                            val char = it.getOffsetForPosition(at)
                            val word = wordAt(slice.text, char)
                            state.focus(
                                row.start + slice.byteAt(char),
                                TextRange(
                                    row.start + slice.byteAt(word.start),
                                    row.start + slice.byteAt(word.end),
                                ),
                            )
                        }
                    },
                )
            },
        style = LocalTextStyle.current.copy(color = LocalContentColor.current),
        onTextLayout = { layout.value = it },
    )
}

@Composable
private fun WindowRow(state: DocumentState) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.windowStart) {
        if (state.consumeFocus()) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
    }
    LaunchedEffect(state) { snapshotFlow { state.window.selection }.collect { state.follow() } }
    BasicTextField(
        state = state.window,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        textStyle = LocalTextStyle.current.copy(color = LocalContentColor.current),
        cursorBrush = SolidColor(LocalContentColor.current),
    )
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
