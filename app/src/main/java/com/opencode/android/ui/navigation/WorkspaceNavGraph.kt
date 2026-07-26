package com.opencode.android.ui.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.opencode.android.OpenCodeApplication
import com.opencode.android.feature.workspace.CodeViewerScreen
import com.opencode.android.feature.workspace.LocalRuntimeManagementScreen
import com.opencode.android.feature.workspace.LocalRuntimeManagementViewModel
import com.opencode.android.feature.workspace.RemoteConnectionScreen
import com.opencode.android.feature.workspace.TerminalScreen
import com.opencode.android.feature.workspace.TerminalViewModel
import com.opencode.android.feature.workspace.WorkspaceExplorerScreen
import com.opencode.android.feature.workspace.WorkspaceExplorerViewModel
import com.opencode.android.feature.workspace.WorkspaceUiState
import com.opencode.android.feature.workspace.WorkspaceViewModel
import com.opencode.android.feature.workspace.WorkspacesScreen
import com.opencode.android.runtime.RuntimeTarget
import com.opencode.android.runtime.WorkspaceRef
import com.opencode.android.ui.ViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun NavGraphBuilder.workspaceNavGraph(
    navController: NavController,
    workspaceViewModel: WorkspaceViewModel,
    workspaceState: WorkspaceUiState,
    selectedWorkspace: WorkspaceRef?,
    onSelectWorkspace: (WorkspaceRef?) -> Unit,
    selectedRuntime: RuntimeTarget?,
    app: OpenCodeApplication,
    onImportFolder: () -> Unit,
    onShowCloneDialog: () -> Unit,
    completeOnboardingAndGoToChat: () -> Unit,
) {
    composable(ROUTE_REMOTE_CONNECTION) {
        RemoteConnectionScreen(
            onTestConnection = workspaceViewModel::testConnection,
            // Saving here is the user pressing "connect", so the PC becomes the active runtime even
            // when an Android-local runtime is already set up and selected.
            onSaveConnection = { form -> workspaceViewModel.saveConnection(form, activate = true) },
            onBack = { navController.popBackStack() },
            onConnected = completeOnboardingAndGoToChat,
        )
    }

    composable(ROUTE_WORKSPACES) {
        WorkspacesScreen(
            state = workspaceState,
            onSelectRuntime = workspaceViewModel::selectRuntime,
            // This screen edits the connection list and has its own per-target "select" action, so
            // saving must not move the running target under the user.
            onSaveConnection = { form -> workspaceViewModel.saveConnection(form, activate = false) },
            onDeleteConnection = workspaceViewModel::deleteConnection,
            onTestConnection = workspaceViewModel::testConnection,
            onRefresh = workspaceViewModel::refresh,
            onOpenWorkspace = { workspace ->
                onSelectWorkspace(workspace)
                navController.navigate(WORKSPACE_DETAIL_ROUTE)
            },
            onSetupLocal = workspaceViewModel::setupLocalRuntime,
            onStartLocal = workspaceViewModel::startLocalRuntime,
            onStopLocal = workspaceViewModel::stopLocalRuntime,
            onReinstallLocal = workspaceViewModel::reinstallLocalRuntime,
            onOpenLocalManagement = {
                navController.navigate(LOCAL_RUNTIME_MANAGEMENT_ROUTE)
            },
            onImportFolder = onImportFolder,
            onCloneGithub = onShowCloneDialog,
            onRemoveProject = workspaceViewModel::removeProject,
            onDeleteProjectFiles = workspaceViewModel::deleteProjectFiles,
            onBack = { navController.popBackStack() },
        )
    }

    composable(LOCAL_RUNTIME_MANAGEMENT_ROUTE) {
        val managementViewModel: LocalRuntimeManagementViewModel =
            viewModel(
                key = "local-runtime-management",
                factory =
                    ViewModelFactory {
                        LocalRuntimeManagementViewModel(
                            runtimeState = app.localRuntimeManager.state,
                            lastOperationState = app.localRuntimeManager.lastOperation,
                            diagnosticsProvider = {
                                withContext(Dispatchers.IO) {
                                    app.localRuntimeDiagnosticsCollector.collect()
                                }
                            },
                            updateCheckProvider = app.localRuntimeManager::checkForUpdate,
                            rollbackVersionProvider = app.localRuntimeManager::rollbackVersion,
                            repairAction = app.localRuntimeController::reinstall,
                            updateAction = app.localRuntimeController::update,
                            rollbackAction = app.localRuntimeController::rollback,
                            deleteAction = app.localRuntimeController::delete,
                            getString = { app.getString(it) },
                            adbState = app.adbConnectionManager.state,
                            adbPairAction = app.adbConnectionManager::pair,
                            adbConnectAction = app.adbConnectionManager::connect,
                            adbDisconnectAction = app.adbConnectionManager::disconnect,
                            adbStartDiscovery = app.adbConnectionManager::startDiscovery,
                        )
                    },
            )
        val managementState by managementViewModel.state.collectAsState()
        LaunchedEffect(managementState.deleteCompleted) {
            if (managementState.deleteCompleted) {
                managementViewModel.consumeDeleteCompleted()
                workspaceViewModel.refresh()
                navController.popBackStack()
            }
        }
        LocalRuntimeManagementScreen(
            state = managementState,
            onBack = { navController.popBackStack() },
            onRefresh = managementViewModel::refresh,
            onCheckForUpdate = managementViewModel::checkForUpdate,
            onRepair = managementViewModel::repair,
            onRequestUpdate = managementViewModel::requestUpdate,
            onDismissUpdate = managementViewModel::dismissUpdate,
            onConfirmUpdate = managementViewModel::confirmUpdate,
            onRequestRollback = managementViewModel::requestRollback,
            onDismissRollback = managementViewModel::dismissRollback,
            onConfirmRollback = managementViewModel::confirmRollback,
            onRequestDelete = managementViewModel::requestDelete,
            onDismissDelete = managementViewModel::dismissDelete,
            onConfirmDelete = managementViewModel::confirmDelete,
            onShowAdbPairDialog = managementViewModel::showAdbPairDialog,
            onDismissAdbPairDialog = managementViewModel::dismissAdbPairDialog,
            onAdbPair = managementViewModel::adbPair,
            onAdbConnect = managementViewModel::adbConnect,
            onAdbDisconnect = managementViewModel::adbDisconnect,
        )
    }

    composable(WORKSPACE_DETAIL_ROUTE) {
        val workspace = selectedWorkspace
        val runtime = selectedRuntime
        if (workspace == null || runtime == null) {
            LaunchedEffect(Unit) { navController.popBackStack() }
        } else {
            val explorerViewModel: WorkspaceExplorerViewModel =
                viewModel(
                    key = "workspace-explorer-${runtime.id}-${workspace.id}",
                    factory =
                        ViewModelFactory {
                            WorkspaceExplorerViewModel(runtime, workspace)
                        },
                )
            val explorerState by explorerViewModel.state.collectAsState()
            WorkspaceExplorerScreen(
                state = explorerState,
                onBack = { navController.popBackStack() },
                onRefresh = explorerViewModel::refresh,
                onOpenNode = explorerViewModel::open,
                onCloseFile = explorerViewModel::closeFile,
                onNavigateUp = explorerViewModel::navigateUp,
                onSearch = explorerViewModel::search,
                onRefreshChanges = explorerViewModel::refreshChanges,
                onOpenTerminal = { navController.navigate(ROUTE_TERMINAL) },
            )
        }
    }

    composable(ROUTE_TERMINAL) {
        val terminalViewModel: TerminalViewModel =
            viewModel(
                key = "terminal",
                factory =
                    ViewModelFactory {
                        TerminalViewModel(app.commandRunner)
                    },
            )
        val terminalState by terminalViewModel.state.collectAsState()
        TerminalScreen(
            state = terminalState,
            onCommand = terminalViewModel::executeCommand,
            onInputChange = terminalViewModel::updateInput,
            onClear = terminalViewModel::clear,
        )
    }

    composable("$ROUTE_CODE_VIEWER?filePath={filePath}") { backStack ->
        val filePath = backStack.arguments?.getString("filePath").orEmpty()
        CodeViewerScreen(
            fileName = filePath.substringAfterLast('/'),
            content = "",
            onBack = { navController.popBackStack() },
        )
    }
}
