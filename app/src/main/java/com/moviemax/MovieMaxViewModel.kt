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

data class ResultItem(val title: String, val link: String, val posterLink: String?, val baseName: String)
data class HistoryItem(
    val title: String,
    val info: String,
    val link: String,
    val posterLink: String?,
    val baseName: String
)

data class UiState(
    val query: String = "",
    val selectedYear: String = "No Year",
    val serverStatus: String = "Connecting to servers...",
    val canSearch: Boolean = false,
    val serverChecking: Boolean = true,
    val actionStatus: String = "",
    val appVersion: String = APP_VERSION,
    val dbVersion: String? = null,
    val updateAvailable: Boolean = false,
    val updateVersion: String? = null,
    val availableServers: Set<String> = emptySet(),
    val results: List<ResultItem> = emptyList(),
    val aiResults: List<ResultItem> = emptyList(),
    val aiRefreshing: Boolean = false,
    val aiStatus: String = "",
    val aiLastUpdated: Long? = null,
    val aiStale: Boolean = false,
    val isSearching: Boolean = false,
    val discoverStatus: String = "",
    val historyRefreshing: Boolean = false,
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
    private val aiRecommender = AiRecommender(BuildConfig.GROQ_API_KEY)
    private val omdbKey = BuildConfig.OMDB_API_KEY
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var availableServers: List<String> = emptyList()
    private var historyIndex: MutableMap<String, HistoryEntry> = mutableMapOf()
    private var statusJob: Job? = null
    private var historySaveJob: Job? = null
    private var aiRefreshJob: Job? = null
    private var posterCacheSaveJob: Job? = null
    private val posterCache: MutableMap<String, String> = mutableMapOf()
    private val posterInFlight: MutableSet<String> = mutableSetOf()
    private val aiAutoEnabled = false
    private var lastDiscoverAutoRequestAt = 0L
    private val aiTtlMs = 12L * 60L * 60L * 1000L

    init {
        loadPosterCache()
        loadAiCache()
        loadHistory()
        refreshDbVersion()
        checkAppUpdate()
        ensureDb()
        checkServers()
    }

    private fun checkAppUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            val remote = repo.fetchRemoteDbVersion(APP_VERSION_URL).getOrNull()?.trim()
            if (remote.isNullOrBlank()) return@launch
            val local = parseVersion(APP_VERSION)
            val remoteParsed = parseVersion(remote)
            if (remoteParsed.isNotEmpty() && local.isNotEmpty()) {
                if (compareVersions(remoteParsed, local) > 0) {
                    _uiState.update {
                        it.copy(updateAvailable = true, updateVersion = remote)
                    }
                }
            }
        }
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

    fun onDiscoverViewed() {
        val state = _uiState.value
        updateAiStaleState()
        if (state.history.isEmpty()) return
        if (state.aiRefreshing) return
        val needsAuto = state.aiResults.isEmpty() || state.aiStale
        if (!needsAuto) return
        val now = System.currentTimeMillis()
        if (now - lastDiscoverAutoRequestAt < 5000) return
        lastDiscoverAutoRequestAt = now
        refreshAiNow()
    }

    fun dismissUpdate() {
        _uiState.update { it.copy(updateAvailable = false) }
    }

    private fun setDiscoverStatus(message: String, autoClear: Boolean = true) {
        _uiState.update { it.copy(discoverStatus = message) }
        if (message.isNotBlank() && autoClear) {
            viewModelScope.launch {
                delay(3500)
                _uiState.update {
                    if (it.discoverStatus == message) it.copy(discoverStatus = "") else it
                }
            }
        }
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
        _uiState.update { it.copy(isSearching = true, selectedTab = MainTab.Results, discoverStatus = "Searching...") }

        viewModelScope.launch(Dispatchers.IO) {
            val results = repo.matchMovies(availableServers, query, year)
            val posterByBase = mutableMapOf<String, String>()
            results.forEach { result ->
                val key = result.baseName.trim().lowercase()
                val poster = result.posterLink?.takeIf { it.isNotBlank() }
                if (poster != null) {
                    posterByBase[key] = poster
                }
            }
            val items = results.map { result ->
                val key = result.baseName.trim().lowercase()
                val posterRaw = result.posterLink?.takeIf { it.isNotBlank() } ?: posterByBase[key]
                val poster = choosePoster(posterRaw, result.baseName)
                ResultItem(result.title, result.link, poster, result.baseName)
            }
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        results = items,
                        isSearching = false,
                        discoverStatus = if (items.isNotEmpty()) "Search results" else "No results found."
                    )
                }
                ensurePostersForResults(items)
                if (items.isEmpty()) setStatus("No results found.")
                else setStatus("Found ${items.size} results.")
            }
        }
    }

    fun openResult(link: String, title: String, posterLink: String?, baseName: String) {
        if (link.isBlank()) return
        openInternal(link, title, posterLink, baseName)
    }

    fun openHistory(link: String, title: String, posterLink: String?, baseName: String) {
        openInternal(link, title, posterLink, baseName)
    }

    fun openInternal(link: String, title: String, posterLink: String?, baseName: String?) {
        if (link.isBlank()) return
        val safeTitle = title.ifBlank { link }
        val entry = historyIndex[link]
        val resolvedBase = baseName?.ifBlank { null } ?: entry?.baseName ?: guessBaseName(safeTitle)
        val resumeMs = if (entry != null && entry.duration > 0 && entry.position > 0) {
            entry.position * 1000L
        } else {
            0L
        }
        saveHistoryPlayback(link, safeTitle, posterLink, resolvedBase)
        cachePosterForHistory(resolvedBase, posterLink, safeTitle)
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
        val baseName = historyIndex[link]?.baseName ?: guessBaseName(title)
        val entry = historyIndex[link] ?: HistoryEntry(
            link = link,
            name = title.ifBlank { link },
            position = 0,
            duration = 0,
            lastPlayedTs = now,
            baseName = baseName
        )
        val updated = entry.copy(
            name = title.ifBlank { entry.name },
            position = positionSec,
            duration = durationSec,
            lastPlayedTs = now,
            baseName = entry.baseName ?: baseName
        )
        historyIndex[link] = updated
        val history = historyIndex.values.sortedByDescending { it.lastPlayedTs }.take(100)
        _uiState.update { it.copy(history = renderHistory(history)) }

        historySaveJob?.cancel()
        historySaveJob = viewModelScope.launch {
            delay(1500)
            repo.saveHistory(history)
        }
        scheduleAiRefresh()
        ensurePostersForHistory(_uiState.value.history)
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
        val baseName = historyIndex[link]?.baseName ?: guessBaseName(title)
        val entry = historyIndex[link] ?: HistoryEntry(
            link = link,
            name = title.ifBlank { link },
            position = 0,
            duration = 0,
            lastPlayedTs = now,
            baseName = baseName
        )
        val updated = entry.copy(
            name = title.ifBlank { entry.name },
            lastPlayedTs = now,
            baseName = entry.baseName ?: baseName,
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
        scheduleAiRefresh()
        ensurePostersForHistory(_uiState.value.history)
    }

    fun openExternal(link: String, title: String) {
        if (link.isBlank()) return
        val opened = repo.openExternal(link)
        if (opened) {
            saveHistoryPlayback(link, title.ifBlank { link }, null, null)
            setStatus("Opened in external player.")
        } else {
            setStatus("Unable to open external player.")
        }
    }

    fun clearHistory() {
        historyIndex.clear()
        repo.saveHistory(emptyList())
        lastDiscoverAutoRequestAt = 0L
        _uiState.update { it.copy(history = emptyList(), aiResults = emptyList(), aiStatus = "", aiLastUpdated = null, aiStale = false) }
        repo.saveAiCache(null)
        setStatus("History cleared.")
    }

    fun removeHistoryItem(link: String) {
        if (link.isBlank()) return
        historyIndex.remove(link)
        val history = historyIndex.values.sortedByDescending { it.lastPlayedTs }.take(100)
        repo.saveHistory(history)
        _uiState.update { it.copy(history = renderHistory(history)) }
        setStatus("History item removed.")
        scheduleAiRefresh()
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
                    scheduleAiRefresh()
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
                    scheduleAiRefresh()
                    setStatus("Database updated.")
                } else {
                    setStatus("DB update failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}")
                }
            } else {
                _uiState.update { it.copy(dbReady = true) }
                refreshDbVersion()
                scheduleAiRefresh()
                setStatus("Database ready.")
            }
        }
    }

    private fun checkServers() {
        viewModelScope.launch {
            val total = SERVER_LIST.size
            val available = mutableListOf<String>()
            _uiState.update { it.copy(serverChecking = true, serverStatus = "Checking servers...") }
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
                    availableServers = available.toSet(),
                    serverChecking = false
                )
            }
            scheduleAiRefresh()
        }
    }

    private fun loadHistory() {
        val history = repo.loadHistory()
        historyIndex = history.associateBy { it.link }.toMutableMap()
        _uiState.update {
            it.copy(
                history = renderHistory(history),
                selectedTab = if (history.isEmpty()) MainTab.Results else it.selectedTab
            )
        }
        if (history.isEmpty()) {
            lastDiscoverAutoRequestAt = 0L
        }
        scheduleAiRefresh()
        ensurePostersForHistory(_uiState.value.history)
    }

    private fun loadAiCache() {
        val cache = repo.loadAiCache() ?: return
        val items = cache.items.map {
            ResultItem(it.title, it.link, it.posterLink, it.baseName)
        }
        val stale = isAiStale(cache.timestamp)
        _uiState.update { it.copy(aiResults = items, aiLastUpdated = cache.timestamp, aiStale = stale) }
    }

    private fun loadPosterCache() {
        val cached = repo.loadPosterCache().filterValues { it.isNotBlank() }
        if (cached.isNotEmpty()) {
            posterCache.putAll(cached)
        }
    }

    private fun refreshDbVersion() {
        val version = repo.getLocalDbVersion()
        _uiState.update { it.copy(dbVersion = version) }
    }

    private fun updateAiStaleState() {
        val ts = _uiState.value.aiLastUpdated ?: return
        val stale = isAiStale(ts)
        _uiState.update { it.copy(aiStale = stale) }
    }

    private fun parseVersion(raw: String): List<Int> {
        val cleaned = raw.trim().replace(Regex("[^0-9.]"), "")
        if (cleaned.isBlank()) return emptyList()
        return cleaned.split(".").mapNotNull { it.toIntOrNull() }
    }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val ai = a.getOrNull(i) ?: 0
            val bi = b.getOrNull(i) ?: 0
            if (ai != bi) return ai.compareTo(bi)
        }
        return 0
    }

    private fun saveHistoryPlayback(
        link: String,
        title: String,
        posterLink: String?,
        baseName: String?
    ) {
        val now = System.currentTimeMillis() / 1000
        val existing = historyIndex[link]
        val resolvedBase = baseName?.ifBlank { null } ?: existing?.baseName ?: guessBaseName(title)
        val entry = if (existing != null) {
            existing.copy(
                name = title.ifBlank { existing.name },
                lastPlayedTs = now,
                posterLink = posterLink ?: existing.posterLink,
                baseName = existing.baseName ?: resolvedBase,
                localPosterPath = existing.localPosterPath
            )
        } else {
            HistoryEntry(
                link = link,
                name = title,
                position = 0,
                duration = 0,
                lastPlayedTs = now,
                posterLink = posterLink,
                baseName = resolvedBase,
                localPosterPath = null
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
            val baseName = entry.baseName ?: guessBaseName(entry.name)
            HistoryItem(
                title = entry.name,
                info = label,
                link = entry.link,
                posterLink = resolveHistoryPoster(entry),
                baseName = baseName
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

    fun refreshAiNow() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.serverChecking) {
                setDiscoverStatus("Wait to finish server connection checking.", autoClear = true)
                return@launch
            }
            _uiState.update { it.copy(aiRefreshing = true, aiStatus = "AI recommendations loading...") }
            refreshAiRecommendations()
            _uiState.update { it.copy(aiRefreshing = false, aiStale = false) }
        }
    }

    fun refreshHistoryPostersNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(historyRefreshing = true) }
            val items = _uiState.value.history
            if (items.isNotEmpty()) {
                val keys = items.map { normalizeTitle(it.baseName) }.distinct()
                keys.forEach { posterCache.remove(it) }
                ensurePostersForHistory(items)
                items.forEach { item ->
                    cachePosterForHistory(item.baseName, item.posterLink, item.title)
                }
            }
            delay(1200)
            _uiState.update { it.copy(historyRefreshing = false) }
        }
    }

    private fun scheduleAiRefresh() {
        if (!aiAutoEnabled) return
        aiRefreshJob?.cancel()
        aiRefreshJob = viewModelScope.launch {
            delay(1200)
            refreshAiRecommendations()
        }
    }

    private suspend fun refreshAiRecommendations() {
        val state = _uiState.value
        if (!state.dbReady) {
            _uiState.update { it.copy(aiStatus = "Database not ready.") }
            return
        }
        if (availableServers.isEmpty()) {
            _uiState.update { it.copy(aiStatus = "No connected servers yet.") }
            return
        }
        val historyTitles = historyIndex.values
            .sortedByDescending { it.lastPlayedTs }
            .map { it.name }
            .filter { it.isNotBlank() }
            .take(20)

        if (historyTitles.isEmpty()) {
            _uiState.update { it.copy(aiResults = emptyList(), aiStatus = "Watch some movies to get AI recommendations.") }
            return
        }

        _uiState.update { it.copy(aiStatus = "AI analyzing your watch history...") }
        val aiResult = aiRecommender.recommendTitles(historyTitles)
        val aiList = aiResult.getOrNull().orEmpty()
        if (aiResult.isFailure) {
            val err = aiResult.exceptionOrNull()?.message?.take(160) ?: "Unknown error"
            _uiState.update {
                it.copy(
                    aiResults = emptyList(),
                    aiStatus = "AI failed: $err"
                )
            }
            return
        }
        if (aiList.isEmpty()) {
            val fallback = buildFallbackRecommendations()
            if (fallback.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        aiResults = fallback,
                        aiStatus = "AI empty. Showing related movies (${fallback.size}/20)."
                    )
                }
                ensurePostersForResults(fallback)
                saveAiCache(fallback)
            } else {
                val recent = buildHistorySuggestions()
                if (recent.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            aiResults = recent,
                            aiStatus = "AI empty. Showing your recent movies (${recent.size}/20)."
                        )
                    }
                    ensurePostersForResults(recent)
                    saveAiCache(recent)
                } else {
                    _uiState.update { it.copy(aiResults = emptyList(), aiStatus = "No AI suggestions found.") }
                }
            }
            return
        }

        val picked = mutableListOf<ResultItem>()
        val seenLinks = mutableSetOf<String>()
        val globalCandidates = mutableListOf<MovieResult>()
        for (raw in aiList.take(50)) {
            val variants = buildQueryVariants(raw)
            var usedQuery: String? = null
            val candidates = mutableListOf<MovieResult>()
            for (query in variants) {
                val matches = repo.matchMovies(availableServers, query, null)
                if (matches.isEmpty()) continue
                usedQuery = query
                candidates.addAll(matches)
                break
            }
            if (candidates.isEmpty()) {
                val tokens = raw.split(Regex("\\s+")).filter { it.length >= 3 }.take(3)
                for (token in tokens) {
                    val matches = repo.matchMovies(availableServers, token, null)
                    if (matches.isEmpty()) continue
                    usedQuery = token
                    candidates.addAll(matches)
                    break
                }
            }
            if (candidates.isNotEmpty()) {
                val q = usedQuery?.lowercase().orEmpty()
                val sorted = candidates
                    .distinctBy { it.link }
                    .sortedWith(
                        compareByDescending<MovieResult> { !it.posterLink.isNullOrBlank() }
                            .thenByDescending { it.title.lowercase().contains(q) }
                            .thenByDescending { it.score }
                            .thenByDescending { it.year ?: 0 }
                    )
                for (candidate in sorted) {
                    if (seenLinks.add(candidate.link)) {
                        val poster = choosePoster(candidate.posterLink, candidate.baseName)
                        picked.add(ResultItem(candidate.title, candidate.link, poster, candidate.baseName))
                        if (picked.size >= 20) break
                    }
                }
                globalCandidates.addAll(sorted)
            }
            if (picked.size >= 20) break
        }
        if (picked.size < 20 && globalCandidates.isNotEmpty()) {
            val fallback = globalCandidates
                .distinctBy { it.link }
                .sortedWith(
                    compareByDescending<MovieResult> { !it.posterLink.isNullOrBlank() }
                        .thenByDescending { it.score }
                        .thenByDescending { it.year ?: 0 }
                )
            for (candidate in fallback) {
                if (seenLinks.add(candidate.link)) {
                    val poster = choosePoster(candidate.posterLink, candidate.baseName)
                    picked.add(ResultItem(candidate.title, candidate.link, poster, candidate.baseName))
                    if (picked.size >= 20) break
                }
            }
        }
        if (picked.isEmpty()) {
            val fallback = buildFallbackRecommendations()
            if (fallback.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        aiResults = fallback,
                        aiStatus = "AI matches not found. Showing related movies (${fallback.size}/20)."
                    )
                }
                ensurePostersForResults(fallback)
                saveAiCache(fallback)
                return
            } else {
                val recent = buildHistorySuggestions()
                if (recent.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            aiResults = recent,
                            aiStatus = "AI matches not found. Showing your recent movies (${recent.size}/20)."
                        )
                    }
                    ensurePostersForResults(recent)
                    saveAiCache(recent)
                    return
                }
            }
        }

        _uiState.update {
            it.copy(
                aiResults = picked,
                aiStatus = if (picked.isEmpty()) {
                    "No matches found in database."
                } else {
                    "AI recommendations ready (${picked.size}/20)."
                },
                aiLastUpdated = System.currentTimeMillis(),
                aiStale = false
            )
        }
        ensurePostersForResults(picked)
        saveAiCache(picked)
    }

    private fun buildFallbackRecommendations(): List<ResultItem> {
        if (availableServers.isEmpty()) return emptyList()
        val historyLinks = historyIndex.keys.toSet()
        val candidates = mutableListOf<MovieResult>()
        val historyEntries = historyIndex.values
            .sortedByDescending { it.lastPlayedTs }
            .take(10)

        for (entry in historyEntries) {
            val base = entry.baseName ?: guessBaseName(entry.name)
            val variants = buildQueryVariants(base)
            var added = false
            for (query in variants) {
                val matches = repo.matchMovies(availableServers, query, null)
                if (matches.isNotEmpty()) {
                    candidates.addAll(matches)
                    added = true
                    break
                }
            }
            if (!added) {
                val tokens = base.split(Regex("\\s+")).filter { it.length >= 3 }.take(2)
                for (token in tokens) {
                    val matches = repo.matchMovies(availableServers, token, null)
                    if (matches.isNotEmpty()) {
                        candidates.addAll(matches)
                        break
                    }
                }
            }
        }
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates
            .distinctBy { it.link }
            .sortedWith(
                compareByDescending<MovieResult> { !it.posterLink.isNullOrBlank() }
                    .thenByDescending { it.score }
                    .thenByDescending { it.year ?: 0 }
            )
        val filtered = sorted.filter { it.link !in historyLinks }
        val finalList = if (filtered.isNotEmpty()) filtered else sorted
        return finalList.take(20).map {
            val poster = choosePoster(it.posterLink, it.baseName)
            ResultItem(it.title, it.link, poster, it.baseName)
        }
    }

    private fun buildHistorySuggestions(): List<ResultItem> {
        val entries = historyIndex.values
            .sortedByDescending { it.lastPlayedTs }
            .take(20)
        if (entries.isEmpty()) return emptyList()
        return entries.map { entry ->
            val base = entry.baseName ?: guessBaseName(entry.name)
            ResultItem(
                title = entry.name,
                link = entry.link,
                posterLink = choosePoster(entry.posterLink, base),
                baseName = base
            )
        }
    }

    private fun saveAiCache(items: List<ResultItem>) {
        val cacheItems = items.map {
            AiCacheItem(
                title = it.title,
                link = it.link,
                posterLink = it.posterLink,
                baseName = it.baseName
            )
        }
        val cache = AiCache(System.currentTimeMillis(), cacheItems)
        repo.saveAiCache(cache)
        _uiState.update { it.copy(aiLastUpdated = cache.timestamp, aiStale = false) }
    }

    private fun isAiStale(timestamp: Long): Boolean {
        return System.currentTimeMillis() - timestamp >= aiTtlMs
    }

    private fun ensurePostersForResults(items: List<ResultItem>) {
        if (omdbKey.isBlank()) return
        items.forEach { item ->
            if (shouldReplacePoster(item.posterLink, item.baseName)) {
                fetchPosterForTitle(item.baseName, item.title)
            }
        }
    }

    private fun ensurePostersForHistory(items: List<HistoryItem>) {
        if (omdbKey.isBlank()) return
        items.forEach { item ->
            if (shouldReplacePoster(item.posterLink, item.baseName)) {
                fetchPosterForTitle(item.baseName, item.title)
            }
        }
    }

    private fun fetchPosterForTitle(title: String, fallbackTitle: String? = null) {
        if (omdbKey.isBlank()) return
        val queryTitle = title.ifBlank { fallbackTitle.orEmpty() }
        val key = normalizeTitle(queryTitle)
        if (key.isBlank()) return
        if (posterCache.containsKey(key)) {
            val cached = posterCache[key]
            if (!cached.isNullOrBlank()) {
                applyPosterToState(key, cached)
                return
            }
            // Cached as "no poster" before; still allow local DB check (cheap).
            viewModelScope.launch {
                val dbPoster = repo.findPosterByBaseName(title)
                if (!dbPoster.isNullOrBlank()) {
                    posterCache[key] = dbPoster
                    applyPosterToState(key, dbPoster)
                    schedulePosterCacheSave()
                }
            }
            return
        }
        if (!posterInFlight.add(key)) return
        viewModelScope.launch {
            var poster = repo.findPosterByBaseName(title)
            if (poster.isNullOrBlank()) {
                poster = repo.fetchOmdbPoster(queryTitle, omdbKey)
                if (poster.isNullOrBlank() && !fallbackTitle.isNullOrBlank() && fallbackTitle != queryTitle) {
                    poster = repo.fetchOmdbPoster(fallbackTitle, omdbKey)
                }
            }
            posterInFlight.remove(key)
            posterCache[key] = poster ?: ""
            if (!poster.isNullOrBlank()) {
                applyPosterToState(key, poster)
                viewModelScope.launch(Dispatchers.IO) {
                    repo.savePosterCache(posterCache)
                }
            }
            schedulePosterCacheSave()
        }
    }

    private fun applyPosterToState(key: String, poster: String) {
        _uiState.update { state ->
            val results = state.results.map { item ->
                val itemKey = normalizeTitle(item.baseName.ifBlank { item.title })
                if (itemKey == key && shouldReplacePoster(item.posterLink, item.baseName)) {
                    item.copy(posterLink = poster)
                } else item
            }
            val aiResults = state.aiResults.map { item ->
                val itemKey = normalizeTitle(item.baseName.ifBlank { item.title })
                if (itemKey == key && shouldReplacePoster(item.posterLink, item.baseName)) {
                    item.copy(posterLink = poster)
                } else item
            }
            val history = state.history.map { item ->
                val itemKey = normalizeTitle(item.baseName.ifBlank { item.title })
                if (itemKey == key && shouldReplacePoster(item.posterLink, item.baseName)) {
                    item.copy(posterLink = poster)
                } else item
            }
            state.copy(results = results, aiResults = aiResults, history = history)
        }

        var updated = false
        historyIndex.entries.forEach { entry ->
            val normalized = normalizeTitle(entry.value.baseName ?: entry.value.name)
            if (normalized == key && shouldReplacePoster(entry.value.posterLink, entry.value.baseName ?: entry.value.name)) {
                historyIndex[entry.key] = entry.value.copy(posterLink = poster)
                updated = true
            }
        }
        if (updated) {
            scheduleHistorySave()
            viewModelScope.launch(Dispatchers.IO) {
                val history = historyIndex.values.sortedByDescending { it.lastPlayedTs }.take(100)
                repo.saveHistory(history)
            }
        }
    }

    private fun scheduleHistorySave() {
        val history = historyIndex.values.sortedByDescending { it.lastPlayedTs }.take(100)
        historySaveJob?.cancel()
        historySaveJob = viewModelScope.launch {
            delay(1500)
            repo.saveHistory(history)
        }
    }

    private fun schedulePosterCacheSave() {
        posterCacheSaveJob?.cancel()
        posterCacheSaveJob = viewModelScope.launch {
            delay(1200)
            repo.savePosterCache(posterCache)
        }
    }

    private fun normalizeTitle(title: String): String {
        return title.lowercase().replace(Regex("\\s+"), " ").trim()
    }

    private fun choosePoster(raw: String?, baseName: String): String? {
        val cleaned = raw?.trim().takeIf { !it.isNullOrBlank() }
        val cached = posterCache[normalizeTitle(baseName)]?.takeIf { it.isNotBlank() }
        if (cleaned == null) return cached
        if (isExternalPoster(cleaned)) return cleaned
        if (isPosterServerAvailable(cleaned)) return cleaned
        return cached ?: cleaned
    }

    private fun resolveHistoryPoster(entry: HistoryEntry): String? {
        val local = entry.localPosterPath?.let { normalizeLocalPath(it) }
        if (!local.isNullOrBlank()) return local
        val base = entry.baseName ?: guessBaseName(entry.name)
        return choosePoster(entry.posterLink, base)
    }

    private fun normalizeLocalPath(path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isBlank()) return null
        val filePath = if (trimmed.startsWith("file://")) trimmed.removePrefix("file://") else trimmed
        val file = java.io.File(filePath)
        return if (file.exists()) file.absolutePath else null
    }

    private fun isExternalPoster(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        return lower.contains("m.media-amazon.com") ||
            lower.contains("img.omdbapi.com") ||
            lower.contains("image.tmdb.org")
    }

    private fun isPosterServerAvailable(url: String): Boolean {
        return availableServers.any { server -> url.contains(server) }
    }

    private fun shouldReplacePoster(current: String?, baseName: String): Boolean {
        if (current.isNullOrBlank()) return true
        val cleaned = current.trim()
        if (isExternalPoster(cleaned)) return false
        return !isPosterServerAvailable(cleaned) && posterCache[normalizeTitle(baseName)].isNullOrBlank()
    }

    private fun cachePosterForHistory(baseName: String, posterLink: String?, fallbackTitle: String) {
        if (baseName.isBlank()) return
        val key = normalizeTitle(baseName)
        val existingLocal = repo.getLocalPosterPath(key)
        if (!existingLocal.isNullOrBlank()) {
            posterCache[key] = existingLocal
            updateHistoryLocalPoster(key, existingLocal)
            return
        }
        val chosen = choosePoster(posterLink, baseName)
        viewModelScope.launch(Dispatchers.IO) {
            var source = chosen
            if (source.isNullOrBlank()) {
                source = repo.findPosterByBaseName(baseName)
                if (source.isNullOrBlank() && omdbKey.isNotBlank()) {
                    source = repo.fetchOmdbPoster(fallbackTitle, omdbKey)
                }
            }
            if (source.isNullOrBlank()) return@launch
            val local = repo.downloadPosterToFile(source, key)
            if (!local.isNullOrBlank()) {
                posterCache[key] = local
                applyPosterToState(key, local)
                updateHistoryLocalPoster(key, local)
                repo.savePosterCache(posterCache)
            }
        }
    }

    private fun updateHistoryLocalPoster(key: String, localPath: String) {
        var updated = false
        historyIndex.entries.forEach { entry ->
            val normalized = normalizeTitle(entry.value.baseName ?: entry.value.name)
            if (normalized == key && entry.value.localPosterPath != localPath) {
                historyIndex[entry.key] = entry.value.copy(localPosterPath = localPath)
                updated = true
            }
        }
        if (updated) {
            viewModelScope.launch(Dispatchers.IO) {
                val history = historyIndex.values.sortedByDescending { it.lastPlayedTs }.take(100)
                repo.saveHistory(history)
            }
            _uiState.update { it.copy(history = renderHistory(historyIndex.values.sortedByDescending { h -> h.lastPlayedTs }.take(100))) }
        }
    }

    private fun guessBaseName(title: String): String {
        val noYear = title.replace(Regex("\\(\\d{4}\\)"), " ")
        val noBracket = noYear.replace(Regex("\\[[^\\]]*]"), " ")
        val noQuality = noBracket.replace(
            Regex("(?i)\\b(480p|720p|1080p|2160p|4k|fhd|uhd|hd|sd|bluray|brrip|hdrip|web[- ]?dl|webrip|dual audio)\\b"),
            " "
        )
        val noPunct = noQuality.replace(Regex("[^a-zA-Z0-9 ]"), " ")
        val compact = noPunct.replace(Regex("\\s+"), " ").trim()
        return compact.ifBlank { title }
    }

    private fun buildQueryVariants(raw: String): List<String> {
        val base = raw.trim()
        if (base.isBlank()) return emptyList()
        val noYear = base.replace(Regex("\\(\\d{4}\\)"), " ").trim()
        val noQuality = noYear.replace(
            Regex("(?i)\\b(480p|720p|1080p|2160p|4k|fhd|uhd|hd|sd|bluray|brrip|hdrip|web[- ]?dl|webrip)\\b"),
            " "
        ).trim()
        val noPunct = noQuality.replace(Regex("[^a-zA-Z0-9 ]"), " ").trim()
        val compact = noPunct.replace(Regex("\\s+"), " ").trim()
        return listOf(base, noYear, noQuality, compact)
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
    }
}
