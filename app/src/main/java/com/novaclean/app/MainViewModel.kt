package com.novaclean.app

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novaclean.app.ai.AiClassifier
import com.novaclean.app.core.AppEntry
import com.novaclean.app.core.AppScanner
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

data class UiState(
    val engines: List<EngineRow> = emptyList(),
    val activeEngine: String = "",
    val apps: List<AppEntry> = emptyList(),
    val busy: String? = null,
    val message: String = "Tap Scan to list apps that can be reviewed.",
    val usageAccess: Boolean = false,
    val hasKey: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val engines = EngineManager(app)
    private val scanner = AppScanner(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val apiKey get() = prefs.apiKey
    val model get() = prefs.model

    init { refreshEngines() }

    fun saveSettings(key: String, model: String) {
        prefs.apiKey = key
        prefs.model = model
        refreshEngines()
    }

    fun refreshEngines() {
        viewModelScope.launch {
            val st = engines.statuses()
            val best = engines.best()
            _ui.update {
                it.copy(
                    engines = st.map { (e, s) -> EngineRow(e.title, s.ready, s.hint) },
                    activeEngine = best.title,
                    usageAccess = scanner.hasUsageAccess(),
                    hasKey = prefs.apiKey.isNotBlank(),
                )
            }
        }
    }

    fun requestShizuku() {
        engines.shizuku.requestPermission()
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

    fun scan() = launchBusy("Scanning…") {
        val shell = engines.bestShell()
        val running = shell?.runningPackages()
        val fg = shell?.foregroundServicePackages() ?: emptySet()
        val list = withContext(Dispatchers.Default) {
            scanner.scan(running, fg, prefs.protectedPkgs)
        }
        val tip = when {
            shell == null && !scanner.hasUsageAccess() ->
                " Tip: grant Usage Access or start Shizuku for a more accurate list."
            shell == null -> " (Based on apps used in the last 24 h.)"
            else -> ""
        }
        _ui.update {
            it.copy(
                apps = list,
                usageAccess = scanner.hasUsageAccess(),
                message = "${list.count { a -> !a.isProtected }} apps can be reviewed, " +
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
        val device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, " +
            if (getApplication<Application>().packageManager
                    .hasSystemFeature("android.software.leanback")) "TV box" else "phone/tablet"
        val res = withContext(Dispatchers.IO) {
            AiClassifier(prefs.apiKey, prefs.model).classify(cands, device)
        }
        _ui.update { st ->
            st.copy(
                apps = st.apps.map { a ->
                    val r = res[a.pkg]
                    if (a.isProtected || r == null) return@map a
                    if (r.first == Verdict.NEVER) {
                        // AI veto: NEVER becomes locked like the hard list.
                        a.copy(
                            verdict = r.first, aiReason = r.second,
                            protectedReason = "AI: never close (${r.second})", selected = false,
                        )
                    } else {
                        a.copy(
                            verdict = r.first, aiReason = r.second,
                            selected = r.first == Verdict.SAFE,
                        )
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
            st.copy(apps = st.apps.map {
                if (it.pkg == pkg) it.copy(protectedReason = "Protected by you", selected = false) else it
            })
        }
    }

    fun closeSelected() = launchBusy("Closing apps…") {
        val sel = _ui.value.apps.filter { it.selected && !it.isProtected }   // re-check protection
        if (sel.isEmpty()) {
            _ui.update { it.copy(message = "Nothing selected.") }
            return@launchBusy
        }
        val eng = engines.best()
        var ok = 0
        for (a in sel) {
            _ui.update { it.copy(busy = "Closing ${a.label}…") }
            if (eng.forceStop(a.pkg)) ok++
            engines.basic.forceStop(a.pkg)       // cheap extra pass
        }
        val closed = sel.map { it.pkg }.toSet()
        _ui.update { st ->
            st.copy(
                apps = st.apps.filter { it.pkg !in closed },
                message = "Closed $ok of ${sel.size} using ${eng.title}.",
            )
        }
    }

    fun cleanCaches() = launchBusy("Clearing caches…") {
        val shell = engines.bestShell()
        if (shell == null) {
            _ui.update {
                it.copy(message = "Clearing every app's cache needs Shizuku/ADB or root " +
                    "(Android blocks it for normal apps). See README to set up Shizuku.")
            }
            return@launchBusy
        }
        val ok = shell.trimCaches()
        _ui.update {
            it.copy(message = if (ok) "Caches cleared using ${shell.title}." else "Cache clearing failed.")
        }
    }
}
