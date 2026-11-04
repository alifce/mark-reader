package com.markreader.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Why dictation stopped on its own. */
enum class DictationError { NoPermission, Unavailable, Network, Microphone, Language, Silence, Other }

/** Languages offered by the dictation button. */
enum class DictationLanguage(val tag: String, val label: String) {
    Bangla("bn-BD", "বাংলা"),
    English("en-US", "EN");

    fun next(): DictationLanguage = if (this == Bangla) English else Bangla
}

/**
 * Joins a dictated phrase into the text around the cursor, adding a space on either side only
 * where the neighbouring text doesn't already provide one.
 */
internal fun joinDictated(before: String, spoken: String, after: String): String {
    val lead = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
    val trail = if (after.isNotEmpty() && !after.first().isWhitespace()) " " else ""
    return lead + spoken + trail
}

/**
 * Keeps the system speech recogniser listening: it hears one phrase, reports it, and starts again
 * until [stop] is called. Must be used from the main thread.
 *
 * Android's recogniser ends after every pause, so the restart loop is what makes this feel like
 * continuous dictation. It gives up after [MAX_QUIET_ROUNDS] rounds in a row with nothing heard,
 * so a forgotten microphone does not stay open.
 */
class DictationController(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onListening()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onStopped()
        fun onError(error: DictationError)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var wanted = false
    private var quietRounds = 0
    private var language = DictationLanguage.Bangla

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun setLanguage(value: DictationLanguage) {
        language = value // Picked up by the next round.
    }

    fun start(value: DictationLanguage) {
        language = value
        wanted = true
        quietRounds = 0
        listen()
    }

    /** Lets the phrase in progress finish, then stops. */
    fun stop() {
        wanted = false
        handler.removeCallbacksAndMessages(null)
        val active = recognizer
        if (active == null) {
            listener.onStopped()
        } else {
            active.stopListening() // The result (if any) still arrives, then onResults/onError ends it.
            // Between two rounds nothing is listening, so no callback will come: close it ourselves.
            handler.postDelayed({ if (!wanted && recognizer != null) finish() }, STOP_GRACE_MS)
        }
    }

    fun release() {
        wanted = false
        handler.removeCallbacksAndMessages(null)
        destroy()
    }

    private fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        if (!wanted) return
        val active = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(callbacks)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            active.startListening(intent)
        } catch (_: SecurityException) {
            fail(DictationError.NoPermission)
        } catch (_: IllegalStateException) {
            fail(DictationError.Other)
        }
    }

    private fun restart(delayMs: Long) {
        if (!wanted) {
            finish()
            return
        }
        quietRounds++
        if (quietRounds > MAX_QUIET_ROUNDS) {
            fail(DictationError.Silence)
            return
        }
        handler.postDelayed({ listen() }, delayMs)
    }

    private fun finish() {
        destroy()
        listener.onStopped()
    }

    private fun fail(error: DictationError) {
        wanted = false
        handler.removeCallbacksAndMessages(null)
        destroy()
        listener.onError(error)
        listener.onStopped()
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = listener.onListening()

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults.firstResult() ?: return
            quietRounds = 0
            listener.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            val text = results.firstResult()?.trim().orEmpty()
            listener.onPartial("")
            if (text.isNotEmpty()) {
                quietRounds = 0
                listener.onFinal(text)
            }
            restart(RESTART_DELAY_MS)
        }

        override fun onError(error: Int) {
            listener.onPartial("")
            when (error) {
                // Nothing heard, or our own cancel: just listen again.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_CLIENT -> restart(RESTART_DELAY_MS)
                // The recogniser is still shutting down the last round: rebuild it and retry.
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
                ERROR_SERVER_DISCONNECTED -> {
                    destroy()
                    restart(BUSY_DELAY_MS)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail(DictationError.NoPermission)
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> fail(DictationError.Network)
                SpeechRecognizer.ERROR_AUDIO -> fail(DictationError.Microphone)
                ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> fail(DictationError.Language)
                else -> fail(DictationError.Other)
            }
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val RESTART_DELAY_MS = 150L
        const val BUSY_DELAY_MS = 600L
        const val STOP_GRACE_MS = 1500L
        const val MAX_QUIET_ROUNDS = 8

        // SpeechRecognizer constants added in API 31, written out because minSdk is 24.
        const val ERROR_SERVER_DISCONNECTED = 11
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
