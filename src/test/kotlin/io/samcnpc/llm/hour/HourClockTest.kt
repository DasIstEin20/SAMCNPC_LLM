package io.samcnpc.llm.hour

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HourClockTest {
    @Test fun idleSetupAndLongGapsCannotSatisfyTheActiveHour() {
        val clock = HourClock()
        clock.observe(1,true)
        clock.observe(1_500_000_001L,true)
        assertEquals(0,clock.activeTicks)
        assertEquals(1,clock.excludedGaps)
        clock.observe(1_550_000_001L,false)
        clock.observe(1_600_000_001L,true)
        assertEquals(0,clock.activeTicks)
        clock.observe(1_650_000_001L,true)
        assertEquals(1,clock.activeTicks)
        assertEquals(0.05,clock.seconds)
        assertFalse(clock.reached(false))
    }
    @Test fun bothPhysicalTicksAndMonotonicDurationAreRequired() {
        val fast = HourClock()
        for (i in 0..72000) fast.observe(1L+i*40_000_000L,true)
        assertEquals(72000,fast.activeTicks)
        assertFalse(fast.reached(false))
        val slow = HourClock()
        for (i in 0..36000) slow.observe(1L+i*100_000_000L,true)
        assertEquals(3600.0,slow.seconds)
        assertFalse(slow.reached(false))
        val normal = HourClock()
        for (i in 0..72000) normal.observe(1L+i*50_000_000L,true)
        assertTrue(normal.reached(false))
    }
}
