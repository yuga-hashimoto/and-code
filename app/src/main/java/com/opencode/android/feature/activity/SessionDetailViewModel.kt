package com.opencode.android.feature.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.android.core.api.OpenCodeApiException
import com.opencode.android.core.api.OpenCodeFileChange
import com.opencode.android.core.api.OpenCodeSession
import com.opencode.android.core.api.OpenCodeTodo
import com.opencode.android.core.util.safeMessage
import com.opencode.android.runtime.OpenCodeBackend
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionDetailUiState(
    val session: OpenCodeSession,
    val todos: List<OpenCodeTodo> = emptyList(),
    val diff: List<OpenCodeFileChange> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class SessionDetailViewModel(
    private val backend: OpenCodeBackend,
    session: OpenCodeSession,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SessionDetailUiState(session = session))
    val state: StateFlow<SessionDetailUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        mutableState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val current = mutableState.value.session
            val result =
                coroutineScope {
                    val todos =
                        async {
                            runCatching { backend.sessionTodo(current.id, current.directory) }
                                .recoverCatching { error ->
                                    if (error is OpenCodeApiException && error.statusCode == 404) emptyList() else throw error
                                }
                        }
                    val diff = async { runCatching { backend.sessionDiff(current.id, current.directory) } }
                    todos.await() to diff.await()
                }
            val error = result.first.exceptionOrNull() ?: result.second.exceptionOrNull()
            mutableState.update {
                it.copy(
                    todos = result.first.getOrDefault(emptyList()),
                    diff = result.second.getOrDefault(emptyList()),
                    isLoading = false,
                    error = error?.safeMessage("OpenCode session details could not be loaded"),
                )
            }
        }
    }
}
