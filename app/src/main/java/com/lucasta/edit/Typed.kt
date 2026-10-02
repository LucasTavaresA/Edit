package com.lucasta.edit

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Bytes moved per pass whenever the whole document has to go somewhere. */
internal const val PASS = 64 * 1024

/**
 * How much of the typed buffer of a plain document may be in memory before it moves onto disk.
 * Typing never gets near it. Pasting can, and so can merging pieces, which copies the file's own
 * bytes into the buffer: over a long session on a large file that can be much of the file.
 */
internal const val SPILL_BYTES = 16 shl 20

/**
 * The buffer holding what was typed, and what merging pieces has copied in. Nothing in it is ever
 * written over: bytes go on the end, so a piece that names a run of it names the same bytes for as
 * long as the buffer is there, and the only way to let go of what nothing names any more is to copy
 * what still is into another one.
 *
 * Bytes go into memory, and a plain document's move onto the end of a file in [directory] whenever
 * what is in memory would pass [limit]: in app storage and unlinked from the moment it is open, so
 * nothing but this can reach it, the way the private copy of the file is kept. A disk that refuses
 * them — full, most likely — leaves them in memory, where they would be if there were no disk at
 * all, and the move is tried again once as much again has gone in: a full disk costs heap, and never
 * an edit. An encrypted document's never move, since no byte of a plaintext is written anywhere, and
 * [directory] is null for it.
 */
class Typed(private val directory: File?, private val limit: Int = SPILL_BYTES) : Closeable {

    /** Everything from [spilled] on. */
    private var memory = ByteArray(0)

    /** Everything below [spilled], or null while nothing has moved. */
    private var file: Spill? = null

    /** Where the part on disk ends and the part in memory starts. */
    private var spilled = 0

    /** How much may be in memory before a move onto disk is tried: [limit], or more after a refusal. */
    private var room = limit

    var length = 0
        private set

    /** Whether any of it has moved onto disk. */
    val onDisk: Boolean get() = spilled > 0

    /** Puts [count] bytes of [bytes] from [from] on the end, and answers where they went. */
    fun append(bytes: ByteArray, from: Int = 0, count: Int = bytes.size - from): Int {
        val at = length
        if (count <= 0) return at
        // Before they would pass it rather than after, so the array is never doubled past the limit
        // only to be let go.
        if (directory != null && at - spilled + count > room) move(directory)
        val held = at - spilled
        if (held + count > memory.size) memory = memory.copyOf(maxOf(held + count, minOf(memory.size, Int.MAX_VALUE / 2) * 2, PASS))
        System.arraycopy(bytes, from, memory, held, count)
        length = at + count
        return at
    }

    fun read(at: Int, into: ByteArray, offset: Int, count: Int) = read(file, memory, spilled, at, into, offset, count)

    /**
     * Everything this holds now, as something that goes on reading exactly that for as long as it
     * is held, whatever this does next — which is what a save writes from while the document goes
     * on being edited. Nothing is copied to make it. Nothing is ever written below where the buffer
     * ends, growing it in memory copies it somewhere new, moving it onto disk leaves the memory it
     * was in as it was, and compacting it makes another one; so what a view names stays put, and
     * all that has to be done for it is to keep the file it may be reading open until it lets go.
     */
    fun view(): View = View(memory, file?.hold(), spilled, length)

    /** Another buffer, empty, that moves onto disk where this one would: what compacting copies into. */
    fun fresh(): Typed = Typed(directory, limit)

    /** The [count] bytes at [at] in [from], put on the end of this one. */
    fun copy(from: Typed, at: Int, count: Int) {
        if (at >= from.spilled) {
            append(from.memory, at - from.spilled, count)
            return
        }
        val pass = ByteArray(minOf(count, PASS))
        var done = 0
        while (done < count) {
            val step = minOf(pass.size, count - done)
            from.read(at + done, pass, 0, step)
            append(pass, 0, step)
            done += step
        }
    }

    /**
     * What is in memory, put on the end of the file, and the memory it was in left as it was for any
     * view of it. A disk that refuses it leaves everything where it was.
     */
    private fun move(directory: File) {
        try {
            val file = this.file ?: Spill(directory).also { this.file = it }
            file.write(spilled.toLong(), memory, 0, length - spilled)
        } catch (e: IOException) {
            // Full, most likely, so whatever of it did get written is given back, and the next try
            // waits until as much again has gone in.
            runCatching { file?.cut(spilled.toLong()) }
            room = length - spilled + limit
            return
        }
        spilled = length
        memory = ByteArray(0)
        room = limit
    }

    override fun close() {
        file?.close()
        file = null
    }

    /** What a buffer held when [view] was asked, readable until it is closed. */
    class View internal constructor(
        private val memory: ByteArray,
        private val file: Spill?,
        private val spilled: Int,
        val length: Int,
    ) : Closeable {

        fun read(at: Int, into: ByteArray, offset: Int, count: Int) = read(file, memory, spilled, at, into, offset, count)

        override fun close() {
            file?.close()
        }
    }
}

/** [count] bytes from [at] of a buffer whose part below [spilled] is in [file] and the rest in [memory]. */
private fun read(file: Spill?, memory: ByteArray, spilled: Int, at: Int, into: ByteArray, offset: Int, count: Int) {
    val below = (spilled - at).coerceIn(0, count)
    if (below > 0) file!!.read(at.toLong(), into, offset, below)
    if (below < count) System.arraycopy(memory, at + below - spilled, into, offset + below, count - below)
}

/**
 * A file only this process can reach, holding the part of a typed buffer that has moved out of
 * memory: unlinked from the moment it is open. It stays open for as long as anything holds it — the buffer, and any
 * view a save is still writing from — and the last of them to let go closes it.
 *
 * Reads and writes name where they go, so a view reading on one thread and the buffer growing on
 * another never move anything the other depends on. Nothing may use it from a thread that can be
 * interrupted: an interrupt closes a channel, for every holder at once.
 */
internal class Spill(directory: File) {

    private val channel: FileChannel

    private var holders = 1

    init {
        val file = File.createTempFile("typed", null, directory)
        channel = try {
            RandomAccessFile(file, "rw").channel
        } finally {
            file.delete()
        }
    }

    fun write(at: Long, bytes: ByteArray, from: Int, count: Int) {
        val buffer = ByteBuffer.wrap(bytes, from, count)
        while (buffer.hasRemaining()) channel.write(buffer, at + (buffer.position() - from))
    }

    fun read(at: Long, into: ByteArray, offset: Int, count: Int) {
        val buffer = ByteBuffer.wrap(into, offset, count)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, at + (buffer.position() - offset)) <= 0) break
        }
    }

    /** Gives back everything from [at] on: what a write the disk refused got as far as. */
    fun cut(at: Long) {
        channel.truncate(at)
    }

    /** Keeps it open for one more holder, who lets go with [close]. */
    fun hold(): Spill = synchronized(this) {
        holders++
        this
    }

    fun close() {
        if (synchronized(this) { --holders == 0 }) channel.close()
    }
}
