package com.yugahashimoto.andcode.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.core.api.OpenCodeFileChange
import com.yugahashimoto.andcode.core.api.OpenCodeFileNode
import com.yugahashimoto.andcode.core.api.OpenCodeSearchMatch
import com.yugahashimoto.andcode.core.api.OpenCodeVcsInfo
import com.yugahashimoto.andcode.core.util.safeMessage
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkspaceExplorerUiState(
    val workspace: WorkspaceRef,
    val currentPath: String = ".",
    val files: List<OpenCodeFileNode> = emptyList(),
    val searchQuery: String = "",
    val textMatches: List<OpenCodeSearchMatch> = emptyList(),
    val fileMatches: List<String> = emptyList(),
    val vcsInfo: OpenCodeVcsInfo? = null,
    val changes: List<OpenCodeFileChange> = emptyList(),
    val diff: List<OpenCodeFileChange> = emptyList(),
    val isLoadingFiles: Boolean = false,
    val isSearching: Boolean = false,
    val isLoadingChanges: Boolean = false,
    val error: String? = null,
)

class WorkspaceExplorerViewModel(
    private val backend: OpenCodeBackend,
    workspace: WorkspaceRef,
) : ViewModel() {
    private val mutableState = MutableStateFlow(WorkspaceExplorerUiState(workspace = workspace))
    val state: StateFlow<WorkspaceExplorerUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        loadDirectory(mutableState.value.currentPath)
        refreshChanges()
    }

    fun open(node: OpenCodeFileNode) {
        if (node.type == "directory") {
            loadDirectory(node.path.trimEnd('/').ifBlank { "." })
        }
    }

    fun navigateUp() {
        val path = mutableState.value.currentPath.trimEnd('/')
        if (path == "." || path.isBlank()) return
        val parent = path.substringBeforeLast('/', missingDelimiterValue = ".").ifBlank { "." }
        loadDirectory(parent)
    }

    fun search(query: String) {
        val normalized = query.trim()
        mutableState.update {
            it.copy(
                searchQuery = query,
                isSearching = normalized.isNotEmpty(),
                textMatches = if (normalized.isEmpty()) emptyList() else it.textMatches,
                fileMatches = if (normalized.isEmpty()) emptyList() else it.fileMatches,
                error = null,
            )
        }
        if (normalized.isEmpty()) return

        viewModelScope.launch {
            val directory = mutableState.value.workspace.path
            val result =
                coroutineScope {
                    val text = async { runCatching { backend.searchText(directory, normalized) } }
                    val files =
                        async {
                            runCatching {
                                backend.findFiles(
                                    directory = directory,
                                    query = normalized,
                                    includeDirectories = false,
                                    type = "file",
                                    limit = 100,
                                )
                            }
                        }
                    text.await() to files.await()
                }
            val error = result.first.exceptionOrNull() ?: result.second.exceptionOrNull()
            mutableState.update {
                it.copy(
                    textMatches = result.first.getOrDefault(emptyList()),
                    fileMatches = result.second.getOrDefault(emptyList()),
                    isSearching = false,
                    error = error?.safeMessage("OpenCode workspace operation failed"),
                )
            }
        }
    }

    fun refreshChanges() {
        mutableState.update { it.copy(isLoadingChanges = true, error = null) }
        viewModelScope.launch {
            val directory = mutableState.value.workspace.path
            val result =
                coroutineScope {
                    val info = async { runCatching { backend.vcsInfo(directory) } }
                    val status = async { runCatching { backend.vcsStatus(directory) } }
                    val diff = async { runCatching { backend.vcsDiff(directory) } }
                    Triple(info.await(), status.await(), diff.await())
                }
            val error =
                result.first.exceptionOrNull()
                    ?: result.second.exceptionOrNull()
                    ?: result.third.exceptionOrNull()
            mutableState.update {
                it.copy(
                    vcsInfo = result.first.getOrNull(),
                    changes = result.second.getOrDefault(emptyList()),
                    diff = result.third.getOrDefault(emptyList()),
                    isLoadingChanges = false,
                    error = error?.takeUnless(::isNonGitWorkspace)?.safeMessage("OpenCode workspace operation failed"),
                )
            }
        }
    }

    private fun loadDirectory(path: String) {
        mutableState.update {
            it.copy(
                currentPath = path,
                files = emptyList(),
                isLoadingFiles = true,
                error = null,
            )
        }
        viewModelScope.launch {
            runCatching { backend.listFiles(mutableState.value.workspace.path, path) }
                .onSuccess { files ->
                    mutableState.update {
                        it.copy(
                            files =
                                files.sortedWith(
                                    compareBy<OpenCodeFileNode> { node -> node.type != "directory" }
                                        .thenBy { node -> node.name.lowercase() },
                                ),
                            isLoadingFiles = false,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(isLoadingFiles = false, error = error.safeMessage("OpenCode workspace operation failed"))
                    }
                }
        }
    }

    private fun isNonGitWorkspace(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase()
        return "git" in message && ("not" in message || "repository" in message)
    }
}
