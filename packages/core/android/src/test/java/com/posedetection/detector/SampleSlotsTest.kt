package com.posedetection.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SampleSlotsTest {
    private fun sample(
        framesMs: List<Long>,
        stepMs: Long = 100,
        startMs: Long = 0,
        endMs: Long = Long.MAX_VALUE / 1_000,
    ): List<Long> {
        val slots = SampleSlots<String>(startMs * 1_000, endMs * 1_000, stepMs * 1_000)
        val out = ArrayList<Long>()
        for (frame in framesMs) {
            slots.settled(false)?.let { out.add(it.ptsUs / 1_000) }
            val slot = slots.wants(frame * 1_000) ?: continue
            slots.fill(slot, frame * 1_000, slots.itemIn(slot) ?: "frame-$frame")
        }
        while (true) out.add((slots.settled(true) ?: break).ptsUs / 1_000)
        return out
    }

    private val presentationOrder = (0 until 30).map { it * 100L / 3 }

    @Test
    fun `frames in presentation order sample the start of every slot`() {
        assertEquals(listOf(0L, 100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L), sample(presentationOrder))
    }

    @Test
    fun `frames in decode order sample the same frames, in time order`() {
        // What the emulator's decoder returned for a clip with B-frames.
        val decodeOrder =
            listOf(0L, 133, 66, 33, 100, 266, 200, 166, 233, 400, 333, 300, 366, 533, 466, 433, 500, 666, 600, 566, 633)
        assertEquals(listOf(0L, 100L, 200L, 300L, 400L, 500L, 600L), sample(decodeOrder))
    }

    @Test
    fun `a trimmed range samples inside it only`() {
        assertEquals(listOf(400L, 500L, 600L), sample(presentationOrder, startMs = 400, endMs = 666))
    }

    @Test
    fun `the end of a range is exclusive, as on iOS`() {
        assertEquals(listOf(400L, 500L), sample(presentationOrder, startMs = 400, endMs = 600))
    }

    @Test
    fun `a slot already sent on is never sampled again`() {
        val slots = SampleSlots<String>(0, Long.MAX_VALUE, 100_000, windowUs = 0)
        slots.fill(slots.wants(0)!!, 0, "a")
        slots.wants(250_000)
        assertEquals(0L, slots.settled(false)!!.ptsUs)
        assertNull("its slot has gone", slots.wants(50_000))
    }

    @Test
    fun `too many held slots settle the oldest`() {
        val slots = SampleSlots<String>(0, Long.MAX_VALUE, 10_000, windowUs = Long.MAX_VALUE / 2, maxHeld = 2)
        for (frame in listOf(0L, 10_000, 20_000)) slots.fill(slots.wants(frame)!!, frame, "f$frame")
        assertEquals(0L, slots.settled(false)!!.ptsUs)
        assertNull(slots.settled(false))
    }

    @Test
    fun `the range is over once a frame arrives a window past its end`() {
        val slots = SampleSlots<String>(0, 1_000_000, 100_000)
        slots.wants(1_200_000)
        assertEquals(false, slots.pastRange())
        slots.wants(1_400_000)
        assertEquals(true, slots.pastRange())
    }
}
