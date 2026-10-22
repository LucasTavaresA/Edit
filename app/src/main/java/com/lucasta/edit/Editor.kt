package com.lucasta.edit

import android.os.SystemClock
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.dp
import java.io.Closeable
import java.io.File
import java.text.BreakIterator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Chunks either side of the cursor that are editable at once. The window is the only text a
 * keystroke has to lay out again, so this is the dial between how long a keystroke takes and how
 * often typing has to cut the window: at [CHUNK_TARGET] each, one chunk of slack is about 6 KB of
 * text, roughly a hundred lines, and roughly 25 ms of layout per keystroke. How far a selection
 * reaches has nothing to do with it; see [DocumentState.marked].
 */
private const val WINDOW_CHUNKS = 1

/** How close to the edge of the window the cursor may get before the window moves with it. */
private const val WINDOW_GUARD = 256

/**
 * How much text the window may hold before it is cut back to size whatever the cursor is doing.
 * Text put in the middle of the window — a paste — moves neither edge nearer the cursor, so without
 * this the field would go on holding everything pasted into it and laying all of it out again on
 * every keystroke.
 *
 * Lowering it does not make the window smaller. A window is whole chunks and never fewer than one,
 * so set under about a chunk this is exceeded for ever: every change cuts the window again and
 * disposes the field, which fails in ways no real value can. It sits just above the size a window
 * has anyway, so to reach it, grow the document with real pastes.
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
 * How long a stretch of typing may pause before what comes next is a change of its own, measured
 * from the last keystroke. Every keystroke is recorded either way, so this decides only how many of
 * them one press of undo walks back.
 */
private const val PAUSE_MILLIS = 1_000L

/** Blank space under the last line, so the end of a file is not stuck against the bottom edge. */
private val TAIL = 160.dp

/**
 * The document, plus the slice of it that is currently editable.
 *
 * The document is a [Content], addressed in bytes. A row is decoded when it is drawn and thrown
 * away when it scrolls off, so nothing here holds the text of the file.
 *
 * The window — a few chunks around the cursor, held in a [TextFieldState] — is what takes input.
 * Every other chunk on screen is laid out read-only, and everything off screen is not laid out at
 * all. The window is cut again around the cursor whenever the cursor nears an edge of it or it has
 * been filled past [WINDOW_MAX]. A selection is never the field's, whatever made it and wherever it
 * reaches: it is held as [marked].
 *
 * Every offset in here is a byte offset into the document, except the window's own, which are
 * characters; [Slice] crosses between the two.
 *
 * [spill] is where the document it starts with keeps its typed buffer once that is too large for
 * memory.
 */
@Stable
class DocumentState(spill: File?) : Closeable {

    var content: Content by mutableStateOf(Content.empty(spill))
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

    /** Bumped whenever the window is cut again or tapped, which is when the field wants focus back. */
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
     * The selection, in the document's bytes, from where it was started to where it has been
     * carried — the end is the one that moves, and may come before the start — or null when there
     * is none.
     *
     * A selection is always this and never the field's. The field holds a few kilobytes and a
     * selection may reach across a gigabyte, so the rows draw it, the editor's own handles carry it
     * and [swallow] applies an edit made over it, while the field holds only the part of it the
     * window covers, [parked]. Nothing is decoded, laid out or copied to hold one, whatever it
     * covers.
     *
     * While one stands the rows are in step with the document: whatever makes one commits first,
     * and whatever the field then does to its text is applied to the selection instead.
     *
     * A touch makes one and so does the keyboard. A selection the keyboard makes or moves in the
     * field — a swipe from backspace, its select keys — becomes this one in [swallow], and since
     * the field goes on holding exactly what the keyboard set, the keyboard reads back what it
     * set: it builds every step of such a gesture on the selection it last set and acts on what it
     * reads, so a field answering with anything else leaves it working on a selection that is not
     * there.
     */
    var marked: TextRange? by mutableStateOf(null)
        private set

    /**
     * Whether a touch made [marked], or last carried it. Handles and the menu belong to a touch; a
     * selection the keyboard makes or moves shows only its highlight, as a text field's own does,
     * so a swipe across the keyboard never brings a menu up over the text.
     */
    var touched by mutableStateOf(false)
        private set

    /** What the field holds while [marked] stands; see [park]. */
    private var parked: TextRange? = null

    /**
     * Where the field's cursor stood when the mark was made, in its own characters, which is where
     * it stays while the window holds none of the mark.
     */
    private var rest = 0

    /** Set by [deselect] so that putting the cursor back where it stood does not scroll to it. */
    private var still = false

    /** Keeps one key's step from starting before the last has finished, since it reads the screen. */
    private val stepping = Mutex()

    /**
     * Across where a step up or down aims, and where the last one landed: carried on while they
     * follow one another, so crossing a short line does not pull the next ones over to its end.
     */
    private var goalX = 0f

    private var goalAt = -1

    /**
     * What each row other than the window last drew, by where it starts, so that a touch can be
     * read as a place in the document; kept by the rows themselves, and read through [drawnAt].
     *
     * Not snapshot state: it is written while the rows are laid out, and everything that reads it
     * also reads the list's layout, which changes whenever it does.
     */
    private val drawn = HashMap<Int, Drawn>()

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
     * Every keystroke goes into the document as it happens — see [record] — but it lands inside
     * the window, so every row outside it draws the same text as before and only its offsets have
     * moved. Telling them means re-indexing and shifting every key after the cursor, per keystroke,
     * so the rows go on addressing the document as it was when the window opened until [commit]
     * moves them on.
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
     * current by [DocumentEditor]. The window never starts above the top row — see [reach].
     *
     * Not snapshot state: it changes with every scrolled pixel and nothing is drawn from it.
     */
    var visibleFrom = 0

    var visibleTo = 0

