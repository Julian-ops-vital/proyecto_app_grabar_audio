package com.example.audiosecretrecorder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.audiosecretrecorder.databinding.ActivityMainBinding
import com.example.audiosecretrecorder.model.RecordingModel
import com.example.audiosecretrecorder.service.AudioRecordService
import com.example.audiosecretrecorder.service.KeywordManager
import com.example.audiosecretrecorder.ui.RecordingsAdapter
import com.example.audiosecretrecorder.util.WavHelper
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var keywordManager: KeywordManager
    private lateinit var adapter: RecordingsAdapter

    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingModel: RecordingModel? = null
    private val playerHandler = Handler(Looper.getMainLooper())
    private var pulseAnimation: Animation? = null

    // Manejador de permisos de Android en tiempo de ejecución
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (recordGranted) {
            Toast.makeText(this, "Permiso de micrófono concedido", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Se requiere permiso de micrófono para la app", Toast.LENGTH_LONG).show()
        }
    }

    // Receptor de estados en vivo desde AudioRecordService
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isRecording = intent?.getBooleanExtra(AudioRecordService.EXTRA_IS_RECORDING, false) ?: false
            val isListening = intent?.getBooleanExtra(AudioRecordService.EXTRA_IS_LISTENING, false) ?: false
            val seconds = intent?.getIntExtra(AudioRecordService.EXTRA_SECONDS, 0) ?: 0
            val statusText = intent?.getStringExtra(AudioRecordService.EXTRA_STATUS_TEXT)

            updateUiState(isRecording, isListening, seconds, statusText)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        keywordManager = KeywordManager(this, object : KeywordManager.KeywordListener {
            override fun onStartKeywordDetected(keyword: String) {}
            override fun onStopKeywordDetected(keyword: String) {}
            override fun onStatusChanged(status: String) {}
        })

        setupViews()
        setupRecyclerView()
        setupAudioPlayer()
        checkPermissions()
        loadSavedRecordings()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(AudioRecordService.BROADCAST_STATE_CHANGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }

        updateUiState(
            AudioRecordService.isCurrentlyRecording,
            AudioRecordService.isVoiceListeningEnabled,
            0,
            null
        )
        loadSavedRecordings()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(stateReceiver)
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioPlayback()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun setupViews() {
        // Cargar palabras clave guardadas
        binding.etKeywordStart.setText(keywordManager.getStartWord())
        binding.etKeywordStop.setText(keywordManager.getStopWord())

        // Guardar nuevas palabras clave
        binding.btnSaveKeywords.setOnClickListener {
            val startWord = binding.etKeywordStart.text.toString().trim()
            val stopWord = binding.etKeywordStop.text.toString().trim()

            if (startWord.isEmpty() || stopWord.isEmpty()) {
                Toast.makeText(this, "Las palabras clave no pueden estar vacías", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            keywordManager.setKeywords(startWord, stopWord)
            Toast.makeText(this, "Palabras guardadas: '$startWord' y '$stopWord'", Toast.LENGTH_SHORT).show()
        }

        // Botón Activar / Desactivar Escucha por Voz
        binding.btnToggleVoice.setOnClickListener {
            if (AudioRecordService.isVoiceListeningEnabled) {
                sendServiceAction(AudioRecordService.ACTION_STOP_SERVICE)
            } else {
                sendServiceAction(AudioRecordService.ACTION_START_SERVICE)
            }
        }

        // Botón Grabar / Detener Manual
        binding.btnToggleManual.setOnClickListener {
            sendServiceAction(AudioRecordService.ACTION_TOGGLE_RECORD)
        }

        // Botón Refrescar lista
        binding.btnRefreshList.setOnClickListener {
            loadSavedRecordings()
            Toast.makeText(this, "Lista de audios actualizada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, AudioRecordService::class.java).apply {
            this.action = action
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun updateUiState(isRecording: Boolean, isListening: Boolean, seconds: Int, customStatus: String?) {
        if (isRecording) {
            binding.tvStatusTitle.text = getString(R.string.status_recording)
            binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.accent_recording))

            val min = seconds / 60
            val sec = seconds % 60
            binding.tvRecordingTimer.text = String.format("%02d:%02d", min, sec)
            binding.tvRecordingTimer.visibility = View.VISIBLE

            binding.ivMicStatus.setImageResource(R.drawable.ic_stop)
            binding.ivMicStatus.setColorFilter(ContextCompat.getColor(this, R.color.accent_recording))

            binding.btnToggleManual.text = getString(R.string.btn_manual_record_stop)
            binding.btnToggleManual.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_recording)

            startPulseAnimation()
        } else {
            stopPulseAnimation()
            binding.tvRecordingTimer.visibility = View.GONE
            binding.btnToggleManual.text = getString(R.string.btn_manual_record_start)
            binding.btnToggleManual.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)

            if (isListening) {
                binding.tvStatusTitle.text = customStatus ?: getString(R.string.status_listening)
                binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.accent_listening))
                binding.ivMicStatus.setImageResource(R.drawable.ic_mic)
                binding.ivMicStatus.setColorFilter(ContextCompat.getColor(this, R.color.accent_listening))

                binding.btnToggleVoice.text = getString(R.string.btn_stop_listening)
                binding.btnToggleVoice.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_recording)
            } else {
                binding.tvStatusTitle.text = getString(R.string.status_idle)
                binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                binding.ivMicStatus.setImageResource(R.drawable.ic_mic)
                binding.ivMicStatus.setColorFilter(ContextCompat.getColor(this, R.color.primary))

                binding.btnToggleVoice.text = getString(R.string.btn_start_listening)
                binding.btnToggleVoice.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_listening)
            }
        }
    }

    private fun startPulseAnimation() {
        if (pulseAnimation == null) {
            pulseAnimation = AlphaAnimation(0.2f, 1.0f).apply {
                duration = 600
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            }
        }
        binding.viewPulse.visibility = View.VISIBLE
        binding.viewPulse.startAnimation(pulseAnimation)
    }

    private fun stopPulseAnimation() {
        binding.viewPulse.clearAnimation()
        binding.viewPulse.visibility = View.INVISIBLE
    }

    // ==========================================
    // RECYCLER VIEW Y LISTA DE GRABACIONES
    // ==========================================

    private fun setupRecyclerView() {
        adapter = RecordingsAdapter(
            recordings = emptyList(),
            onPlayClick = { item -> handlePlayRecording(item) },
            onShareClick = { item -> shareRecording(item) },
            onDeleteClick = { item -> confirmDeleteRecording(item) }
        )
        binding.rvRecordings.layoutManager = LinearLayoutManager(this)
        binding.rvRecordings.adapter = adapter
    }

    private fun loadSavedRecordings() {
        val list = WavHelper.getSavedRecordings(this)
        adapter.updateList(list)

        if (list.isEmpty()) {
            binding.tvEmptyList.visibility = View.VISIBLE
            binding.rvRecordings.visibility = View.GONE
        } else {
            binding.tvEmptyList.visibility = View.GONE
            binding.rvRecordings.visibility = View.VISIBLE
        }
    }

    // ==========================================
    // REPRODUCTOR DE AUDIO INTEGRADO
    // ==========================================

    private fun setupAudioPlayer() {
        binding.btnPlayerClose.setOnClickListener {
            stopAudioPlayback()
        }

        binding.btnPlayerPlayPause.setOnClickListener {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.pause()
                    binding.btnPlayerPlayPause.setImageResource(R.drawable.ic_play)
                } else {
                    mp.start()
                    binding.btnPlayerPlayPause.setImageResource(R.drawable.ic_pause)
                }
            }
        }

        binding.seekbarAudio.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.seekTo(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun handlePlayRecording(item: RecordingModel) {
        if (currentlyPlayingModel?.id == item.id && mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            item.isPlaying = false
            adapter.notifyDataSetChanged()
            binding.btnPlayerPlayPause.setImageResource(R.drawable.ic_play)
            return
        }

        stopAudioPlayback()

        try {
            mediaPlayer = MediaPlayer().apply {
                val file = item.file ?: return
                setDataSource(file.absolutePath)
                prepare()
                start()

                binding.cardPlayer.visibility = View.VISIBLE
                binding.tvPlayerTitle.text = item.name
                binding.seekbarAudio.max = duration
                binding.btnPlayerPlayPause.setImageResource(R.drawable.ic_pause)

                currentlyPlayingModel = item
                item.isPlaying = true
                adapter.notifyDataSetChanged()

                setOnCompletionListener {
                    stopAudioPlayback()
                }
            }

            startPlayerSeekBarUpdater()
        } catch (e: Exception) {
            Toast.makeText(this, "Error al reproducir audio: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startPlayerSeekBarUpdater() {
        playerHandler.postDelayed(object : Runnable {
            override fun run() {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        val current = mp.currentPosition
                        val total = mp.duration
                        binding.seekbarAudio.progress = current

                        val curStr = String.format("%02d:%02d", (current / 1000) / 60, (current / 1000) % 60)
                        val totStr = String.format("%02d:%02d", (total / 1000) / 60, (total / 1000) % 60)
                        binding.tvPlayerTime.text = "$curStr / $totStr"

                        playerHandler.postDelayed(this, 300)
                    }
                }
            }
        }, 300)
    }

    private fun stopAudioPlayback() {
        playerHandler.removeCallbacksAndMessages(null)
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        currentlyPlayingModel?.isPlaying = false
        currentlyPlayingModel = null
        adapter.notifyDataSetChanged()

        binding.cardPlayer.visibility = View.GONE
    }

    // ==========================================
    // COMPARTIR Y ELIMINAR AUDIOS
    // ==========================================

    private fun shareRecording(item: RecordingModel) {
        val file = item.file ?: return
        if (!file.exists()) {
            Toast.makeText(this, "El archivo ya no existe", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Compartir audio grabado"))
        } catch (e: Exception) {
            Toast.makeText(this, "Error al compartir: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteRecording(item: RecordingModel) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar grabación")
            .setMessage("¿Deseas eliminar '${item.name}' permanentemente?")
            .setPositiveButton("Eliminar") { _, _ ->
                if (currentlyPlayingModel?.id == item.id) {
                    stopAudioPlayback()
                }
                item.file?.delete()
                loadSavedRecordings()
                Toast.makeText(this, "Audio eliminado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}

