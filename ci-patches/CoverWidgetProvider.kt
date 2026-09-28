package com.fliphomeos.app.widget

import android.app.ActivityOptions
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.fliphomeos.app.R
import com.fliphomeos.app.ui.MainActivity

class CoverWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

            val coverOptions = ActivityOptions.makeBasic().apply {
                launchDisplayId = COVER_SCREEN_DISPLAY_ID
            }.toBundle()

            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                coverOptions
            )

            val views = RemoteViews(context.packageName, R.layout.cover_widget).apply {
                setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    companion object {
        private const val COVER_SCREEN_DISPLAY_ID = 1
    }
}
