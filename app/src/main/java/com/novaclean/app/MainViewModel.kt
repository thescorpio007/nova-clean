package com.novaclean.app

import android.Manifest
import android.app.Application
import android.content.Context
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
import com.novaclean.app.ai.AiHttpException
import com.novaclean.app.ai.ChatAi
import com.novaclean.app.ai.ModelTester
import com.novaclean.app.core.AppEntry
import com.novaclean.app.core.AppScanner
import com.novaclean.app.core.Backup
import com.novaclean.app.core.ChatMsg
import com.novaclean.app.core.ChatStore
import com.novaclean.app.core.CleanJob
import com.novaclean.app.core.CrashLog
import com.novaclean.app.core.NovaFiles
import com.novaclean.app.core.Prefs
import com.novaclean.app.core.Report
import com.novaclean.app.core.SettingsApplier
import com.novaclean.app.core.UpdateScheduler
import com.novaclean.app.core.Updater
import com.novaclean.app.core.Verdict
import com.novaclean.app.engine.EngineManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class EngineRow(val title: String, val ready: Boolean, val hint: String)

data class Perms(
    val battery: Boolean = false,
    val overlay: Boolean = false,
    val usage: Boolean = false,
    val notif: Boolean = false,
    val storage: Boolean = true,
    val install: Boolean = true,
    val shizuku: Boolean = false,
    val recents: Boolean = false,
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
    val autoUpdate: Boolean = true,
    val exploreSystem: Boolean = false,
    val clearRecents: Boolean = false,
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
    val backupMsg: String = "",
    val backupBusy: Boolean = false,
    val needPassword: Boolean = false,
    val updateStatus: String = "",
    val updateReady: String = "",
    val updatePrompt: Boolean = false,
    val models: List<ModelTester.Result> = emptyList(),
    val bestModel: String = "",
    val modelStatus: String = "",
    val chat: List<ChatMsg> = emptyList(),
    val chatBusy: Boolean = false,
)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val engines = EngineManager(app)
    private val scanner = AppScanner(app)
    private var pendingRestore: ByteArray? = null

    private val _ui = MutableStateFlow(UiState(cfg = readCfg()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        val ready = Updater.pendingReady(app)
        _ui.update {
            it.copy(
                updateStatus = prefs.updateStatus,
                updateReady = ready ?: "",
                updatePrompt = ready != null,
                chat = ChatStore.load(app),
                message = if (CrashLog.consumeCrashFlag(app))
                    "Nova Clean closed unexpectedly last time. The crash was saved. " +
                        "Settings → 7 → Share report sends it for analysis."
                else it.message,
            )
        }
        refreshAll()
    }

    private fun readCfg() = Cfg(
        apiKey = prefs.apiKey, model = prefs.model, mode = prefs.mode,
        closeApps = prefs.closeApps, cleanCaches = prefs.cleanCaches,
        toast = prefs.toastResult, notify = prefs.notifyResult, history = prefs.keepHistory,
        recentMin = prefs.recentProtectMin, widgetLabel = prefs.widgetLabel,
        autoUpdate = prefs.autoUpdate, exploreSystem = prefs.exploreSystem, clearRecents = prefs.clearRecents, protectedPkgs = prefs.protectedPkgs.sorted(),
    )

    private fun readPerms(shizukuReady: Boolean): Perms {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val notif = if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else NotificationManagerCompat.from(app).areNotificationsEnabled()
        val storage = Build.VERSION.SDK_INT > 28 ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val install = Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()
        return Perms(
            battery = pm.isIgnoringBatteryOptimizations(app.packageName),
            overlay = Settings.canDrawOverlays(app),
            usage = scanner.hasUsageAccess(),
            notif = notif, storage = storage, install = install, shizuku = shizukuReady,
            recents = com.novaclean.app.core.RecentsService.isEnabled(app),
        )
    }

    fun refreshAll() {
        viewModelScope.launch {
            try {
                val st = engines.statuses()
                val best = engines.best()
                CrashLog.engine = best.title
                val shizukuReady = st.firstOrNull { it.first.id == "shizuku" }?.second?.ready == true
                _ui.update {
                    it.copy(
                        engines = st.map { (e, s) -> EngineRow(e.title, s.ready, s.hint) },
                        activeEngine = best.title,
                        perms = readPerms(shizukuReady),
                        cfg = readCfg(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                CrashLog.logError(app, "refresh", t)
            }
        }
    }

    fun requestShizuku() = engines.shizuku.requestPermission()

    /** Change any setting: vm.update { closeApps = true } */
    fun update(block: Prefs.() -> Unit) {
        prefs.block()
        CleanWidget.updateAll(app)
        UpdateScheduler.apply(app)
        _ui.update { it.copy(cfg = readCfg()) }
    }

    /** Every action runs inside this wrapper: a failure is logged and shown, never a crash. */
    private var busyJob: Job? = null

    /** Stops whatever is running now (used by the Cancel button). */
    fun cancelBusy() {
        busyJob?.cancel()
    }

    private fun launchBusy(msg: String, block: suspend () -> Unit) {
        busyJob = viewModelScope.launch {
            CrashLog.action = msg
            _ui.update { it.copy(busy = msg) }
            try {
                withTimeout(10 * 60_000L) { block() }
            } catch (e: TimeoutCancellationException) {
                CrashLog.logError(app, msg, e)
                _ui.update { it.copy(message = "“$msg” took too long and was stopped.") }
            } catch (e: CancellationException) {
                _ui.update { it.copy(message = "Cancelled.") }
            } catch (t: Throwable) {
                CrashLog.logError(app, msg, t)
                _ui.update {
                    it.copy(message = "Something went wrong and was saved to the log (Settings → 7). ${t.message ?: ""}")
                }
            } finally {
                _ui.update { it.copy(busy = null) }
                CrashLog.action = "idle"
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
        val running = shell?.let { s -> CrashLog.guard(app, "process list") { s.runningPackages() } }
        val fg = shell?.let { s -> CrashLog.guard(app, "foreground services") { s.foregroundServicePackages() } }
            ?: emptySet()
        val list = withContext(Dispatchers.Default) {
            scanner.scan(running, fg, prefs.protectedPkgs, prefs.recentProtectMin, prefs.exploreSystem, prefs.unlockedPkgs)
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
            _ui.update { it.copy(message = "Add your Gemini API key in Settings → 1.") }
            return@launchBusy
        }
        val res = withContext(Dispatchers.IO) {
            AiClassifier(prefs.apiKey, prefs.model, prefs.fallbackModel).classify(cands, CleanJob.deviceInfo(app))
        }
        _ui.update { st ->
            st.copy(
                apps = st.apps.map { a ->
                    val r = res[a.pkg]
                    if (a.isProtected || r == null) return@map a
                    if (r.first == Verdict.NEVER && !a.unlocked) {
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

    /** Tick or untick every app that is not protected (protected and locked apps never change). */
    fun selectAll(on: Boolean) = _ui.update { st ->
        st.copy(apps = st.apps.map { if (it.isProtected) it else it.copy(selected = on) })
    }

    /** The user knowingly removes any lock (automatic, AI or own) from an app. Nova Clean itself stays locked. */
    fun unlock(pkg: String) {
        if (pkg == app.packageName) return
        prefs.protectedPkgs = prefs.protectedPkgs - pkg
        prefs.unlockedPkgs = prefs.unlockedPkgs + pkg
        _ui.update { st ->
            st.copy(
                cfg = readCfg(),
                apps = st.apps.map {
                    if (it.pkg == pkg) it.copy(protectedReason = null, unlocked = true, selected = false) else it
                },
            )
        }
    }

    fun protect(pkg: String) {
        prefs.unlockedPkgs = prefs.unlockedPkgs - pkg
        prefs.protectedPkgs = prefs.protectedPkgs + pkg
        _ui.update { st ->
            st.copy(
                cfg = readCfg(),
                apps = st.apps.map {
                    if (it.pkg == pkg) it.copy(protectedReason = "Protected by you", unlocked = false, selected = false) else it
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
            if (CrashLog.guard(app, "force-stop ${a.pkg}") { eng.forceStop(a.pkg) } == true) ok++
            CrashLog.guard(app, "background-kill ${a.pkg}") { engines.basic.forceStop(a.pkg) }
        }
        val closed = sel.map { it.pkg }.toSet()
        var cacheNote = ""
        if (prefs.cleanCaches) {
            val shell = engines.bestShell()
            if (shell == null) {
                cacheNote = " Cache clean for all apps needs Shizuku or root."
            } else {
                _ui.update { it.copy(busy = "Clearing caches of all apps…") }
                val c = CrashLog.guard(app, "trim caches") { shell.trimCaches() } == true
                cacheNote = if (c) " Caches of all apps cleared." else " Cache clean failed."
            }
        }
        var recentsNote = ""
        if (prefs.clearRecents) {
            if (!com.novaclean.app.core.RecentsService.isEnabled(app)) {
                recentsNote = " Clear Recents is on but not granted (Settings -> 4)."
            } else {
                _ui.update { it.copy(busy = "Clearing Recents...") }
                val done = com.novaclean.app.core.RecentsService.clearRecents(app)
                recentsNote = if (done) " Recents cleared." else " Could not find Clear all."
            }
        }
        _ui.update { st ->
            st.copy(
                apps = st.apps.filter { it.pkg !in closed },
                message = "Closed $ok of ${sel.size} using ${eng.title}.$cacheNote$recentsNote",
            )
        }
    }

    // ---------- API check and model test (same procedure as Nova Enhance) ----------
    fun checkApi() = launchBusy("Checking API…") {
        val key = prefs.apiKey
        if (key.isBlank()) {
            _ui.update { it.copy(modelStatus = "Enter and save your API key first.") }
            return@launchBusy
        }
        _ui.update { it.copy(models = emptyList(), bestModel = "", modelStatus = "Reading the model list…") }
        val names = try {
            withContext(Dispatchers.IO) { ModelTester.list(key) }
        } catch (e: AiHttpException) {
            val why = if (e.code == 400 || e.code == 401 || e.code == 403)
                "The API key was rejected (HTTP ${e.code}): ${e.message}. Use Paste, then Save, and make sure the key starts with AIza."
            else "Google answered HTTP ${e.code}: ${e.message}"
            _ui.update { it.copy(modelStatus = why) }
            return@launchBusy
        } catch (e: Exception) {
            _ui.update { it.copy(modelStatus = "Could not reach Google. Check internet or VPN.") }
            return@launchBusy
        }
        if (names.isEmpty()) {
            _ui.update { it.copy(modelStatus = "The key works but no usable Gemini text model was listed.") }
            return@launchBusy
        }
        val results = mutableListOf<ModelTester.Result>()
        for ((i, n) in names.withIndex()) {
            _ui.update { it.copy(busy = "Testing $n (${i + 1}/${names.size})…") }
            results += withContext(Dispatchers.IO) { ModelTester.test(key, n) }
            _ui.update { it.copy(models = results.toList()) }
        }
        val (best, second) = ModelTester.pickBest(results)
        if (best == null) {
            _ui.update { it.copy(modelStatus = "The key is valid but no model passed the test (quota or busy). Try again later.") }
        } else {
            prefs.model = best.model
            if (second != null) prefs.fallbackModel = second.model
            val okCount = results.count { it.ok && it.score > 0 }
            _ui.update {
                it.copy(
                    cfg = readCfg(), bestModel = best.model,
                    modelStatus = "Key OK. $okCount of ${results.size} models available. " +
                        "Selected ${best.model} (score ${best.score}/10, ${best.ms} ms)" +
                        (second?.let { s -> "; backup ${s.model}" } ?: "") + ".",
                )
            }
        }
    }

    fun useModel(name: String) {
        prefs.model = name
        _ui.update { it.copy(cfg = readCfg(), modelStatus = "Model set to $name.") }
    }

    // ---------- AI chat ----------
    private fun setChat(list: List<ChatMsg>, busy: Boolean) {
        ChatStore.save(app, list)
        _ui.update { it.copy(chat = list, chatBusy = busy) }
    }

    fun newChat() = setChat(emptyList(), false)

    fun sendChat(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _ui.value.chatBusy) return
        if (prefs.apiKey.isBlank()) {
            setChat(_ui.value.chat + ChatMsg("sys", "Add your Gemini API key in Settings → 1 first."), false)
            return
        }
        val msgs = _ui.value.chat + ChatMsg("user", t)
        setChat(msgs, true)
        viewModelScope.launch {
            CrashLog.action = "ai chat"
            val turns = msgs.filter { it.role == "user" || it.role == "ai" }.takeLast(24)
                .map { (if (it.role == "user") "user" else "model") to it.text }
            val lines = _ui.value.engines.map { "${if (it.ready) "●" else "○"} ${it.title} — ${it.hint}" }
            val reply = try {
                withContext(Dispatchers.IO) {
                    val system = ChatAi.systemPrompt(app, prefs, lines)
                    ChatAi.ask(prefs.apiKey, prefs.model, prefs.fallbackModel, system, turns)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                CrashLog.logError(app, "ai chat", e)
                null
            }
            val aiMsg = if (reply == null) {
                ChatMsg("sys", "The AI request failed (saved to the log). Check the key and model in Settings → 1 (Check API & models).")
            } else {
                val applied = reply.changes?.let { SettingsApplier.apply(prefs, it) }
                if (applied != null) {
                    CleanWidget.updateAll(app)
                    UpdateScheduler.apply(app)
                }
                ChatMsg("ai", reply.text, applied?.summary ?: "", applied?.undo ?: "")
            }
            _ui.update { it.copy(cfg = readCfg()) }
            setChat(_ui.value.chat + aiMsg, false)
            CrashLog.action = "idle"
        }
    }

    fun undoChat(index: Int) {
        val m = _ui.value.chat.getOrNull(index) ?: return
        if (m.undo.isEmpty() || m.undone) return
        try {
            prefs.import(JSONObject(m.undo))
            CleanWidget.updateAll(app)
            UpdateScheduler.apply(app)
        } catch (t: Throwable) {
            CrashLog.logError(app, "undo chat change", t)
        }
        val list = _ui.value.chat.mapIndexed { i, x -> if (i == index) x.copy(undone = true) else x }
        _ui.update { it.copy(cfg = readCfg()) }
        setChat(list, _ui.value.chatBusy)
    }

    // ---------- Backup / restore ----------
    private fun backupRun(msg: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(backupBusy = true, backupMsg = msg) }
            try {
                withTimeout(60_000L) { block() }
            } catch (e: TimeoutCancellationException) {
                _ui.update { it.copy(backupMsg = "Took too long and was stopped. Try again.") }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                CrashLog.logError(app, "backup/restore", t)
                _ui.update { it.copy(backupMsg = "Failed: ${t.message ?: t.javaClass.simpleName}") }
            } finally {
                _ui.update { it.copy(backupBusy = false) }
            }
        }
    }

    fun backup(password: String) = backupRun("Saving backup…") {
        val r = withContext(Dispatchers.IO) { Backup.create(app, prefs, password) }
        _ui.update { it.copy(backupMsg = (if (r.ok) "✓ " else "✗ ") + r.message) }
    }

    fun restoreFrom(uri: Uri) = backupRun("Reading backup…") {
        val bytes = withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
        if (bytes == null) {
            _ui.update { it.copy(backupMsg = "✗ Could not open that file.") }
        } else if (Backup.isEncrypted(bytes)) {
            pendingRestore = bytes
            _ui.update { it.copy(needPassword = true, backupMsg = "Enter the backup password.") }
        } else {
            applyRestore(bytes, "")
        }
    }

    fun restoreWithPassword(password: String) {
        val bytes = pendingRestore ?: return
        _ui.update { it.copy(needPassword = false) }
        viewModelScope.launch {
            CrashLog.guard(app, "restore") { applyRestore(bytes, password) }
        }
    }

    fun cancelRestore() {
        pendingRestore = null
        _ui.update { it.copy(needPassword = false) }
    }

    private suspend fun applyRestore(bytes: ByteArray, password: String) {
        val r = withContext(Dispatchers.IO) { Backup.restore(prefs, bytes, password) }
        if (r.ok) pendingRestore = null
        CleanWidget.updateAll(app)
        UpdateScheduler.apply(app)
        _ui.update { it.copy(backupMsg = (if (r.ok) "✓ " else "✗ ") + r.message, cfg = readCfg()) }
    }

    // ---------- Updates ----------
    fun checkUpdates() = launchBusy("Checking GitHub…") {
        val o = withContext(Dispatchers.IO) { Updater.checkAndDownload(app) }
        prefs.updateStatus = o.status
        val ready = if (o.ready) Updater.pendingReady(app) else null
        _ui.update {
            it.copy(updateStatus = o.status, updateReady = ready ?: "", updatePrompt = ready != null)
        }
    }

    fun installUpdate() {
        try {
            val err = Updater.install(app)
            if (err != null) _ui.update { it.copy(updateStatus = err) }
        } catch (t: Throwable) {
            CrashLog.logError(app, "install update", t)
            _ui.update { it.copy(updateStatus = "Could not open the installer: ${t.message}") }
        }
    }

    fun dismissUpdatePrompt() = _ui.update { it.copy(updatePrompt = false) }

    // ---------- Logs & report ----------
    fun prepareReport(onReady: (Uri, String) -> Unit) {
        viewModelScope.launch {
            val lines = _ui.value.engines.map { "${if (it.ready) "●" else "○"} ${it.title} — ${it.hint}" }
            val r = withContext(Dispatchers.IO) {
                CrashLog.guard(app, "report") { Report.write(app, prefs, lines) }
            }
            if (r != null) {
                _ui.update { it.copy(settingsMsg = "Report saved: ${NovaFiles.pathOf(CrashLog.SUB)}/NovaClean-report.txt") }
                onReady(r.first, r.second)
            } else {
                _ui.update { it.copy(settingsMsg = "Could not build the report.") }
            }
        }
    }

    fun clearCrashLog() {
        CrashLog.clear(app)
        _ui.update { it.copy(settingsMsg = "Crash log cleared.") }
    }

    fun clearHistory() {
        NovaFiles.clearHistory(app)
        _ui.update { it.copy(settingsMsg = "History cleared.") }
    }
}
