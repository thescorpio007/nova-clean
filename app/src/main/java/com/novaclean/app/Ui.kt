package com.novaclean.app

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Bg = Color(0xFF0B1F3A)
internal val CardBg = Color(0xFF12294A)
internal val Mint = Color(0xFF22E39B)
internal val Amber = Color(0xFFFFC857)
internal val Red = Color(0xFFFF6B6B)
internal val Grey = Color(0xFF8FA3BF)

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .background(CardBg, RoundedCornerShape(12.dp)).padding(12.dp)
    ) {
        Text(title, color = Mint, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

/** Try each intent in order until one opens. */
internal fun Context.launchFirst(vararg intents: Intent) {
    for (i in intents) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // try next
        }
    }
    Toast.makeText(this, "This screen is not available on this device", Toast.LENGTH_LONG).show()
}

internal fun Context.appDetailsIntent() =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

private val AUTOSTART = listOf(
    "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
    "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
    "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
    "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
    "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
    "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
)

/** Opens the phone maker's "Autostart" page when it exists, otherwise this app's info page. */
internal fun Context.openAutostart() {
    for ((pkg, cls) in AUTOSTART) {
        try {
            startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
        } catch (e: SecurityException) {
        }
    }
    launchFirst(appDetailsIntent())
}
