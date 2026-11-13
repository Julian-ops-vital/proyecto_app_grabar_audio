package com.example.audiosecretrecorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.audiosecretrecorder.MainActivity
import com.example.audiosecretrecorder.R
import com.example.audiosecretrecorder.util.WavHelper
import com.example.audiosecretrecorder.widget.AudioRecordWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class AudioRecordService : Service(), KeywordManager.KeywordListener {

    companion object {
        private const val TAG = "AudioRecordService"
        const val CHANNEL_ID = "AudioSecretRecorderChannel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_SERVICE = "com.example.audiosecretrecorder.ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.example.audiosecretrecorder.ACTION_STOP_SERVICE"
        const val ACTION_TOGGLE_RECORD = "com.example.audiosecretrecorder.ACTION_TOGGLE_RECORD"
        const val ACTION_START_RECORD = "com.example.audiosecretrecorder.ACTION_START_RECORD"
        const val ACTION_STOP_RECORD = "com.example.audiosecretrecorder.ACTION_STOP_RECORD"

        const val BROADCAST_STATE_CHANGE = "com.example.audiosecretrecorder.BROADCAST_STATE"
        const val EXTRA_IS_RECORDING = "extra_is_recording"
        const val EXTRA_IS_LISTENING = "extra_is_listening"
        const val EXTRA_SECONDS = "extra_seconds"
        const val EXTRA_STATUS_TEXT = "extra_status_text"

        var isServiceRunning = false
            private set
        var isCurrentlyRecording = false
            private set
        var isVoiceListeningEnabled = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var recordingJob: Job? = null
    private var timerJob: Job? = null

    private lateinit var keywordManager: KeywordManager
    private var currentRecordingFile: File? = null
    private var currentFileOutputStream: FileOutputStream? = null

    private var audioRecord: AudioRecord? = null
    private var recordingSeconds = 0

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = 2048

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()

        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            .coerceAtLeast(4096)

        keywordManager = KeywordManager(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_SERVICE
        Log.d(TAG, "onStartCommand recibido con acción: $action")

        startForegroundWithNotification("Inicializando servicio...")

        when (action) {
            ACTION_START_SERVICE -> {
                enableVoiceListening(true)
            }
            ACTION_STOP_SERVICE -> {
                stopSelfService()
            }
            ACTION_TOGGLE_RECORD -> {
                if (isCurrentlyRecording) {
                    stopRecordingAudio()
                } else {
                    startRecordingAudio()
                }
            }
            ACTION_START_RECORD -> {
                if (!isCurrentlyRecording) {
                    startRecordingAudio()
                }
            }
            ACTION_STOP_RECORD -> {
                if (isCurrentlyRecording) {
                    stopRecordingAudio()
                }
            }
        }

        return START_STICKY
    }

    private fun enableVoiceListening(enable: Boolean) {
        isVoiceListeningEnabled = enable
        if (enable) {
            if (keywordManager.isVoskReady) {
                // Vosk corre directamente en el bucle de AudioRecord
                startAudioRecordCaptureLoop()
            } else {
                // Usar SpeechRecognizer nativo de Android
                keywordManager.startNativeListening()
            }
            updateNotification("Escuchando palabra clave...")
        } else {
            keywordManager.stopNativeListening()
            if (!isCurrentlyRecording) {
                stopAudioRecordCaptureLoop()
            }
            updateNotification("Servicio en espera")
        }
        broadcastState()
        AudioRecordWidget.updateWidget(this, isCurrentlyRecording, isVoiceListeningEnabled)
    }

    /**
     * Inicia la captura física de audio y guardado en archivo WAV.
     */
    @Synchronized
    private fun startRecordingAudio() {
        if (isCurrentlyRecording) return

        try {
            // Pausar temporalmente el reconocedor nativo para evitar interferencias de mic
            keywordManager.stopNativeListening()

            currentRecordingFile = WavHelper.createWavFile(this)
            currentFileOutputStream = FileOutputStream(currentRecordingFile, true) // Append después de cabecera

            isCurrentlyRecording = true
            recordingSeconds = 0

            startTimer()
            startAudioRecordCaptureLoop()

            updateNotification("¡Grabando Audio! (00:00)")
            broadcastState()
            AudioRecordWidget.updateWidget(this, true, isVoiceListeningEnabled)

            Log.d(TAG, "Grabación iniciada en: ${currentRecordingFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando grabación: ${e.message}", e)
            isCurrentlyRecording = false
        }
    }

    /**
     * Detiene la grabación y finaliza el archivo WAV en el sistema.
     */
    @Synchronized
    private fun stopRecordingAudio() {
        if (!isCurrentlyRecording) return

        isCurrentlyRecording = false
        stopTimer()

        try {
            currentFileOutputStream?.flush()
            currentFileOutputStream?.close()
            currentFileOutputStream = null

            currentRecordingFile?.let { file ->
                WavHelper.registerInMediaStore(this, file)
                Log.d(TAG, "Archivo WAV finalizado y registrado: ${file.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al cerrar archivo WAV: ${e.message}")
        }

        // Si la escucha por voz sigue habilitada, reanudarla
        if (isVoiceListeningEnabled) {
            if (!keywordManager.isVoskReady) {
                stopAudioRecordCaptureLoop()
                keywordManager.startNativeListening()
            }
            updateNotification("Escuchando palabra clave...")
        } else {
            stopAudioRecordCaptureLoop()
            updateNotification("Grabación completada.")
        }

        broadcastState()
        AudioRecordWidget.updateWidget(this, false, isVoiceListeningEnabled)
    }

    /**
     * Bucle de captura continua con AudioRecord.
     * Alimenta simultáneamente el archivo WAV y el detector de palabras clave Vosk.
     */
    private fun startAudioRecordCaptureLoop() {
        if (recordingJob?.isActive == true) return

        recordingJob = serviceScope.launch {
            try {
                if (audioRecord == null || audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )
                }

                audioRecord?.startRecording()
                val buffer = ByteArray(bufferSize)

                while (isActive && (isCurrentlyRecording || (isVoiceListeningEnabled && keywordManager.isVoskReady))) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        // 1. Si estamos grabando, guardar directamente en el archivo WAV
                        if (isCurrentlyRecording) {
                            currentFileOutputStream?.write(buffer, 0, read)
                        }

                        // 2. Si Vosk está listo, procesar palabras clave en tiempo real
                        if (keywordManager.isVoskReady) {
                            keywordManager.processAudioPcm(buffer, read, isCurrentlyRecording)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en bucle de captura AudioRecord: ${e.message}")
            } finally {
                stopAudioRecordHardware()
            }
        }
    }

    private fun stopAudioRecordCaptureLoop() {
        recordingJob?.cancel()
        recordingJob = null
        stopAudioRecordHardware()
    }

    private fun stopAudioRecordHardware() {
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error liberando AudioRecord: ${e.message}")
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive && isCurrentlyRecording) {
                delay(1000)
                recordingSeconds++
                val minutes = recordingSeconds / 60
                val secs = recordingSeconds % 60
                val timeStr = String.format("%02d:%02d", minutes, secs)

                updateNotification("¡Grabando Audio! ($timeStr)")
                broadcastState()
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        recordingSeconds = 0
    }

    private fun broadcastState() {
        val intent = Intent(BROADCAST_STATE_CHANGE).apply {
            putExtra(EXTRA_IS_RECORDING, isCurrentlyRecording)
            putExtra(EXTRA_IS_LISTENING, isVoiceListeningEnabled)
            putExtra(EXTRA_SECONDS, recordingSeconds)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // Callbacks del KeywordListener
    override fun onStartKeywordDetected(keyword: String) {
        Log.i(TAG, "¡Palabra clave de INICIO detectada: '$keyword'!")
        if (!isCurrentlyRecording) {
            startRecordingAudio()
        }
    }

    override fun onStopKeywordDetected(keyword: String) {
        Log.i(TAG, "¡Palabra clave de FIN detectada: '$keyword'!")
        if (isCurrentlyRecording) {
            stopRecordingAudio()
        }
    }

    override fun onStatusChanged(status: String) {
        updateNotification(status)
        val intent = Intent(BROADCAST_STATE_CHANGE).apply {
            putExtra(EXTRA_IS_RECORDING, isCurrentlyRecording)
            putExtra(EXTRA_IS_LISTENING, isVoiceListeningEnabled)
            putExtra(EXTRA_SECONDS, recordingSeconds)
            putExtra(EXTRA_STATUS_TEXT, status)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // Notificaciones en Foreground
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleRecordIntent = Intent(this, AudioRecordService::class.java).apply {
            action = ACTION_TOGGLE_RECORD
        }
        val pToggle = PendingIntent.getService(
            this, 1, toggleRecordIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (isCurrentlyRecording) R.drawable.ic_stop else R.drawable.ic_mic)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setContentIntent(pOpenApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (isCurrentlyRecording) {
            builder.addAction(R.drawable.ic_stop, "Detener Grabación", pToggle)
        } else {
            builder.addAction(R.drawable.ic_play, "Grabar Ahora", pToggle)
        }

        return builder.build()
    }

    private fun startForegroundWithNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun stopSelfService() {
        stopRecordingAudio()
        enableVoiceListening(false)
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        stopRecordingAudio()
        keywordManager.destroy()
        stopAudioRecordCaptureLoop()
        AudioRecordWidget.updateWidget(this, false, false)
        Log.d(TAG, "AudioRecordService destruido.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

