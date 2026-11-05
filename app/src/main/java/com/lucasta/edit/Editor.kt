package com.lucasta.edit

import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import java.io.Closeable
import java.io.File
import java.text.BreakIterator
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
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
 * this the window would go on holding everything pasted into it and laying all of it out again on
 * every keystroke.
 *
 * Lowering it does not make the window smaller. A window is whole chunks and never fewer than one,
 * so set under about a chunk this is exceeded for ever and every change cuts the window again. It
 * sits just above the size a window has anyway, so to reach it, grow the document with real pastes.
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

/** How the cursor is drawn: as wide as a text field's, and blinking at the same pace. */
private val CURSOR_WIDTH = 2.dp

private const val CURSOR_BLINK_MILLIS = 500L

/**
 * How far a pinch may take the text from the theme's size either way, and the steps it goes in:
 * every step lays out the rows on screen again, so a pinch moves through a few dozen of them
 * rather than one a frame.
 */
private const val SCALE_MIN = 0.5f
private const val SCALE_MAX = 3f
private const val SCALE_STEP = 1 / 32f

/**
 * The document, plus the slice of it that is currently editable.
 *
 * The document is a [Content], addressed in bytes. A row is decoded when it is drawn and thrown
 * away when it scrolls off, so nothing here holds the text of the file.
 *
 * The window — a few chunks around the cursor, held in a [WindowText] — is the one row that is
 * edited in place and the one that holds the cursor. Every other chunk on screen is laid out
 * read-only, and everything off screen is not laid out at all. The window is cut again around the
 * cursor whenever the cursor nears an edge of it or it has been filled past [WINDOW_MAX]. A
 * selection is never the window's, whatever made it and wherever it reaches: it is held as
 * [marked].
 *
 * The keyboard is connected to the document rather than to the window, through [Keyboard], and
 * addresses it in characters counted from its start, which is the only way a keyboard can address
 * anything. So where the window sits, and its being cut, is nothing a keyboard can see.
 *
 * Every offset in here is a byte offset into the document, except the window's own, which are
 * characters, and the keyboard's, which are characters too; [Slice] crosses between the first two,
 * and [charOf] and [offsetAtChar] between the document's bytes and the keyboard's characters.
 *
 * [spill] is where the document it starts with keeps its typed buffer once that is too large for
 * memory.
 */
@Stable
class DocumentState(spill: File?) : Closeable {

    var content: Content by mutableStateOf(Content.empty(spill))
        private set

    var index: Index by mutableStateOf(Index(intArrayOf(0), intArrayOf(0, 0)))
        private set

    /** Where each chunk starts, in bytes. */
    val chunks: IntArray get() = index.starts

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

    /**
     * Bumped whenever the user puts the cursor somewhere — a tap, a key, an undo — which is when the
     * editor takes focus and the keyboard comes up. Cutting the window is not one of them.
     */
    var opened by mutableIntStateOf(0)
        private set

    val window = WindowText()

    /** Whether the editor holds focus, which is when the cursor is drawn. Kept by [Keyboard]. */
    var focused by mutableStateOf(false)

    /**
     * The word the keyboard is composing, in the document's characters as the keyboard counts them,
     * or null. Kept in those rather than in the window's, so that cutting the window leaves it
     * where it is. Anything the editor changes itself lets go of it, as a text field does.
     */
    var composition: TextRange? by mutableStateOf(null)

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
     * A selection is always this. The window holds a few kilobytes and a selection may reach
     * across a gigabyte, so the rows draw it and the editor's own handles carry it. Nothing is
     * decoded, laid out or copied to hold one, whatever it covers.
     *
     * While one stands the rows are in step with the document: whatever makes one commits first.
     *
     * A touch makes one and so does the keyboard. A selection the keyboard sets — a swipe from
     * backspace, its select keys — is this one, set through [selectChars], and what the keyboard
     * reads back is this one too: it builds every step of such a gesture on the selection it last
     * set and acts on what it reads.
     */
    var marked: TextRange? by mutableStateOf(null)
        private set

    /**
     * Whether a drag is carrying [marked], which the keyboard hears of once it lets go — see
     * [park] — rather than at every character it crosses.
     */
    var held by mutableStateOf(false)
        private set

    /**
     * Whether a touch made [marked], or last carried it. Handles and the menu belong to a touch; a
     * selection the keyboard makes or moves shows only its highlight, as a text field's own does,
     * so a swipe across the keyboard never brings a menu up over the text.
     */
    var touched by mutableStateOf(false)
        private set

    /**
     * Where the window's cursor stood when the mark was made, in its own characters, which is where
     * it goes back to when the mark is let go of somewhere off screen; see [deselect].
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

    /** The line break at the end of the window's range, in bytes, which [window] does not hold. */
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
     * One chunk for the whole document, which is what encrypted files get: the window covers
     * everything and every edit is an ordinary edit in one text field. Only possible because such a
     * document is capped at [MAX_ENCRYPTED_BYTES].
     */
    private var whole = false

    /**
     * Where the list of rows stands in this document. The document's rather than the screen's, so
     * that a document put back on screen is where it was left, and nothing of another's place
     * carries over to it.
     */
    val place = LazyListState()

    /**
     * [place] while the document is on screen, and null otherwise, so the window can make room for
     * itself before it moves. A list that is not being drawn has no layout to read or scroll by.
     */
    private var list: LazyListState? = null

    /**
     * The margin the rows are drawn in from either side, in pixels. The editor's own coordinates
     * take it in, so a touch on it is a touch on the text, and a row's do not.
     */
    private var inset = 0f

    /** The window's last text layout, which is the only thing that knows where the cursor is drawn. */
    private var layout: TextLayoutResult? = null

