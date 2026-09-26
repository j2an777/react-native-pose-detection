package com.posedetection.detector

import java.util.TreeMap

/**
 * Which decoded frames become samples, whatever order a decoder returns them in.
 *
 * The range is cut into slots one sampling interval long, and each slot's sample is its earliest
 * frame. Decoders are meant to return frames in the order they are shown, and some do not: the
 * emulator's returns a clip with B-frames in the order it decoded them, 0, 133, 66, 33 ms. So a
 * slot is held until the stream has moved [windowUs] past it, an earlier frame that arrives late
 * replaces the one it holds, and samples leave in time order either way.
 *
 * Holds items rather than frames, so the video's bitmaps stay with the sampler and this stays a
 * plain class a JVM test can drive.
 */
internal class SampleSlots<T>(
    private val startUs: Long,
    private val endUs: Long,
    private val stepUs: Long,
    private val windowUs: Long = WINDOW_US,
    private val maxHeld: Int = MAX_HELD,
) {
    private class Held<T>(
        val item: T,
        var ptsUs: Long,
    )

    class Sample<T>(
        val item: T,
        val ptsUs: Long,
    )

    private val held = TreeMap<Long, Held<T>>()
    private var lastSlot = NO_SLOT
    private var newestPtsUs = Long.MIN_VALUE

    /** How many slots are holding a frame. */
    val holding: Int
        get() = held.size

    /**
     * The slot a decoded frame would fill, or null when it is not wanted: outside the range, in a
     * slot already sent on, or later than the frame its slot already holds.
     */
    fun wants(ptsUs: Long): Long? {
        newestPtsUs = maxOf(newestPtsUs, ptsUs)
        // The end is exclusive, as it is for the reader iOS trims with, so both return the same frames.
        if (ptsUs < startUs || ptsUs >= endUs) return null
        val slot = (ptsUs - startUs) / stepUs
        if (slot <= lastSlot) return null
        val current = held[slot]
        return if (current == null || ptsUs < current.ptsUs) slot else null
    }

    /** The item already holding [slot], which a better frame is drawn over rather than a new one. */
    fun itemIn(slot: Long): T? = held[slot]?.item

    /** Records that [slot] now holds the frame at [ptsUs] in [item]. */
    fun fill(
        slot: Long,
        ptsUs: Long,
        item: T,
    ) {
        val current = held[slot]
        if (current != null && current.item === item) {
            current.ptsUs = ptsUs
        } else {
            held[slot] = Held(item, ptsUs)
        }
    }

    /**
     * The earliest held sample once nothing earlier can still arrive: the stream has moved past its
     * slot by the window, too many are held, or the stream has [ended].
     */
    fun settled(ended: Boolean): Sample<T>? {
        val first = held.firstEntry() ?: return null
        val slotEndUs = startUs + (first.key + 1) * stepUs
        val ready = ended || held.size > maxHeld || newestPtsUs - slotEndUs > windowUs
        if (!ready) return null
        held.pollFirstEntry()
        lastSlot = first.key
        return Sample(first.value.item, first.value.ptsUs)
    }

    /** True once a frame this far past the range has arrived: nothing in it can still come. */
    fun pastRange(): Boolean = endUs != Long.MAX_VALUE && newestPtsUs > endUs + windowUs

    /** Everything still held, for a caller releasing what the items own. */
    fun drain(): List<T> {
        val items = held.values.map { it.item }
        held.clear()
        return items
    }

    companion object {
        /** Far longer than any B-frame reorder a decoder applies, and short enough to hold few frames. */
        const val WINDOW_US = 300_000L
        const val MAX_HELD = 8
        private const val NO_SLOT = -1L
    }
}