    /**
     * Which window the field is to take focus for, or -1 when nobody is waiting for it.
     *
     * Addressed to a window rather than kept as a flag, because cutting the window leaves two rows
     * alive at once: the one being replaced re-runs its effects before the new one has composed, so
     * a flag would be taken by the row about to be disposed and go with it, leaving the field that
     * survives unfocused and the keyboard shut.
     */
    private var focusFor = -1

    /**
     * One chunk for the whole document, which is what encrypted files get: the window covers
     * everything and every edit is an ordinary edit in one text field. Only possible because such a
     * document is capped at [MAX_ENCRYPTED_BYTES].
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

    fun drew(start: Int, row: Drawn) {
        drawn[start] = row
    }

    fun forget(start: Int) {
        drawn.remove(start)
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
        unmark()
        visibleFrom = 0
        visibleTo = 0
        content = value
        chunks = starts
        generation++
        changed()
    }

    /**
     * Saves the document as it stands, window edits included, through [write], and moves the base on
     * once the file holds it.
     *
     * The take is made here on the main thread, in one step with nothing that waits, so no keyboard
     * event can land half way through it; [write] writes from it alone, off the main thread. The
     * base moves back here, still within the save's turn, since the next save's take reads what it
     * sets.
     */
    suspend fun save(write: suspend (Take) -> Result<Collision>): Result<Collision> {
        // An edit made over a mark and not yet applied is applied first, or the take would have the
        // field's few kilobytes stand in for the whole of the mark.
        swallow()
        commit()
        val content = content
        val take = content.take()
        return try {
            write(take).onSuccess { if (it == Collision.None) content.saved(take) }
        } finally {
            take.close()
        }
    }

    /** Puts the cursor at [offset], moving the window there. The offset is the document's bytes. */
    suspend fun focus(offset: Int) {
        // An edit made over the mark is applied first, or committing would record it as the field's
        // few kilobytes changing on their own. It moves the document, so [offset] names nothing now.
        if (swallow()) return
        val at = shifted(offset)
        unmark()
        commit()
        val to = at.coerceIn(0, content.length)
        reach(chunkIndexAt(chunks, to))
        open(to)
        changed()
    }

    /**
     * Selects the whole document, which is two offsets however long it is. [touched] says whether
     * a touch asked for it, which brings up the handles and the menu with it.
     */
    fun selectAll(touched: Boolean = true) {
        swallow()
        // Before the emptiness test, not after: a document typed from new holds everything it has
        // in the field, and asking the document how long it is before that is written back answers
        // for a document nobody has typed into.
        commit()
        if (content.length == 0) return
        // Nothing scrolls. The field selects all of itself, and no cursor is drawn or followed
        // while a selection stands, so neither the end of the document nor of the window is
        // brought on screen.
        mark(TextRange(0, content.length), touched = touched)
    }

    /**
     * Carries the moving end of the selection by [step], or starts a selection at the cursor when
     * there is none, and scrolls to where it went: shift and an arrow, a page or a line's end. It
     * reaches anywhere in the document, since the rows rather than the field say where it goes.
     */
    suspend fun select(step: Step) = stepping.withLock {
        if (swallow() || windowStart < 0) return@withLock
        commit()
        val mark = marked
        val anchor = mark?.start ?: byteOf(window.selection.end)
        val from = mark?.end ?: anchor
        val to = stepFrom(from, step)
        when {
            to == anchor -> if (mark != null) collapseAt(anchor)
            to != from || mark == null -> mark(TextRange(anchor, to), touched = false)
        }
        reveal(to)
    }

    /**
     * A key that moves the cursor without shift. A selection is let go of instead: the cursor goes
     * to its start for a [step] back and to its end otherwise. Without one, the cursor moves by
     * [step] as the rows have it, which is how a page or the document's ends are reached: the field
     * would take those only as far as the window.
     */
    suspend fun move(step: Step) = stepping.withLock {
        if (swallow()) return@withLock
        val mark = marked
        if (mark != null) return@withLock collapseAt(if (step.back) mark.min else mark.max)
        if (windowStart < 0) return@withLock
        commit()
        collapseAt(stepFrom(byteOf(window.selection.end), step))
    }

    /** Lets go of the selection and puts the cursor at [offset], moving the window only to reach it. */
    private suspend fun collapseAt(offset: Int) {
        unmark()
        val at = offset - windowStart
        if (windowStart >= 0 && at in 0..windowSlice.bytes) {
            window.edit { selection = TextRange(windowSlice.charAt(at)) }
        } else {
            focus(offset)
        }
    }

    /** Where a [step] from [from] lands, as the document and, for a line or a page, the rows have it. */
    private suspend fun stepFrom(from: Int, step: Step): Int {
        // Only a step up or down carries on across from where the last one aimed.
        if (step != Step.Up && step != Step.Down && step != Step.PageUp && step != Step.PageDown) goalAt = -1
        return when (step) {
            Step.Left, Step.Right -> characterFrom(from, step.back)
            Step.WordLeft, Step.WordRight -> wordFrom(from, step.back)
            Step.Start -> 0
            Step.End -> content.length
            else -> lineFrom(from, step) ?: from
        }
    }

    /**
     * The next place a character starts, from [from], as the platform counts characters: a letter
     * with its accents, an emoji with its modifiers, a line break of two characters as one.
     */
    private fun characterFrom(from: Int, back: Boolean): Int {
        if (if (back) from <= 0 else from >= content.length) return from
        // The chunk the step crosses into is all it needs: a chunk ends at a line break or a space,
        // and no character goes on past either.
        val index = chunkIndexAt(chunks, if (back) from - 1 else from)
        val start = chunks[index]
        val slice = content.slice(start, chunkEndAt(chunks, index, content.length))
        val characters = BreakIterator.getCharacterInstance()
        characters.setText(slice.text)
        val char = slice.charAt(from - start)
        val next = if (back) characters.preceding(char) else characters.following(char)
        if (next == BreakIterator.DONE) return if (back) start else start + slice.bytes
        return start + slice.byteAt(next)
    }

