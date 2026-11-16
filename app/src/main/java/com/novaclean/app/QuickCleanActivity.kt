package com.novaclean.app

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.novaclean.app.core.CleanJob
import com.novaclean.app.core.Prefs
import kotlinx.coroutines.launch

/**
 * Invisible activity started by the widget and the launcher shortcut.
 * Runs the one-tap clean, reports the result, then closes itself.
 */
class QuickCleanActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        if (prefs.toastResult) Toast.makeText(this, "Nova Clean: cleaning…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val text = try {
                CleanJob(this@QuickCleanActivity).run().summary()
            } catch (e: Exception) {
                "Clean failed: ${e.message}"
            }
            if (prefs.toastResult) Toast.makeText(this@QuickCleanActivity, text, Toast.LENGTH_LONG).show()
            if (prefs.notifyResult) notifyResult(text)
            finish()
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyResult(text: String) {
        val nm = NotificationManagerCompat.from(this)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder("result", NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Clean results").build()
            )
        }
        if (!nm.areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(this, "result")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Nova Clean")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(1, n)
        } catch (e: SecurityException) {
            // notification permission not granted
        }
    }
}
