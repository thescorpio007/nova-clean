package com.novaclean.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novaclean.app.core.NovaFiles
import com.novaclean.app.core.Prefs

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val cfg = ui.cfg
    val p = ui.perms

    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.restoreFrom(uri)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refreshAll()
    }

    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Settings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        // ---------------- AI ----------------
        Section("AI (Gemini)") {
            var key by remember(cfg.apiKey) { mutableStateOf(cfg.apiKey) }
            var model by remember(cfg.model) { mutableStateOf(cfg.model) }
            OutlinedTextField(key, { key = it }, label = { Text("API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Text("Only package names, app names and last-used times are sent. No personal data.",
                color = Grey, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
            Button(onClick = { vm.update { apiKey = key; this.model = model } }) { Text("Save AI settings") }
        }

        // ---------------- One tap ----------------
        Section("One-tap clean (Clean now, widget, shortcut)") {
            SwitchRow("Close background apps", cfg.closeApps) { v -> vm.update { closeApps = v } }
            SwitchRow("Clear caches (needs Shizuku or root)", cfg.cleanCaches) { v -> vm.update { cleanCaches = v } }
            Text("Which apps get closed", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            RadioRow("Only apps the AI marks SAFE (recommended)", cfg.mode == Prefs.MODE_AI_SAFE) {
                vm.update { mode = Prefs.MODE_AI_SAFE }
            }
            RadioRow("Every unprotected app, no AI", cfg.mode == Prefs.MODE_ALL) {
                vm.update { mode = Prefs.MODE_ALL }
            }
            SwitchRow("Show result message", cfg.toast) { v -> vm.update { toastResult = v } }
            SwitchRow("Show result notification", cfg.notify) { v -> vm.update { notifyResult = v } }
            SwitchRow("Keep a history file", cfg.history) { v -> vm.update { keepHistory = v } }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("Protect apps used in the last ${cfg.recentMin} min", color = Color.White,
                    fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.update { recentProtectMin = cfg.recentMin - 1 } }) { Text("−") }
                TextButton(onClick = { vm.update { recentProtectMin = cfg.recentMin + 1 } }) { Text("+") }
            }
            var label by remember(cfg.widgetLabel) { mutableStateOf(cfg.widgetLabel) }
            OutlinedTextField(label, { label = it }, label = { Text("Widget button text") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Button(onClick = { vm.update { widgetLabel = label } }) { Text("Save widget text") }
            Text("Add the Nova Clean widget from your home screen's widget list. " +
                "Long-press the app icon for a Clean now shortcut. TV boxes: use the Clean now button.",
                color = Grey, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }

        // ---------------- Protected ----------------
        Section("Protected apps (never closed)") {
            if (cfg.protectedPkgs.isEmpty()) {
                Text("None yet. Tap Protect next to an app on the main screen.", color = Grey, fontSize = 12.sp)
            }
            cfg.protectedPkgs.forEach { pkg ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pkg, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.unprotect(pkg) }) { Text("Remove") }
                }
            }
        }

        // ---------------- Permissions ----------------
        Section("Permissions & background") {
            PermRow("Unrestricted battery", "Stops Android from killing Nova Clean in the background.", p.battery) {
                ctx.launchFirst(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            }
            PermRow("Display over other apps", "Lets the widget and shortcut run Nova Clean in front of any app.", p.overlay) {
                ctx.launchFirst(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")),
                    ctx.appDetailsIntent(),
                )
            }
            PermRow("Usage access", "Shows which apps were used recently.", p.usage) {
                ctx.launchFirst(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), ctx.appDetailsIntent())
            }
            PermRow("Notifications", "For the result notification.", p.notif) {
                if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else ctx.launchFirst(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                    ctx.appDetailsIntent(),
                )
            }
            if (Build.VERSION.SDK_INT <= 28) {
                PermRow("Storage (Android 7-9)", "Needed to save files in Download/NovaClean.", p.storage) {
                    permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            PermRow("Shizuku", "Lets Nova Clean force-stop apps and clear all caches without root.", p.shizuku) {
                vm.requestShizuku()
            }
            PermRow("Autostart / background", "Allow Nova Clean to auto-start and run in the background (phone maker's page).", null) {
                ctx.openAutostart()
            }
            PermRow("App info page", "Set Battery to Unrestricted / No restrictions here if the phone offers it.", null) {
                ctx.launchFirst(ctx.appDetailsIntent())
            }
            TextButton(onClick = vm::refreshAll) { Text("Refresh status") }
        }

        // ---------------- Engines ----------------
        Section("Engines (strongest ready one is used)") {
            ui.engines.forEach {
                Text(
                    "${if (it.ready) "●" else "○"} ${it.title} — ${it.hint}",
                    color = if (it.ready) Color.White else Grey, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        // ---------------- Backup ----------------
        Section("Backup & restore (local)") {
            var pass by remember { mutableStateOf("") }
            Text("Files are kept in ${NovaFiles.FOLDER}: Backups/ and ${NovaFiles.HISTORY}.",
                color = Grey, fontSize = 12.sp)
            OutlinedTextField(pass, { pass = it }, label = { Text("Backup password (optional)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Text("With a password the backup is encrypted and includes your API key. Without one, the key is left out.",
                color = Grey, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.backup(pass) }, enabled = ui.busy == null) { Text("Back up now") }
                Button(onClick = { restorePicker.launch(arrayOf("*/*")) }, enabled = ui.busy == null) { Text("Restore…") }
            }
            if (ui.settingsMsg.isNotEmpty()) {
                Text(ui.settingsMsg, color = Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        Section("About") {
            Text("Nova Clean", color = Color.White, fontWeight = FontWeight.Bold)
            Text("A.HAMROUNI", color = Grey, fontSize = 12.sp)
            Text(BuildConfig.BUILD_LABEL, color = Grey, fontSize = 9.sp)
        }
    }

    if (ui.needPassword) {
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            title = { Text("Backup password") },
            text = {
                OutlinedTextField(pw, { pw = it }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), label = { Text("Password") })
            },
            confirmButton = { TextButton(onClick = { vm.restoreWithPassword(pw) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

/** granted == null means the status cannot be read (button only). */
@Composable
private fun PermRow(title: String, desc: String, granted: Boolean?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val mark = when (granted) { true -> "✓ "; false -> "○ "; null -> "" }
            Text(mark + title, color = if (granted == true) Mint else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = Grey, fontSize = 11.sp)
        }
        TextButton(onClick = onClick) { Text(if (granted == true) "Open" else "Grant") }
    }
}
