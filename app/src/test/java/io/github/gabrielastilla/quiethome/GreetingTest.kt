package io.github.gabrielastilla.quiethome

import io.github.gabrielastilla.quiethome.Greeting.Period
import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {

    @Test
    fun everyHourMapsToTheRightPeriod() {
        val expected = mapOf(
            0 to Period.EVENING, 1 to Period.EVENING, 2 to Period.EVENING, 3 to Period.EVENING, 4 to Period.EVENING,
            5 to Period.MORNING, 6 to Period.MORNING, 7 to Period.MORNING, 8 to Period.MORNING, 9 to Period.MORNING,
            10 to Period.MORNING, 11 to Period.MORNING,
            12 to Period.NOON,
            13 to Period.AFTERNOON, 14 to Period.AFTERNOON, 15 to Period.AFTERNOON, 16 to Period.AFTERNOON, 17 to Period.AFTERNOON,
            18 to Period.EVENING, 19 to Period.EVENING, 20 to Period.EVENING, 21 to Period.EVENING, 22 to Period.EVENING, 23 to Period.EVENING,
        )
        for ((h, p) in expected) assertEquals("hour $h", p, Greeting.period(h))
    }

    @Test
    fun boundaries() {
        assertEquals(Period.EVENING, Greeting.period(4))   // 4:59 AM
        assertEquals(Period.MORNING, Greeting.period(5))   // 5:00 AM
        assertEquals(Period.MORNING, Greeting.period(11))  // 11:59 AM
        assertEquals(Period.NOON, Greeting.period(12))     // 12:00–12:59 PM
        assertEquals(Period.AFTERNOON, Greeting.period(13))// 1:00 PM
        assertEquals(Period.AFTERNOON, Greeting.period(17))// 5:59 PM
        assertEquals(Period.EVENING, Greeting.period(18))  // 6:00 PM
    }

    @Test
    fun wording() {
        assertEquals("Good morning, Gabriel", Greeting.compose(Period.MORNING, "Gabriel", true))
        assertEquals("Good noon, Gabriel", Greeting.compose(Period.NOON, "Gabriel", true))
        assertEquals("Good afternoon, Gabriel", Greeting.compose(Period.AFTERNOON, " Gabriel ", true))
        assertEquals("Good evening, Gabriel", Greeting.compose(Period.EVENING, "Gabriel", true))
        // Blank or hidden name: no trailing comma.
        assertEquals("Good evening", Greeting.compose(Period.EVENING, "", true))
        assertEquals("Good evening", Greeting.compose(Period.EVENING, "   ", true))
        assertEquals("Good morning", Greeting.compose(Period.MORNING, "Gabriel", false))
    }
}
