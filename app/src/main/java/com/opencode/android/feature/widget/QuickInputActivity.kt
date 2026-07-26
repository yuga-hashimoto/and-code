package com.opencode.android.feature.widget

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.opencode.android.OpenCodeApplication
import com.opencode.android.R
import com.opencode.android.core.api.PromptRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickInputActivity : ComponentActivity() {
    private var speechRecognizer: SpeechRecognizer? = null
    private var pendingInputField: EditText? = null
    private var pendingStatusText: TextView? = null

    private val audioPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                Toast.makeText(this, getString(R.string.toast_permission_granted), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.toast_mic_permission_required), Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 0, 32, 48)
            }

        val statusText =
            TextView(this).apply {
                textSize = 14f
                setTextColor(0xFFAAAAAA.toInt())
                setPadding(0, 0, 0, 16)
            }
        rootLayout.addView(statusText)

        val inputRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

        val inputField =
            EditText(this).apply {
                hint = getString(R.string.widget_quick_input_hint)
                isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        inputRow.addView(inputField)

        val sendButton =
            ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_send)
                layoutParams = LinearLayout.LayoutParams(96, 96)
            }
        inputRow.addView(sendButton)

        val micButton =
            ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_btn_speak_now)
                layoutParams =
                    LinearLayout.LayoutParams(96, 96).apply {
                        marginStart = 8
                    }
            }
        inputRow.addView(micButton)

        rootLayout.addView(inputRow)
        setContentView(rootLayout)

        sendButton.setOnClickListener {
            val text = inputField.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text, statusText)
            }
        }

        micButton.setOnClickListener {
            startVoiceInput(inputField, statusText)
        }

        when (intent?.action) {
            QuickInputWidgetProvider.ACTION_MIC -> startVoiceInput(inputField, statusText)
            QuickInputWidgetProvider.ACTION_SEND -> {
                val text = intent.getStringExtra(QuickInputWidgetProvider.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    inputField.setText(text)
                    sendMessage(text, statusText)
                }
            }
        }
    }

    private fun sendMessage(
        text: String,
        statusText: TextView,
    ) {
        val app = application as OpenCodeApplication
        val runtime = app.runtimeRegistry.selected.value

        if (runtime == null) {
            statusText.text = getString(R.string.widget_no_runtime)
            statusText.setTextColor(0xFFFF5555.toInt())
            finishAfterDelay()
            return
        }

        statusText.text = getString(R.string.widget_sending)
        statusText.setTextColor(0xFFAAAAAA.toInt())

        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val title = getString(R.string.widget_session_title, text.take(30))
                        val session = runtime.createSession(title = title)
                        runtime.sendMessage(session.id, PromptRequest(text = text))
                    }
                }

            result.onSuccess {
                statusText.text = getString(R.string.widget_sent)
                statusText.setTextColor(0xFF55FF55.toInt())
                finishAfterDelay()
            }.onFailure { error ->
                statusText.text = getString(R.string.widget_error, error.message)
                statusText.setTextColor(0xFFFF5555.toInt())
                finishAfterDelay(3000L)
            }
        }
    }

    private fun startVoiceInput(
        inputField: EditText,
        statusText: TextView,
    ) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer

        recognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    statusText.text = getString(R.string.widget_listening)
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    statusText.text = getString(R.string.widget_processing)
                }

                override fun onError(error: Int) {
                    statusText.text = getString(R.string.widget_voice_error)
                    statusText.setTextColor(0xFFFF5555.toInt())
                    finishAfterDelay()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        inputField.setText(text)
                        sendMessage(text, statusText)
                    } else {
                        statusText.text = getString(R.string.widget_no_speech)
                        finishAfterDelay()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.firstOrNull()?.let { inputField.setText(it) }
                }

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?,
                ) {}
            },
        )

        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
        recognizer.startListening(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
    }

    private fun finishAfterDelay(delayMs: Long = 1200L) {
        lifecycleScope.launch {
            delay(delayMs)
            finish()
        }
    }

    private companion object {
    }
}
