package com.yugahashimoto.andcode.feature.workspace

import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import com.yugahashimoto.andcode.runtime.local.AdbConnectionState
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeDiagnostics
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeOperationResult
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeProcessMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalRuntimeManagementViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load collects diagnostics`() =
        runTest(dispatcher) {
            val runtimeState = MutableStateFlow<LocalRuntimeStatus>(LocalRuntimeStatus.Ready("1.18.3", 4097))
            val expected = diagnostics(runtimeState.value)
            val viewModel =
                viewModel(
                    runtimeState = runtimeState,
                    diagnosticsProvider = { expected },
                )

            advanceUntilIdle()

            assertFalse(viewModel.state.value.isLoading)
            assertEquals(expected, viewModel.state.value.diagnostics)
            assertEquals(expected.status, viewModel.state.value.runtimeStatus)
            assertEquals(null, viewModel.state.value.error)
        }

    @Test
    fun `diagnostic failure is exposed`() =
        runTest(dispatcher) {
            val runtimeState = MutableStateFlow<LocalRuntimeStatus>(LocalRuntimeStatus.Stopped("1.18.3", 4097))
            val viewModel =
                viewModel(
                    runtimeState = runtimeState,
                    diagnosticsProvider = { error("diagnostic failed") },
                )

            advanceUntilIdle()

            assertEquals("diagnostic failed", viewModel.state.value.error)
            assertEquals(null, viewModel.state.value.diagnostics)
        }

    @Test
    fun `runtime progress and operation result are reflected`() =
        runTest(dispatcher) {
            val runtimeState = MutableStateFlow<LocalRuntimeStatus>(LocalRuntimeStatus.Ready("1.18.3", 4097))
            val lastOperation = MutableStateFlow<LocalRuntimeOperationResult?>(null)
            val viewModel =
                viewModel(
                    runtimeState = runtimeState,
                    lastOperationState = lastOperation,
                )
            advanceUntilIdle()

            runtimeState.value = LocalRuntimeStatus.Updating("1.18.3", "1.19.0", 0.5f, "展開中")
            lastOperation.value =
                LocalRuntimeOperationResult.AutomaticRollback(
                    failedVersion = "1.19.0",
                    restoredVersion = "1.18.3",
                    reason = "起動失敗",
                )
            advanceUntilIdle()

            assertTrue(viewModel.state.value.runtimeStatus is LocalRuntimeStatus.Updating)
            assertTrue(viewModel.state.value.lastOperation is LocalRuntimeOperationResult.AutomaticRollback)
        }

    @Test
    fun `confirmed delete waits for not installed state and reports completion`() =
        runTest(dispatcher) {
            val runtimeState = MutableStateFlow<LocalRuntimeStatus>(LocalRuntimeStatus.Ready("1.18.3", 4097))
            var deleteCalls = 0
            val viewModel =
                viewModel(
                    runtimeState = runtimeState,
                    deleteAction = { deleteCalls++ },
                )
            advanceUntilIdle()

            viewModel.requestDelete()
            assertTrue(viewModel.state.value.showDeleteConfirmation)

            viewModel.confirmDelete()
            assertEquals(1, deleteCalls)
            assertTrue(viewModel.state.value.isDeleting)
            assertFalse(viewModel.state.value.showDeleteConfirmation)

            runtimeState.value = LocalRuntimeStatus.NotInstalled
            advanceUntilIdle()

            assertFalse(viewModel.state.value.isDeleting)
            assertTrue(viewModel.state.value.deleteCompleted)
        }

    @Test
    fun `delete timeout clears deleting state and reports error`() =
        runTest(dispatcher) {
            val runtimeState = MutableStateFlow<LocalRuntimeStatus>(LocalRuntimeStatus.Ready("1.18.3", 4097))
            val viewModel =
                viewModel(
                    runtimeState = runtimeState,
                    deleteTimeoutMillis = 1_000L,
                )
            advanceUntilIdle()

            viewModel.confirmDelete()
            assertTrue(viewModel.state.value.isDeleting)

            advanceTimeBy(1_000L)
            runCurrent()

            assertFalse(viewModel.state.value.isDeleting)
            assertEquals("string/${R.string.runtime_delete_timeout}", viewModel.state.value.error)
        }

    @Test
    fun `repair action is invoked`() =
        runTest(dispatcher) {
            val runtimeState = MutableStateFlow<LocalRuntimeStatus>(LocalRuntimeStatus.Broken("broken"))
            var repairCalls = 0
            val viewModel =
                viewModel(
                    runtimeState = runtimeState,
                    repairAction = { repairCalls++ },
                )
            advanceUntilIdle()

            viewModel.repair()

            assertEquals(1, repairCalls)
            assertNotNull(viewModel.state.value.diagnostics)
        }

    @Test
    fun `adb state is collected from adb state flow`() =
        runTest(dispatcher) {
            val runtimeState = MutableStateFlow<LocalRuntimeStatus>(LocalRuntimeStatus.Ready("1.18.3", 4097))
            val adbState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
            val viewModel =
                viewModel(
                    runtimeState = runtimeState,
                    adbState = adbState,
                )
            advanceUntilIdle()

            assertEquals(AdbConnectionState.Disconnected, viewModel.state.value.adbState)

            adbState.value = AdbConnectionState.Connected(5555)
            advanceUntilIdle()

            assertEquals(AdbConnectionState.Connected(5555), viewModel.state.value.adbState)
        }

    @Test
    fun `adb pair dialog opens and triggers discovery`() =
        runTest(dispatcher) {
            val runtimeState = MutableStateFlow<LocalRuntimeStatus>(LocalRuntimeStatus.Ready("1.18.3", 4097))
            var discoveryCalls = 0
            val viewModel =
                viewModel(
                    runtimeState = runtimeState,
                    adbStartDiscovery = { discoveryCalls++ },
                )
            advanceUntilIdle()

            viewModel.showAdbPairDialog()

            assertTrue(viewModel.state.value.showAdbPairDialog)
            assertEquals(1, discoveryCalls)

            viewModel.dismissAdbPairDialog()
            assertFalse(viewModel.state.value.showAdbPairDialog)
        }

    @Test
    fun `adb pair success closes dialog`() =
        runTest(dispatcher) {
            val runtimeState = MutableStateFlow<LocalRuntimeStatus>(LocalRuntimeStatus.Ready("1.18.3", 4097))
            val viewModel =
                viewModel(
                    runtimeState = runtimeState,
                    adbPairAction = { _, _ -> Result.success(Unit) },
                )
            advanceUntilIdle()

            viewModel.showAdbPairDialog()
            viewModel.adbPair(37123, "123456")
            advanceUntilIdle()

            assertFalse(viewModel.state.value.showAdbPairDialog)
            assertFalse(viewModel.state.value.isAdbPairing)
        }

    @Test
    fun `adb pair failure exposes error`() =
        runTest(dispatcher) {
            val runtimeState = MutableStateFlow<LocalRuntimeStatus>(LocalRuntimeStatus.Ready("1.18.3", 4097))
            val viewModel =
                viewModel(
                    runtimeState = runtimeState,
                    adbPairAction = { _, _ -> Result.failure(RuntimeException("pair failed")) },
                )
            advanceUntilIdle()

            viewModel.adbPair(37123, "123456")
            advanceUntilIdle()

            assertEquals("pair failed", viewModel.state.value.error)
            assertFalse(viewModel.state.value.isAdbPairing)
        }

    @Test
    fun `adb connect success clears connecting state`() =
        runTest(dispatcher) {
            val runtimeState = MutableStateFlow<LocalRuntimeStatus>(LocalRuntimeStatus.Ready("1.18.3", 4097))
            val viewModel =
                viewModel(
                    runtimeState = runtimeState,
                    adbConnectAction = { Result.success(Unit) },
                )
            advanceUntilIdle()

            viewModel.adbConnect(5555)
            advanceUntilIdle()

            assertFalse(viewModel.state.value.isAdbConnecting)
        }

    private fun viewModel(
        runtimeState: MutableStateFlow<LocalRuntimeStatus>,
        diagnosticsProvider: suspend () -> LocalRuntimeDiagnostics = { diagnostics(runtimeState.value) },
        lastOperationState: MutableStateFlow<LocalRuntimeOperationResult?> = MutableStateFlow(null),
        repairAction: () -> Unit = {},
        deleteAction: () -> Unit = {},
        deleteTimeoutMillis: Long = 30_000L,
        adbState: MutableStateFlow<AdbConnectionState>? = null,
        adbPairAction: (suspend (Int, String) -> Result<Unit>)? = null,
        adbConnectAction: (suspend (Int) -> Result<Unit>)? = null,
        adbDisconnectAction: (suspend () -> Result<Unit>)? = null,
        adbStartDiscovery: (() -> Unit)? = null,
    ) = LocalRuntimeManagementViewModel(
        runtimeState = runtimeState,
        lastOperationState = lastOperationState,
        diagnosticsProvider = diagnosticsProvider,
        repairAction = repairAction,
        deleteAction = deleteAction,
        getString = { resId -> "string/$resId" },
        deleteTimeoutMillis = deleteTimeoutMillis,
        adbState = adbState,
        adbPairAction = adbPairAction,
        adbConnectAction = adbConnectAction,
        adbDisconnectAction = adbDisconnectAction,
        adbStartDiscovery = adbStartDiscovery,
    )

    private fun diagnostics(status: LocalRuntimeStatus) =
        LocalRuntimeDiagnostics(
            status = status,
            version = "1.18.3",
            abi = "arm64-v8a",
            port = 4097,
            runtimeBytes = 100,
            freeBytes = 200,
            process = LocalRuntimeProcessMetrics(42, 50, 1_000),
            tools = emptyList(),
            logTail = "ok",
            collectedAtMillis = 123,
        )
}
