package com.posedetection.detector

import com.google.mediapipe.tasks.core.Delegate
import org.junit.Assert.assertEquals
import org.junit.Test

class StartPlanTest {
    @Test
    fun `auto starts on the CPU and moves to the GPU`() {
        assertEquals(listOf(Delegate.CPU, Delegate.GPU), StartPlan.delegates(DelegateRequest.AUTO, null))
        assertEquals(listOf(Delegate.CPU, Delegate.GPU), StartPlan.delegates(DelegateRequest.AUTO, true))
    }

    @Test
    fun `auto on a device whose GPU failed builds the CPU alone`() {
        assertEquals(listOf(Delegate.CPU), StartPlan.delegates(DelegateRequest.AUTO, false))
    }

    @Test
    fun `an explicit delegate is the only one built, whatever the GPU check said`() {
        for (verdict in listOf(null, true, false)) {
            assertEquals(listOf(Delegate.GPU), StartPlan.delegates(DelegateRequest.GPU, verdict))
            assertEquals(listOf(Delegate.CPU), StartPlan.delegates(DelegateRequest.CPU, verdict))
        }
    }
}
