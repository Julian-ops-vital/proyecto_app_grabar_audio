package com.example.audiosecretrecorder.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.google.gson.Gson
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.Locale

/**
 * Administrador inteligente de detección de palabras clave.
 * Soporta dos estrategias:
 * 1. Vosk Offline Engine (Procesa el flujo PCM de AudioRecord directamente,
 *    permitiendo detectar 'detener' mientras se graba el archivo sin conflictos).
 * 2. Native SpeechRecognizer de Android (Modo continuo estándar).
 */
class KeywordManager(
    private val context: Context,
    private val listener: KeywordListener
) {
    interface KeywordListener {
        fun onStartKeywordDetected(keyword: String)
        fun onStopKeywordDetected(keyword: String)
        fun onStatusChanged(status: String)
    }

    companion object {
        private const val TAG = "KeywordManager"
        const val PREFS_NAME = "audio_secret_prefs"
        const val KEY_START_WORD = "key_start_word"
        const val KEY_STOP_WORD = "key_stop_word"
        const val DEFAULT_START_WORD = "grabar"
        const val DEFAULT_STOP_WORD = "detener"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isNativeRecognizerListening = false
    private var isDestroyed = false

    // Vosk Engine Components
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    var isVoskReady: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()

    data class VoskTextResult(val text: String?)

    init {
        initVoskIfAvailable()
    }

    /**
     * Intenta inicializar Vosk si el modelo está presente en filesDir o assets.
     */
    private fun initVoskIfAvailable() {
        try {
            val modelDir = File(context.filesDir, "model-es")
            if (modelDir.exists() && modelDir.isDirectory) {
                voskModel = Model(modelDir.absolutePath)
                setupVoskRecognizer()
                isVoskReady = true
                Log.d(TAG, "Vosk Model cargado exitosamente desde archivos internos.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vosk no inicializado: ${e.message}. Se usará SpeechRecognizer nativo.")
        }
    }

    fun setupVoskRecognizer() {
        val model = voskModel ?: return
        val startWord = getStartWord().lowercase().trim()
        val stopWord = getStopWord().lowercase().trim()

        // Gramática restringida a las palabras clave para alta velocidad y precisión
        val grammarJson = "[\"$startWord\", \"$stopWord\", \"parar\", \"iniciar\", \"[unk]\"]"
        try {
            voskRecognizer = Recognizer(model, 16000.0f, grammarJson)
        } catch (e: Exception) {
            Log.e(TAG, "Error configurando recognizer Vosk: ${e.message}")
        }
    }

    /**
     * Procesa datos de audio PCM entrantes desde AudioRecord.
     * Si Vosk está listo, analiza las palabras clave en tiempo real.
     */
    fun processAudioPcm(buffer: ByteArray, length: Int, isCurrentlyRecording: Boolean) {
        val recognizer = voskRecognizer ?: return
        if (recognizer.acceptWaveForm(buffer, length)) {
            val jsonResult = recognizer.result
            checkVoskResult(jsonResult, isCurrentlyRecording)
        } else {
            val partialJson = recognizer.partialResult
            checkVoskResult(partialJson, isCurrentlyRecording)
        }
    }

    private fun checkVoskResult(json: String?, isCurrentlyRecording: Boolean) {
        if (json.isNullOrBlank()) return
        try {
            val parsed = gson.fromJson(json, VoskTextResult::class.java)
            val text = parsed.text?.lowercase(Locale.ROOT) ?: ""
            if (text.isBlank()) return

            val startWord = getStartWord().lowercase().trim()
            val stopWord = getStopWord().lowercase().trim()

            if (!isCurrentlyRecording && (text.contains(startWord) || text.contains("iniciar"))) {
                mainHandler.post {
                    listener.onStartKeywordDetected(startWord)
                }
                voskRecognizer?.reset()
            } else if (isCurrentlyRecording && (text.contains(stopWord) || text.contains("parar"))) {
                mainHandler.post {
                    listener.onStopKeywordDetected(stopWord)
                }
                voskRecognizer?.reset()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analizando resultado Vosk: ${e.message}")
        }
    }

    // ==========================================
    // RECONOCIMIENTO NATIVO DE ANDROID
    // ==========================================

    fun startNativeListening() {
        if (isDestroyed) return
        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                        listener.onStatusChanged("Reconocimiento no disponible")
                        return@post
                    }
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    speechRecognizer?.setRecognitionListener(createNativeListener())
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }

                isNativeRecognizerListening = true
                speechRecognizer?.startListening(intent)
                listener.onStatusChanged("Escuchando palabra clave...")
            } catch (e: Exception) {
                Log.e(TAG, "Error iniciando reconocimiento nativo: ${e.message}")
            }
        }
    }

    fun stopNativeListening() {
        isNativeRecognizerListening = false
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Error deteniendo reconocimiento nativo: ${e.message}")
            }
        }
    }

    private fun createNativeListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            // Error 7 (No match) y Error 6 (Speech timeout) son normales en escucha continua
            if (isNativeRecognizerListening && !isDestroyed) {
                mainHandler.postDelayed({
                    if (isNativeRecognizerListening) {
                        try {
                            speechRecognizer?.cancel()
                            startNativeListening()
                        } catch (e: Exception) {}
                    }
                }, 400)
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            processMatches(matches)

            if (isNativeRecognizerListening && !isDestroyed) {
                startNativeListening()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            processMatches(matches)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun processMatches(matches: List<String>?) {
        if (matches.isNullOrEmpty()) return
        val startWord = getStartWord().lowercase().trim()
        val stopWord = getStopWord().lowercase().trim()

        for (text in matches) {
            val lower = text.lowercase(Locale.ROOT)
            Log.d(TAG, "Texto reconocido: $lower")

            if (lower.contains(startWord) || lower.contains("iniciar")) {
                listener.onStartKeywordDetected(startWord)
                break
            } else if (lower.contains(stopWord) || lower.contains("parar") || lower.contains("detener")) {
                listener.onStopKeywordDetected(stopWord)
                break
            }
        }
    }

    fun getStartWord(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_START_WORD, DEFAULT_START_WORD) ?: DEFAULT_START_WORD
    }

    fun getStopWord(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_STOP_WORD, DEFAULT_STOP_WORD) ?: DEFAULT_STOP_WORD
    }

    fun setKeywords(start: String, stop: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_START_WORD, start.trim().lowercase())
            .putString(KEY_STOP_WORD, stop.trim().lowercase())
            .apply()

        setupVoskRecognizer()
    }

    fun destroy() {
        isDestroyed = true
        stopNativeListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        voskRecognizer = null
        voskModel = null
    }
}

