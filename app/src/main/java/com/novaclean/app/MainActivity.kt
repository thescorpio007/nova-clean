package com.novaclean.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novaclean.app.core.AppEntry
import com.novaclean.app.core.Verdict

private val Bg = Color(0xFF0B1F3A)
private val Mint = Color(0xFF22E39B)
private val Amber = Color(0xFFFFC857)
private val Red = Color(0xFFFF6B6B)
private val Grey = Color(0xFF8FA3BF)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Mint, background = Bg, surface = Bg)) {
                NovaScreen(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshEngines()
    }
}

private fun Context.openSafely(action: String) {
    try {
        startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "This screen is not available on this device", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun NovaScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    val idle = ui.busy == null

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        Text("Nova Clean", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Active engine: ${ui.activeEngine}", color = Mint, fontSize = 14.sp)
        ui.engines.forEach {
            Text(
                "${if (it.ready) "●" else "○"} ${it.title} — ${it.hint}",
                color = if (it.ready) Color.White else Grey, fontSize = 12.sp,
            )
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = vm::scan, enabled = idle) { Text("Scan") }
            Button(onClick = vm::askAi, enabled = idle) { Text("Ask AI") }
            Button(onClick = vm::closeSelected, enabled = idle) { Text("Close selected") }
            Button(onClick = vm::cleanCaches, enabled = idle) { Text("Clean caches") }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = { ctx.openSafely(Settings.ACTION_USAGE_ACCESS_SETTINGS) }) {
                Text(if (ui.usageAccess) "Usage access ✓" else "Usage access")
            }
            TextButton(onClick = { ctx.openSafely(Settings.ACTION_ACCESSIBILITY_SETTINGS) }) { Text("Accessibility") }
            TextButton(onClick = vm::requestShizuku) { Text("Grant Shizuku") }
            TextButton(onClick = vm::refreshEngines) { Text("Refresh") }
            TextButton(onClick = { showSettings = true }) { Text(if (ui.hasKey) "Settings ✓" else "Settings (AI key)") }
        }

        Text(
            ui.busy ?: ui.message,
            color = if (ui.busy != null) Amber else Grey,
            fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp),
        )

        LazyColumn(Modifier.weight(1f)) {
            items(ui.apps, key = { it.pkg }) { a ->
                AppRow(a, onToggle = { vm.toggle(a.pkg) }, onProtect = { vm.protect(a.pkg) })
            }
        }

        Text("A.HAMROUNI", color = Grey, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Text(BuildConfig.BUILD_LABEL, color = Grey, fontSize = 9.sp)
    }

    if (showSettings) SettingsDialog(vm, onClose = { showSettings = false })
}

@Composable
private fun AppRow(a: AppEntry, onToggle: () -> Unit, onProtect: () -> Unit) {
    val line = when {
        a.isProtected -> "🔒 ${a.protectedReason}"
        a.verdict != Verdict.UNKNOWN -> "${a.verdict.name}: ${a.aiReason}"
        else -> ""
    }
    val color = when {
        a.isProtected -> Grey
        a.verdict == Verdict.SAFE -> Mint
        a.verdict == Verdict.CAUTION -> Amber
        a.verdict == Verdict.NEVER -> Red
        else -> Color.White
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !a.isProtected, onClick = onToggle).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = a.selected, onCheckedChange = { onToggle() }, enabled = !a.isProtected)
        Column(Modifier.weight(1f)) {
            Text(a.label, color = if (a.isProtected) Grey else Color.White, fontWeight = FontWeight.Bold)
            Text(a.pkg, color = Grey, fontSize = 11.sp)
            if (line.isNotEmpty()) Text(line, color = color, fontSize = 12.sp)
        }
        if (!a.isProtected) TextButton(onClick = onProtect) { Text("Protect") }
    }
}

@Composable
private fun SettingsDialog(vm: MainViewModel, onClose: () -> Unit) {
    var key by remember { mutableStateOf(vm.apiKey) }
    var model by remember { mutableStateOf(vm.model) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("AI settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(key, { key = it }, label = { Text("Gemini API key") }, singleLine = true)
                OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true)
                Text(
                    "Only package names, app names and last-used times are sent. No personal data.",
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = { vm.saveSettings(key, model); onClose() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
