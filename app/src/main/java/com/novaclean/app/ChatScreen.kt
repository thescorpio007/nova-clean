package com.novaclean.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.novaclean.app.core.ChatMsg

@Composable
fun ChatScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(ui.chat.size, ui.chatBusy) {
        val last = ui.chat.size - 1 + (if (ui.chatBusy) 1 else 0)
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("AI chat", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = vm::newChat) { Text("New chat") }
        }

        if (ui.chat.isEmpty()) {
            Text(
                "Talk freely with the AI about how Nova Clean should work for you: what to close, " +
                    "what to protect, caches, the widget, updates. When you agree with a suggestion, " +
                    "say so (for example \"yes, do it\") and the AI changes the settings. " +
                    "Every change has an Undo button. Then tell it what to adjust next.",
                color = Grey, fontSize = 13.sp, modifier = Modifier.padding(8.dp),
            )
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(ui.chat) { i, m -> Bubble(m, onUndo = { vm.undoChat(i) }) }
            if (ui.chatBusy) {
                item { Text("AI is thinking…", color = Amber, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("Message…") },
                modifier = Modifier.weight(1f), maxLines = 4,
            )
            Button(
                onClick = { vm.sendChat(input); input = "" },
                enabled = input.isNotBlank() && !ui.chatBusy,
            ) { Text("Send") }
        }
    }
}

@Composable
private fun Bubble(m: ChatMsg, onUndo: () -> Unit) {
    val mine = m.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(
            Modifier.fillMaxWidth(0.88f)
                .background(
                    when (m.role) { "user" -> Color(0xFF1B4D5C); "sys" -> Color(0xFF4A2B2B); else -> CardBg },
                    RoundedCornerShape(12.dp),
                ).padding(10.dp)
        ) {
            Text(m.text, color = Color.White, fontSize = 14.sp)
            if (m.applied.isNotEmpty()) {
                Text(
                    (if (m.undone) "↩ Undone: " else "✓ Applied: ") + m.applied,
                    color = if (m.undone) Grey else Mint, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (!m.undone) TextButton(onClick = onUndo) { Text("Undo") }
            }
        }
    }
}
