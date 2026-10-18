package com.floatbrowser.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulesTest {
    private fun s(spec: String, last: Long, enabled: Boolean = true) =
        Schedule("1", "perfil", spec, enabled, last)

    @Test fun validSpecs() {
        listOf("08:30", "0:00", "23:59", "30m", "2h", " 15M ").forEach { assertTrue(it, Schedules.valid(it)) }
    }

    @Test fun invalidSpecs() {
        listOf("24:00", "12:60", "0m", "0h", "abc", "", "10", "5x").forEach { assertFalse(it, Schedules.valid(it)) }
    }

    @Test fun everyMinutesDueAfterInterval() {
        val now = System.currentTimeMillis()
        assertTrue(Schedules.isDue(s("30m", now - 31 * 60_000L), now))
        assertFalse(Schedules.isDue(s("30m", now - 29 * 60_000L), now))
    }

    @Test fun everyHoursDueAfterInterval() {
        val now = System.currentTimeMillis()
        assertTrue(Schedules.isDue(s("2h", now - 121 * 60_000L), now))
        assertFalse(Schedules.isDue(s("2h", now - 119 * 60_000L), now))
    }

    @Test fun dailyDueOnlyOncePerDay() {
        val now = System.currentTimeMillis()
        // 00:00 já passou hoje: vence se nunca rodou, não vence se já rodou agora
        assertTrue(Schedules.isDue(s("00:00", 0L), now))
        assertFalse(Schedules.isDue(s("00:00", now), now))
    }
}
