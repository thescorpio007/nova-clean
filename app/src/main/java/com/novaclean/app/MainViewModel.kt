package com.novaclean.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novaclean.app.ai.AiClassifier
import com.novaclean.app.core.AppEntry
import com.novaclean.app.core.AppScanner
import com.novaclean.app.core.Backup
import com.novaclean.app.core.CleanJob
import com.novaclean.app.core.Prefs
import com.novaclean.app.core.Verdict
import com.novaclean.app.engine.EngineManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EngineRow(val title: String, val ready: Boolean, val hint: String)

data class Perms(
    val battery: Boolean = false,
    val overlay: Boolean = false,
    val usage: Boolean = false,
    val notif: Boolean = false,
    val storage: Boolean = true,
    val shizuku: Boolean = false,
)

data class Cfg(
    val apiKey: String = "",
    val model: String = Prefs.DEFAULT_MODEL,
    val mode: String = Prefs.MODE_AI_SAFE,
    val closeApps: Boolean = true,
    val cleanCaches: Boolean = true,
    val toast: Boolean = true,
    val notify: Boolean = true,
    val history: Boolean = true,
    val recentMin: Int = 3,
    val widgetLabel: String = "Clean now",
    val protectedPkgs: List<String> = emptyList(),
)

data class UiState(
    val engines: List<EngineRow> = emptyList(),
    val activeEngine: String = "",
    val apps: List<AppEntry> = emptyList(),
    val busy: String? = null,
    val message: String = "Tap Clean now, or Scan to review apps first.",
    val perms: Perms = Perms(),
    val cfg: Cfg = Cfg(),
    val settingsMsg: String = "",
    val needPassword: Boolean = false,
)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val engines = EngineManager(app)
    private val scanner = AppScanner(app)
    private var pendingRestore: ByteArray? = null

    private val _ui = MutableStateFlow(UiState(cfg = readCfg()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init { refreshAll() }

    private fun readCfg() = Cfg(
        apiKey = prefs.apiKey, model = prefs.model, mode = prefs.mode,
        closeApps = prefs.closeApps, cleanCaches = prefs.cleanCaches,
        toast = prefs.toastResult, notify = prefs.notifyResult, history = prefs.keepHistory,
        recentMin = prefs.recentProtectMin, widgetLabel = prefs.widgetLabel,
        protectedPkgs = prefs.protectedPkgs.sorted(),
    )

    private fun readPerms(shizukuReady: Boolean): Perms {
        val pm = app.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        val notif = if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else NotificationManagerCompat.from(app).areNotificationsEnabled()
        val storage = Build.VERSION.SDK_INT > 28 ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        return Perms(
            battery = pm.isIgnoringBatteryOptimizations(app.packageName),
            overlay = Settings.canDrawOverlays(app),
            usage = scanner.hasUsageAccess(),
            notif = notif, storage = storage, shizuku = shizukuReady,
        )
    }

    fun refreshAll() {
        viewModelScope.launch {
            val st = engines.statuses()
            val best = engines.best()
            val shizukuReady = st.firstOrNull { it.first.id == "shizuku" }?.second?.ready == true
            _ui.update {
                it.copy(
                    engines = st.map { (e, s) -> EngineRow(e.title, s.ready, s.hint) },
                    activeEngine = best.title,
                    perms = readPerms(shizukuReady),
                    cfg = readCfg(),
                )
            }
        }
    }

    fun requestShizuku() = engines.shizuku.requestPermission()

    /** Change any setting: vm.update { closeApps = true } */
    fun update(block: Prefs.() -> Unit) {
        prefs.block()
        CleanWidget.updateAll(app)
        _ui.update { it.copy(cfg = readCfg()) }
    }

    private fun launchBusy(msg: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = msg) }
            try {
                block()
            } catch (e: Exception) {
                _ui.update { it.copy(message = "Error: ${e.message}") }
            } finally {
                _ui.update { it.copy(busy = null) }
            }
        }
    }

    // ---------- One tap ----------
    fun cleanNow() = launchBusy("Cleaning…") {
        val r = CleanJob(app).run { step -> _ui.update { it.copy(busy = step) } }
        _ui.update { it.copy(message = r.summary(), apps = emptyList()) }
    }

    // ---------- Review flow ----------
    fun scan() = launchBusy("Scanning…") {
        val shell = engines.bestShell()
        val running = shell?.runningPackages()
        val fg = shell?.foregroundServicePackages() ?: emptySet()
        val list = withContext(Dispatchers.Default) {
            scanner.scan(running, fg, prefs.protectedPkgs, prefs.recentProtectMin)
        }
        val tip = when {
            shell == null && !scanner.hasUsageAccess() ->
                " Tip: grant Usage Access in Settings for a more accurate list."
            shell == null -> " (Apps used in the last 24 h.)"
            else -> ""
        }
        _ui.update {
            it.copy(
                apps = list,
                message = "${list.count { a -> !a.isProtected }} apps to review, " +
                    "${list.count { a -> a.isProtected }} protected.$tip",
            )
        }
    }

    fun askAi() = launchBusy("Asking AI…") {
        val cands = _ui.value.apps.filter { !it.isProtected }
        if (cands.isEmpty()) {
            _ui.update { it.copy(message = "Scan first: nothing to analyse.") }
            return@launchBusy
        }
        if (prefs.apiKey.isBlank()) {
            _ui.update { it.copy(message = "Add your Gemini API key in Settings.") }
            return@launchBusy
        }
        val res = withContext(Dispatchers.IO) {
            AiClassifier(prefs.apiKey, prefs.model).classify(cands, CleanJob.deviceInfo(app))
        }
        _ui.update { st ->
            st.copy(
                apps = st.apps.map { a ->
                    val r = res[a.pkg]
                    if (a.isProtected || r == null) return@map a
                    if (r.first == Verdict.NEVER) {
                        a.copy(
                            verdict = r.first, aiReason = r.second,
                            protectedReason = "AI: never close (${r.second})", selected = false,
                        )
                    } else {
                        a.copy(verdict = r.first, aiReason = r.second, selected = r.first == Verdict.SAFE)
                    }
                },
                message = "AI done. Safe apps are pre-selected. Review, then tap Close selected.",
            )
        }
    }

    fun toggle(pkg: String) = _ui.update { st ->
        st.copy(apps = st.apps.map { if (it.pkg == pkg && !it.isProtected) it.copy(selected = !it.selected) else it })
    }

    fun protect(pkg: String) {
        prefs.protectedPkgs = prefs.protectedPkgs + pkg
        _ui.update { st ->
            st.copy(
                cfg = readCfg(),
                apps = st.apps.map {
                    if (it.pkg == pkg) it.copy(protectedReason = "Protected by you", selected = false) else it
                },
            )
        }
    }

    fun unprotect(pkg: String) {
        prefs.protectedPkgs = prefs.protectedPkgs - pkg
        _ui.update { it.copy(cfg = readCfg()) }
    }

    fun closeSelected() = launchBusy("Closing apps…") {
        val sel = _ui.value.apps.filter { it.selected && !it.isProtected }
        if (sel.isEmpty()) {
            _ui.update { it.copy(message = "Nothing selected.") }
            return@launchBusy
        }
        val eng = engines.best()
        var ok = 0
        for (a in sel) {
            _ui.update { it.copy(busy = "Closing ${a.label}…") }
            if (eng.forceStop(a.pkg)) ok++
            engines.basic.forceStop(a.pkg)
        }
        val closed = sel.map { it.pkg }.toSet()
        _ui.update { st ->
            st.copy(
                apps = st.apps.filter { it.pkg !in closed },
                message = "Closed $ok of ${sel.size} using ${eng.title}.",
            )
        }
    }

    // ---------- Backup / restore ----------
    fun backup(password: String) = launchBusy("Saving backup…") {
        val r = withContext(Dispatchers.IO) { Backup.create(app, prefs, password) }
        _ui.update { it.copy(settingsMsg = r.message) }
    }

    fun restoreFrom(uri: Uri) = launchBusy("Reading backup…") {
        val bytes = withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
        if (bytes == null) {
            _ui.update { it.copy(settingsMsg = "Could not open that file.") }
            return@launchBusy
        }
        if (Backup.isEncrypted(bytes)) {
            pendingRestore = bytes
            _ui.update { it.copy(needPassword = true) }
        } else {
            applyRestore(bytes, "")
        }
    }

    fun restoreWithPassword(password: String) {
        val bytes = pendingRestore ?: return
        _ui.update { it.copy(needPassword = false) }
        viewModelScope.launch { applyRestore(bytes, password) }
    }

    fun cancelRestore() {
        pendingRestore = null
        _ui.update { it.copy(needPassword = false) }
    }

    private suspend fun applyRestore(bytes: ByteArray, password: String) {
        val r = withContext(Dispatchers.IO) { Backup.restore(prefs, bytes, password) }
        if (r.ok) pendingRestore = null
        CleanWidget.updateAll(app)
        _ui.update { it.copy(settingsMsg = r.message, cfg = readCfg()) }
    }
}
