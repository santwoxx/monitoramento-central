package com.corp.digitaltwin.overlay

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import com.corp.digitaltwin.network.DeviceMode
import com.corp.digitaltwin.state.DeviceTelemetryState
import java.text.SimpleDateFormat
import java.util.*

/**
 * HomeScreen Widget para exibição pública e transparente do estado de monitoramento.
 */
class DigitalTwinAppWidget : AppWidgetProvider() {

    companion object {
        fun updateAllWidgets(context: Context, state: DeviceTelemetryState) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, DigitalTwinAppWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            for (widgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, widgetId, state)
            }
        }

        private fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            state: DeviceTelemetryState
        ) {
            // Layout dinâmico do widget via RemoteViews
            val views = RemoteViews(context.packageName, android.R.layout.simple_list_item_2)

            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val formattedTime = timeFormat.format(Date(state.lastUpdateEpochMs))

            val (badgeText, badgeColor) = when (state.mode) {
                DeviceMode.IDLE -> "STATUS: IDLE (60s)" to Color.rgb(34, 197, 94)
                DeviceMode.FOCUS -> "STATUS: FOCO ATIVO (2s)" to Color.rgb(245, 158, 11)
                DeviceMode.LIVE -> "STATUS: TRANSMISSÃO AO VIVO" to Color.rgb(239, 68, 68)
            }

            views.setTextViewText(
                android.R.id.text1,
                "🛡️ DIGITAL TWIN: $badgeText"
            )
            views.setTextColor(android.R.id.text1, badgeColor)

            views.setTextViewText(
                android.R.id.text2,
                "Bateria: ${state.batteryPct}% | App: ${state.focusedPackage} | Sync: $formattedTime"
            )

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launchIntent != null) {
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(android.R.id.text1, pendingIntent)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, widgetId, DeviceTelemetryState())
        }
    }
}
