package com.lucasta.edit

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.InputType
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.SurroundingText
import androidx.annotation.RequiresApi
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * How much text either side of the selection a keyboard is given when it asks, in characters. A
 * keyboard asks for about a thousand to read the word and the sentence it is in; nothing reads
 * further, and an answer has to cross into the keyboard's process in one go.
 */
private const val SURROUNDING_MAX = 1 shl 16

/**
 * The longest selection a keyboard is given the text of when it asks. Past it the answer is that
 * there is none to give, which every keyboard has to expect anyway: a selection may cover the
 * whole of a gigabyte, and nothing that size can be handed over.
 */
private const val SELECTED_MAX = 1 shl 16

/** How far back the start of a sentence is looked for, to say whether the next letter is a capital. */
private const val CAPS_LOOKBACK = 1024

/**
 * Connects the keyboard to the document while the editor holds focus. Goes on the editor as a
 * whole rather than on any row, so that nothing the rows do — the window being cut, a row
 * scrolling off — ends the keyboard's session: its panels stay open and the word it is composing
 * stays the word it is composing.
 */
internal class KeyboardElement(private val state: DocumentState, private val clip: Clip) :
    ModifierNodeElement<KeyboardNode>() {

    override fun create() = KeyboardNode(state, clip)

    override fun update(node: KeyboardNode) = node.update(state, clip)

    override fun equals(other: Any?) = other is KeyboardElement && other.state === state && other.clip === clip

    override fun hashCode() = 31 * state.hashCode() + clip.hashCode()

    // A name alone: left to itself the layout inspector would read the whole document by reflection.
    override fun InspectorInfo.inspectableProperties() {
        name = "keyboard"
    }
}

internal class KeyboardNode(private var state: DocumentState, private var clip: Clip) :
    Modifier.Node(), PlatformTextInputModifierNode, FocusEventModifierNode {

    private var session: Job? = null

    /** Waiting, after going to another document, for the user to put the cursor somewhere. */
    private var waiting: Job? = null

    /**
     * Another document, or another clipboard, under the same editor. Focus stays where it was, so
     * the document coming in takes it over from the one going.
     *
     * Going to another document puts the keyboard away, whether or not it has a cursor: one left up
     * over a document with no cursor would drop what is typed into it, and keeping it up only for
     * some would read as random. Compose shows the keyboard whenever a session starts and puts it
     * away when one ends with none after it, so the session ends here and the next one starts only
     * once the user puts the cursor somewhere, as after opening a file. What the keyboard was told
     * about the last document, its word included, is nothing to this one either way.
     */
    fun update(state: DocumentState, clip: Clip) {
        if (state === this.state && clip === this.clip) return
        val left = this.state
        this.state = state
        this.clip = clip
        if (state !== left) {
            state.focused = left.focused
            left.focused = false
            left.composition = null
            waiting?.cancel()
            waiting = null
            if (session != null) {
                session?.cancel()
                session = null
                val was = state.opened
                waiting = coroutineScope.launch {
                    snapshotFlow { state.opened }.first { it != was }
                    if (session == null && state.focused) start()
                }
            }
        } else if (session != null) {
            session?.cancel()
            start()
        }
    }

    override fun onFocusEvent(focusState: FocusState) {
        // Only focus arriving, not focus the editor already held: going to another document hands
        // the focus over in [update], and Compose reports focus again after an update, which must
        // neither start the session [update] ended nor place a cursor in a document that was left
        // without one.
        val arrived = focusState.isFocused && !state.focused
        state.focused = focusState.isFocused
        if (focusState.isFocused) {
            if (arrived && session == null) start()
            // Focus that came without a place to type, as moving focus with a hardware keyboard's
            // arrows brings it, puts the cursor at the first line on screen, as a text field puts one
            // somewhere when focus reaches it: otherwise the keyboard is up and typing goes nowhere.
            val state = state
            if (arrived && state.windowStart < 0 && state.marked == null) coroutineScope.launch {
                state.topSpot()?.let { state.tap(it) } ?: state.focus(state.visibleFrom)
            }
        } else {
            session?.cancel()
            session = null
            waiting?.cancel()
            waiting = null
        }
    }

    override fun onDetach() {
        session = null
        state.focused = false
    }

    private fun start() {
        val state = state
        val clip = clip
        session = coroutineScope.launch {
            establishTextInputSession {
                val keyboard = Keyboard(state, clip, view)
                launch { keyboard.follow() }
                startInputMethod { info -> keyboard.connect(info) }
            }
        }
    }
}

