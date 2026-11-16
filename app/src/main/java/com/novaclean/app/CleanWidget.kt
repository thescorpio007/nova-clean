package com.novaclean.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.novaclean.app.core.Prefs

/** One button that runs the whole clean using the options chosen in Settings. */
class CleanWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context)) }
    }

    companion object {
        fun views(ctx: Context): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_clean)
            rv.setTextViewText(R.id.widget_label, Prefs(ctx).widgetLabel)
            val pi = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, QuickCleanActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            rv.setOnClickPendingIntent(R.id.widget_root, pi)
            return rv
        }

        fun updateAll(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            val ids = m.getAppWidgetIds(ComponentName(ctx, CleanWidget::class.java))
            ids.forEach { m.updateAppWidget(it, views(ctx)) }
        }
    }
}