    /**
     * Where a step a word at a time from [from] lands: past the run of other characters next to it
     * and the word after that, which is how ctrl and an arrow go.
     */
    private fun wordFrom(from: Int, back: Boolean): Int {
        var at = from
        // A word never goes on past a chunk edge, which is a line break or a space, so only the run
        // before it can, and one chunk further is as far as that run is followed.
        repeat(2) {
            if (if (back) at <= 0 else at >= content.length) return at
            val index = chunkIndexAt(chunks, if (back) at - 1 else at)
            val start = chunks[index]
            val slice = content.slice(start, chunkEndAt(chunks, index, content.length))
            val text = slice.text
            var char = slice.charAt(at - start)
            val found: Boolean
            if (back) {
                while (char > 0 && !isWord(text[char - 1])) char--
                found = char > 0
                while (char > 0 && isWord(text[char - 1])) char--
            } else {
                while (char < text.length && !isWord(text[char])) char++
                found = char < text.length
                while (char < text.length && isWord(text[char])) char++
            }
            at = start + slice.byteAt(char)
            if (found) return at
        }
        return at
    }

    /**
     * Where a step a line or a page up or down, or to either end of a line, lands from [from], as
     * the rows lay the text out, or null when the screen cannot say. Only rows on screen are laid
     * out, so [from] is brought on screen first, and the text is scrolled under the step when it
     * goes past an edge.
     */
    private suspend fun lineFrom(from: Int, step: Step): Int? {
        val list = this.list ?: return null
        reveal(from)
        if (step == Step.LineStart || step == Step.LineEnd) {
            val (item, row) = rowOf(from) ?: return null
            val start = item.key as Int
            val layout = row.layout
            val line = layout.getLineForOffset(row.slice.charAt(from - start).coerceAtMost(layout.layoutInput.text.length))
            val to = if (step == Step.LineStart) layout.getLineStart(line) else layout.getLineEnd(line, visibleEnd = true)
            return start + row.slice.byteAt(to)
        }
        val caret = caretAt(from) ?: return null
        val viewport = list.layoutInfo.viewportSize.height
        if (viewport <= 0) return null
        val x = if (goalAt == from) goalX else caret.left
        val page = step == Step.PageUp || step == Step.PageDown
        var y = when (step) {
            Step.Up -> caret.top - caret.height / 2
            Step.Down -> caret.bottom + caret.height / 2
            Step.PageUp -> caret.center.y - viewport
            else -> caret.center.y + viewport
        }
        if (y < 0 || y >= viewport) {
            // A page keeps the line it lands on where the one it left was, except where the
            // document ends first; a line is brought a quarter of the screen in, as the cursor is.
            val by = when {
                page -> y - caret.center.y
                y < 0 -> y - viewport / 4
                else -> y - viewport + viewport / 4
            }
            y -= list.scrollBy(by)
            noteVisible()
        }
        val spot = spotAt(Offset(x, y)) ?: return null
        val to = offsetOf(spot)
        goalX = x
        goalAt = to
        return to
    }

    /**
     * Scrolls until the byte at [offset] is on screen, as [showCursor] does for the cursor: the
     * moving end of a selection is what a key carries, and no cursor is there to follow.
     */
    private suspend fun reveal(offset: Int) {
        val list = this.list ?: return
        // Twice at most: a row off screen is gone to first, and only then can the line in it be
        // found.
        repeat(2) {
            val viewport = list.layoutInfo.viewportSize.height
            if (viewport <= 0) return
            val caret = caretAt(offset)
            when {
                caret == null -> list.scrollToItem(rowsNow().rowAt(offset))
                caret.top < 0 -> list.scrollBy(caret.top - viewport / 4)
                caret.bottom > viewport -> list.scrollBy(caret.bottom - viewport + viewport / 4)
                else -> return
            }
            noteVisible()
        }
    }

    /** Walks the last change back, or forward again, and puts the window where it happened. */
    suspend fun undo() = step(back = true)

    suspend fun redo() = step(back = false)

