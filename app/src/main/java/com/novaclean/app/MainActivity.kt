package com.novaclean.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novaclean.app.core.AppEntry
import com.novaclean.app.core.UpdateScheduler
import com.novaclean.app.core.Verdict

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            UpdateScheduler.apply(this)
        } catch (t: Throwable) {
            com.novaclean.app.core.CrashLog.logError(this, "schedule updates", t)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Mint, background = Bg, surface = Bg)) {
                var screen by remember { mutableStateOf("main") }
                BackHandler(enabled = screen != "main") { screen = "main" }
                when (screen) {
                    "settings" -> SettingsScreen(vm, onBack = { screen = "main" })
                    "chat" -> ChatScreen(vm, onBack = { screen = "main" })
                    else -> MainScreen(vm, onSettings = { screen = "settings" }, onChat = { screen = "chat" })
                }
                UpdateDialog(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshAll()
    }
}

@Composable
private fun UpdateDialog(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    if (ui.updatePrompt && ui.updateReady.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = vm::dismissUpdatePrompt,
            title = { Text("Update ready") },
            text = { Text("Nova Clean ${ui.updateReady} is downloaded and verified. Install now?") },
            confirmButton = {
                TextButton(onClick = { vm.dismissUpdatePrompt(); vm.installUpdate() }) { Text("Install") }
            },
            dismissButton = { TextButton(onClick = vm::dismissUpdatePrompt) { Text("Later") } },
        )
    }
}

@Composable
fun MainScreen(vm: MainViewModel, onSettings: () -> Unit, onChat: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val idle = ui.busy == null

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Nova Clean", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("Engine: ${ui.activeEngine}", color = Mint, fontSize = 12.sp)
            }
            TextButton(onClick = onChat) { Text("💬 Chat") }
            TextButton(onClick = onSettings) { Text("⚙ Settings") }
        }

        Button(
            onClick = vm::cleanNow, enabled = idle,
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 6.dp),
        ) { Text("Clean now", fontSize = 18.sp, fontWeight = FontWeight.Bold) }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::scan, enabled = idle, modifier = Modifier.weight(1f)) { Text("Scan") }
            Button(onClick = vm::askAi, enabled = idle, modifier = Modifier.weight(1f)) { Text("Ask AI") }
            Button(onClick = vm::closeSelected, enabled = idle, modifier = Modifier.weight(1.3f)) { Text("Close selected") }
        }

        Text(
            ui.busy ?: ui.message,
            color = if (ui.busy != null) Amber else Grey,
            fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp),
        )

        if (ui.apps.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.selectAll(true) }, enabled = idle, modifier = Modifier.weight(1f)) { Text("☑ Select all") }
                OutlinedButton(onClick = { vm.selectAll(false) }, enabled = idle, modifier = Modifier.weight(1f)) { Text("☐ Unselect all") }
            }
        }
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyColumn(Modifier.weight(1f).vScrollbar(listState), state = listState) {
            items(ui.apps, key = { it.pkg }) { a ->
                AppRow(a, onToggle = { vm.toggle(a.pkg) }, onProtect = { vm.protect(a.pkg) })
            }
        }

        Text("A.HAMROUNI", color = Grey, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Text(BuildConfig.BUILD_LABEL, color = Grey, fontSize = 9.sp)
    }
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
            Text(if (a.system) "${a.pkg}  ·  SYSTEM" else a.pkg, color = Grey, fontSize = 11.sp)
            if (line.isNotEmpty()) Text(line, color = color, fontSize = 12.sp)
        }
        if (!a.isProtected) TextButton(onClick = onProtect) { Text("Protect") }
    }
}
