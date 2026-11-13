package com.example.audiosecretrecorder.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.example.audiosecretrecorder.R
import com.example.audiosecretrecorder.service.AudioRecordService

/**
 * Widget interactivo para la pantalla de inicio de Android.
 * Permite iniciar y detener la grabación de audio con un solo toque.
 */
class AudioRecordWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_CLICK = "com.example.audiosecretrecorder.ACTION_TOGGLE_RECORD"

        /**
         * Actualiza la apariencia visual del widget en la pantalla de inicio
         * reflejando si actualmente está grabando o en reposo.
         */
        fun updateWidget(context: Context, isRecording: Boolean, isListening: Boolean) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, AudioRecordWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)

            for (widgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_audio_recorder)

                // Configurar Intent de clic al presionar el widget
                val intent = Intent(context, AudioRecordWidget::class.java).apply {
                    action = ACTION_WIDGET_CLICK
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    widgetId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
                views.setOnClickPendingIntent(R.id.iv_widget_btn, pendingIntent)

                // Cambiar estado visual
                if (isRecording) {
                    views.setImageViewResource(R.id.iv_widget_btn, R.drawable.ic_stop)
                    views.setTextViewText(R.id.tv_widget_status, context.getString(R.string.widget_recording_now))
                    views.setTextColor(R.id.tv_widget_status, ContextCompat.getColor(context, R.color.accent_recording))
                } else {
                    views.setImageViewResource(R.id.iv_widget_btn, R.drawable.ic_mic)
                    val statusText = if (isListening) "Escuchando voz (Toca para grabar)" else context.getString(R.string.widget_tap_to_record)
                    views.setTextViewText(R.id.tv_widget_status, statusText)
                    views.setTextColor(R.id.tv_widget_status, ContextCompat.getColor(context, R.color.primary_dark))
                }

                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Al actualizar el widget, refrescar su estado con los datos actuales del servicio
        updateWidget(context, AudioRecordService.isCurrentlyRecording, AudioRecordService.isVoiceListeningEnabled)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == ACTION_WIDGET_CLICK) {
            // Disparar o alternar la grabación en el Foreground Service
            val serviceIntent = Intent(context, AudioRecordService::class.java).apply {
                action = AudioRecordService.ACTION_TOGGLE_RECORD
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}

