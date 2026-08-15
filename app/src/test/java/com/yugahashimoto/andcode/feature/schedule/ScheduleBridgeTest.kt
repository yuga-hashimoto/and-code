package com.yugahashimoto.andcode.feature.schedule

import com.yugahashimoto.andcode.data.schedule.Schedule
import com.yugahashimoto.andcode.data.schedule.ScheduleRun
import com.yugahashimoto.andcode.data.schedule.ScheduleRunStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.UUID

class ScheduleBridgeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class FakeStore : ScheduleStore {
        val schedules = mutableListOf<Schedule>()
        val runs = mutableListOf<ScheduleRun>()
        val runNowCalls = mutableListOf<String>()
        var reschedules = 0

        override fun schedules(): List<Schedule> = schedules.toList()

        override fun schedule(id: String): Schedule? = schedules.firstOrNull { it.id == id }

        override fun runs(scheduleId: String?): List<ScheduleRun> =
            if (scheduleId == null) runs.toList() else runs.filter { it.scheduleId == scheduleId }

        override fun upsert(schedule: Schedule) {
            schedules.removeAll { it.id == schedule.id }
            schedules.add(schedule)
            reschedules++
        }

        override fun delete(id: String) {
            schedules.removeAll { it.id == id }
            runs.removeAll { it.scheduleId == id }
        }

        override fun setEnabled(
            id: String,
            enabled: Boolean,
        ) {
            schedules.replaceAll { if (it.id == id) it.copy(enabled = enabled) else it }
            reschedules++
        }

        override fun runNow(id: String): Boolean {
            runNowCalls += id
            return true
        }

