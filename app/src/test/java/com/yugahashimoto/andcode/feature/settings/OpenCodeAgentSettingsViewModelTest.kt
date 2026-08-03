package com.yugahashimoto.andcode.feature.settings

import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeOperationResult
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeRelease
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeReleaseAsset
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeUpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenCodeAgentSettingsViewModelTest {
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
    fun `initial load reads version port update state and rollback target`() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(
                    runtimeState = MutableStateFlow(LocalRuntimeStatus.Ready("1.18.3", 4097)),
                    updateCheckProvider = { Result.success(upToDate()) },
                    rollbackVersionProvider = { "1.17.0" },
                )

            advanceUntilIdle()

            assertEquals("1.18.3", viewModel.state.value.version)
            assertEquals(4097, viewModel.state.value.port)
            assertEquals(upToDate(), viewModel.state.value.updateCheck)
            assertEquals("1.17.0", viewModel.state.value.rollbackVersion)
            assertFalse(viewModel.state.value.isCheckingUpdate)
            assertTrue(viewModel.state.value.installed)
        }

    @Test
    fun `stopped runtime can be started`() =
        runTest(dispatcher) {
            var startCalls = 0
            val viewModel =
                viewModel(
                    runtimeState = MutableStateFlow(LocalRuntimeStatus.Stopped("1.18.3", 4097)),
                    startAction = { startCalls++ },
                )
            advanceUntilIdle()

            viewModel.start()

            assertEquals(1, startCalls)
            assertNull(viewModel.state.value.error)
        }

    @Test
    fun `start is refused while an install is in flight`() =
        runTest(dispatcher) {
            var startCalls = 0
            val viewModel =
                viewModel(
                    runtimeState = MutableStateFlow(LocalRuntimeStatus.Installing(0.5f, "unpacking")),
                    startAction = { startCalls++ },
                )
            advanceUntilIdle()

            viewModel.start()

            assertEquals(0, startCalls)
        }

    @Test
    fun `start is refused when nothing is installed`() =
        runTest(dispatcher) {
            var startCalls = 0
            val viewModel =
                viewModel(
                    runtimeState = MutableStateFlow(LocalRuntimeStatus.NotInstalled),
                    startAction = { startCalls++ },
                )
            advanceUntilIdle()

            viewModel.start()

            assertEquals(0, startCalls)
            assertFalse(viewModel.state.value.installed)
        }

    @Test
    fun `stop stays available while the server is still coming up`() =
        runTest(dispatcher) {
            var stopCalls = 0
            val viewModel =
                viewModel(
                    runtimeState = MutableStateFlow(LocalRuntimeStatus.Starting("1.18.3", 4097)),
                    stopAction = { stopCalls++ },
                )
            advanceUntilIdle()

            viewModel.stop()

            assertEquals(1, stopCalls)
        }

    @Test
    fun `restart dispatches and reports a failure to start`() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(
                    runtimeState = MutableStateFlow(LocalRuntimeStatus.Ready("1.18.3", 4097)),
                    restartAction = { error("service refused") },
                )
            advanceUntilIdle()

            viewModel.restart()

            assertEquals("service refused", viewModel.state.value.error)
        }

    @Test
    fun `available update can be confirmed and dispatched`() =
        runTest(dispatcher) {
            var updateCalls = 0
            val viewModel =
                viewModel(
                    runtimeState = MutableStateFlow(LocalRuntimeStatus.Ready("1.18.3", 4097)),
                    updateCheckProvider = { Result.success(available()) },
                    updateAction = { updateCalls++ },
                )
            advanceUntilIdle()

            viewModel.requestUpdate()
            assertTrue(viewModel.state.value.showUpdateConfirmation)

            viewModel.confirmUpdate()

            assertEquals(1, updateCalls)
            assertFalse(viewModel.state.value.showUpdateConfirmation)
        }

    @Test
    fun `rollback requires confirmation and dispatches selected target`() =
        runTest(dispatcher) {
            var rollbackCalls = 0
            val viewModel =
                viewModel(
                    runtimeState = MutableStateFlow(LocalRuntimeStatus.Ready("1.19.0", 4097)),
                    updateCheckProvider = { Result.success(upToDate("1.19.0")) },
                    rollbackVersionProvider = { "1.18.3" },
                    rollbackAction = { rollbackCalls++ },
                )
            advanceUntilIdle()

            viewModel.requestRollback()
            assertTrue(viewModel.state.value.showRollbackConfirmation)

            viewModel.confirmRollback()

            assertEquals(1, rollbackCalls)
            assertFalse(viewModel.state.value.showRollbackConfirmation)
        }

    @Test
    fun `rollback is refused without a stored previous version`() =
        runTest(dispatcher) {
            var rollbackCalls = 0
            val viewModel =
                viewModel(
                    runtimeState = MutableStateFlow(LocalRuntimeStatus.Ready("1.18.3", 4097)),
                    rollbackVersionProvider = { null },
                    rollbackAction = { rollbackCalls++ },
                )
            advanceUntilIdle()

            viewModel.requestRollback()

            assertFalse(viewModel.state.value.showRollbackConfirmation)
            assertEquals(0, rollbackCalls)
        }

    @Test
    fun `update check failure is exposed without clearing the status`() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(
                    runtimeState = MutableStateFlow(LocalRuntimeStatus.Ready("1.18.3", 4097)),
                    updateCheckProvider = { Result.failure(IllegalStateException("network failed")) },
                )

            advanceUntilIdle()

            assertEquals("network failed", viewModel.state.value.updateError)
            assertEquals("1.18.3", viewModel.state.value.version)
            assertFalse(viewModel.state.value.isCheckingUpdate)
        }

    /** A finished update has to re-read the release state, or the card keeps naming the old one. */
    @Test
    fun `settling after an update re-reads version and rollback target`() =
        runTest(dispatcher) {
            val runtimeState =
                MutableStateFlow<LocalRuntimeStatus>(LocalRuntimeStatus.Updating("1.18.3", "1.19.0", 0.5f, "downloading"))
            var rollbackVersion = "1.17.0"
            val viewModel =
                viewModel(
                    runtimeState = runtimeState,
                    updateCheckProvider = { Result.success(upToDate("1.19.0")) },
                    rollbackVersionProvider = { rollbackVersion },
                )
            advanceUntilIdle()

            rollbackVersion = "1.18.3"
            runtimeState.value = LocalRuntimeStatus.Ready("1.19.0", 4097)
            advanceUntilIdle()

            assertEquals("1.19.0", viewModel.state.value.version)
            assertEquals("1.18.3", viewModel.state.value.rollbackVersion)
        }

    @Test
    fun `last operation result is mirrored from the runtime manager`() =
        runTest(dispatcher) {
            val lastOperation = MutableStateFlow<LocalRuntimeOperationResult?>(null)
            val viewModel =
                viewModel(
                    runtimeState = MutableStateFlow(LocalRuntimeStatus.Ready("1.19.0", 4097)),
                    lastOperationState = lastOperation,
                )
            advanceUntilIdle()

            lastOperation.value = LocalRuntimeOperationResult.Updated("1.18.3", "1.19.0")
            advanceUntilIdle()

            assertTrue(viewModel.state.value.lastOperation is LocalRuntimeOperationResult.Updated)
        }

    private fun viewModel(
        runtimeState: MutableStateFlow<LocalRuntimeStatus>,
        lastOperationState: MutableStateFlow<LocalRuntimeOperationResult?> = MutableStateFlow(null),
        updateCheckProvider: suspend () -> Result<LocalRuntimeUpdateCheck> = { Result.success(upToDate()) },
        rollbackVersionProvider: suspend () -> String? = { null },
        freeBytesProvider: () -> Long = { 1_000_000L },
        startAction: () -> Unit = {},
        stopAction: () -> Unit = {},
        restartAction: () -> Unit = {},
        updateAction: () -> Unit = {},
        rollbackAction: () -> Unit = {},
    ) = OpenCodeAgentSettingsViewModel(
        runtimeState = runtimeState,
        lastOperationState = lastOperationState,
        updateCheckProvider = updateCheckProvider,
        rollbackVersionProvider = rollbackVersionProvider,
        freeBytesProvider = freeBytesProvider,
        startAction = startAction,
        stopAction = stopAction,
        restartAction = restartAction,
        updateAction = updateAction,
        rollbackAction = rollbackAction,
        getString = { resId -> "string/$resId" },
    )

    private fun upToDate(version: String = "1.18.3") = LocalRuntimeUpdateCheck.UpToDate(version, version)

    private fun available() =
        LocalRuntimeUpdateCheck.Available(
            currentVersion = "1.18.3",
            release =
                LocalRuntimeRelease(
                    version = "1.19.0",
                    releaseNotes = "Improved Android support",
                    asset =
                        LocalRuntimeReleaseAsset(
                            name = "opencode-linux-arm64-musl.tar.gz",
                            url = "https://example.test/opencode.tar.gz",
                            sha256 = "a".repeat(64),
                            sizeBytes = 100,
                        ),
                ),
        )
}
