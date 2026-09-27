package com.posedetection.detector

import java.util.TreeMap

/** Each slot keeps its earliest frame, held [windowUs]: some decoders return B-frames out of order. */
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

    val holding: Int
        get() = held.size

    fun wants(ptsUs: Long): Long? {
        newestPtsUs = maxOf(newestPtsUs, ptsUs)
        // End exclusive, like the reader iOS trims with, so both platforms return the same frames.
        if (ptsUs < startUs || ptsUs >= endUs) return null
        val slot = (ptsUs - startUs) / stepUs
        if (slot <= lastSlot) return null
        val current = held[slot]
        return if (current == null || ptsUs < current.ptsUs) slot else null
    }

    /** A better frame is drawn over this item rather than a new one. */
    fun itemIn(slot: Long): T? = held[slot]?.item

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

    fun settled(ended: Boolean): Sample<T>? {
        val first = held.firstEntry() ?: return null
        val slotEndUs = startUs + (first.key + 1) * stepUs
        val ready = ended || held.size > maxHeld || newestPtsUs - slotEndUs > windowUs
        if (!ready) return null
        held.pollFirstEntry()
        lastSlot = first.key
        return Sample(first.value.item, first.value.ptsUs)
    }

    fun pastRange(): Boolean = endUs != Long.MAX_VALUE && newestPtsUs > endUs + windowUs

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
