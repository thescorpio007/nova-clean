package com.novaclean.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
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

private const val AI_STUDIO_KEYS = "https://aistudio.google.com/apikey"

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

    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().background(Bg).vScrollbar(scroll).verticalScroll(scroll).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Settings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        if (ui.busy != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text("⏳ ${ui.busy}", color = Amber, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::cancelBusy) { Text("Cancel") }
            }
        }

        // ---------------- 1 ----------------
        Section("1. AI (Gemini)") {
            var key by remember(cfg.apiKey) { mutableStateOf(cfg.apiKey) }
            var model by remember(cfg.model) { mutableStateOf(cfg.model) }
            RemoteTextField(key, { key = it }, "API key", Modifier.fillMaxWidth(), password = true)
            RemoteTextField(model, { model = it }, "Model", Modifier.fillMaxWidth())
            Text("Only package names, app names and last-used times are sent. No personal data.",
                color = Grey, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                    if (t.isBlank()) Toast.makeText(ctx, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                    else { key = t.filter { !it.isWhitespace() && it != '"' && it != '\'' }
                        Toast.makeText(ctx, "Pasted. Tap Save.", Toast.LENGTH_SHORT).show() }
                }) { Text("Paste") }
                Button(onClick = { vm.update { apiKey = key; this.model = model } }) { Text("Save") }
                Button(onClick = {
                    // Chrome first, then any browser
                    ctx.launchFirst(
                        Intent(Intent.ACTION_VIEW, Uri.parse(AI_STUDIO_KEYS)).setPackage("com.android.chrome"),
                        Intent(Intent.ACTION_VIEW, Uri.parse(AI_STUDIO_KEYS)),
                    )
                }) { Text("Get API key") }
            }
            Text("Get API key opens Google AI Studio. Sign in, tap Create API key, copy it, come back and paste it above.",
                color = Grey, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))

            Button(onClick = {
                // always test what is typed in the box, saved first
                vm.update { apiKey = key; this.model = model }
                vm.checkApi()
            }, enabled = ui.busy == null,
                modifier = Modifier.padding(top = 8.dp)) { Text("Check API & models") }
            Text("Tests every model your key can use with a small fixed sample, scores each 1-10, " +
                "and selects the fastest one within 1 point of the best score.",
                color = Grey, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            if (ui.modelStatus.isNotEmpty()) {
                Text(ui.modelStatus, color = Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            ui.models.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val star = if (r.model == ui.bestModel) "★ " else ""
                    val line = if (r.ok) "$star${r.model} — ${r.score}/10 · ${r.ms} ms" else "✗ ${r.model} — ${r.note}"
                    Text(line, fontSize = 12.sp, modifier = Modifier.weight(1f),
                        color = if (r.ok) (if (r.model == cfg.model) Mint else Color.White) else Grey)
                    if (r.ok && r.model != cfg.model) TextButton(onClick = { vm.useModel(r.model) }) { Text("Use") }
                }
            }
        }

        // ---------------- 2 ----------------
        Section("2. One-tap clean (Clean now, widget, shortcut)") {
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
            RemoteTextField(label, { label = it }, "Widget button text", Modifier.fillMaxWidth().padding(top = 6.dp))
            Button(onClick = { vm.update { widgetLabel = label } }) { Text("Save widget text") }
            Text("Add the Nova Clean widget from your home screen's widget list. " +
                "Long-press the app icon for a Clean now shortcut. TV boxes: use the Clean now button.",
                color = Grey, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }

        // ---------------- 3 ----------------
        Section("3. Protected apps (never closed)") {
            SwitchRow("Explore system apps in Scan (AI assesses them, you approve)", cfg.exploreSystem) { v ->
                vm.update { exploreSystem = v }
            }
            Text("Core Android, launcher, keyboard, phone, Bluetooth, Wi-Fi, security and remote helpers stay locked. " +
                "System apps are never touched by Clean now, only by Scan → Ask AI → Close selected after you review.",
                color = Grey, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
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

        // ---------------- 4 ----------------
        Section("4. Permissions & background") {
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
            PermRow("Notifications", "For result and update notifications.", p.notif) {
                if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else ctx.launchFirst(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                    ctx.appDetailsIntent(),
                )
            }
            PermRow("Install updates", "Allows Nova Clean to open the installer for its own updates.", p.install) {
                ctx.launchFirst(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")),
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

        // ---------------- 5 ----------------
        Section("5. Engines (strongest ready one is used)") {
            ui.engines.forEach {
                Text(
                    "${if (it.ready) "●" else "○"} ${it.title} — ${it.hint}",
                    color = if (it.ready) Color.White else Grey, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        // ---------------- 6 ----------------
        Section("6. Backup & restore (local)") {
            var pass by remember { mutableStateOf("") }
            Text("Files are kept in ${NovaFiles.FOLDER}: Backups/, Logs/ and ${NovaFiles.HISTORY}.",
                color = Grey, fontSize = 12.sp)
            RemoteTextField(pass, { pass = it }, "Backup password (optional)",
                Modifier.fillMaxWidth().padding(top = 6.dp), password = true)
            Text("With a password the backup is encrypted and includes your API key. Without one, the key is left out.",
                color = Grey, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.backup(pass) }, enabled = !ui.backupBusy) { Text("Back up now") }
                Button(onClick = { restorePicker.launch(arrayOf("*/*")) }, enabled = !ui.backupBusy) { Text("Restore…") }
            }
            if (ui.backupMsg.isNotEmpty()) {
                Text(ui.backupMsg, color = Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        // ---------------- 7 ----------------
        Section("7. Logs & crash reports") {
            Text("Every crash and error is saved with device information in " +
                "${NovaFiles.pathOf("Logs")}/crash-log.txt. The report adds settings and clean history in one text file " +
                "you can attach in Claude.", color = Grey, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = {
                    vm.prepareReport { uri, _ ->
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_SUBJECT, "Nova Clean report")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        ctx.startActivity(Intent.createChooser(send, "Share report"))
                    }
                }) { Text("Share report") }
                Button(onClick = {
                    vm.prepareReport { _, text ->
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Nova Clean report", text))
                        Toast.makeText(ctx, "Report copied. Paste it in Claude.", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Copy report") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = vm::clearCrashLog) { Text("Clear crash log") }
                TextButton(onClick = vm::clearHistory) { Text("Clear history") }
            }
        }

        if (ui.settingsMsg.isNotEmpty()) {
            Text(ui.settingsMsg, color = Amber, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
        }

        Section("8. About") {
            Text("Nova Clean", color = Color.White, fontWeight = FontWeight.Bold)
            Text("A.HAMROUNI", color = Grey, fontSize = 12.sp)
            Text(BuildConfig.BUILD_LABEL, color = Grey, fontSize = 9.sp)
        }

        // ---------------- 9 (bottom) ----------------
        Section("9. Updates (GitHub)") {
            SwitchRow("Check every 6 hours and download silently", cfg.autoUpdate) { v -> vm.update { autoUpdate = v } }
            Text("Current: ${BuildConfig.BUILD_LABEL}", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
            if (ui.updateStatus.isNotEmpty()) {
                Text(ui.updateStatus, color = Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = vm::checkUpdates, enabled = ui.busy == null) { Text("Check now") }
                if (ui.updateReady.isNotEmpty()) {
                    Button(onClick = vm::installUpdate) { Text("Install ${ui.updateReady}") }
                }
            }
            Text("Updates come from github.com/thescorpio007/nova-clean and are verified with SHA-256 before install.",
                color = Grey, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
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