    fun drawnIn(shown: Boolean, inset: Float = 0f) {
        this.list = if (shown) place else null
        this.inset = inset
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
        val indexed = when {
            whole -> oneChunk(value)
            value.length < INDEX_OFF_THREAD -> timed("index") { chunkStarts(value, 0, value.length, value.encoding) }
            else -> withContext(Dispatchers.IO) { timed("index") { chunkStarts(value, 0, value.length, value.encoding) } }
        }
        if (value !== content) content.close()
        this.whole = whole
        windowStart = -1
        windowEnd = -1
        behind = false
        lastFrom = -1
        windowBreak = 0
        windowSlice = Slice("", 0, value.encoding, intArrayOf(0, 0))
        window.set("", 0)
        composition = null
        unmark()
        visibleFrom = 0
        visibleTo = 0
        content = value
        index = indexed
        place.requestScrollToItem(0)
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
        commit()
        val content = content
        val take = content.take()
        return try {
            write(take).onSuccess { if (it == Collision.None) content.saved(take) }
        } finally {
            take.close()
        }
    }

    /**
     * Puts the cursor at [offset], moving the window there. The offset is where the rows have it,
     * which past a window holding typing is not yet where the document does.
     *
     * Committing that typing moves the start of every row past the window, and rows are keyed on
     * where they start, so the list loses the row it holds its place by and falls back on its
     * number, which the window moving has changed: the text jumps, and the cursor is then pulled
     * back from wherever it went. So the place is held by hand, and the nudge [reach] makes comes
     * before the commit, while the list still draws the rows it reads.
     */
    suspend fun focus(offset: Int) {
        unmark()
        reach(chunkIndexAt(chunks, offset.coerceIn(0, length)))
        val at = shifted(offset)
        commit()
        open(at.coerceIn(0, content.length))
        opened++
        list?.let { it.requestScrollToItem(rowsNow().rowAt(visibleFrom), it.firstVisibleItemScrollOffset) }
        changed()
    }

    /**
     * Selects the whole document, which is two offsets however long it is. [touched] says whether
     * a touch asked for it, which brings up the handles and the menu with it.
     */
    fun selectAll(touched: Boolean = true) {
        // Before the emptiness test, not after: a document typed from new holds everything it has
        // in the window, and asking the document how long it is before that is written back answers
        // for a document nobody has typed into.
        commit()
        if (content.length == 0) return
        // Nothing scrolls. No cursor is drawn or followed while a selection stands, so neither the
        // end of the document nor of the window is brought on screen.
        mark(TextRange(0, content.length), touched = touched)
    }

    /**
     * Carries the moving end of the selection by [step], or starts a selection at the cursor when
     * there is none, and scrolls to where it went: shift and an arrow, a page or a line's end. It
     * reaches anywhere in the document, since the rows say where it goes.
     */
    suspend fun select(step: Step) = stepping.withLock {
        if (windowStart < 0) return@withLock
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
     * [step] as the rows have it, which reaches a page away or the document's ends as readily as
     * the next character.
     */
    suspend fun move(step: Step) = stepping.withLock {
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
            window.select(windowSlice.charAt(at))
        } else {
            focus(offset)
        }
    }

    /**
     * Takes out what a [step] from the cursor crosses, or the mark when there is one: backspace and
     * delete, and with ctrl a word of either.
     */
    suspend fun erase(step: Step) = stepping.withLock {
        val mark = marked
        if (mark != null) return@withLock cut(raise = true)
        if (windowStart < 0) return@withLock
        composition = null
        // A character either side is found in the window itself, which is every backspace but the
        // one at its very edge, so that a key held down is typing and commits nothing.
        record()
        val at = window.selection.start
        val near = characterWithin(window.text, at, step)
        if (near != null) {
            if (near != at) type(TextRange(near, at), "", own = false)
            return@withLock
        }
        commit()
        val from = byteOf(at)
        val to = stepFrom(from, step)
        if (to != from) put(TextRange(from, to), "")
    }

    /**
     * Where a step of one character from [at] lands in [text], or null when the step is not one
     * character or would leave it.
     */
    private fun characterWithin(text: String, at: Int, step: Step): Int? {
        if (step != Step.Left && step != Step.Right) return null
        if (if (step.back) at <= 0 else at >= text.length) return null
        val characters = BreakIterator.getCharacterInstance()
        characters.setText(text)
        val next = if (step.back) characters.preceding(at) else characters.following(at)
        return if (next == BreakIterator.DONE) null else next
    }

    /** Puts [text] over the mark, or at the cursor when there is none: typing on a hardware keyboard. */
    fun typeText(text: CharSequence) {
        val mark = marked
        if (mark == null && windowStart < 0) return
        unmark()
        // Recorded rather than committed, which is all reading the cursor's place takes, so that
        // typing commits nothing; a mark has the rows in step already.
        record()
        composition = null
        put(mark ?: TextRange(byteOf(window.selection.end)), text)
    }

    /**
     * Puts [text] over [range], in the document's bytes, as typing: in the window when the window
     * holds all of it, and through the document otherwise, the window opening again at the end of
     * it either way.
     */
    private fun put(range: TextRange, text: CharSequence) {
        val chars = windowChars(range)
        if (chars != null) type(chars, text, own = false)
        else replace(range, text, reopen = true)
    }

