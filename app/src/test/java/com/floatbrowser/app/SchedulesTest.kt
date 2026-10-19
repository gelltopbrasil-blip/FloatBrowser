package com.floatbrowser.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulesTest {
    private fun s(spec: String, last: Long, enabled: Boolean = true) =
        Schedule("1", "perfil", spec, enabled, last)

    @Test fun nextDelayEveryUsesLastRun() {
        val now = 1_000_000_000L
        assertTrue(Schedules.nextDelay(listOf(s("30m", now - 10 * 60_000)), now) == 20 * 60_000L)
    }

    @Test fun nextDelayIsZeroWhenOverdue() {
        val now = 1_000_000_000L
        assertTrue(Schedules.nextDelay(listOf(s("30m", now - 45 * 60_000)), now) == 0L)
    }

    @Test fun nextDelayDailyNotRunYetIsZero() {
        // 00:00 de hoje já passou e nunca rodou
        assertTrue(Schedules.nextDelay(listOf(s("00:00", 0L)), System.currentTimeMillis()) == 0L)
    }

    @Test fun nextDelayDailyAlreadyRanWaitsForTomorrow() {
        val now = System.currentTimeMillis()
        val d = Schedules.nextDelay(listOf(s("00:00", now)), now)!!
        assertTrue(d > 0 && d <= 25 * 3600_000L)
    }

    @Test fun nextDelayIgnoresDisabledAndEmpty() {
        val now = 1_000_000_000L
        assertTrue(Schedules.nextDelay(emptyList(), now) == null)
        assertTrue(Schedules.nextDelay(listOf(s("30m", now, enabled = false)), now) == null)
    }

    @Test fun nextDelayPicksTheSoonest() {
        val now = 1_000_000_000L
        val l = listOf(s("2h", now), s("30m", now - 25 * 60_000))
        assertTrue(Schedules.nextDelay(l, now) == 5 * 60_000L)
    }

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
