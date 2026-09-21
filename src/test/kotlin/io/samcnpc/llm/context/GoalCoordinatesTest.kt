package io.samcnpc.llm.context

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GoalCoordinatesTest {
    @Test fun preservesSignedAxesDecimalsAndOrderWithoutInventingCoordinates() {
        val points = GoalCoordinates.encode("obszar 15,-58,6 do 17,-54,8; cel -22.5, +64, 8.25")
        assertEquals(3, points.size())
        assertEquals(15, points[0].asJsonObject["x"].asInt)
        assertEquals(-58, points[0].asJsonObject["y"].asInt)
        assertEquals(17, points[1].asJsonObject["x"].asInt)
        assertEquals(-22.5, points[2].asJsonObject["x"].asDouble)
        assertEquals(64, points[2].asJsonObject["y"].asInt)
        assertEquals(8.25, points[2].asJsonObject["z"].asDouble)
        assertEquals(0, GoalCoordinates.encode("32 cobblestone near Sam").size())
        assertEquals(8, GoalCoordinates.encode("1,2,3 ".repeat(50)).size())
    }
}
