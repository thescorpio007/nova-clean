package com.novaclean.app

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.ScrollState
import com.novaclean.app.core.AppEntry
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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


// ---------- Always-visible scroll bar on the right edge ----------
private fun Modifier.bar(progress: () -> Pair<Float, Float>): Modifier = drawWithContent {
    drawContent()
    val w = 6.dp.toPx()
    val x = size.width - w - 2.dp.toPx()
    drawRoundRect(Color(0x33FFFFFF), Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2))
    val (start, frac) = progress()
    val h = (size.height * frac).coerceAtLeast(40.dp.toPx()).coerceAtMost(size.height)
    val y = start * (size.height - h)
    drawRoundRect(Mint, Offset(x, y), Size(w, h), CornerRadius(w / 2))
}

fun Modifier.vScrollbar(state: ScrollState): Modifier = bar {
    val max = state.maxValue.toFloat()
    val view = if (max <= 0f) 1f else 1f / (1f + max / 1000f)
    (if (max <= 0f) 0f else state.value / max) to view.coerceIn(0.05f, 1f)
}

fun Modifier.vScrollbar(state: LazyListState): Modifier = bar {
    val total = state.layoutInfo.totalItemsCount
    val vis = state.layoutInfo.visibleItemsInfo.size
    if (total <= 0 || vis >= total) 0f to 1f
    else (state.firstVisibleItemIndex.toFloat() / (total - vis).coerceAtLeast(1)).coerceIn(0f, 1f) to vis.toFloat() / total
}

/**
 * Text box that is safe for a TV remote: D-pad moves straight through it. Only OK/Enter
 * (or a touch) starts editing; Done/Back stops editing and returns to normal navigation.
 */
@Composable
fun RemoteTextField(
    value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, password: Boolean = false,
) {
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val outer = remember { FocusRequester() }
    val inner = remember { FocusRequester() }
    fun stop() { editing = false; runCatching { outer.requestFocus() } }

    LaunchedEffect(editing) { if (editing) runCatching { inner.requestFocus() } }

    Box(
        modifier
            .focusRequester(outer)
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused && !editing) 2.dp else 0.dp, if (focused && !editing) Mint else Color.Transparent,
                RoundedCornerShape(8.dp))
            .clickable { editing = true }
    ) {
        OutlinedTextField(
            value, onValueChange, label = { Text(label) }, singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { stop() }),
            modifier = Modifier.fillMaxWidth()
                .focusRequester(inner)
                .focusProperties { canFocus = editing }
                .onPreviewKeyEvent {
                    if (editing && it.type == KeyEventType.KeyUp && (it.key == Key.Back || it.key == Key.Escape)) {
                        stop(); true
                    } else false
                },
        )
        if (!editing) {
            Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { editing = true } })
        }
    }
}


/** Warning shown before any lock is removed. */
@Composable
fun UnprotectDialog(a: AppEntry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⚠ Unprotect ${a.label}?", color = Red) },
        text = {
            Text(
                "${a.label} is locked: ${a.protectedReason ?: "it is currently unprotected"}.\n\n" +
                    "If you unprotect it, Close selected and Clean now may force-stop it, " +
                    (if (a.system) "and as a system app this can break calls, remotes, the home screen or other device functions " +
                        "until it restarts or you reboot. " else "and you may lose unsaved data or a running service. ") +
                    "Only continue if you are sure. You can protect it again any time."
            )
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onConfirm) { Text("Unprotect anyway", color = Red) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Keep protected") } },
    )
}
