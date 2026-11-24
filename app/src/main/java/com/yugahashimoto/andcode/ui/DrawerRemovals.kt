package com.yugahashimoto.andcode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class RemovalKind { DELETE, ARCHIVE }

/** Null when the removal took effect on at least one runtime, otherwise why it did not. */
internal fun removalFailure(
    kind: RemovalKind,
    results: List<Result<Boolean>>,
): Throwable? {
    if (results.isEmpty()) return null
    val succeeded =
        when (kind) {
            RemovalKind.DELETE -> results.any { it.getOrNull() == true }
            RemovalKind.ARCHIVE -> results.any { it.isSuccess }
        }
    if (succeeded) return null
    return results.firstNotNullOfOrNull { it.exceptionOrNull() }
        ?: IllegalStateException("session removal failed")
}

/**
 * Hides a chat as soon as its delete/archive is requested and only commits after the undo offer
 * ends. A still-running offer is ended by the caller dismissing the previous snackbar, so the older
 * removal commits immediately; two offers never overlap.
 *
 * If the owning scope is cancelled inside the undo window the commit never runs - the safe failure
 * for a torn-down composition is that the chat simply reappears.
 */
internal class DrawerRemovals(
    private val scope: CoroutineScope,
    private val offerUndo: suspend (RemovalKind) -> Boolean,
    private val commit: suspend (String, RemovalKind) -> Unit,
    private val onCommitFailed: (String, RemovalKind, Throwable) -> Unit = { _, _, _ -> },
) {
    private val mutablePending = MutableStateFlow<Set<String>>(emptySet())
    val pending: StateFlow<Set<String>> = mutablePending.asStateFlow()

    fun remove(
        id: String,
        kind: RemovalKind,
    ) {
        if (id in mutablePending.value) return
        mutablePending.update { it + id }

        scope.launch {
            try {
                if (offerUndo(kind)) return@launch
                commit(id, kind)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                onCommitFailed(id, kind, failure)
            } finally {
                // Also runs when the commit fails or is cancelled: the row is allowed back.
                mutablePending.update { it - id }
            }
        }
    }
}
