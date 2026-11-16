package com.example.audiosecretrecorder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.audiosecretrecorder.databinding.ActivityMainBinding
import com.example.audiosecretrecorder.databinding.DialogRenameBinding
import com.example.audiosecretrecorder.model.RecordingModel
import com.example.audiosecretrecorder.service.AudioRecordService
import com.example.audiosecretrecorder.service.KeywordManager
import com.example.audiosecretrecorder.ui.KeywordsBottomSheet
import com.example.audiosecretrecorder.ui.RecordingsAdapter
import com.example.audiosecretrecorder.util.WavHelper
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var keywordManager: KeywordManager
    private lateinit var adapter: RecordingsAdapter

    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingModel: RecordingModel? = null
    private val playerHandler = Handler(Looper.getMainLooper())
    private var pulseAnimator: ObjectAnimator? = null

    // Para detectar el momento en que termina una grabación y refrescar la lista sola
    private var wasRecording = false

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

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
        ContextCompat.registerReceiver(this, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

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
        pulseAnimator?.cancel()
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
        // Ajustes: abre la hoja inferior para editar las palabras clave
        binding.btnSettings.setOnClickListener {
            KeywordsBottomSheet
                .newInstance(keywordManager.getStartWord(), keywordManager.getStopWord())
                .show(supportFragmentManager, KeywordsBottomSheet.TAG)
        }

        // Resultado de la hoja: aquí es donde realmente se guardan las palabras
        supportFragmentManager.setFragmentResultListener(KeywordsBottomSheet.REQUEST_KEY, this) { _, result ->
            val startWord = result.getString(KeywordsBottomSheet.KEY_START).orEmpty()
            val stopWord = result.getString(KeywordsBottomSheet.KEY_STOP).orEmpty()

            if (startWord.isNotEmpty() && stopWord.isNotEmpty()) {
                keywordManager.setKeywords(startWord, stopWord)
                Toast.makeText(
                    this,
                    getString(R.string.keywords_saved, startWord, stopWord),
                    Toast.LENGTH_SHORT
                ).show()
                refreshVoiceHint()
            }
        }
        refreshVoiceHint()

        // Aviso del widget: se puede cerrar y no vuelve a aparecer
        binding.cardTip.visibility =
            if (prefs.getBoolean(PREF_TIP_DISMISSED, false)) View.GONE else View.VISIBLE
        binding.btnTipClose.setOnClickListener {
            binding.cardTip.visibility = View.GONE
            prefs.edit().putBoolean(PREF_TIP_DISMISSED, true).apply()
        }

        // Switch Escucha por Voz
        binding.btnToggleVoice.setOnClickListener {
            val enabled = AudioRecordService.isVoiceListeningEnabled
            // El estado real lo confirma el servicio por broadcast; mientras tanto
            // el switch no se queda en un estado que todavía no es verdad.
            binding.btnToggleVoice.isChecked = enabled
            if (enabled) {
                sendServiceAction(AudioRecordService.ACTION_STOP_SERVICE)
            } else {
                sendServiceAction(AudioRecordService.ACTION_START_SERVICE)
            }
        }

        // Botón grande: Grabar / Detener Manual
        binding.btnToggleManual.setOnClickListener {
            sendServiceAction(AudioRecordService.ACTION_TOGGLE_RECORD)
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

    // Detener por voz solo funciona si el modelo offline de Vosk está instalado
    private fun hasVoskModel(): Boolean = File(filesDir, "model-es").isDirectory

    private fun refreshVoiceHint() {
        val startWord = keywordManager.getStartWord()
        val stopWord = keywordManager.getStopWord()
        binding.tvVoiceHint.text = if (hasVoskModel()) {
            getString(R.string.voice_hint_full, startWord, stopWord)
        } else {
            getString(R.string.voice_hint_start_only, startWord)
        }
    }

    private fun applyStatusChip(text: String, @ColorRes bgColor: Int, @ColorRes textColor: Int) {
        binding.tvStatusTitle.text = text
        binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, textColor))
        binding.tvStatusTitle.backgroundTintList = ContextCompat.getColorStateList(this, bgColor)
        binding.tvStatusTitle.compoundDrawableTintList = ContextCompat.getColorStateList(this, textColor)
    }

    private fun updateUiState(isRecording: Boolean, isListening: Boolean, seconds: Int, customStatus: String?) {
        if (isRecording) {
            applyStatusChip(
                getString(R.string.chip_recording),
                R.color.chip_recording_bg,
                R.color.chip_recording_text
            )

            val min = seconds / 60
            val sec = seconds % 60
            binding.tvRecordingTimer.text = String.format(Locale.getDefault(), "%02d:%02d", min, sec)

            binding.btnToggleManual.setIconResource(R.drawable.ic_stop)
            binding.btnToggleManual.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.accent_recording)
            binding.btnToggleManual.contentDescription = getString(R.string.cd_record_stop)

            binding.tvHint.text = if (isListening && hasVoskModel()) {
                getString(R.string.hint_recording_voice, keywordManager.getStopWord())
            } else {
                getString(R.string.hint_recording)
            }

            startPulseAnimation()
        } else {
            stopPulseAnimation()
            binding.tvRecordingTimer.text = String.format(Locale.getDefault(), "%02d:%02d", 0, 0)

            binding.btnToggleManual.setIconResource(R.drawable.ic_mic)
            binding.btnToggleManual.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.primary)
            binding.btnToggleManual.contentDescription = getString(R.string.cd_record_start)

            binding.btnToggleVoice.isChecked = isListening

            if (isListening) {
                // El chip siempre lleva una etiqueta corta; el texto que manda el servicio
                // (que puede ser largo o un aviso de error) va en la línea de abajo.
                applyStatusChip(
                    getString(R.string.chip_listening),
                    R.color.chip_listening_bg,
                    R.color.chip_listening_text
                )
                binding.tvHint.text = customStatus
                    ?: getString(R.string.hint_listening, keywordManager.getStartWord())
            } else {
                applyStatusChip(
                    getString(R.string.chip_idle),
                    R.color.chip_idle_bg,
                    R.color.chip_idle_text
                )
                binding.tvHint.text = getString(R.string.hint_idle)
            }
        }

        // Al terminar una grabación, la lista se actualiza sola (ya no hay botón de refrescar)
        if (wasRecording && !isRecording) {
            binding.root.postDelayed({ loadSavedRecordings() }, 600)
        }
        wasRecording = isRecording
    }

    // Aro que "respira" (escala + opacidad) mientras se graba
    private fun startPulseAnimation() {
        binding.viewPulse.visibility = View.VISIBLE
        // Esta función se llama cada segundo con el cronómetro: no reiniciar si ya está corriendo
        if (pulseAnimator?.isRunning == true) return

        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.viewPulse,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.88f, 1.0f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.88f, 1.0f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.35f, 1.0f)
        ).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.viewPulse.scaleX = 1f
        binding.viewPulse.scaleY = 1f
        binding.viewPulse.alpha = 1f
        binding.viewPulse.visibility = View.INVISIBLE
    }

    // ==========================================
    // RECYCLER VIEW Y LISTA DE GRABACIONES
    // ==========================================

    private fun setupRecyclerView() {
        adapter = RecordingsAdapter(
            recordings = emptyList(),
            onPlayClick = { item -> handlePlayRecording(item) },
            onRenameClick = { item -> promptRenameRecording(item) },
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
            binding.tvRecordingsCount.visibility = View.GONE
        } else {
            binding.tvEmptyList.visibility = View.GONE
            binding.rvRecordings.visibility = View.VISIBLE
            binding.tvRecordingsCount.visibility = View.VISIBLE
            binding.tvRecordingsCount.text =
                resources.getQuantityString(R.plurals.recordings_count, list.size, list.size)
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

    // ==========================================
    // RENOMBRAR AUDIOS
    // ==========================================

    private fun promptRenameRecording(item: RecordingModel) {
        val file = item.file
        if (file == null || !file.exists()) {
            Toast.makeText(this, R.string.rename_error_missing, Toast.LENGTH_SHORT).show()
            return
        }

        val dialogBinding = DialogRenameBinding.inflate(layoutInflater)
        dialogBinding.etNewName.setText(file.nameWithoutExtension)
        dialogBinding.etNewName.selectAll()

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(dialogBinding.root)
            // El click real se asigna abajo para poder validar sin que el diálogo se cierre
            .setPositiveButton(R.string.rename_confirm, null)
            .setNegativeButton(R.string.btn_sheet_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialogBinding.etNewName.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val error = renameRecording(file, dialogBinding.etNewName.text.toString())
                if (error == null) {
                    dialog.dismiss()
                    loadSavedRecordings()
                    Toast.makeText(this, R.string.rename_done, Toast.LENGTH_SHORT).show()
                } else {
                    dialogBinding.tilNewName.error = getString(error)
                }
            }
        }
        dialog.show()
    }

    /** Devuelve null si se renombró bien, o el texto de error (recurso) si no se pudo. */
    private fun renameRecording(file: File, rawName: String): Int? {
        // Quita caracteres que Android no permite en nombres de archivo
        val cleanName = rawName
            .replace(Regex("[\\\\/:*?\"<>|]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .take(80)
            .trim()
        if (cleanName.isEmpty()) return R.string.rename_error_empty

        val extension = file.extension.ifEmpty { "wav" }
        val newFile = File(file.parentFile, "$cleanName.$extension")

        if (newFile.absolutePath == file.absolutePath) return null
        if (newFile.exists()) return R.string.rename_error_exists
        if (!file.renameTo(newFile)) return R.string.rename_error_failed

        // Actualiza el índice del sistema para que la app "Archivos" vea el nombre nuevo
        MediaScannerConnection.scanFile(
            this,
            arrayOf(file.absolutePath, newFile.absolutePath),
            null,
            null
        )

        if (currentlyPlayingModel?.file?.absolutePath == file.absolutePath) {
            stopAudioPlayback()
        }
        return null
    }

    private companion object {
        const val PREFS_NAME = "audio_secret_prefs"
        const val PREF_TIP_DISMISSED = "tip_dismissed"
    }
}