package com.yugahashimoto.andcode.feature.wakeword

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.MainActivity
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.feature.assistant.AndCodeVoiceInteractionService
import com.yugahashimoto.andcode.feature.assistant.AssistantStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listenJob: Job? = null

    @Volatile private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // What the running recogniser was actually built from, which is not the same as what settings
    // hold: all three are read once when it is built, so a change to any of them only reaches
    // detection by building a new one.
    @Volatile private var currentLanguage = VoskModelLanguage.ENGLISH

    @Volatile private var currentPhrase: String? = null

    @Volatile private var currentSensitivity: Float? = null

    @Volatile private var assistantRequestId: String? = null
    private var assistantTimeoutJob: Job? = null

    // Barge-in state. A session owns the microphone through its own recogniser for everything
    // except the stretch where it is reading an answer out, which is the only window detection may
    // reclaim it in.
    @Volatile private var sessionActive = false

    @Volatile private var speaking = false

    // Detection is not the only microphone reader in the app: chat dictation runs through
    // SpeechRecognizer, which records from *another* process. Two live capture clients make the
    // platform silence one of them, and the one it keeps is this service - so the recogniser hears
    // nothing but zeroes and reports "no match". Whoever needs the microphone takes this hold and
    // detection stands down until it is handed back.
    @Volatile private var micHoldToken: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                persistEnabled(false)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val preferences = (application as? AndCodeApplication)?.preferences?.state?.value
        if (
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !AssistantStatus.isActive(this) ||
            (intent == null && preferences?.wakeWordEnabled != true)
        ) {
            persistEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        val language =
            VoskModelLanguage.fromId(intent?.getStringExtra(EXTRA_LANGUAGE) ?: settings()?.wakeWordModelLanguage)
                ?: VoskModelCatalog.defaultLanguageFor(Locale.getDefault())
        // The model is downloaded, not packaged, so it can genuinely be absent here - the settings
        // screen fetches it before switching this on, but a cleared app storage would not have.
        if (voskModels()?.isInstalled(language) != true) {
            Log.e(TAG, "No speech model installed for ${language.id}")
            persistEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { startForegroundWithNotification() }
            .onFailure {
                Log.e(TAG, "Unable to start wake-word foreground service", it)
                persistEnabled(false)
                stopSelf()
                return START_NOT_STICKY
            }
        if (assistantRequestId != null) {
            currentLanguage = language
        } else {
            startListening(language)
        }
        return START_STICKY
    }

    private fun phrase(): String = settings()?.wakeWordPhrase.orEmpty()

    private fun sensitivity(): Float = settings()?.wakeWordSensitivity ?: DEFAULT_SENSITIVITY

    override fun onDestroy() {
        stopListening()
        assistantTimeoutJob?.cancel()
        if (activeInstance === this) activeInstance = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wake_word_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)

        val tapIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stopIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, WakeWordService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.wake_word_notification_title))
                .setContentText(
                    getString(
                        R.string.wake_word_notification_text,
                        WakeWordGrammar.normalize(settings()?.wakeWordPhrase.orEmpty()),
                    ),
                )
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(tapIntent)
                .addAction(0, getString(R.string.wake_word_notification_stop), stopIntent)
                .setOngoing(true)
                .setSilent(true)
                .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * @param resetSession clears the session this service is waiting on. False when detection is
     *   being resumed *inside* a live session for barge-in, where forgetting the session would
     *   turn the next hit into a second assistant on top of the running one.
     */
    @Synchronized
    private fun startListening(
        language: VoskModelLanguage,
        resetSession: Boolean = true,
    ) {
        if (micHoldToken != null) {
            // The holder gets detection back when it releases the microphone, and the recogniser it
            // comes back with is built then - so a phrase applied during a hold still lands.
            currentLanguage = language
            return
        }
        val phrase = phrase()
        val sensitivity = sensitivity()
        // Comparing the language alone used to make an in-place restart a no-op, so editing the
        // phrase left the old recogniser listening while the notification already advertised the
        // new one - the only way to actually apply a phrase was to switch the wake word off and on.
        if (
            listenJob?.isActive == true &&
            currentLanguage == language &&
            currentPhrase == phrase &&
            currentSensitivity == sensitivity
        ) {
            return
        }

        val previousJob = listenJob
        currentLanguage = language
        currentPhrase = phrase
        currentSensitivity = sensitivity
        stopAudioRecord()
        previousJob?.cancel()
        if (resetSession) {
            assistantTimeoutJob?.cancel()
            assistantRequestId = null
            sessionActive = false
            speaking = false
        }

        listenJob =
            scope.launch {
                previousJob?.join()
                val pm = getSystemService(PowerManager::class.java)
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock =
                    pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                        acquire(WAKELOCK_TIMEOUT)
                    }
                val modelDirectory = voskModels()?.directoryFor(language)
                if (modelDirectory == null) {
                    Log.e(TAG, "Speech model for ${language.id} disappeared")
                    persistEnabled(false)
                    stopSelf()
                    return@launch
                }
                // Vosk drops grammar entries the model has no dictionary entry for, leaving a
                // recogniser that can never fire. Listening with one burns the battery and holds
                // the microphone indicator on while looking, from the outside, exactly like a bug
                // in the microphone - so it is refused rather than run.
                val unknown = VoskVocabulary.unknownWords(modelDirectory, phrase)
                if (!unknown.isNullOrEmpty()) {
                    Log.e(TAG, "The ${language.id} model does not know $unknown - refusing to listen")
                    persistEnabled(false)
                    stopSelf()
                    return@launch
                }
                val det =
                    VoskWakeWordDetector(
                        modelDirectory = modelDirectory,
                        phrase = phrase,
                        sensitivity = sensitivity,
                    )
                if (!det.initialize()) {
                    Log.e(TAG, "Detector initialization failed")
                    det.release()
                    persistEnabled(false)
                    stopSelf()
                    return@launch
                }

                val bufferSize =
                    maxOf(
                        AudioRecord.getMinBufferSize(
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                        ),
                        FRAME_SIZE * 2,
                    )

                val record =
                    try {
                        AudioRecord(
                            MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize,
                        )
                    } catch (e: SecurityException) {
                        Log.e(TAG, "No microphone permission", e)
                        det.release()
                        persistEnabled(false)
                        stopSelf()
                        return@launch
                    }

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    record.release()
                    det.release()
                    persistEnabled(false)
                    stopSelf()
                    return@launch
                }

                val buffer = ShortArray(FRAME_SIZE)
                var detected = false

                try {
                    audioRecord = record
                    record.startRecording()
                    Log.i(TAG, "Wake word listening started")
                    while (isActive) {
                        if (wakeLock?.isHeld != true) wakeLock?.acquire(WAKELOCK_TIMEOUT)
                        val read = record.read(buffer, 0, FRAME_SIZE)
                        if (read < 0) error("AudioRecord read failed with code $read")
                        if (read == 0) continue

                        val result = det.processAudio(buffer, read)
                        if (result != null) {
                            Log.i(TAG, "Wake word detected: ${result.phrase} (${result.confidence})")
                            detected = true
                            break
                        }
                    }
                } catch (error: RuntimeException) {
                    if (isActive) {
                        Log.e(TAG, "Wake-word audio capture failed", error)
                        persistEnabled(false)
                        stopSelf()
                    }
                } finally {
                    runCatching { record.stop() }
                    record.release()
                    if (audioRecord === record) audioRecord = null
                    det.release()
                    wakeLock?.let { if (it.isHeld) it.release() }
                    wakeLock = null
                    Log.i(TAG, "Wake word listening stopped")
                }
                if (detected && isActive) onDetected()
            }
    }

    private fun onDetected() {
        when (BargeInPolicy.outcomeFor(sessionActive, speaking, bargeInEnabled())) {
            WakeWordOutcome.START_SESSION -> requestAssistant()
            WakeWordOutcome.INTERRUPT_SPEECH -> {
                // Detection has already stopped, and stays stopped: the session hands the
                // microphone back through speechStarted if it has more to read out.
                speaking = false
                Log.i(TAG, "Wake word interrupted playback")
                interruptListener?.invoke()
            }
            WakeWordOutcome.IGNORE -> Unit
        }
    }

    private fun bargeInEnabled(): Boolean = app()?.preferences?.state?.value?.ttsBargeInEnabled ?: true

    private fun app(): AndCodeApplication? = application as? AndCodeApplication

    private fun settings() = app()?.settings

    private fun voskModels() = app()?.voskModels

    private fun requestAssistant() {
        if (!AssistantStatus.isActive(this)) {
            Log.e(TAG, "Wake word disabled because AndCode is no longer the active assistant")
            persistEnabled(false)
            stopSelf()
            return
        }
        val requestId = UUID.randomUUID().toString()
        assistantRequestId = requestId
        assistantTimeoutJob?.cancel()
        assistantTimeoutJob =
            scope.launch {
                delay(ASSISTANT_SHOW_TIMEOUT_MS)
                if (assistantRequestId == requestId) {
                    Log.e(TAG, "Timed out waiting for the active assistant")
                    resumeAfterSession(requestId)
                }
            }
        AndCodeVoiceInteractionService.show(this, requestId)
    }

    @Synchronized
    private fun pauseForSessionInternal(requestId: String) {
        assistantRequestId = requestId
        sessionActive = true
        speaking = false
        stopListening()
    }

    @Synchronized
    private fun holdMicrophoneInternal(token: String) {
        micHoldToken = token
        stopListening()
    }

    @Synchronized
    private fun releaseMicrophoneInternal(token: String) {
        if (micHoldToken != token) return
        micHoldToken = null
        // An assistant session owns the microphone on its own terms, and hands detection back
        // itself through setSpeaking and resumeAfterSession.
        if (assistantRequestId != null) return
        startListening(currentLanguage, resetSession = false)
    }

    @Synchronized
    private fun setSpeakingInternal(
        requestId: String,
        isSpeaking: Boolean,
    ) {
        if (assistantRequestId != requestId) return
        speaking = isSpeaking
        if (BargeInPolicy.shouldListenDuringSession(isSpeaking, bargeInEnabled())) {
            startListening(currentLanguage, resetSession = false)
        } else {
            stopListening()
        }
    }

    @Synchronized
    private fun confirmSessionInternal(requestId: String) {
        if (assistantRequestId != requestId) return
        assistantTimeoutJob?.cancel()
        assistantTimeoutJob = null
    }

    @Synchronized
    private fun resumeAfterSessionInternal(requestId: String) {
        if (assistantRequestId != requestId) return
        assistantRequestId = null
        assistantTimeoutJob?.cancel()
        assistantTimeoutJob = null
        sessionActive = false
        speaking = false
        // Barge-in may have left a listen job running for this very model, which startListening
        // would take as "already listening" and return from without clearing the session state.
        stopListening()
        startListening(currentLanguage)
    }

    @Synchronized
    private fun stopListening() {
        stopAudioRecord()
        listenJob?.cancel()
        listenJob = null
    }

    private fun stopAudioRecord() {
        audioRecord?.let { record ->
            runCatching { record.stop() }
        }
    }

    private fun persistEnabled(enabled: Boolean) {
        (application as? AndCodeApplication)?.preferences?.setWakeWordEnabled(enabled)
    }

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "wakeword_channel"
        private const val NOTIFICATION_ID = 9001
        private const val ACTION_STOP = "com.yugahashimoto.andcode.action.STOP_WAKEWORD"
        private const val EXTRA_LANGUAGE = "wake_word_model_language"
        private const val DEFAULT_SENSITIVITY = 0.7f
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 1280
        private const val WAKELOCK_TAG = "opencode:wakeword"
        private const val WAKELOCK_TIMEOUT = 10 * 60 * 1000L
        private const val ASSISTANT_SHOW_TIMEOUT_MS = 5_000L

        @Volatile private var activeInstance: WakeWordService? = null

        /**
         * Called on the voice session when the wake word lands mid-playback.
         *
         * A direct callback rather than a broadcast: both live in this process, and an interrupt
         * that arrives after the sentence has finished is worse than none at all.
         */
        @Volatile private var interruptListener: (() -> Unit)? = null

        fun setInterruptListener(listener: (() -> Unit)?) {
            interruptListener = listener
        }

        /** Tells the service whether the assistant is reading an answer out right now. */
        fun setSpeaking(
            requestId: String,
            speaking: Boolean,
        ) {
            activeInstance?.setSpeakingInternal(requestId, speaking)
        }

        /**
         * Stops wake-word capture so another recogniser in this app can actually hear the user.
         * A no-op when the service is not running, and safe to call more than once.
         */
        fun holdMicrophone(token: String) {
            activeInstance?.holdMicrophoneInternal(token)
        }

        /** Hands the microphone back and resumes detection, unless a session is holding it. */
        fun releaseMicrophone(token: String) {
            activeInstance?.releaseMicrophoneInternal(token)
        }

        fun pauseForSession(requestId: String) {
            activeInstance?.pauseForSessionInternal(requestId)
        }

        fun confirmSession(requestId: String) {
            activeInstance?.confirmSessionInternal(requestId)
        }

        fun resumeAfterSession(requestId: String) {
            activeInstance?.resumeAfterSessionInternal(requestId)
        }

        fun start(
            context: Context,
            language: VoskModelLanguage,
        ): Boolean {
            if (
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED || !AssistantStatus.isActive(context)
            ) {
                return false
            }
            val intent = Intent(context, WakeWordService::class.java)
            intent.putExtra(EXTRA_LANGUAGE, language.id)
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.isSuccess
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
