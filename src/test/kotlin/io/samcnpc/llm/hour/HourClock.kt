package io.samcnpc.llm.hour

/** Only consecutive ticks with six physically active bodies count; long clock gaps are excluded. */
internal class HourClock {
    private var last = 0L
    private var previousActive = false
    var activeTicks = 0
        private set
    var activeNanos = 0L
        private set
    var excludedGaps = 0
        private set
    val seconds get() = activeNanos / 1_000_000_000.0
    fun observe(now: Long, active: Boolean) {
        require(now >= last)
        if (last != 0L) {
            val delta = now - last
            if (delta > 1_000_000_000L) excludedGaps++
            else if (delta > 0 && active && previousActive) { activeTicks++; activeNanos += delta }
        }
        last = now
        previousActive = active
    }
    fun reached(probe: Boolean) = activeTicks >= (if (probe) 1200 else 72000) &&
        seconds >= (if (probe) 60.0 else 3600.0)
}