        override fun nextFireAt(schedule: Schedule): Instant? = null
    }

    private fun bridgeWith(
        store: ScheduleStore,
        staleAfterMillis: Long = ScheduleBridge.STALE_AFTER_MILLIS,
        clock: () -> Long = System::currentTimeMillis,
    ): ScheduleBridge {
        val bridge = ScheduleBridge(folder.root, store, staleAfterMillis = staleAfterMillis, clock = clock)
        bridge.pollOnce()
        return bridge
    }

    private fun pendingDir(bridge: ScheduleBridge): File = File(bridge.bridgeDir, "pending")

    private fun responsesDir(bridge: ScheduleBridge): File = File(bridge.bridgeDir, "responses")

    private fun writeRequest(
        bridge: ScheduleBridge,
        requestId: String,
        op: String,
        args: JSONObject = JSONObject(),
    ) {
        val file = File(pendingDir(bridge), "$requestId.json")
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject()
                .put("op", op)
                .put("args", args)
                .put("createdAtMs", 0L)
                .toString(),
        )
    }

    private fun readResponse(
        bridge: ScheduleBridge,
        requestId: String,
    ): JSONObject = JSONObject(File(responsesDir(bridge), "$requestId.json").readText())

    private fun requestId() = UUID.randomUUID().toString()

    private fun createArgs(
        prompt: String = "整理のリマインダー",
        runtimeId: String = "local",
        name: String = "",
        cron: String? = "0 9 * * *",
        oneTimeAt: Long? = null,
    ): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("runtimeId", runtimeId)
            put("prompt", prompt)
            if (cron != null) put("cron", cron) else put("oneTimeAt", oneTimeAt)
        }

    @Test
    fun `create writes the new schedule back to the guest`() {
        val store = FakeStore()
        val bridge = bridgeWith(store)
        val id = requestId()
        writeRequest(bridge, id, "create", createArgs())

        bridge.pollOnce()

        assertFalse(File(responsesDir(bridge), "$id.json.tmp").exists())
        val response = readResponse(bridge, id)
        assertTrue(response.getBoolean("ok"))
        val schedule = response.getJSONObject("data").getJSONObject("schedule")
        assertEquals("local", schedule.getString("runtimeId"))
        assertEquals("整理のリマインダー", schedule.getString("prompt"))
        assertEquals("0 9 * * *", schedule.getString("cron"))
        assertTrue(schedule.isNull("oneTimeAt"))
        assertTrue(schedule.isNull("nextFireAt"))
        assertEquals("整理のリマインダー", schedule.getString("displayName"))
        assertTrue(schedule.getBoolean("isRecurring"))
        assertTrue(schedule.getBoolean("enabled"))
        assertEquals(1, store.schedules.size)
        assertTrue(store.reschedules == 1)
        assertTrue(!File(pendingDir(bridge), "$id.json").exists())
    }

    @Test
    fun `create requires a prompt`() {
        val store = FakeStore()
        val bridge = bridgeWith(store)
        val id = requestId()
        writeRequest(bridge, id, "create", createArgs(prompt = "   "))

        bridge.pollOnce()

        val response = readResponse(bridge, id)
        assertTrue(!response.getBoolean("ok"))
        assertTrue(response.getString("error").contains("prompt"))
        assertTrue(store.schedules.isEmpty())
    }

    @Test
    fun `create requires exactly one trigger`() {
        val store = FakeStore()
        val bridge = bridgeWith(store)
        val id = requestId()
        writeRequest(
            bridge,
            id,
            "create",
            JSONObject().apply {
                put("runtimeId", "local")
                put("prompt", "x")
                put("cron", "0 9 * * *")
                put("oneTimeAt", 1L)
            },
        )

        bridge.pollOnce()

        val response = readResponse(bridge, id)
        assertTrue(!response.getBoolean("ok"))
        assertTrue(response.getString("error").contains("exactly one"))
    }

    @Test
    fun `create validates the cron expression`() {
        val store = FakeStore()
        val bridge = bridgeWith(store)
        val id = requestId()
        writeRequest(bridge, id, "create", createArgs(cron = "not a cron"))

        bridge.pollOnce()

        val response = readResponse(bridge, id)
        assertTrue(!response.getBoolean("ok"))
        assertTrue(response.getString("error").contains("cron"))
    }

    @Test
    fun `list returns stored schedules with computed fields`() {
        val store = FakeStore()
        store.schedules.add(Schedule(id = "s1", name = "", runtimeId = "local", prompt = "hello", cron = "0 6 * * *"))
        val bridge = bridgeWith(store)
        val id = requestId()
        writeRequest(bridge, id, "list")

        bridge.pollOnce()

        val response = readResponse(bridge, id)
        assertTrue(response.getBoolean("ok"))
        val schedules = response.getJSONObject("data").getJSONArray("schedules")
        assertEquals(1, schedules.length())
        val schedule = schedules.getJSONObject(0)
        assertEquals("s1", schedule.getString("id"))
        assertEquals("hello", schedule.getString("displayName"))
        assertTrue(schedule.getBoolean("isRecurring"))
    }

    @Test
    fun `get returns a missing schedule as null with empty runs`() {
        val store = FakeStore()
        val bridge = bridgeWith(store)
        val id = requestId()
        writeRequest(bridge, id, "get", JSONObject().put("scheduleId", "missing"))

        bridge.pollOnce()

        val response = readResponse(bridge, id)
        assertTrue(response.getBoolean("ok"))
        val data = response.getJSONObject("data")
        assertTrue(data.isNull("schedule"))
        assertEquals(0, data.getJSONArray("runs").length())
    }

    @Test
    fun `get returns the schedule and its runs`() {
        val store = FakeStore()
        store.schedules.add(Schedule(id = "s1", runtimeId = "local", prompt = "p", cron = "0 9 * * *"))
        store.runs.add(
            ScheduleRun(
                id = "run1",
                scheduleId = "s1",
                sessionId = "sess-1",
                runtimeId = "local",
                status = ScheduleRunStatus.COMPLETED,
            ),
        )
        val bridge = bridgeWith(store)
        val id = requestId()
        writeRequest(bridge, id, "get", JSONObject().put("scheduleId", "s1"))

        bridge.pollOnce()

        val data = readResponse(bridge, id).getJSONObject("data")
        assertEquals("s1", data.getJSONObject("schedule").getString("id"))
        val runs = data.getJSONArray("runs")
        assertEquals(1, runs.length())
        assertEquals("sess-1", runs.getJSONObject(0).getString("sessionId"))
        assertTrue(!runs.getJSONObject(0).getBoolean("isActive"))
    }

    @Test
    fun `runs filter by schedule and return status`() {
        val store = FakeStore()
        store.runs.add(
            ScheduleRun(
                id = "r1",
                scheduleId = "a",
                sessionId = "",
                runtimeId = "local",
                status = ScheduleRunStatus.FAILED,
                error = "boom",
            ),
        )
        store.runs.add(ScheduleRun(id = "r2", scheduleId = "b", sessionId = "", runtimeId = "local", status = ScheduleRunStatus.RUNNING))
        val bridge = bridgeWith(store)
        val id = requestId()
        writeRequest(bridge, id, "runs", JSONObject().put("scheduleId", "b"))

        bridge.pollOnce()

        val data = readResponse(bridge, id).getJSONObject("data")
        val runs = data.getJSONArray("runs")
        assertEquals(1, runs.length())
        assertEquals("RUNNING", runs.getJSONObject(0).getString("status"))
        assertTrue(runs.getJSONObject(0).getBoolean("isActive"))
        assertTrue(runs.getJSONObject(0).isNull("error"))
    }

    @Test
    fun `update keeps the id and timestamps and can switch to a one-time trigger`() {
        val store = FakeStore()
        store.schedules.add(Schedule(id = "s1", name = "old", runtimeId = "local", prompt = "p", cron = "0 9 * * *"))
        val bridge = bridgeWith(store)
        val id = requestId()
        writeRequest(
            bridge,
            id,
            "update",
            JSONObject().apply {
                put("scheduleId", "s1")
                put("name", "new name")
                put("enabled", false)
                put("oneTimeAt", 123456789L)
            },
        )

        bridge.pollOnce()

        val response = readResponse(bridge, id)
        assertTrue(response.getBoolean("ok"))
        val schedule = response.getJSONObject("data").getJSONObject("schedule")
        assertEquals("s1", schedule.getString("id"))
        assertEquals("new name", schedule.getString("name"))
        assertTrue(!schedule.getBoolean("enabled"))
        assertTrue(schedule.isNull("cron"))
        assertEquals(123456789L, schedule.getLong("oneTimeAt"))
        assertTrue(store.schedules.single().displayName == "new name")
    }

    @Test
    fun `update validates cron before applying`() {
        val store = FakeStore()
        store.schedules.add(Schedule(id = "s1", runtimeId = "local", prompt = "p", cron = "0 9 * * *"))
        val bridge = bridgeWith(store)
        val id = requestId()
        writeRequest(
            bridge,
            id,
            "update",
            JSONObject().apply {
                put("scheduleId", "s1")
                put("cron", "broken")
            },
        )

        bridge.pollOnce()

        val response = readResponse(bridge, id)
        assertTrue(!response.getBoolean("ok"))
        assertTrue(response.getString("error").contains("cron"))
        assertEquals("0 9 * * *", store.schedules.single().cron)
    }

    @Test
    fun `update on a missing schedule errors`() {
        val store = FakeStore()
        val bridge = bridgeWith(store)
        val id = requestId()
        writeRequest(bridge, id, "update", JSONObject().put("scheduleId", "nope").put("name", "x"))

        bridge.pollOnce()

        val response = readResponse(bridge, id)
        assertTrue(!response.getBoolean("ok"))
        assertTrue(response.getString("error").contains("not found"))
    }

    @Test
    fun `delete removes the schedule and its runs`() {
        val store = FakeStore()
        store.schedules.add(Schedule(id = "s1", runtimeId = "local", prompt = "p", cron = "0 9 * * *"))
        store.runs.add(ScheduleRun(id = "r1", scheduleId = "s1", sessionId = "", runtimeId = "local"))
        val bridge = bridgeWith(store)
        val id = requestId()
        writeRequest(bridge, id, "delete", JSONObject().put("scheduleId", "s1"))

        bridge.pollOnce()

        val data = readResponse(bridge, id).getJSONObject("data")
        assertEquals("s1", data.getString("id"))
        assertTrue(data.getBoolean("deleted"))
        assertTrue(store.schedules.isEmpty())
        assertTrue(store.runs.isEmpty())
    }

    @Test
    fun `setEnabled toggles the schedule without touching timing`() {
        val store = FakeStore()
        store.schedules.add(Schedule(id = "s1", runtimeId = "local", prompt = "p", cron = "0 9 * * *"))
        val bridge = bridgeWith(store)
        val id = requestId()
        writeRequest(bridge, id, "setEnabled", JSONObject().put("scheduleId", "s1").put("enabled", false))

        bridge.pollOnce()

        val data = readResponse(bridge, id).getJSONObject("data")
        assertTrue(!data.getBoolean("enabled"))
        assertTrue(!store.schedules.single().enabled)
        assertEquals("0 9 * * *", store.schedules.single().cron)
    }

    @Test
    fun `runNow starts the run immediately`() {
        val store = FakeStore()
        store.schedules.add(Schedule(id = "s1", runtimeId = "local", prompt = "p", cron = "0 9 * * *"))
        val bridge = bridgeWith(store)
        val id = requestId()
        writeRequest(bridge, id, "runNow", JSONObject().put("scheduleId", "s1"))

        bridge.pollOnce()

        val data = readResponse(bridge, id).getJSONObject("data")
        assertEquals("s1", data.getString("id"))
        assertTrue(data.getBoolean("started"))
        assertEquals(listOf("s1"), store.runNowCalls)
    }

    @Test
    fun `unknown operation is reported as an error`() {
        val store = FakeStore()
        val bridge = bridgeWith(store)
        val id = requestId()
        writeRequest(bridge, id, "explode")

        bridge.pollOnce()

        val response = readResponse(bridge, id)
        assertTrue(!response.getBoolean("ok"))
        assertTrue(response.getString("error").contains("unknown operation"))
    }

    @Test
    fun `malformed request produces an error without crashing the loop`() {
        val store = FakeStore()
        val bridge = bridgeWith(store)
        val id = requestId()
        File(pendingDir(bridge), "$id.json").apply {
            parentFile?.mkdirs()
            writeText("this is not json")
        }

        bridge.pollOnce()

        val response = readResponse(bridge, id)
        assertTrue(!response.getBoolean("ok"))
        assertTrue(!File(pendingDir(bridge), "$id.json").exists())
    }

    @Test
    fun `stale orphaned responses are pruned`() {
        val store = FakeStore()
        val bridge = bridgeWith(store, staleAfterMillis = 0L, clock = { System.currentTimeMillis() + 5 * 60 * 1000L })
        val orphan = File(responsesDir(bridge), "old-response.json")
        orphan.parentFile?.mkdirs()
        orphan.writeText("""{"ok":true}""")

        bridge.pollOnce()

        assertTrue(!orphan.exists())
    }
}
