package com.yugahashimoto.andcode.data.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ScheduleModelsTest {
    @Test
    fun `parses five field cron`() {
        val cron = CronExpression.parse("0 9 * * 1-5")
        assertNotNull(cron)
        assertEquals(listOf(0), cron?.minutes)
        assertEquals(listOf(9), cron?.hours)
        assertEquals((1..31).toList(), cron?.daysOfMonth)
        assertEquals(listOf(1, 2, 3, 4, 5), cron?.daysOfWeek)
    }

    @Test
    fun `rejects malformed cron`() {
        assertNull(CronExpression.parse(""))
        assertNull(CronExpression.parse("0 9 * *"))
        assertNull(CronExpression.parse("60 9 * * *"))
        assertNull(CronExpression.parse("0 24 * * *"))
        assertNull(CronExpression.parse("0 9 * 13 *"))
        assertNull(CronExpression.parse("0 9 * * 8"))
        assertNull(CronExpression.parse("a b c d e"))
    }

    @Test
    fun `normalizes sunday 7 to 0`() {
        val cron = CronExpression.parse("0 9 * * 7")
        assertEquals(listOf(0), cron?.daysOfWeek)
    }

    @Test
    fun `nextAfter finds same day later time`() {
        val cron = CronExpression.parse("30 10 * * *")!!
        val from = at("2026-08-01T09:00:00")
        assertNextAt(at("2026-08-01T10:30:00"), cron.nextAfter(from))
    }

    @Test
    fun `nextAfter rolls to the following day`() {
        val cron = CronExpression.parse("30 10 * * *")!!
        val from = at("2026-08-01T11:00:00")
        assertNextAt(at("2026-08-02T10:30:00"), cron.nextAfter(from))
    }

    @Test
    fun `nextAfter respects day of week`() {
        // 2026-08-01 is a Saturday; the next weekday 09:00 is Monday 2026-08-03.
        val cron = CronExpression.parse("0 9 * * 1-5")!!
        val from = at("2026-08-01T00:00:00")
        assertNextAt(at("2026-08-03T09:00:00"), cron.nextAfter(from))
    }

    @Test
    fun `nextAfter handles every n minutes`() {
        val cron = CronExpression.parse("*/30 9 * * *")!!
        val from = at("2026-08-01T09:10:00")
        assertNextAt(at("2026-08-01T09:30:00"), cron.nextAfter(from))
    }

    @Test
    fun `nextAfter handles monthly and leap day`() {
        val monthly = CronExpression.parse("0 9 15 * *")!!
        val from = at("2026-08-01T00:00:00")
        assertNextAt(at("2026-08-15T09:00:00"), monthly.nextAfter(from))

        // Feb 29 only exists in leap years; the next one after 2026 is 2028.
        val leap = CronExpression.parse("0 9 29 2 *")!!
        assertNextAt(at("2028-02-29T09:00:00"), leap.nextAfter(from))
    }

    @Test
    fun `nextAfter supports comma lists and ranges`() {
        val cron = CronExpression.parse("0 7,9 1-2 * *")!!
        val from = at("2026-08-01T00:00:00")
        assertNextAt(at("2026-08-01T07:00:00"), cron.nextAfter(from))
    }

    @Test
    fun `codec round trips schedules and runs`() {
        val schedules =
            listOf(
                Schedule(
                    name = "nightly",
                    runtimeId = "local-android",
                    workspacePath = "/workspace",
                    cron = "0 2 * * *",
                    prompt = "run tests",
                ),
                Schedule(name = "one shot", oneTimeAt = 12345L, prompt = "greet"),
            )
        val encoded = ScheduleCodec.encodeSchedules(schedules)
        val decoded = ScheduleCodec.decodeSchedules(encoded)
        assertEquals(schedules, decoded)

        val runs = listOf(ScheduleRun(scheduleId = "s1", sessionId = "sess", runtimeId = "r"))
        assertEquals(runs, ScheduleCodec.decodeRuns(ScheduleCodec.encodeRuns(runs)))
    }

    @Test
    fun `schedule display name falls back to prompt`() {
        assertEquals("run tests", Schedule(prompt = "run tests").displayName)
        assertEquals("named", Schedule(name = "named", prompt = "run tests").displayName)
    }

    @Test
    fun `run is active while running`() {
        assertTrue(ScheduleRun(scheduleId = "s", sessionId = "x", runtimeId = "r").isActive)
        assertFalse(
            ScheduleRun(scheduleId = "s", sessionId = "x", runtimeId = "r", status = ScheduleRunStatus.COMPLETED).isActive,
        )
    }

    private fun at(isoDateTime: String): Long = LocalDateTime.parse(isoDateTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun assertNextAt(
        expectedMillis: Long,
        actualMillis: Long?,
    ) {
        assertEquals(
            "expected ${LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(expectedMillis), ZoneId.systemDefault())} " +
                "but got ${actualMillis?.let { LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), ZoneId.systemDefault()) }}",
            expectedMillis,
            actualMillis,
        )
    }
}
