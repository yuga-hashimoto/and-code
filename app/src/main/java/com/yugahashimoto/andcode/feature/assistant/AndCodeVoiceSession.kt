package com.yugahashimoto.andcode.feature.assistant

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.feature.wakeword.WakeWordService
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.ui.theme.AndCodeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class AndCodeVoiceSession(context: Context) :
    VoiceInteractionSession(context),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    private val app = context.applicationContext as AndCodeApplication
    private val settings = app.settings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var speech: SpeechRecognizerManager
    private lateinit var tts: TTSManager
    private var backend: OpenCodeBackend? = null
    private var preferredWorkspace: String? = null
    private var eventJob: Job? = null
    private var listeningJob: Job? = null
    private var responseJob: Job? = null
    private var sessionToken: String? = null
    private var sessionVisible = false
    private var sessionEnded = true
    private var sessionId: String? = null
    private val responseParts = linkedMapOf<String, String>()
    private val textPartIds = mutableSetOf<String>()
    private var responseHandled = false

    @Volatile private var bargedIn = false

    private val assistantState = mutableStateOf(VoiceState.IDLE)
    private val userText = mutableStateOf("")
    private val responseText = mutableStateOf("")
    private val partialText = mutableStateOf("")
    private val errorText = mutableStateOf<String?>(null)
    private val permissionRequest = mutableStateOf<PermissionRequest?>(null)

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        speech = SpeechRecognizerManager(context)
        tts = TTSManager(context, ttsConfiguration())
    }

    private fun ttsConfiguration(): TTSProviderConfig = TtsConfiguration.from(settings.ttsSettings())

    override fun onCreateContentView(): View =
        ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@AndCodeVoiceSession)
            setViewTreeSavedStateRegistryOwner(this@AndCodeVoiceSession)
            setViewTreeViewModelStoreOwner(this@AndCodeVoiceSession)
            setContent {
                AndCodeTheme {
                    VoiceAssistantSurface(
                        state = assistantState.value,
                        userText = userText.value,
                        responseText = responseText.value,
                        partialText = partialText.value,
                        error = errorText.value,
                        permission = permissionRequest.value,
                        onClose = ::finish,
                        onRetry = ::startListening,
                        onPermission = ::respondPermission,
                    )
                }
            }
        }

    override fun onShow(
        args: Bundle?,
        showFlags: Int,
    ) {
        super.onShow(args, showFlags)
        sessionVisible = true
        sessionEnded = false
        sessionToken = args?.getString(AndCodeVoiceInteractionService.EXTRA_REQUEST_ID) ?: UUID.randomUUID().toString()
        sessionToken?.let(WakeWordService::pauseForSession)
        sessionToken?.let(WakeWordService::confirmSession)
        bargedIn = false
        WakeWordService.setInterruptListener(::onWakeWordInterrupt)
        speech.destroy()
        speech = SpeechRecognizerManager(context)
        responseHandled = false
        responseParts.clear()
        textPartIds.clear()
        tts.configure(ttsConfiguration())
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        val preferredRuntimeId = settings.assistantRuntimeId
        val target =
            app.runtimeRegistry.targets.value
                .firstOrNull { it.id == preferredRuntimeId }
                ?: app.runtimeRegistry.selected.value
        if (target == null) {
            assistantState.value = VoiceState.ERROR
            errorText.value = context.getString(R.string.voice_no_runtime_configured)
            return
        }

        backend = target
        preferredWorkspace = settings.assistantWorkspacePath
            ?.takeIf { it.isNotBlank() }
            ?: "/workspace"
        sessionId = settings.assistantSessionId.takeIf { settings.continuousConversation }
        startEventCollection()
        startListening()
    }

    override fun onHide() {
        sessionVisible = false
        listeningJob?.cancel()
        responseJob?.cancel()
        eventJob?.cancel()
        speech.destroy()
        tts.stop()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        endWakeWordPause()
        super.onHide()
    }

    override fun onDestroy() {
        sessionVisible = false
        responseJob?.cancel()
        scope.cancel()
        speech.destroy()
        tts.shutdown()
        viewModelStore.clear()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        endWakeWordPause()
        super.onDestroy()
    }

    private fun endWakeWordPause() {
        WakeWordService.setInterruptListener(null)
        if (sessionEnded) return
        sessionEnded = true
        sessionToken?.let(WakeWordService::resumeAfterSession)
        sessionToken = null
    }

    private fun startEventCollection() {
        val activeBackend = backend ?: return
        eventJob?.cancel()
        eventJob =
            scope.launch {
                activeBackend.events()
                    .catch { error -> showError(error.message ?: context.getString(R.string.voice_event_connection_failed)) }
                    .collect { event ->
                        when (event) {
                            is OpenCodeEvent.MessagePartUpdated -> {
                                if (event.part.sessionId == sessionId && event.part.type == "text") {
                                    val partKey = event.part.id ?: event.part.messageId ?: "text"
                                    textPartIds += partKey
                                    responseParts[partKey] = event.part.text.orEmpty()
                                    responseText.value = responseParts.values.joinToString("")
                                    assistantState.value = VoiceState.THINKING
                                }
                            }
                            is OpenCodeEvent.MessagePartDelta -> {
                                if (
                                    event.sessionId == sessionId &&
                                    event.field == "text" &&
                                    event.partId in textPartIds
                                ) {
                                    responseParts[event.partId] = responseParts[event.partId].orEmpty() + event.delta
                                    responseText.value = responseParts.values.joinToString("")
                                    assistantState.value = VoiceState.THINKING
                                }
                            }
                            is OpenCodeEvent.PermissionAsked -> {
                                if (event.request.sessionId == sessionId) {
                                    if (settings.autoAcceptPermissions) {
                                        val request = event.request
                                        val activeBackend = backend ?: return@collect
                                        scope.launch {
                                            runCatching {
                                                activeBackend.respondToPermission(
                                                    request.sessionId,
                                                    request.id,
                                                    PermissionResponse.ONCE,
                                                    false,
                                                )
                                            }
                                        }
                                    } else {
                                        permissionRequest.value = event.request
                                        assistantState.value = VoiceState.PERMISSION
                                    }
                                }
                            }
                            is OpenCodeEvent.SessionIdle -> {
                                if (event.sessionId == sessionId) onResponseComplete()
                            }
                            is OpenCodeEvent.SessionError -> {
                                if (event.sessionId == null || event.sessionId == sessionId) {
                                    showError(event.message ?: context.getString(R.string.voice_processing_failed))
                                }
                            }
                            is OpenCodeEvent.PermissionReplied -> {
                                if (
                                    event.sessionId == sessionId &&
                                    permissionRequest.value?.id == event.requestId
                                ) {
                                    permissionRequest.value = null
                                }
                            }
                            // session.idle above is the single completion signal for voice;
                            // handling session.status too would finish the same turn twice.
                            OpenCodeEvent.ServerConnected,
                            is OpenCodeEvent.MessageUpdated,
                            is OpenCodeEvent.SessionCreated,
                            is OpenCodeEvent.SessionUpdated,
                            is OpenCodeEvent.SessionStatusChanged,
                            is OpenCodeEvent.QuestionAsked,
                            is OpenCodeEvent.Unknown,
                            -> Unit
                        }
                    }
            }
    }

    private fun startListening() {
        if (backend == null) return
        listeningJob?.cancel()
        userText.value = ""
        responseParts.clear()
        textPartIds.clear()
        responseText.value = ""
        partialText.value = ""
        errorText.value = null
        permissionRequest.value = null
        responseHandled = false
        assistantState.value = VoiceState.LISTENING

        listeningJob =
            scope.launch {
                var silentSegments = 0
                while (isActive) {
                    var outcome = VoiceDictationOutcome.REPORT
                    speech.startListening(language = speechLanguageTag(context)).collect { result ->
                        when (result) {
                            SpeechResult.Ready,
                            SpeechResult.Listening,
                            -> assistantState.value = VoiceState.LISTENING
                            SpeechResult.Processing -> assistantState.value = VoiceState.THINKING
                            is SpeechResult.PartialResult -> partialText.value = result.text
                            is SpeechResult.Result -> {
                                silentSegments = 0
                                submitRecognizedText(result.text)
                                outcome = VoiceDictationOutcome.FINISH
                            }
                            is SpeechResult.Error -> {
                                // Some recognisers deliver a partial utterance followed by an
                                // empty final bundle. Keep that utterance instead of discarding it.
                                val partial = partialText.value.trim()
                                outcome =
                                    VoiceDictationPolicy.outcomeFor(
                                        code = result.code,
                                        hasTranscript = partial.isNotBlank(),
                                        consecutiveFailures = silentSegments + 1,
                                    )
                                when (outcome) {
                                    VoiceDictationOutcome.RESTART -> silentSegments++
                                    VoiceDictationOutcome.FINISH -> submitRecognizedText(partial)
                                    VoiceDictationOutcome.REPORT -> showError(result.message)
                                }
                            }
                        }
                    }
                    if (outcome != VoiceDictationOutcome.RESTART) break
                    partialText.value = ""
                    assistantState.value = VoiceState.LISTENING
                    delay(SPEECH_RESTART_DELAY_MS)
                }
            }
    }

    private fun submitRecognizedText(text: String) {
        userText.value = text
        partialText.value = ""
        sendToOpenCode(text)
    }

    private fun sendToOpenCode(text: String) {
        val activeBackend = backend ?: return
        assistantState.value = VoiceState.THINKING
        responseText.value = ""
        scope.launch {
            runCatching {
                val targetSessionId =
                    sessionId ?: activeBackend.createSession(
                        title = "Voice: ${text.take(48)}",
                        directory = preferredWorkspace,
                    ).id
                sessionId = targetSessionId
                if (settings.continuousConversation) settings.assistantSessionId = targetSessionId
                activeBackend.sendMessage(
                    targetSessionId,
                    PromptRequest(
                        text = text,
                        providerId = settings.assistantProviderId ?: settings.selectedProviderId,
                        modelId = settings.assistantModelId ?: settings.selectedModelId,
                        agent = settings.selectedAgentId,
                    ),
                )
            }.onFailure { showError(it.message ?: context.getString(R.string.voice_send_failed)) }
        }
    }

    private fun onResponseComplete() {
        if (responseHandled) return
        responseHandled = true
        val answer = responseText.value.trim()
        if (answer.isEmpty()) {
            showError(context.getString(R.string.voice_no_answer_text))
            return
        }
        responseJob =
            scope.launch {
                assistantState.value = VoiceState.SPEAKING
                val spoken = !settings.ttsEnabled || speakInterruptibly(answer)
                if (!sessionVisible) return@launch
                if (bargedIn) {
                    // The wake word cut in. Take the question that prompted it rather than
                    // announcing the answer nobody is still listening to.
                    bargedIn = false
                    assistantState.value = VoiceState.DONE
                    if (sessionVisible) startListening()
                    return@launch
                }
                if (!spoken) {
                    showError(context.getString(R.string.tts_error))
                    return@launch
                }
                assistantState.value = VoiceState.DONE
                if (settings.continuousConversation && sessionVisible) {
                    delay(300)
                    if (sessionVisible) startListening()
                }
            }
    }

    /**
     * Reads [answer] out with the wake word left listening, so it can be cut short.
     *
     * The wake-word service is otherwise stopped for the whole session; it is handed the
     * microphone back for exactly the stretch where this session is not using it, and told to give
     * it up again the moment playback ends however it ends.
     */
    private suspend fun speakInterruptibly(answer: String): Boolean {
        val token = sessionToken
        token?.let { WakeWordService.setSpeaking(it, true) }
        return try {
            tts.speak(answer)
        } finally {
            token?.let { WakeWordService.setSpeaking(it, false) }
        }
    }

    private fun onWakeWordInterrupt() {
        if (!sessionVisible) return
        bargedIn = true
        tts.stop()
    }

    private fun respondPermission(response: PermissionResponse) {
        val activeBackend = backend ?: return
        val request = permissionRequest.value ?: return
        scope.launch {
            runCatching {
                activeBackend.respondToPermission(
                    sessionId = request.sessionId,
                    permissionId = request.id,
                    response = response,
                    remember = response == PermissionResponse.ALWAYS,
                )
            }.onSuccess {
                permissionRequest.value = null
                assistantState.value = VoiceState.THINKING
            }.onFailure { showError(it.message ?: context.getString(R.string.voice_permission_send_failed)) }
        }
    }

    private fun showError(message: String) {
        assistantState.value = VoiceState.ERROR
        errorText.value = message
    }
}

