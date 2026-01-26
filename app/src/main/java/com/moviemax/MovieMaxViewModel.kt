package com.moviemax

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

enum class MainTab { Results, History }

data class ResultItem(val title: String, val link: String)
data class HistoryItem(val title: String, val info: String, val link: String)

data class UiState(
    val query: String = "",
    val selectedYear: String = "No Year",
    val serverStatus: String = "Connecting to servers...",
    val canSearch: Boolean = false,
    val actionStatus: String = "",
    val appVersion: String = APP_VERSION,
    val dbVersion: String? = null,
    val availableServers: Set<String> = emptySet(),
    val results: List<ResultItem> = emptyList(),
    val history: List<HistoryItem> = emptyList(),
    val selectedTab: MainTab = MainTab.History,
    val dbReady: Boolean = false,
    val playerLink: String? = null,
    val playerTitle: String? = null,
    val playerStartMs: Long = 0L,
    val playerAudioLabel: String? = null,
    val playerAudioLanguage: String? = null,
    val playerAudioGroupIndex: Int? = null,
    val playerAudioTrackIndex: Int? = null,
    val playerSubtitleLabel: String? = null,
    val playerSubtitleLanguage: String? = null,
    val playerSubtitleGroupIndex: Int? = null,
    val playerSubtitleTrackIndex: Int? = null,
    val playerSubtitleEnabled: Boolean = false,
    val playerSubtitleUri: String? = null
)

class MovieMaxViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MovieRepository(app)
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var availableServers: List<String> = emptyList()
    private var historyIndex: MutableMap<String, HistoryEntry> = mutableMapOf()
    private var statusJob: Job? = null
    private var historySaveJob: Job? = null

    init {
        loadHistory()
        refreshDbVersion()
        ensureDb()
        checkServers()
    }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
    }

    fun onYearChange(value: String) {
        _uiState.update { it.copy(selectedYear = value) }
    }

    fun onTabSelected(tab: MainTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun startSearch() {
        val state = _uiState.value
        if (!state.canSearch) {
            setStatus("Connect at least one server to search.")
            return
        }
        if (!state.dbReady) {
            setStatus("Database is not ready yet.")
            return
        }
        val query = state.query.trim()
        if (query.length < 2) {
            setStatus("Type at least 2 characters to search.")
            return
        }
        val year = state.selectedYear.takeIf { it != "No Year" }
        _uiState.update { it.copy(actionStatus = "Searching...", selectedTab = MainTab.Results) }

        viewModelScope.launch(Dispatchers.IO) {
            val results = repo.matchMovies(availableServers, query, year)
            val items = results.map { ResultItem(it.title, it.link) }
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(results = items) }
                if (items.isEmpty()) setStatus("No results found.")
                else setStatus("Found ${items.size} results.")
            }
        }
    }

    fun openResult(link: String, title: String) {
        if (link.isBlank()) return
        openInternal(link, title)
    }

    fun openHistory(link: String, title: String) {
        openInternal(link, title)
    }

    fun openInternal(link: String, title: String) {
        if (link.isBlank()) return
        val safeTitle = title.ifBlank { link }
        val entry = historyIndex[link]
        val resumeMs = if (entry != null && entry.duration > 0 && entry.position > 0) {
            entry.position * 1000L
        } else {
            0L
        }
        saveHistoryPlayback(link, safeTitle)
        _uiState.update {
            it.copy(
                playerLink = link,
                playerTitle = safeTitle,
                playerStartMs = resumeMs,
                playerAudioLabel = entry?.audioLabel,
                playerAudioLanguage = entry?.audioLanguage,
                playerAudioGroupIndex = entry?.audioGroupIndex,
                playerAudioTrackIndex = entry?.audioTrackIndex,
                playerSubtitleLabel = entry?.subtitleLabel,
                playerSubtitleLanguage = entry?.subtitleLanguage,
                playerSubtitleGroupIndex = entry?.subtitleGroupIndex,
                playerSubtitleTrackIndex = entry?.subtitleTrackIndex,
                playerSubtitleEnabled = entry?.subtitleEnabled ?: false,
                playerSubtitleUri = entry?.subtitleUri
            )
        }
    }

    fun closePlayer() {
        _uiState.update {
            it.copy(
                playerLink = null,
                playerTitle = null,
                playerStartMs = 0L,
                playerAudioLabel = null,
                playerAudioLanguage = null,
                playerAudioGroupIndex = null,
                playerAudioTrackIndex = null,
                playerSubtitleLabel = null,
                playerSubtitleLanguage = null,
                playerSubtitleGroupIndex = null,
                playerSubtitleTrackIndex = null,
                playerSubtitleEnabled = false,
                playerSubtitleUri = null
            )
        }
    }

    fun updateHistoryProgress(link: String, title: String, positionMs: Long, durationMs: Long) {
        if (link.isBlank()) return
        val now = System.currentTimeMillis() / 1000
        val positionSec = maxOf(0L, positionMs / 1000L)
        val durationSec = maxOf(0L, durationMs / 1000L)
        val entry = historyIndex[link] ?: HistoryEntry(
            link = link,
            name = title.ifBlank { link },
            position = 0,
            duration = 0,
            lastPlayedTs = now
        )
        val updated = entry.copy(
            name = title.ifBlank { entry.name },
            position = positionSec,
            duration = durationSec,
            lastPlayedTs = now
        )
        historyIndex[link] = updated
        val history = historyIndex.values.sortedByDescending { it.lastPlayedTs }.take(100)
        _uiState.update { it.copy(history = renderHistory(history)) }

        historySaveJob?.cancel()
        historySaveJob = viewModelScope.launch {
            delay(1500)
            repo.saveHistory(history)
        }
    }

    fun updateTrackSelection(
        link: String,
        title: String,
        audioLabel: String?,
        audioLanguage: String?,
        audioGroupIndex: Int?,
        audioTrackIndex: Int?,
        subtitleLabel: String?,
        subtitleLanguage: String?,
        subtitleGroupIndex: Int?,
        subtitleTrackIndex: Int?,
        subtitleEnabled: Boolean,
        subtitleUri: String?
    ) {
        if (link.isBlank()) return
        val now = System.currentTimeMillis() / 1000
        val entry = historyIndex[link] ?: HistoryEntry(
            link = link,
            name = title.ifBlank { link },
            position = 0,
            duration = 0,
            lastPlayedTs = now
        )
        val updated = entry.copy(
            name = title.ifBlank { entry.name },
            lastPlayedTs = now,
            audioLabel = audioLabel,
            audioLanguage = audioLanguage,
            audioGroupIndex = audioGroupIndex,
            audioTrackIndex = audioTrackIndex,
            subtitleLabel = subtitleLabel,
            subtitleLanguage = subtitleLanguage,
            subtitleGroupIndex = subtitleGroupIndex,
            subtitleTrackIndex = subtitleTrackIndex,
            subtitleEnabled = subtitleEnabled,
            subtitleUri = subtitleUri
        )
        historyIndex[link] = updated
        val history = historyIndex.values.sortedByDescending { it.lastPlayedTs }.take(100)
        _uiState.update { it.copy(history = renderHistory(history)) }

        historySaveJob?.cancel()
        historySaveJob = viewModelScope.launch {
            delay(1500)
            repo.saveHistory(history)
        }
    }

    fun openExternal(link: String, title: String) {
        if (link.isBlank()) return
        val opened = repo.openExternal(link)
        if (opened) {
            saveHistoryPlayback(link, title.ifBlank { link })
            setStatus("Opened in external player.")
        } else {
            setStatus("Unable to open external player.")
        }
    }

    fun clearHistory() {
        historyIndex.clear()
        repo.saveHistory(emptyList())
        _uiState.update { it.copy(history = emptyList()) }
        setStatus("History cleared.")
    }

    fun removeHistoryItem(link: String) {
        if (link.isBlank()) return
        historyIndex.remove(link)
        val history = historyIndex.values.sortedByDescending { it.lastPlayedTs }.take(100)
        repo.saveHistory(history)
        _uiState.update { it.copy(history = renderHistory(history)) }
        setStatus("History item removed.")
    }

    private fun ensureDb() {
        viewModelScope.launch {
            if (!repo.dbReady()) {
                val progress = createProgressUpdater("Downloading database...")
                setStatus("Downloading database...", autoClear = false)
                val remoteVersion = repo.fetchRemoteDbVersion(DB_VERSION_URL).getOrNull()
                val result = repo.downloadDb(DB_URL, progress)
                if (result.isSuccess) {
                    if (!remoteVersion.isNullOrBlank()) {
                        repo.saveLocalDbVersion(remoteVersion)
                    }
                    _uiState.update { it.copy(dbReady = true) }
                    refreshDbVersion()
                    setStatus("Database ready.")
                } else {
                    setStatus("DB download failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}")
                }
                return@launch
            }

            setStatus("Checking for updates...")
            val remoteVersion = repo.fetchRemoteDbVersion(DB_VERSION_URL).getOrNull()
            if (remoteVersion.isNullOrBlank()) {
                _uiState.update { it.copy(dbReady = true) }
                setStatus("Database ready.")
                return@launch
            }

            val localVersion = repo.getLocalDbVersion()
            if (localVersion == null || localVersion != remoteVersion) {
                val progress = createProgressUpdater("Updating database...")
                setStatus("Updating database...", autoClear = false)
                val result = repo.downloadDb(DB_URL, progress)
                if (result.isSuccess) {
                    repo.saveLocalDbVersion(remoteVersion)
                    _uiState.update { it.copy(dbReady = true) }
                    refreshDbVersion()
                    setStatus("Database updated.")
                } else {
                    setStatus("DB update failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}")
                }
            } else {
                _uiState.update { it.copy(dbReady = true) }
                refreshDbVersion()
                setStatus("Database ready.")
            }
        }
    }

    private fun checkServers() {
        viewModelScope.launch {
            val total = SERVER_LIST.size
            val available = mutableListOf<String>()
            for (server in SERVER_LIST) {
                if (repo.pingServer(server)) available.add(server)
                _uiState.update {
                    it.copy(
                        serverStatus = "Connected Servers: ${available.size}/$total",
                        availableServers = available.toSet()
                    )
                }
            }
            availableServers = available
            _uiState.update {
                it.copy(
                    canSearch = available.isNotEmpty(),
                    serverStatus = "Connected Servers: ${available.size}/$total",
                    availableServers = available.toSet()
                )
            }
        }
    }

    private fun loadHistory() {
        val history = repo.loadHistory()
        historyIndex = history.associateBy { it.link }.toMutableMap()
        _uiState.update { it.copy(history = renderHistory(history)) }
    }

    private fun refreshDbVersion() {
        val version = repo.getLocalDbVersion()
        _uiState.update { it.copy(dbVersion = version) }
    }

    private fun saveHistoryPlayback(link: String, title: String) {
        val now = System.currentTimeMillis() / 1000
        val existing = historyIndex[link]
        val entry = if (existing != null) {
            existing.copy(
                name = title.ifBlank { existing.name },
                lastPlayedTs = now
            )
        } else {
            HistoryEntry(
                link = link,
                name = title,
                position = 0,
                duration = 0,
                lastPlayedTs = now
            )
        }
        historyIndex[link] = entry
        val history = historyIndex.values.sortedByDescending { it.lastPlayedTs }.take(100)
        repo.saveHistory(history)
        _uiState.update { it.copy(history = renderHistory(history)) }
    }

    private fun renderHistory(entries: List<HistoryEntry>): List<HistoryItem> {
        return entries.map { entry ->
            val info = if (entry.duration > 0) {
                "${fmtTime(entry.position)} / ${fmtTime(entry.duration)}"
            } else {
                "Player"
            }
            val label = if (entry.lastPlayedTs > 0) "$info | ${humanTime(entry.lastPlayedTs)}" else info
            HistoryItem(
                title = entry.name,
                info = label,
                link = entry.link
            )
        }
    }

    private fun humanTime(tsSeconds: Long): String {
        if (tsSeconds <= 0) return ""
        val delta = (System.currentTimeMillis() / 1000 - tsSeconds).toInt()
        return when {
            delta < 60 -> "just now"
            delta < 3600 -> "${delta / 60} min ago"
            delta < 86400 -> {
                val hours = delta / 3600
                if (hours == 1) "1 hour ago" else "$hours hours ago"
            }
            else -> {
                val days = delta / 86400
                if (days == 1) "1 day ago" else "$days days ago"
            }
        }
    }

    private fun fmtTime(seconds: Long): String {
        if (seconds <= 0) return "00:00"
        val totalSeconds = seconds.toInt()
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        val h = m / 60
        val mm = m % 60
        return if (h > 0) {
            String.format("%02d:%02d:%02d", h, mm, s)
        } else {
            String.format("%02d:%02d", mm, s)
        }
    }

    private fun setStatus(message: String, autoClear: Boolean = true) {
        _uiState.update { it.copy(actionStatus = message) }
        statusJob?.cancel()
        if (message.isNotBlank() && autoClear) {
            statusJob = viewModelScope.launch {
                delay(7000)
                _uiState.update {
                    if (it.actionStatus == message) it.copy(actionStatus = "") else it
                }
            }
        }
    }

    private fun createProgressUpdater(prefix: String): (Long, Long) -> Unit {
        return object : (Long, Long) -> Unit {
            private var lastPct = -1
            private var lastTime = 0L
            override fun invoke(bytes: Long, total: Long) {
                val now = System.currentTimeMillis()
                if (total > 0) {
                    val pct = ((bytes * 100) / total).toInt().coerceIn(0, 100)
                    if (pct == lastPct && now - lastTime < 300) return
                    lastPct = pct
                    lastTime = now
                    setStatus("$prefix $pct%", autoClear = false)
                } else {
                    if (now - lastTime < 300) return
                    lastTime = now
                    val mb = bytes / (1024.0 * 1024.0)
                    val text = String.format(Locale.US, "%.1fMB", mb)
                    setStatus("$prefix $text", autoClear = false)
                }
            }
        }
    }
}