/**
 * One session of the keyboard with the document: what it has been told, and the connections it
 * talks through. Everything it is told is in characters counted from the document's start, which
 * are the only coordinates a keyboard knows, and they are true ones: keyboards built on Android's
 * own compare the place they are given with the length of the text before the cursor, and believe
 * the text.
 */
private class Keyboard(val state: DocumentState, val clip: Clip, private val view: View) {

    private val imm = view.context.getSystemService(InputMethodManager::class.java)

    /** How deep the keyboard's batch edits are nested. Nothing is reported until they all end. */
    var batch = 0

    /** What the keyboard was last told, which it is not told again. */
    private var selection: TextRange? = null

    private var composing: TextRange? = null

    /** The token of an extracted text the keyboard asked to be kept up to date on, or -1. */
    var monitor = -1

    fun connect(info: EditorInfo): InputConnection {
        batch = 0
        monitor = -1
        info.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        info.imeOptions = EditorInfo.IME_ACTION_UNSPECIFIED or EditorInfo.IME_FLAG_NO_ENTER_ACTION or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        val now = state.selectionChars() ?: TextRange.Zero
        info.initialSelStart = now.start
        info.initialSelEnd = now.end
        if (Build.VERSION.SDK_INT >= 30 && now.length <= SELECTED_MAX) {
            val from = (now.min - CAPS_LOOKBACK).coerceAtLeast(0)
            info.setInitialSurroundingSubText(state.textAt(from, now.max + CAPS_LOOKBACK), from)
        }
        selection = now
        composing = state.composition
        return Link(this)
    }

    /**
     * Tells the keyboard whatever moves the selection or the word being composed, until the session
     * ends, and starts it over when another document is loaded.
     */
    suspend fun follow() = coroutineScope {
        launch { snapshotFlow { state.generation }.drop(1).collect { imm.restartInput(view) } }
        snapshotFlow {
            listOf(
                state.window.text, state.window.selection, state.windowStart, state.version,
                state.marked, state.held, state.composition,
            )
        }.collect { report() }
    }

    /**
     * Tells the keyboard where the selection and the word being composed are, when either has
     * moved since it was last told, unless a batch edit is still open or a drag is still carrying
     * the mark.
     */
    fun report() {
        if (batch > 0 || state.held) return
        val now = state.selectionChars() ?: return
        val word = state.composition
        if (now == selection && word == composing) return
        selection = now
        composing = word
        imm.updateSelection(view, now.start, now.end, word?.min ?: -1, word?.max ?: -1)
        if (monitor >= 0) imm.updateExtractedText(view, monitor, extract())
    }

    /**
     * The text around the selection, for a keyboard that asks for it as extracted text: where it
     * starts in the document and where the selection is in it, which is how some keyboards read
     * where the cursor is.
     */
    fun extract(): ExtractedText {
        val now = state.selectionChars() ?: TextRange.Zero
        val from = (now.min - CAPS_LOOKBACK).coerceAtLeast(0)
        val to = if (now.length <= SELECTED_MAX) now.max + CAPS_LOOKBACK else now.min + CAPS_LOOKBACK
        return ExtractedText().apply {
            text = state.textAt(from, to)
            startOffset = from
            partialStartOffset = -1
            partialEndOffset = -1
            selectionStart = now.start - from
            selectionEnd = now.end - from
        }
    }