    /**
     * [range], in the document's bytes, as the window's own characters, or null when the window
     * does not hold all of it or crossing between the two would round either end.
     */
    private fun windowChars(range: TextRange): TextRange? {
        if (windowStart < 0) return null
        val from = range.min - windowStart
        val to = range.max - windowStart
        if (from < 0 || to > windowSlice.bytes) return null
        val start = windowSlice.charAt(from)
        val end = windowSlice.charAt(to)
        if (windowSlice.byteAt(start) != from || windowSlice.byteAt(end) != to) return null
        return TextRange(start, end)
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
     * The screen under the text got shorter than [was]: brings the cursor back on screen if it was
     * on it before, as a text field does when the keyboard comes up over it. A cursor the user had
     * already scrolled away from is left where it is.
     */
    suspend fun shrunk(was: Int) {
        if (!focused || windowStart < 0 || marked != null) return
        val caret = cursorCaret() ?: return
        if (caret.top >= 0 && caret.bottom <= was) showCursor()
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
        unmark()
        commit()
        composition = null
        lastFrom = -1
        val span = (if (back) content.undo() else content.redo()) ?: return
        val delta = span.now - span.was
        index = if (whole) oneChunk(content)
        else respan(index, span.at, span.at + span.was, delta, content, content.encoding, content.length)
        val seen = span.at in visibleFrom..visibleTo
        if (visibleFrom > span.at && visibleFrom >= span.at + span.was) visibleFrom += delta
        else if (visibleFrom > span.at) visibleFrom = span.at
        windowStart = -1
        reach(chunkIndexAt(chunks, span.at.coerceIn(0, content.length)))
        open(span.at + span.now)
        opened++
        // A change walked back somewhere off screen would look like nothing happening at all.
        if (!seen) list?.scrollToItem(rowsNow().rowAt(windowStart))
        changed()
    }

    /**
     * Keeps the window around the cursor and keeps it small: cut again whenever the cursor comes
     * within [WINDOW_GUARD] of an edge, or whenever more than [WINDOW_MAX] has been put into it.
     *
     * Called for every change to the window, not only for every move of the cursor: deleting
     * forwards does not move the cursor and pasting does not move it towards an edge, and both
     * still have to move the window.
     *
     * Left alone while a selection stands: no cursor is drawn then, and the screen is not to be
     * pulled over to wherever the window is.
     *
     * Nothing the keyboard can see moves when the window is cut. It counts the document from its
     * start, and the cursor and what it is composing are in the same place of the document after a
     * cut as before it.
     */
    suspend fun follow() {
        // Taken whatever happens below, so that it answers for this change and no later one.
        val quiet = still
        still = false
        if (windowStart < 0) return
        if (marked != null) return
        // Before anything that suspends, so what reaches the change list is what the window holds
        // now. The flow conflates, so keystrokes faster than a frame are one change, which loses
        // nothing.
        record()
        track()
        // The layout that says where the cursor is drawn is a frame behind whatever was just typed,
        // and both of the things below read it. Waiting for it also means that however fast input
        // arrives, this runs once a frame rather than once a keystroke.
        withFrameNanos { }
        if (windowStart < 0) return
        if (!quiet) showCursor()
        val selection = window.selection
        if (whole) return
        val text = window.text
        val nearStart = selection.min < WINDOW_GUARD && windowStart > 0
        val nearEnd = selection.max > text.length - WINDOW_GUARD && windowEnd < length
        val tooLong = text.length > WINDOW_MAX
        if (!nearStart && !nearEnd && !tooLong) return
        // Where the cursor is, closely enough to decide which chunks the window should cover. It
        // has to be answered before committing, or every keystroke near an edge would commit; the
        // exact offset is taken afterwards, off a window slice that has caught up with the window.
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
     * Selects [range], in the document's bytes, from its start to its end, which may come first.
     * Commits before anything else, so that the rows drawing it are in step with the document.
     * [touched] says whether a touch made it; see [DocumentState.touched].
     *
     * Without [park] the keyboard is not told yet: a drag tells it once it lets go, through [park],
     * rather than at every character it crosses.
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
        held = !park
    }

    /** Tells the keyboard where the mark a drag carried has got to. */
    fun park() {
        held = false
    }

    /**
     * Lets go of the selection. The cursor goes to the end that moved when the screen shows it
     * there, and back where it stood before the selection otherwise, and nothing scrolls to it.
     */
    fun deselect() {
        val mark = marked ?: return
        unmark()
        if (windowStart < 0) return
        val end = mark.end - windowStart
        val viewport = list?.layoutInfo?.viewportSize?.height ?: 0
        val seen = caretAt(mark.end)?.let { it.top >= 0 && it.bottom <= viewport } == true
        val at = TextRange(if (seen && end in 0..windowSlice.bytes) windowSlice.charAt(end) else rest.coerceIn(0, window.text.length))
        if (window.selection == at) return
        still = true
        window.select(at.start)
    }

    private fun unmark() {
        marked = null
        held = false
    }

    /** What the mark covers, read out of the document, or null when there is none. */
    fun markedText(): String? = marked?.let { content.slice(it.min, it.max).text }

    /** Takes the mark out of the document. [raise] keeps the window, and the keyboard, at the gap. */
    fun cut(raise: Boolean) {
        val mark = marked ?: return
        unmark()
        composition = null
        val chars = windowChars(mark)
        if (chars != null) type(chars, "")
        else replace(mark, "", reopen = raise && windowStart >= 0)
    }

    /** Puts [text] over the mark, or at the cursor when there is none, which is an ordinary edit. */
    fun paste(text: CharSequence, raise: Boolean) {
        val mark = marked
        unmark()
        composition = null
        val chars = if (mark != null) windowChars(mark) else window.selection
        if (mark != null && chars == null) replace(mark, text, reopen = raise && windowStart >= 0)
        else if (windowStart >= 0 && chars != null) type(chars, text)
    }

    /**
     * Puts [text] over the window's characters [chars], and the cursor after it. The window stays
     * where it is. With [own] it is a change of its own, which is what a paste or a cut is, rather
     * than typing that carries on from the typing before it.
     */
    private fun type(chars: TextRange, text: CharSequence, own: Boolean = true) {
        // Before the edit as well as after it, so that what the window held before is not taken
        // into this change.
        record()
        if (own) lastFrom = -1
        window.replace(chars.min, chars.max, text)
        record()
        if (own) lastFrom = -1
    }

    /** Where the window's character [char] is in the document, once what it holds is recorded. */
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
        // What the window holds goes in first: it is about to be read again from the document.
        commit()
        val from = range.min
        val to = range.max
        val typed = content.encoding.encode(text, 0, text.length)
        val was = content.length
        // The window stays where it stood whenever the change is inside it, so that the rows on
        // screen are not laid out again for nothing.
        val stood = windowStart >= 0 && from >= windowStart && to <= windowEnd
        val start = windowStart
        val end = windowEnd
        // What [respan] indexes again: the chunks the change falls in, widened to whole ones.
        val head = chunks[chunkIndexAt(chunks, from)]
        val tail = chunkEndAt(chunks, chunkIndexAt(chunks, to.coerceAtMost(was)), was)
        val delta = content.replace(from, to, typed)
        lastFrom = -1
        index = if (whole) oneChunk(content)
        else respan(index, from, to, delta, content, content.encoding, content.length)
        val top = visibleFrom
        val held = top <= head || top >= tail
        visibleFrom = when {
            top <= head -> top
            top >= tail -> top + delta
            else -> chunks[chunkIndexAt(chunks, from)]
        }
        windowStart = -1
        windowEnd = -1
        if (reopen && stood) open(from + typed.size, start, end + delta)
        else if (reopen) open(from + typed.size)
        list?.let {
            if (held) it.requestScrollToItem(rowsNow().rowAt(visibleFrom), it.firstVisibleItemScrollOffset)
            else it.requestScrollToItem(rowsNow().rowAt(from + typed.size))
        }
        changed()
    }