    private suspend fun step(back: Boolean) {
        swallow()
        unmark()
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
     * Called for every change to the field, not only for every move of the cursor: deleting
     * forwards does not move the cursor and pasting does not move it towards an edge, and both
     * still have to move the window.
     *
     * Left alone while a selection stands. The field holds only the part of it the window covers
     * then, so nothing it does needs the window moved, and the screen is not to be pulled over to
     * wherever that part is.
     */
    suspend fun follow() {
        // Taken whatever happens below, so that it answers for this change and no later one.
        val quiet = still
        still = false
        if (windowStart < 0) return
        if (swallow()) return
        if (marked != null) return
        // Before anything that suspends, so what reaches the change list is what the field holds
        // now. The flow conflates, so keystrokes faster than a frame are one change, which loses
        // nothing.
        record()
        track()
        // The layout that says where the cursor is drawn is a frame behind whatever was just typed,
        // and both of the things below read it. Waiting for it also means that however fast input
        // arrives, this runs once a frame rather than once a keystroke.
        withFrameNanos { }
        if (windowStart < 0) return
        // Before deciding whether to cut, so that a cut never lands on a field that is off screen.
        // Scrolling away from the cursor does not dispose the field either: a focused field pins
        // the list item it sits in (its FocusableNode does, in foundation 1.12, and nothing
        // documents it), so the keyboard stays up and the next keystroke brings it back here.
        if (!quiet) showCursor()
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
     * Whether the row drawing [start] is the one being asked to take focus: true once per cut of
     * the window, so the field takes focus when it appears but not every time scrolling brings it
     * back. Only asks; [focusTaken] answers, once the field really holds focus, since the row
     * asking may be disposed, or its effect cancelled by the next cut, before it gets there.
     */
    fun wantsFocus(start: Int) = focusFor >= 0 && focusFor == start

    /** The field is focused, so nothing is waiting for it any more. */
    fun focusTaken(start: Int) {
        if (focusFor == start) focusFor = -1
    }

    /**
     * Brings the mark up to whatever the field has done since it was last looked at, and answers
     * whether that moved the document, which leaves any offset taken before it naming nothing.
     *
     * A selection the field holds that the mark does not is the keyboard's doing, and becomes the
     * mark: one end it moved moves the mark's, and an end the window cut short stays where it is
     * beyond it. A cursor in place of one drops the mark.
     *
     * An edit made over the mark — typing, pasting or deleting over it — is applied to the whole of
     * it, and that is what moves the document. Where the field held all of it, the edit is the
     * field's own and goes into the document as any other does, which keeps the window, and the
     * keyboard's session, where they are. Otherwise what the field put where its selection stood
     * replaces the mark, and the window opens again around it.
     */
    fun swallow(): Boolean {
        if (windowStart < 0) return false
        val mark = marked
        val selection = window.selection
        if (mark == null) {
            if (selection.collapsed) return false
            // Before reading offsets off the window, which have to answer for what it holds now.
            commit()
            mark(TextRange(byteOf(selection.start), byteOf(selection.end)), touched = false)
            return false
        }
        val now = window.text
        val held = parked
        if (windowSlice.text.contentEquals(now)) {
            if (selection == held) return false
            if (selection.collapsed) {
                unmark()
                return false
            }
            val moved = when {
                held == null || held.collapsed -> TextRange(byteOf(selection.start), byteOf(selection.end))
                selection.min == held.min && selection.max == held.max -> {
                    parked = selection
                    return false
                }
                selection.min == held.min -> TextRange(mark.min, byteOf(selection.max))
                selection.max == held.max -> TextRange(mark.max, byteOf(selection.min))
                else -> TextRange(byteOf(selection.start), byteOf(selection.end))
            }
            mark(moved, touched = false)
            return false
        }
        unmark()
        if (held != null && fits(mark, held)) {
            // Nothing carries on from the typing before it.
            lastFrom = -1
            return false
        }
        replace(mark, typedOver(now, held), reopen = true)
        return true
    }

    /**
     * Whether the field's selection [held] is exactly [mark]: the window holds all of it, and
     * crossing between bytes and characters rounds neither end.
     */
    private fun fits(mark: TextRange, held: TextRange): Boolean {
        if (windowStart < 0) return false
        val from = mark.min - windowStart
        val to = mark.max - windowStart
        if (from < 0 || to > windowSlice.bytes) return false
        val start = windowSlice.charAt(from)
        val end = windowSlice.charAt(to)
        return held.min == start && held.max == end && windowSlice.byteAt(start) == from && windowSlice.byteAt(end) == to
    }

    /**
     * What the keyboard put where the field's selection [held] stood: whatever is now between the
     * text before it and the text after it, where it left both as they were. Should it have changed
     * either, what changed is taken instead.
     */
    private fun typedOver(now: CharSequence, held: TextRange?): CharSequence {
        val was = windowSlice.text
        if (held != null) {
            val tail = was.length - held.max
            val end = now.length - tail
            if (end >= held.min && now.same(0, was, 0, held.min) && now.same(end, was, held.max, tail)) {
                return now.subSequence(held.min, end)
            }
        }
        val change = change(was, now, window.selection.max) ?: return ""
        return now.subSequence(change.from, change.now)
    }

    /**
     * Selects [range], in the document's bytes, from its start to its end, which may come first.
     * Commits before anything else, so that the rows drawing it are in step with the document.
     * [touched] says whether a touch made it; see [DocumentState.touched].
     *
     * With [park] the field takes the part of it the window holds. A drag leaves that until it
     * lets go, rather than tell the keyboard about every character it crosses.
     */
    fun mark(range: TextRange, park: Boolean = true, touched: Boolean = true) {
        commit()
        if (range.collapsed) {
            unmark()
            return
        }
        if (marked == null) rest = window.selection.end
        marked = range
        this.touched = touched
        if (park) this.park()
    }

    /**
     * Puts the part of the mark the window holds into the field as its selection, or a cursor at
     * [rest] when it holds none of it. The field goes on holding exactly what the keyboard set
     * while that is the same stretch, whichever way round, since the keyboard reads back what it
     * set and acts on that.
     */
    fun park() {
        val mark = marked ?: return
        if (windowStart < 0) return
        val bytes = windowSlice.bytes
        val from = mark.start - windowStart
        val to = mark.end - windowStart
        val held = if (mark.max - windowStart <= 0 || mark.min - windowStart >= bytes) {
            TextRange(rest.coerceIn(0, window.text.length))
        } else {
            TextRange(windowSlice.charAt(from.coerceIn(0, bytes)), windowSlice.charAt(to.coerceIn(0, bytes)))
        }
        val now = window.selection
        if (now.min != held.min || now.max != held.max) window.edit { selection = held }
        parked = window.selection
    }

    /**
     * Lets go of the selection. The cursor goes to the end that moved when the screen shows it
     * there, and back where it stood before the selection otherwise, and nothing scrolls to it.
     */
    fun deselect() {
        if (swallow()) return
        val mark = marked ?: return
        unmark()
        if (windowStart < 0) return
        val end = mark.end - windowStart
        val viewport = list?.layoutInfo?.viewportSize?.height ?: 0
        val seen = caretAt(mark.end)?.let { it.top >= 0 && it.bottom <= viewport } == true
        val at = TextRange(if (seen && end in 0..windowSlice.bytes) windowSlice.charAt(end) else rest.coerceIn(0, window.text.length))
        if (window.selection == at) return
        still = true
        window.edit { selection = at }
    }

    private fun unmark() {
        marked = null
        parked = null
    }

    /** What the mark covers, read out of the document, or null when there is none. */
    fun markedText(): String? = marked?.let { content.slice(it.min, it.max).text }

    /** Takes the mark out of the document. [raise] keeps the window, and the keyboard, at the gap. */
    fun cut(raise: Boolean) {
        if (swallow()) return
        val mark = marked ?: return
        unmark()
        if (fits(mark, window.selection)) type("")
        else replace(mark, "", reopen = raise && windowStart >= 0)
    }

    /** Puts [text] over the mark, or at the cursor when there is none, which is an ordinary edit. */
    fun paste(text: CharSequence, raise: Boolean) {
        if (swallow()) return
        val mark = marked
        unmark()
        if (mark != null && !fits(mark, window.selection)) replace(mark, text, reopen = raise && windowStart >= 0)
        else if (windowStart >= 0) type(text)
    }

    /**
     * Puts [text] over the field's selection, as an edit of the field's own and a change of its
     * own. The window and the keyboard's session stay as they are, panels and all, which an edit
     * the document makes around the field cannot promise.
     */
    private fun type(text: CharSequence) {
        lastFrom = -1
        window.edit {
            val at = selection.min
            replace(at, selection.max, text)
            selection = TextRange(at + text.length)
        }
    }

    /** Where the field's character [char] is in the document, while it holds what the window was given. */
    private fun byteOf(char: Int) = windowStart + windowSlice.byteAt(char)

    /**
     * Puts [text] where [range] was, as a change of its own, and opens the window at the end of it
     * when [reopen]; otherwise the window closes, since it may be standing on text that is gone.
     *
     * The list holds its place by the row at the top of the screen, which is untouched when it
     * starts before everything indexed again and has only moved when it starts after it. When the
     * change took it, there is no place left to hold, and the list goes to where the change ends.
     */
    private fun replace(range: TextRange, text: CharSequence, reopen: Boolean) {
        val from = range.min
        val to = range.max
        val typed = content.encoding.encode(text, 0, text.length)
        val was = content.length
        // A window opened anywhere else is another field. Focus going over to it closes the
        // keyboard and opens it again, and the list growing and shrinking under the field that has
        // focus scrolls it to keep that field in view, which leaves the cursor off the screen. So
        // the window stays where it stood whenever the change is inside it.
        val stood = windowStart >= 0 && from >= windowStart && to <= windowEnd
        val start = windowStart
        val end = windowEnd
        // What [respan] indexes again: the chunks the change falls in, widened to whole ones.
        val head = chunks[chunkIndexAt(chunks, from)]
        val tail = chunkEndAt(chunks, chunkIndexAt(chunks, to.coerceAtMost(was)), was)
        val delta = content.replace(from, to, typed)
        chunks = if (whole) intArrayOf(0)
        else respan(chunks, from, to, delta, content, content.encoding, content.length)
        val top = visibleFrom
        val held = top <= head || top >= tail
        visibleFrom = when {
            top <= head -> top
            top >= tail -> top + delta
            else -> chunks[chunkIndexAt(chunks, from)]
        }
        windowStart = -1
        windowEnd = -1
        focusFor = -1
        if (reopen && stood) open(from + typed.size, start, end + delta)
        else if (reopen) open(from + typed.size)
        list?.let {
            if (held) it.requestScrollToItem(rowsNow().rowAt(visibleFrom), it.firstVisibleItemScrollOffset)
            else it.requestScrollToItem(rowsNow().rowAt(from + typed.size))
        }
        changed()
    }

    /**
     * Which character of which row is drawn at [position], in the list's own coordinates, or null
     * when the screen is not showing the document as it stands: the frame after it changed, before
     * the rows have caught up. Anything below the last row is the end of it, which is what a touch
     * on the empty screen under a short document means.
     *
     * An edit made over the mark is applied first, since until it is the screen is about to change
     * under whatever is being pointed at.
     */
    fun spotAt(position: Offset): Spot? {
        if (swallow()) return null
        val info = list?.layoutInfo ?: return null
        val y = position.y.coerceIn(0f, (info.viewportSize.height - 1).coerceAtLeast(0).toFloat())
        val item = info.visibleItemsInfo.lastOrNull { it.key is Int && it.offset <= y } ?: return null
        val start = item.key as Int
        val row = drawnAt(start) ?: return null
        val text = row.layout.layoutInput.text
        val char = if (y >= item.offset + item.size) text.length
        else row.layout.getOffsetForPosition(Offset(position.x, y - item.offset))
        return Spot(start, char, text, if (start == windowStart) null else row.slice)
    }

    /**
     * Where [char] of the row at [spot] is in the document. The window's own bytes are known only
     * once what the field holds is recorded, so asking about it records that first; a row past the
     * window has moved by whatever the window has gained, which [shifted] adds.
     */
    fun offsetOf(spot: Spot, char: Int = spot.char): Int {
        val slice = spot.slice ?: return run {
            record()
            windowStart + windowSlice.byteAt(char)
        }
        return shifted(spot.row + slice.byteAt(char))
    }

    /**
     * A tap at [spot], which puts the cursor there. Inside the window that is only the field's
     * cursor moving, so the window stays and the keyboard keeps its session; anywhere else the
     * window moves to it.
     */
    suspend fun tap(spot: Spot) {
        if (swallow()) return
        unmark()
        val slice = spot.slice
        if (slice != null) {
            focus(spot.row + slice.byteAt(spot.char))
            return
        }
        window.edit { selection = TextRange(spot.char.coerceIn(0, length)) }
        focusFor = windowStart
        opened++
    }

    /**
     * Where the caret before the byte at [offset] is drawn, as a rect a line high and of no width in
     * the list's own coordinates, or null when its row is not on screen. Meant for the ends of the
     * mark, which is when the rows are in step with the document.
     */
    fun caretAt(offset: Int): Rect? {
        val (item, row) = rowOf(offset) ?: return null
        return caret(row.layout, row.slice.charAt(offset - (item.key as Int)), item.offset)
    }

    /** The row drawing the byte at [offset] and where the list has it, while it is on screen. */
    private fun rowOf(offset: Int): Pair<LazyListItemInfo, Drawn>? {
        val items = list?.layoutInfo?.visibleItemsInfo ?: return null
        val item = items.lastOrNull { val key = it.key; key is Int && key <= offset } ?: return null
        val row = drawnAt(item.key as Int) ?: return null
        // Where the row ends is where the next one starts, and that one is not on screen.
        if (offset >= row.end && row.end < length) return null
        return item to row
    }

    /** Where the field's cursor is drawn, in the list's own coordinates, or null when it is not. */
    fun cursorCaret(): Rect? {
        if (windowStart < 0) return null
        val item = list?.layoutInfo?.visibleItemsInfo?.firstOrNull { it.key == windowStart } ?: return null
        val row = drawnAt(windowStart) ?: return null
        return caret(row.layout, window.selection.start, item.offset)
    }

    private fun caret(layout: TextLayoutResult, char: Int, top: Int): Rect {
        val at = char.coerceIn(0, layout.layoutInput.text.length)
        val line = layout.getLineForOffset(at)
        val x = layout.getHorizontalPosition(at, usePrimaryDirection = true)
        return Rect(x, top + layout.getLineTop(line), x, top + layout.getLineBottom(line))
    }

    /** The part of the mark the window holds, in the field's own characters, or null. */
    fun markedInWindow(): TextRange? {
        val mark = marked ?: return null
        if (windowStart < 0 || mark.max <= windowStart || mark.min >= windowEnd) return null
        return TextRange(windowSlice.charAt(mark.min - windowStart), windowSlice.charAt(mark.max - windowStart))
    }

    /** Whether the mark goes on over the line break the window ends at, which the field does not hold. */
    fun markedPastWindow(): Boolean {
        val mark = marked ?: return false
        val text = windowStart + windowSlice.bytes
        return windowStart >= 0 && windowBreak > 0 && mark.min <= text && mark.max > text
    }

    /**
     * What the row starting at [start] drew, while the screen still shows it. A row is out of date
     * once the document has moved on from the version it drew, and the window once the field holds
     * other text than it laid out.
     */
    private fun drawnAt(start: Int): Drawn? {
        if (start != windowStart) return drawn[start]?.takeIf { it.version == version }
        val layout = layout ?: return null
        if (!layout.layoutInput.text.text.contentEquals(window.text)) return null
        return Drawn(version, windowEnd, windowSlice, layout)
    }

    /**
     * Writes the run the field has changed, as [change] names it, into the document as a change of
     * its own. Called for every change to the field, since text the document never saw cannot be
     * brought back by anything. The rows are not told; see [behind].
     */
    private fun record() {
        if (windowStart < 0) return
        val now = window.text
        val change = change(windowSlice.text, now, window.selection.max) ?: return
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
     *
     * With [from] it starts there instead and ends with the chunk holding the byte before [to],
     * which is how a window is opened again where it already stood.
     */
    @OptIn(ExperimentalFoundationApi::class)
    private fun open(offset: Int, from: Int = -1, to: Int = -1) {
        val at = offset.coerceIn(0, content.length)
        val index = chunkIndexAt(chunks, at)
        // Both ends are worked out with the old window closed. [length] reads the window, and one
        // with a new start, an old end and the slice it was given before answers for neither of
        // them: the end of a window reaching the last chunk comes out short, or below zero.
        windowStart = -1
        val start = if (from >= 0) from else chunks[windowFirst(index)]
        val end = if (from >= 0) chunkEndAt(chunks, chunkIndexAt(chunks, (to - 1).coerceAtLeast(from)), length)
        else windowLast(index)
        windowStart = start
        windowEnd = end
        val full = content.slice(windowStart, windowEnd)
        val slice = full.dropLast(trailingBreak(full.text))
        windowBreak = full.bytes - slice.bytes
        windowSlice = slice
        window.edit {
            replace(0, length, slice.text)
            selection = TextRange(slice.charAt(at - windowStart))
        }
        // The field keeps its own history of what it held, which after a cut is somebody else's
        // text at somebody else's offsets. The document's own history is what undo walks, and the
        // keys that ask for one are taken before the field ever sees them — see [keys].
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
     * The list finds its place again by the key of the top row. A window covering that row would
     * leave it nothing to find, and it would fall back on the row's old number, which by then
     * points somewhere else. Scrolling up by the part of that row already above the screen, and
     * one pixel more, makes the row before it the one being held, and the window may then start at
     * it. That stays a nudge of a few lines only because [showCursor] keeps the cursor on screen.
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
     * Scrolls until the cursor is on screen. The field lays out inside a list item and the list is
     * what scrolls, so neither knows where the cursor is, and deleting backwards walks it off the
     * top: the text below it moves up and the cursor does not.
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
 * Worked out from the chunk index rather than held as a list, which on a large document would be
 * hundreds of thousands of objects built again on every edit.
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

/**
 * What a row other than the window drew, kept so that a touch on it can be read as a place in the
 * document: the bytes it drew, where its range ends — past the line break it does not draw — and
 * the layout it drew them with, all as of [version].
 */
class Drawn(val version: Int, val end: Int, val slice: Slice, val layout: TextLayoutResult)

/**
 * A place on the screen as the rows drew it: the row, by where it starts, the character of what it
 * drew, and that text. [slice] is what a row other than the window drew it from; the window's
 * bytes are known only once what it holds is recorded, which [DocumentState.offsetOf] sees to.
 */
class Spot(val row: Int, val char: Int, val text: CharSequence, val slice: Slice?)

/**
 * @param refuse says why something asked for was not done, which today is a copy or a cut larger
 *   than the clipboard takes.
 */
@Composable
fun DocumentEditor(state: DocumentState, modifier: Modifier = Modifier, refuse: (String) -> Unit = {}) {
    val rows = remember(state.version, state.chunks, state.windowStart, state.windowEnd) {
        Rows(state.chunks, state.length, state.windowStart, state.windowEnd)
    }
    val list = rememberLazyListState()
    val overscroll = rememberOverscrollEffect()
    val clip = rememberClip(state, refuse)
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
    // Collected here rather than in the row, so it goes on running while the row is recomposed.
    LaunchedEffect(state) {
        snapshotFlow { state.window.text to state.window.selection }.collect { state.follow() }
    }
    // Every row is keyed on where it starts, the window included, so a key always means the same
    // place in the document. Keying the window on itself instead would let the list follow it
    // across the file: with the window at the top of the screen, tapping anything below would take
    // the anchor along and land the tap wherever the window went.
    //
    // The list takes no touches itself. [SelectionLayer] lies over it and takes all of them, the
    // scrolling included, since a selection's handles lie over the text and a touch on one is a
    // touch on both.
    Box(modifier) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            userScrollEnabled = false,
            overscrollEffect = overscroll,
        ) {
            items(rows.count, key = { rows.start(it) }) { index ->
                if (rows.editable(index)) WindowRow(state, rows.start(index), clip)
                else ChunkRow(state, rows.start(index), rows.end(index))
            }
            // Room to scroll the last line clear of the bottom edge. A touch on it is a touch below
            // the last row, which is the end of the document.
            item(key = "tail") {
                Spacer(Modifier.fillMaxWidth().height(TAIL))
            }
        }
        SelectionLayer(state, list, overscroll, clip, Modifier.matchParentSize())
    }
}

@Composable
private fun ChunkRow(state: DocumentState, start: Int, end: Int) {
    val version = state.version
    val slice = remember(version, start, end) {
        val full = state.content.slice(start, end)
        full.dropLast(trailingBreak(full.text))
    }
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    // A touch on this row means whatever it drew, so that is kept where touches are read. Again on
    // every version, since a row whose text did not change is not laid out again to say so.
    SideEffect {
        layout.value?.takeIf { it.layoutInput.text.text == slice.text }?.let {
            state.drew(start, Drawn(version, end, slice, it))
        }
    }
    DisposableEffect(state, start) { onDispose { state.forget(start) } }
    val ground = LocalTextSelectionColors.current.backgroundColor
    BasicText(
        text = slice.text,
        modifier = Modifier
            .fillMaxWidth()
            // Drawn under the text rather than styled into it, so that carrying the mark across a
            // row lays nothing out again. Nothing is built for the rows it does not cover, which
            // is nearly all of them.
            .drawWithCache {
                val path = state.marked?.takeIf { it.max > start && it.min < end }?.let { mark ->
                    val text = start + slice.bytes
                    val past = mark.min <= text && mark.max > text
                    layout.value?.pathOf(slice.charAt(mark.min - start), slice.charAt(mark.max - start), past)
                }
                onDrawBehind { if (path != null) drawPath(path, ground) }
            },
        style = LocalTextStyle.current.copy(color = LocalContentColor.current),
        onTextLayout = { result ->
            layout.value = result
            state.drew(start, Drawn(version, end, slice, result))
        },
    )
}

@Composable
private fun WindowRow(state: DocumentState, start: Int, clip: Clip) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val colors = LocalTextSelectionColors.current
    val ground = colors.backgroundColor
    val intercept = remember(state, clip) {
        PlatformTextInputInterceptor { request, next ->
            next.startInputMethod { info -> Connection(request.createInputConnection(info), state, clip) }
        }
    }
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
    // The field holds the part of a selection the window covers, and is never to show it as its
    // own: the mark is drawn under its text below, as the rows draw it, and the handles and the
    // menu are the editor's. The field shows its own only in touch mode, and never hears a touch to
    // leave it once told it is driven by keys.
    CompositionLocalProvider(
        LocalInputModeManager provides KeysOnly,
        LocalTextSelectionColors provides TextSelectionColors(colors.handleColor, Color.Transparent),
    ) {
        InterceptPlatformTextInput(intercept) {
            BasicTextField(
                state = state.window,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onFocusChanged { if (it.isFocused) state.focusTaken(start) }
                    .onPreviewKeyEvent { event -> keys(event, state, clip, scope) }
                    // The mark, drawn under the field's text the way the rows draw it under theirs.
                    .drawWithCache {
                        val path = state.markedInWindow()?.let { layout.value?.pathOf(it.min, it.max, state.markedPastWindow()) }
                        onDrawBehind { if (path != null) drawPath(path, ground) }
                    },
                textStyle = LocalTextStyle.current.copy(color = LocalContentColor.current),
                onTextLayout = { result ->
                    result()?.let {
                        state.laidOut(it)
                        layout.value = it
                    }
                },
                // No cursor while a selection stands, and an unspecified brush is also what stops
                // the field scrolling to one: the ends of its selection are wherever the window cut
                // the mark short, not anywhere the screen should go.
                cursorBrush = SolidColor(if (state.marked == null) LocalContentColor.current else Color.Unspecified),
            )
        }
    }
}

/** What the window's field is told about how it is driven: by keys only; see [WindowRow]. */
private object KeysOnly : InputModeManager {
    override val inputMode = InputMode.Keyboard

    override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
}

/**
 * The keyboard's connection to the window's field, but for the keyboard asking the field to
 * select all, copy, cut or paste. Select all selects the document instead: the field would select
 * itself, which is the window and nothing past it. Copy, cut and paste are the editor's, answered
 * before the keyboard's next call: the field would answer them with a key, which arrives after
 * whatever the keyboard does next, and a keyboard that lets go of the selection straight after
 * copying it, as Samsung's does, leaves nothing to copy by then. Keyboards that send ctrl and a
 * letter instead reach [keys].
 */
private class Connection(target: InputConnection, private val state: DocumentState, private val clip: Clip) :
    InputConnectionWrapper(target, false) {

    override fun performContextMenuAction(id: Int): Boolean {
        when (id) {
            android.R.id.selectAll -> state.selectAll(touched = false)
            android.R.id.copy -> {
                // Whatever the keyboard selected a moment ago is what is copied.
                state.swallow()
                clip.copy()
            }
            android.R.id.cut -> clip.cut()
            android.R.id.paste -> clip.paste()
            else -> return super.performContextMenuAction(id)
        }
        return true
    }
}

/**
 * The outline of the characters [from] to [to], or null when that is nothing. With [past] it goes on
 * to the far edge of the last line, the way a selection over a line break is drawn inside a row: a
 * row ends at a break it does not hold, so without it the last line of every row would stop short.
 */
private fun TextLayoutResult.pathOf(from: Int, to: Int, past: Boolean): Path? {
    // A row's text and its layout can be a frame apart, so neither end may pass what was laid out.
    val length = layoutInput.text.length
    val start = from.coerceIn(0, length)
    val end = to.coerceIn(0, length)
    val path = if (start < end) getPathForRange(start, end) else null
    if (!past) return path
    val line = lineCount - 1
    val top = getLineTop(line)
    val bottom = getLineBottom(line)
    val rest = if (getParagraphDirection(length) == ResolvedTextDirection.Rtl) Rect(0f, top, getLineLeft(line), bottom)
    else Rect(getLineRight(line), top, size.width.toFloat(), bottom)
    return (path ?: Path()).apply { addRect(rest) }
}

/**
 * One step of a key that moves the cursor, or the moving end of a selection with shift held.
 * [back] is towards the start of the document.
 */
enum class Step(val back: Boolean) {
    Left(true), Right(false), WordLeft(true), WordRight(false), Up(true), Down(false),
    PageUp(true), PageDown(false), LineStart(true), LineEnd(false), Start(true), End(false),
}

private fun stepOf(event: KeyEvent): Step? = when (event.key) {
    Key.DirectionLeft -> if (event.isCtrlPressed) Step.WordLeft else Step.Left
    Key.DirectionRight -> if (event.isCtrlPressed) Step.WordRight else Step.Right
    Key.DirectionUp -> Step.Up
    Key.DirectionDown -> Step.Down
    Key.PageUp -> Step.PageUp
    Key.PageDown -> Step.PageDown
    Key.MoveHome -> if (event.isCtrlPressed) Step.Start else Step.LineStart
    Key.MoveEnd -> if (event.isCtrlPressed) Step.End else Step.LineEnd
    else -> null
}

/**
 * The keys that belong to the document rather than to the field.
 *
 * Undo and redo, ctrl+Z and ctrl+Y, are taken before the field can answer them out of a history of
 * its own, which every cut of the window throws away and the document knows nothing about. They
 * belong to the change list, as the toolbar's buttons do. Select all, ctrl+A, selects the document
 * rather than the field. Copy, cut and paste, as ctrl and a letter or as keys of their own, are
 * the mark's and the editor's clipboard's, which refuses a copy too large to carry and survives a
 * clipboard too large to read.
 *
 * Shift and a key that moves the cursor carries the mark, wherever it goes, since the field could
 * carry it only as far as the window. Such a key without shift lets go of a selection, and is
 * otherwise the field's own cursor moving, but for a page and the document's ends, which the field
 * would take only as far as the window. Back lets go of a selection before it leaves anything.
 *
 * Consumed whichever way they arrive, key up included, so nothing of them reaches the field.
 * Ctrl+shift+Z is taken as a redo too: it is what the other half of the world's keyboards send.
 */
private fun keys(event: KeyEvent, state: DocumentState, clip: Clip, scope: CoroutineScope): Boolean {
    val key = event.key
    val step = stepOf(event)
    val ctrl = event.isCtrlPressed
    val copy = key == Key.Copy || ctrl && key == Key.C
    val cut = key == Key.Cut || ctrl && key == Key.X
    val paste = key == Key.Paste || ctrl && key == Key.V
    val history = ctrl && (key == Key.Y || key == Key.Z)
    val all = ctrl && key == Key.A
    if (step == null && !copy && !cut && !paste && !history && !all && key != Key.Back) return false
    // Whatever the keyboard selected a moment ago is what the key acts on.
    state.swallow()
    val marked = state.marked != null
    val far = step == Step.PageUp || step == Step.PageDown || step == Step.Start || step == Step.End
    val taken = when {
        step != null -> event.isShiftPressed || marked || far
        copy || cut || key == Key.Back -> marked
        else -> true
    }
    if (!taken) return false
    // Back is let go of as it comes up, as a text field's own selection is, so the activity never
    // sees the half of it that would close it.
    if (key == Key.Back) {
        if (event.type == KeyEventType.KeyUp) state.deselect()
        return true
    }
    if (event.type != KeyEventType.KeyDown) return true
    when {
        step != null -> scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (event.isShiftPressed) state.select(step) else state.move(step)
        }
        history -> scope.launch { if (key == Key.Y || event.isShiftPressed) state.redo() else state.undo() }
        all -> state.selectAll(touched = false)
        copy -> clip.copy()
        cut -> clip.cut()
        paste -> clip.paste()
    }
    return true
}

/** Whether [count] characters of this from [at] are those of [other] from [from]. */
private fun CharSequence.same(at: Int, other: CharSequence, from: Int, count: Int): Boolean {
    for (i in 0 until count) if (this[at + i] != other[from + i]) return false
    return true
}
