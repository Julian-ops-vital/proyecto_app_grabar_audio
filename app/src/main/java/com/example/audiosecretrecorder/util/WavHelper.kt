package com.example.audiosecretrecorder.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.audiosecretrecorder.model.RecordingModel
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WavHelper {
    private const val TAG = "WavHelper"
    const val SUB_FOLDER_NAME = "AudiosSecretos"

    /**
     * Obtiene el directorio público accesible donde se guardan los audios.
     */
    fun getPublicRecordingsDir(): File {
        val baseDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS)
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        }
        val folder = File(baseDir, SUB_FOLDER_NAME)
        if (!folder.exists()) {
            folder.mkdirs()
        }
        return folder
    }

    /**
     * Crea un archivo WAV vacío y escribe su cabecera inicial de 44 bytes.
     */
    fun createWavFile(context: Context): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "Audio_$timeStamp.wav"
        val dir = getPublicRecordingsDir()
        val file = File(dir, fileName)

        // Escribir cabecera temporal de 44 bytes
        RandomAccessFile(file, "rw").use { raf ->
            writeWavHeader(raf, 0, 16000, 1, 16)
        }
        return file
    }

    /**
     * Escribe la cabecera canónica RIFF/WAVE para audio PCM 16-bit.
     */
    fun writeWavHeader(
        raf: RandomAccessFile,
        totalAudioLen: Long,
        sampleRate: Int = 16000,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()
        val blockAlign = (channels * bitsPerSample / 8).toShort()

        raf.seek(0)
        raf.writeBytes("RIFF")
        raf.write(intToByteArray(totalDataLen.toInt()))
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        raf.write(intToByteArray(16)) // Subchunk1Size para PCM
        raf.write(shortToByteArray(1.toShort())) // Formato de audio 1 = PCM
        raf.write(shortToByteArray(channels.toShort()))
        raf.write(intToByteArray(sampleRate))
        raf.write(intToByteArray(byteRate.toInt()))
        raf.write(shortToByteArray(blockAlign))
        raf.write(shortToByteArray(bitsPerSample.toShort()))
        raf.writeBytes("data")
        raf.write(intToByteArray(totalAudioLen.toInt()))
    }

    /**
     * Actualiza la cabecera WAV con el tamaño real una vez que termina la grabación.
     */
    fun updateWavHeader(file: File) {
        if (!file.exists()) return
        val totalAudioLen = file.length() - 44
        if (totalAudioLen < 0) return

        RandomAccessFile(file, "rw").use { raf ->
            writeWavHeader(raf, totalAudioLen, 16000, 1, 16)
        }
    }

    /**
     * Registra el archivo en MediaStore para que aparezca instantáneamente en
     * el explorador de archivos ("Mis Archivos" / Google Files) y reproductores del sistema.
     */
    fun registerInMediaStore(context: Context, file: File): Uri? {
        updateWavHeader(file)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(
                        MediaStore.Audio.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_RECORDINGS + File.separator + SUB_FOLDER_NAME
                    )
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            } else {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf("audio/wav"),
                    null
                )
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registrando en MediaStore: ${e.message}")
            Uri.fromFile(file)
        }
    }

    /**
     * Obtiene la lista de grabaciones existentes en la carpeta de la app.
     */
    fun getSavedRecordings(context: Context): List<RecordingModel> {
        val list = mutableListOf<RecordingModel>()
        val dir = getPublicRecordingsDir()
        val files = dir.listFiles { _, name -> name.endsWith(".wav", ignoreCase = true) } ?: emptyArray()

        files.sortByDescending { it.lastModified() }

        val retriever = MediaMetadataRetriever()
        for (f in files) {
            var durationMs = 0L
            try {
                retriever.setDataSource(f.absolutePath)
                val timeStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                durationMs = timeStr?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                // Cálculo aproximado para PCM 16kHz 16bit mono (32000 bytes/seg)
                val audioBytes = (f.length() - 44).coerceAtLeast(0)
                durationMs = (audioBytes * 1000) / 32000
            }

            list.add(
                RecordingModel(
                    id = f.lastModified(),
                    name = f.name,
                    file = f,
                    uri = Uri.fromFile(f),
                    durationMs = durationMs,
                    sizeBytes = f.length(),
                    dateAdded = f.lastModified()
                )
            )
        }
        try {
            retriever.release()
        } catch (e: Exception) {}

        return list
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte()
        )
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xff).toByte(),
            ((value.toInt() shr 8) and 0xff).toByte()
        )
    }
}