    /**
     * Which character of which row is drawn at [position], in the editor's own coordinates, or null
     * when the screen is not showing the document as it stands: the frame after it changed, before
     * the rows have caught up. Anything below the last row is the end of it, which is what a touch
     * on the empty screen under a short document means.
     */
    fun spotAt(position: Offset): Spot? {
        val info = list?.layoutInfo ?: return null
        val y = position.y.coerceIn(0f, (info.viewportSize.height - 1).coerceAtLeast(0).toFloat())
        val item = info.visibleItemsInfo.lastOrNull { it.key is Int && it.offset <= y } ?: return null
        val start = item.key as Int
        val row = drawnAt(start) ?: return null
        val text = row.layout.layoutInput.text
        val char = if (y >= item.offset + item.size) text.length
        else row.layout.getOffsetForPosition(Offset(position.x - inset, y - item.offset))
        return Spot(start, char, text, if (start == windowStart) null else row.slice)
    }

    /**
     * Where [char] of the row at [spot] is in the document. The window's own bytes are known only
     * once what the window holds is recorded, so asking about it records that first; a row past the
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
     * A tap at [spot], which puts the cursor there. Inside the window that is only the cursor
     * moving, so the window stays; anywhere else the window moves to it.
     */
    suspend fun tap(spot: Spot) {
        unmark()
        val slice = spot.slice
        if (slice != null) {
            focus(spot.row + slice.byteAt(spot.char))
            return
        }
        window.select(spot.char)
        opened++
    }

    /**
     * Where the caret before the byte at [offset] is drawn, as a rect a line high and of no width in
     * the editor's own coordinates, or null when its row is not on screen. Meant for the ends of the
     * mark, which is when the rows are in step with the document.
     */
    fun caretAt(offset: Int): Rect? {
        val (item, row) = rowOf(offset) ?: return null
        return caret(row.layout, row.slice.charAt(offset - (item.key as Int)), item.offset)
    }