    fun sendKey(event: KeyEvent) = imm.dispatchKeyEventFromInputMethod(view, event)
}

/**
 * One connection of a [Keyboard]'s. It acts on the document at once, as a platform text field
 * does, so whatever the keyboard reads straight after an edit is the edit made.
 */
private class Link(private val keyboard: Keyboard) : InputConnection {

    private val state = keyboard.state

    private var closed = false

    /** Runs [block] as one edit: the keyboard hears of it once it is over, and of nothing half way. */
    private inline fun edit(block: () -> Unit): Boolean {
        if (closed) return false
        keyboard.batch++
        try {
            block()
        } finally {
            keyboard.batch--
            keyboard.report()
        }
        return true
    }

    /**
     * Puts [text] over [range], in the document's characters, and the cursor where [cursor] says:
     * past the end of it for 1, before it for 0, and counted from either from there.
     */
    private fun put(range: TextRange, text: CharSequence, cursor: Int, pasted: Boolean = false) {
        state.replaceChars(range.min, range.max, text, pasted)
        val after = range.min + text.length
        val at = (if (cursor > 0) after + cursor - 1 else range.min + cursor).coerceIn(0, state.charCount())
        if (at != after) state.selectChars(at, at)
    }

    /** What is being composed, or failing that the selection: what typing goes over. */
    private fun target(): TextRange? {
        state.composition?.let { return it }
        val now = state.selectionChars() ?: return null
        return TextRange(now.min, now.max)
    }

    /** More than a word at once is a keyboard pasting, and a step of undo of its own. */
    override fun commitText(text: CharSequence?, newCursorPosition: Int) = edit {
        val range = target() ?: return@edit
        val typed = text ?: ""
        put(range, typed, newCursorPosition, pasted = moreThanAWord(typed))
        state.composition = null
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int) = edit {
        val range = target() ?: return@edit
        val typed = text ?: ""
        put(range, typed, newCursorPosition)
        state.composition = if (typed.isEmpty()) null else TextRange(range.min, range.min + typed.length)
    }

    override fun setComposingRegion(start: Int, end: Int) = edit {
        val count = state.charCount()
        val from = start.coerceIn(0, count)
        val to = end.coerceIn(0, count)
        state.composition = if (from == to) null else TextRange(minOf(from, to), maxOf(from, to))
    }

