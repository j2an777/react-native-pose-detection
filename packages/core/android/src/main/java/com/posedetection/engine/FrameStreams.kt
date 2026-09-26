package com.posedetection.engine

import java.lang.ref.WeakReference
import java.nio.ByteBuffer

/**
 * The parts of one camera view that JavaScript reads synchronously: its frames and a live reading
 * of its rate. Every one of them is thread-safe, which is what lets a read run on the JavaScript
 * thread rather than on a queue of Expo's. See ADR 0008.
 */
internal class FrameStream(
    val frames: FrameRingBuffer,
    private val readDetecting: () -> Boolean = { false },
    private val readLive: () -> Map<String, Any?>,
) {
    fun live(): Map<String, Any?> = readLive()

    /** True while this camera runs inference, which is when a file job must stay off the GPU. */
    val isDetecting: Boolean
        get() = readDetecting()
}

/**
 * Streams by the id `<PoseCamera>` gives each view. Held weakly: the view owns its stream, and one
 * that has gone reads as empty rather than stale. The same shape as iOS, where this is what keeps a
 * drain off the main queue; here it also makes the two platforms one code path in JavaScript.
 */
internal object FrameStreams {
    private val streams = HashMap<Int, WeakReference<FrameStream>>()

    @Synchronized
    fun register(
        stream: FrameStream,
        id: Int,
    ) {
        streams[id] = WeakReference(stream)
    }

    /** Only removes the entry if it is still this stream's, so a remount reusing an id is safe. */
    @Synchronized
    fun unregister(
        stream: FrameStream,
        id: Int,
    ) {
        if (streams[id]?.get() === stream) streams.remove(id)
    }

    @Synchronized
    fun stream(id: Int): FrameStream? = streams[id]?.get()

    fun drain(id: Int): ByteBuffer = stream(id)?.frames?.drain() ?: WireWriter.empty()

    fun snapshot(id: Int): ByteBuffer = stream(id)?.frames?.snapshot() ?: WireWriter.empty()

    fun takeSnapshot(
        id: Int,
        ticket: Int,
    ): ByteBuffer = stream(id)?.frames?.takeSnapshot(ticket) ?: WireWriter.empty()

    fun live(id: Int): Map<String, Any?> = stream(id)?.live() ?: emptyMap()

    /** Whether any mounted camera is running inference right now. */
    fun anyDetecting(): Boolean {
        val all = synchronized(this) { streams.values.mapNotNull { it.get() } }
        return all.any { it.isDetecting }
    }
}