    /**
     * How far down the editor the line holding [char] of the row starting at [row] begins, or null
     * when that row is not on screen as the document stands.
     */
    fun lineTop(row: Int, char: Int): Float? {
        val item = list?.layoutInfo?.visibleItemsInfo?.firstOrNull { it.key == row } ?: return null
        val layout = drawnAt(row)?.layout ?: return null
        val at = char.coerceIn(0, layout.layoutInput.text.length)
        return item.offset + layout.getLineTop(layout.getLineForOffset(at))
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

    /** Where the cursor is drawn, in the editor's own coordinates, or null when it is not. */
    fun cursorCaret(): Rect? {
        if (windowStart < 0) return null
        val item = list?.layoutInfo?.visibleItemsInfo?.firstOrNull { it.key == windowStart } ?: return null
        val row = drawnAt(windowStart) ?: return null
        return caret(row.layout, window.selection.start, item.offset)
    }

    private fun caret(layout: TextLayoutResult, char: Int, top: Int): Rect {
        val at = char.coerceIn(0, layout.layoutInput.text.length)
        val line = layout.getLineForOffset(at)
        val x = inset + layout.getHorizontalPosition(at, usePrimaryDirection = true)
        return Rect(x, top + layout.getLineTop(line), x, top + layout.getLineBottom(line))
    }

    /** The part of the mark the window holds, in the window's own characters, or null. */
    fun markedInWindow(): TextRange? {
        val mark = marked ?: return null
        if (windowStart < 0 || mark.max <= windowStart || mark.min >= windowEnd) return null
        return TextRange(windowSlice.charAt(mark.min - windowStart), windowSlice.charAt(mark.max - windowStart))
    }

    /** Whether the mark goes on over the line break the window ends at, which the window does not hold. */
    fun markedPastWindow(): Boolean {
        val mark = marked ?: return false
        val text = windowStart + windowSlice.bytes
        return windowStart >= 0 && windowBreak > 0 && mark.min <= text && mark.max > text
    }

    /**
     * What the row starting at [start] drew, while the screen still shows it. A row is out of date
     * once the document has moved on from the version it drew, and the window once it holds
     * other text than it laid out.
     */
    private fun drawnAt(start: Int): Drawn? {
        if (start != windowStart) return drawn[start]?.takeIf { it.version == version }
        val layout = layout ?: return null
        if (!layout.layoutInput.text.text.contentEquals(window.text)) return null
        return Drawn(version, windowEnd, windowSlice, layout)
    }

    /**
     * Writes the run the window has changed, as [change] names it, into the document as a change of
     * its own. Called for every change to the window, since text the document never saw cannot be
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
     * Brings the rows up to what the document holds, without closing the window: the window keeps
     * its text and its cursor, and only the offsets around it move.
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
        index = if (whole) oneChunk(content)
        else respan(index, windowStart, to, delta, content, content.encoding, content.length)
        behind = false
        version++
    }

    /**
     * The index of a [whole] document: one chunk however long it is, which is what keeps it one
     * text field, and its characters counted afresh, which its cap keeps cheap.
     */
    private fun oneChunk(value: Content): Index {
        val bytes = value.read(0, value.length)
        return Index(intArrayOf(0), intArrayOf(0, value.encoding.decodedLength(bytes, 0, bytes.size)))
    }

    /**
     * Puts the window around [offset]. It takes [WINDOW_CHUNKS] chunks either side of the one the
     * cursor is in, except that it never reaches back past [visibleFrom] — see [reach], which is
     * what makes room for it to.
     *
     * With [from] it starts there instead and ends with the chunk holding the byte before [to],
     * which is how a window is opened again where it already stood.
     */
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
        window.set(slice.text, slice.charAt(at - windowStart))
        behind = false
        // Typing carries on across a cut as if there had been none: where the last change left the
        // text standing is a place in the document, which a cut does not move.
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
        // Where the cursor is drawn can only be read off a layout of what the window holds now, and
        // a window just opened somewhere else may not have one yet: a row off screen is never laid
        // out until it is gone to, and one on screen is laid out again only in the next frame. The
        // last layout is then another text's, and scrolling by it lands wherever its lines were.
        var tries = 3
        while (true) {
            val row = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == windowStart }
            val fresh = layout?.layoutInput?.text?.text?.contentEquals(window.text) == true
            if (row != null && fresh) break
            if (tries-- == 0) return
            if (row == null) list.scrollToItem(rowsNow().rowAt(windowStart))
            withFrameNanos { }
            if (windowStart < 0) return
        }
        val layout = this.layout ?: return
        val viewport = list.layoutInfo.viewportSize.height
        if (viewport <= 0) return
        val line = layout.getLineForOffset(window.selection.max.coerceIn(0, layout.layoutInput.text.length))
        val top = layout.getLineTop(line).toInt()
        val bottom = layout.getLineBottom(line).toInt()
        val row = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == windowStart } ?: return
        if (row.offset + top < 0) {
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

    /**
     * What the row from [start] to [end] holds, in the rows' own offsets. Past the window the
     * document is ahead of the rows by whatever has been recorded since the last commit, so a row
     * laid out for the first time in between, as scrolling brings it on screen, has to read that much
     * further on, or it draws the tail of the row before it again.
     */
    fun rowText(start: Int, end: Int): Slice {
        val moved = if (windowStart >= 0 && start >= windowEnd) recorded else 0
        return content.slice(start + moved, end + moved)
    }

    /** An offset past an open window has moved by whatever the window's own length has changed by. */
    private fun shifted(offset: Int): Int {
        if (windowStart < 0 || offset < windowEnd) return offset
        val text = window.text
        val now = content.encoding.encodedLength(text, 0, text.length) + windowBreak
        return offset + now - (windowEnd - windowStart)
    }

    /**
     * How many characters come before [offset], a byte of the document, counted from its start as a
     * keyboard counts them.
     */
    fun charOf(offset: Int): Int {
        record()
        if (windowStart < 0 || offset < windowStart) return rowChar(offset)
        val at = offset - windowStart
        val base = rowChar(windowStart)
        if (at <= windowSlice.bytes) return base + windowSlice.charAt(at)
        if (at <= windowSlice.bytes + windowBreak) {
            return base + windowSlice.text.length + (at - windowSlice.bytes) / content.encoding.unitBytes
        }
        return rowChar(offset - recorded) + charShift()
    }

    /** The byte of the document [char] characters in, the other way across from [charOf]. */
    fun offsetAtChar(char: Int): Int {
        val at = char.coerceIn(0, charCount())
        if (windowStart >= 0) {
            val base = rowChar(windowStart)
            val held = windowSlice.text.length
            when {
                at < base -> Unit
                at <= base + held -> return windowStart + windowSlice.byteAt(at - base)
                at <= base + held + breakChars() ->
                    return windowStart + windowSlice.bytes + (at - base - held) * content.encoding.unitBytes
                else -> return rowOffset(at - charShift()) + recorded
            }
        }
        return rowOffset(at)
    }

    /** How long the document is, in characters. */
    fun charCount(): Int {
        record()
        return index.chars.last() + if (windowStart < 0) 0 else charShift()
    }

    /**
     * The document's characters [from] to [to]. Read off whole chunks and the window, never off a
     * run of bytes cut anywhere else, so that no character is cut in half on the way, whatever it
     * takes to encode.
     */
    fun textAt(from: Int, to: Int): String {
        val end = to.coerceAtMost(charCount())
        var at = from.coerceAtLeast(0)
        val out = StringBuilder(maxOf(0, end - at))
        val open = windowStart >= 0
        val base = if (open) rowChar(windowStart) else 0
        val held = if (open) windowSlice.text.length + breakChars() else 0
        val shift = if (open) charShift() else 0
        while (at < end) {
            if (open && at >= base && at < base + held) {
                val text = windowSlice.text
                val inside = at - base
                if (inside >= text.length) {
                    // The line break the window ends at, which it does not hold.
                    out.append((if (breakChars() == 2) "\r\n" else "\n")[inside - text.length])
                    at++
                    continue
                }
                val take = minOf(text.length, inside + (end - at))
                out.append(text, inside, take)
                at += take - inside
                continue
            }
            val row = if (open && at >= base + held) at - shift else at
            val index = chunkAtChar(row)
            val start = chunks[index]
            val moved = if (open && start >= windowEnd) recorded else 0
            val text = content.slice(start + moved, chunkEndAt(chunks, index, length) + moved).text
            val inside = row - this.index.chars[index]
            val take = minOf(text.length, inside + (end - at))
            if (take <= inside) break
            out.append(text, inside, take)
            at += take - inside
        }
        return out.toString()
    }

    /**
     * The selection as a keyboard is told it, in characters: the mark, or the cursor, or null when
     * there is neither. Always the earlier end first, whichever end was carried, as a text field
     * tells it: keyboards work out how much a gesture deletes as the end less the start, and one
     * that finds the mark backwards deletes nothing.
     */
    fun selectionChars(): TextRange? {
        marked?.let { return TextRange(charOf(it.min), charOf(it.max)) }
        if (windowStart < 0) return null
        record()
        return TextRange(rowChar(windowStart) + window.selection.start)
    }

    /**
     * The keyboard putting [text] over the characters [from] to [to], with the cursor after it. In
     * the window when the window holds all of it, as typing always does, so that it is typing like
     * any other; through the document otherwise, which is a selection reaching past the window,
     * and the window opens again at the end of it.
     */
    fun replaceChars(from: Int, to: Int, text: CharSequence) {
        record()
        unmark()
        if (windowStart >= 0) {
            val base = rowChar(windowStart)
            if (from >= base && to <= base + window.text.length) {
                type(TextRange(from - base, to - base), text, own = false)
                return
            }
        }
        replace(TextRange(offsetAtChar(from), offsetAtChar(to)), text, reopen = true)
    }

    /**
     * The keyboard setting the selection, in characters: the mark from [start] to [end], which may
     * come first, or the cursor when they are one place. A cursor the window does not hold takes
     * the window with it; nothing scrolls but to keep the cursor on screen, as typing does.
     */
    fun selectChars(start: Int, end: Int) {
        if (start != end) {
            val range = TextRange(offsetAtChar(start), offsetAtChar(end))
            // The mark it was told, given back, which is the earlier end first whichever way the
            // mark runs, and must not turn it round.
            val mark = marked
            if (mark == null || mark.min != range.min || mark.max != range.max) mark(range, touched = false)
            return
        }
        unmark()
        if (windowStart >= 0) {
            record()
            val base = rowChar(windowStart)
            if (start >= base && start <= base + window.text.length) {
                window.select(start - base)
                return
            }
        }
        val at = offsetAtChar(start)
        commit()
        open(at)
        changed()
    }

    /** The part of [composition] the window holds, in its own characters, or null. */
    fun compositionInWindow(): TextRange? {
        val composing = composition ?: return null
        if (windowStart < 0) return null
        val base = index.chars[chunkIndexAt(chunks, windowStart)]
        val held = window.text.length
        val from = (composing.min - base).coerceIn(0, held)
        val to = (composing.max - base).coerceIn(0, held)
        return if (from < to) TextRange(from, to) else null
    }

    /**
     * [charOf] for [offset] in the rows' own offsets, outside the window: what the index counts
     * before its chunk, and what the chunk holds before it.
     */
    private fun rowChar(offset: Int): Int {
        val at = offset.coerceIn(0, length)
        val index = chunkIndexAt(chunks, at)
        val start = chunks[index]
        if (at <= start) return this.index.chars[index]
        val moved = if (windowStart >= 0 && start >= windowEnd) recorded else 0
        val bytes = content.read(start + moved, at + moved)
        return this.index.chars[index] + content.encoding.decodedLength(bytes, 0, bytes.size)
    }

    /** [offsetAtChar] for [char] in the rows' own count, outside the window, as the rows' offset. */
    private fun rowOffset(char: Int): Int {
        val index = chunkAtChar(char)
        val start = chunks[index]
        val before = this.index.chars[index]
        if (char <= before) return start
        val moved = if (windowStart >= 0 && start >= windowEnd) recorded else 0
        val slice = content.slice(start + moved, chunkEndAt(chunks, index, length) + moved)
        return start + slice.byteAt((char - before).coerceAtMost(slice.text.length))
    }

    /** The chunk the character [char] is in, as the rows count characters. */
    private fun chunkAtChar(char: Int): Int {
        val found = index.chars.binarySearch(char, 0, chunks.size)
        return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
    }

    /**
     * How many characters the window has gained since [commit], which is how far every character
     * after it has moved from where the index counts it.
     */
    private fun charShift(): Int {
        val end = if (windowEnd >= length) index.chars.last() else index.chars[chunkIndexAt(chunks, windowEnd)]
        return windowSlice.text.length + breakChars() - (end - rowChar(windowStart))
    }

    /** How many characters the line break at the end of the window is. */
    private fun breakChars() = windowBreak / content.encoding.unitBytes

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
 * What the window holds and where the cursor is in it, in its own characters. Nothing else: no
 * selection, which is the document's, and no history, which is the document's too.
 */
@Stable
class WindowText {

    var text: String by mutableStateOf("")
        private set

    /** Always a cursor; a selection is [DocumentState.marked], whatever made it. */
    var selection: TextRange by mutableStateOf(TextRange.Zero)
        private set

    fun set(text: String, cursor: Int) {
        this.text = text
        select(cursor)
    }

    fun select(cursor: Int) {
        selection = TextRange(cursor.coerceIn(0, text.length))
    }

    /** Puts [with] where [from] to [to] was, and the cursor after it. */
    fun replace(from: Int, to: Int, with: CharSequence) {
        text = StringBuilder(text.length - (to - from) + with.length)
            .append(text, 0, from)
            .append(with)
            .append(text, to, text.length)
            .toString()
        select(from + with.length)
    }
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
 * Pinching the text to another size. [wanted] is where the fingers have taken the scale and
 * [scale] where that last came to a step; [anchor] is the text they hold, until the rows are laid
 * out at the new size and it can be put back under them.
 */
private class Zoom(var scale: Float) {
    var wanted = scale
    var anchor: Anchor? = null
}

/**
 * The text a pinch holds: [char] of the row starting at [row], which was [below] under the top of
 * its line with the fingers at [at] down the screen, and how much the text has [grown] since.
 */
private class Anchor(val row: Int, val char: Int, val at: Float, val below: Float) {
    var grown = 1f
}

/** This size [by] times over, or left unspecified, which has no size to multiply. */
private fun TextUnit.scaled(by: Float): TextUnit = if (isSpecified) this * by else this

/**
 * @param margin how far in from either side the text is drawn. It is the editor's own, as a text
 *   field's padding is, so a touch on it lands on the text: at the start of a line on the left, at
 *   its end on the right.
 * @param refuse says why something asked for was not done, which today is a copy or a cut larger
 *   than the clipboard takes, or a link nothing on the phone opens.
 * @param scale how large the text is drawn against the theme's size, and [rescale] is asked for
 *   another whenever a pinch takes it a step further.
 */
@Composable
fun DocumentEditor(
    state: DocumentState,
    modifier: Modifier = Modifier,
    margin: Dp = 0.dp,
    refuse: (String) -> Unit = {},
    scale: Float = 1f,
    rescale: (Float) -> Unit = {},
) {
    val rows = remember(state, state.version, state.chunks, state.windowStart, state.windowEnd) {
        Rows(state.chunks, state.length, state.windowStart, state.windowEnd)
    }
    val list = state.place
    val overscroll = rememberOverscrollEffect()
    val clip = rememberClip(state, refuse)
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val inset = with(LocalDensity.current) { margin.toPx() }
    val base = LocalTextStyle.current
    val style = remember(base, scale) {
        base.copy(
            fontSize = base.fontSize.scaled(scale),
            lineHeight = base.lineHeight.scaled(scale),
            letterSpacing = base.letterSpacing.scaled(scale),
        )
    }
    val zoom = remember(list) { Zoom(scale) }
    // The text under the fingers stays under them. How far the text above it moves is not how much
    // the text grew, since its lines wrap again at the new size, so the line itself is followed: its
    // row is held where it starts on screen while the rows are laid out again, which keeps it on
    // screen however much the rows above it change, and then the list is scrolled to put the line
    // back under the fingers.
    LaunchedEffect(zoom, scale) {
        withFrameNanos {}
        val anchor = zoom.anchor ?: return@LaunchedEffect
        zoom.anchor = null
        val top = state.lineTop(anchor.row, anchor.char) ?: return@LaunchedEffect
        list.scrollBy(top + anchor.below * anchor.grown - anchor.at)
    }
    val pinch = { by: Float, at: Offset ->
        zoom.wanted = (zoom.wanted * by).coerceIn(SCALE_MIN, SCALE_MAX)
        val next = (zoom.wanted / SCALE_STEP).roundToInt() * SCALE_STEP
        if (next != zoom.scale) {
            if (zoom.anchor == null) {
                val spot = state.spotAt(at)
                val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == spot?.row }
                val top = spot?.let { state.lineTop(it.row, it.char) }
                if (item != null && top != null) {
                    list.requestScrollToItem(item.index, -item.offset)
                    zoom.anchor = Anchor(spot.row, spot.char, at.y, at.y - top)
                }
            }
            zoom.anchor?.let { it.grown *= next / zoom.scale }
            zoom.scale = next
            rescale(next)
        }
    }
    // The user putting the cursor somewhere brings the keyboard up. Taking focus does that on its
    // own, but the editor usually holds it already, and the keyboard may have been put away since.
    // Only a place put after the document came on screen: one it was given before is not the user
    // asking for the keyboard now.
    LaunchedEffect(state) {
        snapshotFlow { state.opened }.drop(1).collect {
            runCatching { focus.requestFocus() }
            view.context.getSystemService(InputMethodManager::class.java).showSoftInput(view, 0)
        }
    }
    DisposableEffect(state, inset) {
        state.drawnIn(true, inset)
        onDispose { state.drawnIn(false) }
    }
    // The screen under the text getting shorter — the keyboard coming up over a cursor put low on
    // the screen, a turn to landscape — must not leave the cursor under it.
    LaunchedEffect(list, state) {
        var was = 0
        snapshotFlow { list.layoutInfo.viewportSize.height }.collect { now ->
            if (now in 1 until was) state.shrunk(was)
            was = now
        }
    }
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
    //
    // Focus, keys and the keyboard are the editor's as a whole and no row's, so that nothing a row
    // does, the window being cut included, reaches any of them.
    //
    // Everything inside is the document's and starts over with another one. Rows are keyed on
    // where they start, and two documents both have a row at 0, so a row kept across would go on
    // drawing the other document's text; the same goes for whatever the touches remember.
    Box(
        modifier
            .then(KeyboardElement(state, clip))
            .focusRequester(focus)
            .onKeyEvent { event -> keys(event, state, clip, scope) }
            .focusTarget(),
    ) {
        key(state) {
            CompositionLocalProvider(LocalTextStyle provides style) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = list,
                    contentPadding = PaddingValues(horizontal = margin),
                    userScrollEnabled = false,
                    overscrollEffect = overscroll,
                ) {
                    items(rows.count, key = { rows.start(it) }) { index ->
                        if (rows.editable(index)) WindowRow(state)
                        else ChunkRow(state, rows.start(index), rows.end(index))
                    }
                    // Room to scroll the last line clear of the bottom edge. A touch on it is a
                    // touch below the last row, which is the end of the document.
                    item(key = "tail") {
                        Spacer(Modifier.fillMaxWidth().height(TAIL))
                    }
                }
            }
            SelectionLayer(state, list, overscroll, clip, pinch, refuse, Modifier.matchParentSize())
        }
    }
}