private enum class VoiceState {
    IDLE,
    LISTENING,
    THINKING,
    PERMISSION,
    SPEAKING,
    DONE,
    ERROR,
}

private const val SPEECH_RESTART_DELAY_MS = 200L

private fun speechLanguageTag(context: Context): String {
    val locale =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
    return locale?.toLanguageTag()?.takeIf { it.isNotBlank() } ?: "en-US"
}

@Composable
private fun VoiceAssistantSurface(
    state: VoiceState,
    userText: String,
    responseText: String,
    partialText: String,
    error: String?,
    permission: PermissionRequest?,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onPermission: (PermissionResponse) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("AndCode", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.home_assistant), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close_description))
            }
        }

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        ) {
            if (state == VoiceState.THINKING || state == VoiceState.SPEAKING) {
                CircularProgressIndicator(modifier = Modifier.padding(24.dp))
            } else {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = stringResource(R.string.cd_microphone),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }

        Text(
            text =
                when (state) {
                    VoiceState.LISTENING -> stringResource(R.string.voice_state_listening)
                    VoiceState.THINKING -> stringResource(R.string.voice_state_thinking)
                    VoiceState.PERMISSION -> stringResource(R.string.permission_required)
                    VoiceState.SPEAKING -> stringResource(R.string.voice_state_speaking)
                    VoiceState.DONE -> stringResource(R.string.voice_state_done)
                    VoiceState.ERROR -> stringResource(R.string.voice_state_error)
                    VoiceState.IDLE -> stringResource(R.string.voice_state_idle)
                },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )

        val spoken = partialText.ifBlank { userText }
        if (spoken.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(spoken, modifier = Modifier.padding(14.dp))
            }
        }

        if (responseText.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(responseText, modifier = Modifier.padding(14.dp))
            }
        }

        permission?.let { request ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f)),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = stringResource(R.string.cd_permission))
                        Spacer(Modifier.padding(horizontal = 5.dp))
                        Text(request.permission, fontWeight = FontWeight.SemiBold)
                    }
                    request.patterns.forEach {
                        Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = { onPermission(PermissionResponse.REJECT) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.reject)) }
                        FilledTonalButton(
                            onClick = { onPermission(PermissionResponse.ONCE) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.allow_once)) }
                        Button(
                            onClick = { onPermission(PermissionResponse.ALWAYS) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.always_allow)) }
                    }
                }
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry) { Text(stringResource(R.string.voice_retry_button)) }
        }
        Spacer(Modifier.height(8.dp))
    }
}
