package com.yugahashimoto.andcode.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.core.util.safeMessage
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CodeViewerUiState(
    val filePath: String,
    val content: String = "",
    val isLoading: Boolean = true,
    val isBinary: Boolean = false,
    val error: String? = null,
)

class CodeViewerViewModel(
    private val backend: OpenCodeBackend,
    private val workspacePath: String,
    filePath: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CodeViewerUiState(filePath = filePath))
    val state: StateFlow<CodeViewerUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        mutableState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatching { backend.readFile(workspacePath, mutableState.value.filePath) }
                .onSuccess { file ->
                    mutableState.update {
                        it.copy(
                            content = file.content,
                            isLoading = false,
                            isBinary = file.type == "binary" || file.encoding == "base64",
                        )
                    }
                }.onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            error = error.safeMessage("OpenCode workspace operation failed"),
                        )
                    }
                }
        }
    }
}