@Composable
private fun ChunkRow(state: DocumentState, start: Int, end: Int) {
    val version = state.version
    val slice = remember(version, start, end) {
        val full = state.rowText(start, end)
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

/**
 * The window: the row the cursor is in. Laid out read-only like any other row, since the keyboard
 * talks to the document rather than to a text field, and drawing what a text field would draw for
 * itself — the cursor, and a line under the word the keyboard is composing.
 */
@Composable
private fun WindowRow(state: DocumentState) {
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val ground = LocalTextSelectionColors.current.backgroundColor
    val ink = LocalContentColor.current
    val text = state.window.text
    val composing = state.compositionInWindow()
    val shown = remember(text, composing) {
        if (composing == null) AnnotatedString(text)
        else buildAnnotatedString {
            append(text)
            addStyle(SpanStyle(textDecoration = TextDecoration.Underline), composing.min, composing.max)
        }
    }
    // The cursor blinks as a text field's does, and is drawn solid again whenever it moves or the
    // text changes. None while a selection stands, or while the editor does not hold focus.
    val blinking = state.focused && state.marked == null
    var lit by remember { mutableStateOf(true) }
    LaunchedEffect(blinking, text, state.window.selection) {
        lit = true
        if (!blinking) return@LaunchedEffect
        while (true) {
            delay(CURSOR_BLINK_MILLIS)
            lit = !lit
        }
    }
    BasicText(
        text = shown,
        modifier = Modifier
            .fillMaxWidth()
            // The mark, drawn under the text the way the rows draw it under theirs.
            .drawWithCache {
                val path = state.markedInWindow()?.let { layout.value?.pathOf(it.min, it.max, state.markedPastWindow()) }
                onDrawBehind { if (path != null) drawPath(path, ground) }
            }
            .drawWithContent {
                drawContent()
                val result = layout.value
                if (!blinking || !lit || result == null) return@drawWithContent
                val at = state.window.selection.start.coerceIn(0, result.layoutInput.text.length)
                val caret = result.getCursorRect(at)
                val width = CURSOR_WIDTH.toPx()
                val x = (caret.left + width / 2).coerceAtMost(size.width - width / 2)
                drawLine(ink, Offset(x, caret.top), Offset(x, caret.bottom), width)
            },
        style = LocalTextStyle.current.copy(color = ink),
        onTextLayout = { result ->
            state.laidOut(result)
            layout.value = result
        },
    )
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
 * What a key types, or null when it types nothing: Enter a line break, Tab a tab, and anything
 * else what the key's character map says, unless ctrl or meta make it a command.
 */
private fun typedBy(event: KeyEvent): String? {
    if (event.isCtrlPressed || event.isMetaPressed) return null
    when (event.key) {
        Key.Enter, Key.NumPadEnter -> return "\n"
        Key.Tab -> return "\t"
    }
    val code = event.utf16CodePoint
    // A dead key, waiting for the letter it puts an accent on, types nothing yet.
    if (code <= 0 || code and KeyCharacterMap.COMBINING_ACCENT != 0 || Character.isISOControl(code)) return null
    return String(Character.toChars(code))
}

/**
 * The keys: a hardware keyboard's, and those a keyboard on screen sends as keys rather than as text.
 * There is no text field under the editor to answer any of them, so everything a field would do
 * with a key is done here.
 *
 * Undo and redo, ctrl+Z and ctrl+Y, belong to the change list, as the toolbar's buttons do. Select
 * all, ctrl+A, selects the document. Copy, cut and paste, as ctrl and a letter or as keys of their
 * own, are the mark's and the editor's clipboard's, which refuses a copy too large to carry and
 * survives a clipboard too large to read.
 *
 * Shift and a key that moves the cursor carries the mark, wherever it goes. Such a key without
 * shift lets go of a selection, and otherwise moves the cursor. Backspace and delete take out the
 * mark, or a character or with ctrl a word. Back lets go of a selection before it leaves anything.
 *
 * Consumed whichever way they arrive, key up included. Ctrl+shift+Z is taken as a redo too: it is
 * what the other half of the world's keyboards send.
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
    val erase = when (key) {
        Key.Backspace -> if (ctrl) Step.WordLeft else Step.Left
        Key.Delete -> if (ctrl) Step.WordRight else Step.Right
        else -> null
    }
    val typed = if (step == null && erase == null) typedBy(event) else null
    if (step == null && erase == null && typed == null && !copy && !cut && !paste && !history && !all &&
        key != Key.Back
    ) return false
    val marked = state.marked != null
    if ((copy || cut || key == Key.Back) && !marked) return false
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
        erase != null -> scope.launch(start = CoroutineStart.UNDISPATCHED) { state.erase(erase) }
        typed != null -> state.typeText(typed)
        history -> scope.launch { if (key == Key.Y || event.isShiftPressed) state.redo() else state.undo() }
        all -> state.selectAll(touched = false)
        copy -> clip.copy()
        cut -> clip.cut()
        paste -> clip.paste()
    }
    return true
}
