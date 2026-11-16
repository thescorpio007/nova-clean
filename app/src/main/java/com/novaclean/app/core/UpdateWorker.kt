package com.novaclean.app.core

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.novaclean.app.MainActivity
import com.novaclean.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Every 6 hours: look for a newer GitHub release, download it silently, then ask to install. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        if (!prefs.autoUpdate) return Result.success()
        CrashLog.action = "auto-update"
        val out = CrashLog.guard(ctx, "auto-update") {
            withContext(Dispatchers.IO) { Updater.checkAndDownload(ctx) }
        } ?: return Result.retry()
        prefs.updateStatus = out.status
        if (out.ready) {
            val lbl = Updater.label(prefs.updateVer, prefs.updateBuild)
            if (prefs.notifiedUpdate != lbl) {
                notifyReady(ctx, lbl)
                prefs.notifiedUpdate = lbl
            }
        }
        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private fun notifyReady(ctx: Context, lbl: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder("update", NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Updates").build()
            )
        }
        if (!nm.areNotificationsEnabled()) return
        val pi = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, "update")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Nova Clean update ready")
            .setContentText("$lbl is downloaded. Tap to install.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(2, n)
        } catch (e: SecurityException) {
        }
    }
}

object UpdateScheduler {
    fun apply(ctx: Context) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        if (Prefs(ctx).autoUpdate) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork("nova_update", ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            wm.cancelUniqueWork("nova_update")
        }
    }
}