    override fun finishComposingText() = edit { state.composition = null }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) = edit {
        deleteAround(beforeLength, afterLength)
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) = edit {
        val now = state.selectionChars() ?: return@edit
        val before = state.textAt(now.min - 2 * beforeLength.coerceIn(0, SURROUNDING_MAX), now.min)
        val after = state.textAt(now.max, now.max + 2 * afterLength.coerceIn(0, SURROUNDING_MAX))
        var back = before.length
        repeat(beforeLength.coerceIn(0, SURROUNDING_MAX)) {
            if (back == 0) return@repeat
            back -= if (back >= 2 && Character.isSurrogatePair(before[back - 2], before[back - 1])) 2 else 1
        }
        var on = 0
        repeat(afterLength.coerceIn(0, SURROUNDING_MAX)) {
            if (on == after.length) return@repeat
            on += if (on + 1 < after.length && Character.isSurrogatePair(after[on], after[on + 1])) 2 else 1
        }
        deleteAround(before.length - back, on)
    }

    /**
     * Takes out [before] characters before the selection and [after] after it, as a platform text
     * field does: the word being composed counts as part of the selection, and both are left where
     * they were in what remains.
     */
    private fun deleteAround(before: Int, after: Int) {
        val now = state.selectionChars() ?: return
        val word = state.composition
        val start = minOf(now.min, word?.min ?: now.min)
        val end = maxOf(now.max, word?.max ?: now.max)
        val tail = minOf(end.toLong() + after.coerceAtLeast(0), state.charCount().toLong()).toInt()
        if (tail > end) state.replaceChars(end, tail, "")
        val head = (start - before.coerceAtLeast(0)).coerceAtLeast(0)
        if (head < start) state.replaceChars(head, start, "")
        val gone = start - head
        state.composition = word?.let { TextRange(it.min - gone, it.max - gone) }
        state.selectChars(now.start - gone, now.end - gone)
    }

    override fun setSelection(start: Int, end: Int) = edit {
        val count = state.charCount()
        // Out of the document's range is ignored, as a platform text field ignores it.
        if (start in 0..count && end in 0..count) state.selectChars(start, end)
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
        if (closed) return null
        val now = state.selectionChars() ?: return ""
        return state.textAt(now.min - n.coerceIn(0, SURROUNDING_MAX), now.min)
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
        if (closed) return null
        val now = state.selectionChars() ?: return ""
        return state.textAt(now.max, now.max + n.coerceIn(0, SURROUNDING_MAX))
    }

    override fun getSelectedText(flags: Int): CharSequence? {
        if (closed) return null
        val now = state.selectionChars() ?: return null
        if (now.collapsed || now.length > SELECTED_MAX) return null
        return state.textAt(now.min, now.max)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
        if (closed) return null
        val now = state.selectionChars() ?: return null
        if (now.length > SELECTED_MAX) return null
        val from = (now.min - beforeLength.coerceIn(0, SURROUNDING_MAX)).coerceAtLeast(0)
        val text = state.textAt(from, now.max + afterLength.coerceIn(0, SURROUNDING_MAX))
        return SurroundingText(text, now.min - from, now.max - from, from)
    }

    override fun getCursorCapsMode(reqModes: Int): Int {
        if (closed) return 0
        val now = state.selectionChars() ?: return 0
        val before = state.textAt(now.min - CAPS_LOOKBACK, now.min)
        return TextUtils.getCapsMode(before, before.length, reqModes)
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
        if (closed) return null
        if (flags and InputConnection.GET_EXTRACTED_TEXT_MONITOR != 0) keyboard.monitor = request?.token ?: 0
        return keyboard.extract()
    }

    /**
     * Select all selects the document, and copy, cut and paste are the editor's, answered before
     * the keyboard's next call: a keyboard that lets go of the selection straight after copying
     * it, as Samsung's does, would otherwise leave nothing to copy by then.
     */
    override fun performContextMenuAction(id: Int): Boolean {
        if (closed) return false
        var done = true
        edit {
            when (id) {
                android.R.id.selectAll -> state.selectAll(touched = false)
                android.R.id.copy -> keyboard.clip.copy()
                android.R.id.cut -> keyboard.clip.cut()
                android.R.id.paste, android.R.id.pasteAsPlainText -> keyboard.clip.paste()
                else -> done = false
            }
        }
        return done
    }

    /** There is no action but a new line, which is what the key that asks for one means here. */
    override fun performEditorAction(editorAction: Int) = edit {
        state.composition = null
        state.typeText("\n")
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (closed) return false
        keyboard.sendKey(event)
        return true
    }

    override fun beginBatchEdit(): Boolean {
        if (closed) return false
        keyboard.batch++
        return true
    }

    override fun endBatchEdit(): Boolean {
        if (closed) return false
        if (keyboard.batch > 0) keyboard.batch--
        keyboard.report()
        return keyboard.batch > 0
    }

    override fun closeConnection() {
        if (closed) return
        closed = true
        keyboard.batch = 0
        keyboard.monitor = -1
    }

    override fun commitCompletion(text: CompletionInfo?) = commitText(text?.text, 1)

    override fun commitCorrection(correctionInfo: CorrectionInfo?) = !closed

    override fun requestCursorUpdates(cursorUpdateMode: Int) = false

    override fun clearMetaKeyStates(states: Int) = false

    override fun reportFullscreenMode(enabled: Boolean) = false

    override fun performPrivateCommand(action: String?, data: Bundle?) = false

    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?) = false

    override fun getHandler(): Handler? = null
}
