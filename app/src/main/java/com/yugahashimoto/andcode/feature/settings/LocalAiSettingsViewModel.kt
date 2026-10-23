package com.yugahashimoto.andcode.feature.settings

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.data.connection.SecureSettingsRepository
import com.yugahashimoto.andcode.runtime.local.LocalAiController
import com.yugahashimoto.andcode.runtime.local.LocalAiModel
import com.yugahashimoto.andcode.runtime.local.LocalAiModelScanner
import com.yugahashimoto.andcode.runtime.local.LocalAiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class LocalAiUiState(
    val runtimeState: LocalAiState = LocalAiState.Idle,
    val models: List<LocalAiModel> = emptyList(),
    val selectedModel: LocalAiModel? = null,
    val scanning: Boolean = false,
    val importing: Boolean = false,
    val importResult: ImportResult? = null,
)

/** Outcome of a multi-file import, rendered by the settings screen. */
data class ImportResult(
    val ok: Int,
    val skipped: Int,
    val errors: List<String>,
)

class LocalAiSettingsViewModel(
    private val scanner: LocalAiModelScanner,
    private val controller: LocalAiController,
    private val settings: SecureSettingsRepository,
    private val modelsDir: File,
) : ViewModel() {
    private data class ImportUi(val importing: Boolean = false, val result: ImportResult? = null)

    private val models = MutableStateFlow<List<LocalAiModel>>(emptyList())
    private val selected = MutableStateFlow<LocalAiModel?>(null)
    private val scanning = MutableStateFlow(false)
    private val importUi = MutableStateFlow(ImportUi())

    val state: StateFlow<LocalAiUiState> =
        combine(
            controller.state,
            models,
            selected,
            scanning,
            importUi,
        ) { runtimeState, modelList, selectedModel, isScanning, import ->
            LocalAiUiState(
                runtimeState = runtimeState,
                models = modelList,
                selectedModel = selectedModel,
                scanning = isScanning,
                importing = import.importing,
                importResult = import.result,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            LocalAiUiState(),
        )

    init {
        refreshModels()
    }

    fun refreshModels() {
        viewModelScope.launch {
            scanning.value = true
            val found = scanner.scan()
            models.value = found
            val persisted = settings.localAiModelPath?.let { File(it) }
            selected.value =
                found.firstOrNull { it.file == persisted } ?: persisted?.let { p ->
                    found.firstOrNull { it.file.absolutePath == p.absolutePath }
                } ?: found.firstOrNull()
            scanning.value = false
        }
    }

    /** Copies user-picked documents (.gguf) into [modelsDir], then refreshes the model list. */
    fun importModels(uris: List<Uri>, resolver: ContentResolver) {
        val pending = uris.filter { it != Uri.EMPTY }
        if (pending.isEmpty()) return
        viewModelScope.launch {
            importUi.value = ImportUi(importing = true)
            var ok = 0
            var skipped = 0
            val errors = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                modelsDir.mkdirs()
                pending.forEach { uri ->
                    val name = queryDisplayName(uri, resolver)
                    if (name == null || !name.endsWith(".gguf", ignoreCase = true)) {
                        skipped++
                        return@forEach
                    }
                    val target = File(modelsDir, name)
                    if (target.exists()) {
                        skipped++
                        return@forEach
                    }
                    try {
                        val input = resolver.openInputStream(uri)
                            ?: run {
                                errors += name
                                return@forEach
                            }
                        input.use { source ->
                            target.outputStream().use { sink ->
                                source.copyTo(sink, 64 * 1024)
                            }
                        }
                        ok++
                    } catch (error: Exception) {
                        errors += name
                    }
                }
            }
            importUi.value = ImportUi(importing = false, result = ImportResult(ok, skipped, errors))
            refreshModels()
        }
    }

    fun select(model: LocalAiModel) {
        selected.value = model
        settings.localAiModelPath = model.file.absolutePath
    }

    fun start() {
        controller.start()
    }

    fun stop() {
        controller.stop()
    }

    private fun queryDisplayName(uri: Uri, resolver: ContentResolver): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        }.getOrNull()
}
