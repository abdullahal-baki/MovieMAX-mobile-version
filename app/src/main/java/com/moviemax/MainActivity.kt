@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material.ExperimentalMaterialApi::class
)

package com.moviemax

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.layout.ContentScale
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.util.Calendar

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        setContent {
            MovieMaxTheme {
                val viewModel: MovieMaxViewModel = viewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val drawerScope = rememberCoroutineScope()

                val playerLink = state.playerLink
                if (playerLink != null) {
                    val playerTitle = state.playerTitle ?: "MovieMAX Player"
                    val playerStartMs = state.playerStartMs
                    val playerAudioLabel = state.playerAudioLabel
                    val playerAudioLanguage = state.playerAudioLanguage
                    val playerAudioGroupIndex = state.playerAudioGroupIndex
                    val playerAudioTrackIndex = state.playerAudioTrackIndex
                    val playerSubtitleLabel = state.playerSubtitleLabel
                    val playerSubtitleLanguage = state.playerSubtitleLanguage
                    val playerSubtitleGroupIndex = state.playerSubtitleGroupIndex
                    val playerSubtitleTrackIndex = state.playerSubtitleTrackIndex
                    val playerSubtitleEnabled = state.playerSubtitleEnabled
                    val playerSubtitleUri = state.playerSubtitleUri
                    InternalPlayerScreen(
                        url = playerLink,
                        title = playerTitle,
                        startPositionMs = playerStartMs,
                        savedAudioLabel = playerAudioLabel,
                        savedAudioLanguage = playerAudioLanguage,
                        savedAudioGroupIndex = playerAudioGroupIndex,
                        savedAudioTrackIndex = playerAudioTrackIndex,
                        savedSubtitleLabel = playerSubtitleLabel,
                        savedSubtitleLanguage = playerSubtitleLanguage,
                        savedSubtitleGroupIndex = playerSubtitleGroupIndex,
                        savedSubtitleTrackIndex = playerSubtitleTrackIndex,
                        savedSubtitleEnabled = playerSubtitleEnabled,
                        savedSubtitleUri = playerSubtitleUri,
                        onClose = { viewModel.closePlayer() },
                        onOpenExternal = { link -> viewModel.openExternal(link, playerTitle) },
                        onProgress = { posMs, durMs ->
                            viewModel.updateHistoryProgress(playerLink, playerTitle, posMs, durMs)
                        },
                        onTrackSelectionChanged = { audioLabel, audioLang, audioGroup, audioTrack, subLabel, subLang, subGroup, subTrack, subEnabled, subUri ->
                            viewModel.updateTrackSelection(
                                playerLink,
                                playerTitle,
                                audioLabel,
                                audioLang,
                                audioGroup,
                                audioTrack,
                                subLabel,
                                subLang,
                                subGroup,
                                subTrack,
                                subEnabled,
                                subUri
                            )
                        }
                    )
                } else {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            AppDrawer(
                                appVersion = state.appVersion,
                                dbVersion = state.dbVersion
                            )
                        }
                    ) {
                        MovieMaxScreen(
                            state = state,
                            onQueryChange = viewModel::onQueryChange,
                            onYearChange = viewModel::onYearChange,
                            onSearch = viewModel::startSearch,
                            onTabSelected = viewModel::onTabSelected,
                            onResultClick = { viewModel.openResult(it.link, it.title, it.posterLink, it.baseName) },
                            onHistoryClick = { viewModel.openHistory(it.link, it.title, it.posterLink, it.baseName) },
                            onMenuClick = { drawerScope.launch { drawerState.open() } },
                            onClearHistory = viewModel::clearHistory,
                            onRemoveHistoryItem = viewModel::removeHistoryItem,
                            onRefreshAi = viewModel::refreshAiNow,
                            onRefreshHistory = viewModel::refreshHistoryPostersNow,
                            onDiscoverViewed = viewModel::onDiscoverViewed
                        )
                    }
                }

                if (state.updateAvailable && state.updateVersion != null) {
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissUpdate() },
                        title = { Text("New version available") },
                        text = {
                            Text(
                                text = "Version ${state.updateVersion} is available. Visit releases page to update.",
                                color = Color(0xFFE7ECF2)
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        data = android.net.Uri.parse(APP_RELEASES_URL)
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    startActivity(intent)
                                    viewModel.dismissUpdate()
                                }
                            ) {
                                Text("Open Release Page")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.dismissUpdate() }) {
                                Text("Later")
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MovieMaxTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Color(0xFFF2C14E),
        onPrimary = Color(0xFF0B0F14),
        secondary = Color(0xFF74C69D),
        onSecondary = Color(0xFF0B0F14),
        background = Color(0xFF0B0F14),
        onBackground = Color(0xFFE7ECF2),
        surface = Color(0xFF121824),
        onSurface = Color(0xFFE7ECF2),
        error = Color(0xFFFF6B6B)
    )
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieMaxScreen(
    state: UiState,
    onQueryChange: (String) -> Unit,
    onYearChange: (String) -> Unit,
    onSearch: () -> Unit,
    onTabSelected: (MainTab) -> Unit,
    onResultClick: (ResultItem) -> Unit,
    onHistoryClick: (HistoryItem) -> Unit,
    onMenuClick: () -> Unit,
    onClearHistory: () -> Unit,
    onRemoveHistoryItem: (String) -> Unit,
    onRefreshAi: () -> Unit,
    onRefreshHistory: () -> Unit,
    onDiscoverViewed: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val years = remember(currentYear) {
        listOf("No Year") + (currentYear downTo 2000).map { it.toString() }
    }

    var yearExpanded by remember { mutableStateOf(false) }
    var showContent by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { showContent = true }

    val backgroundBrush = Brush.verticalGradient(
        listOf(Color(0xFF0B0F14), Color(0xFF101826), Color(0xFF0B0F14))
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HeaderCard(appVersion = state.appVersion, onMenuClick = onMenuClick)

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF121A24)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = onQueryChange,
                            label = { Text("Search by movie name") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    onSearch()
                                }
                            )
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        ExposedDropdownMenuBox(
                            expanded = yearExpanded,
                            onExpandedChange = { yearExpanded = !yearExpanded }
                        ) {
                            OutlinedTextField(
                                value = state.selectedYear,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Year") },
                                modifier = Modifier
                                    .menuAnchor()
                                    .width(130.dp),
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = yearExpanded)
                                }
                            )
                            DropdownMenu(
                                expanded = yearExpanded,
                                onDismissRequest = { yearExpanded = false },
                                modifier = Modifier
                                    .heightIn(max = 320.dp)
                                    .widthIn(min = 140.dp)
                            ) {
                                years.forEach { year ->
                                    val isSelected = state.selectedYear == year
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = year,
                                                fontSize = 14.sp,
                                                color = if (isSelected) Color(0xFFF2C14E) else Color(0xFFE7ECF2)
                                            )
                                        },
                                        onClick = {
                                            onYearChange(year)
                                            yearExpanded = false
                                        },
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                        modifier = Modifier.height(36.dp)
                                    )
                                }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                onSearch()
                            },
                            enabled = state.canSearch
                        ) {
                            Text("Search")
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        val statusColor = if (state.canSearch) Color(0xFF74C69D) else Color(0xFFFF6B6B)
                        Text(
                            text = state.serverStatus,
                            color = statusColor,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.End
                        )
                    }
                }
            }

            TabRow(selectedTabIndex = if (state.selectedTab == MainTab.Results) 0 else 1) {
                Tab(
                    selected = state.selectedTab == MainTab.Results,
                    onClick = { onTabSelected(MainTab.Results) },
                    text = { Text("Discover") }
                )
                Tab(
                    selected = state.selectedTab == MainTab.History,
                    onClick = { onTabSelected(MainTab.History) },
                    text = { Text("History") }
                )
            }

            var swipeTotal by remember { mutableStateOf(0f) }
            AnimatedVisibility(
                visible = showContent,
                enter = fadeIn(tween(450)) + slideInVertically(
                    initialOffsetY = { it / 6 },
                    animationSpec = tween(450)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(state.selectedTab) {
                        detectHorizontalDragGestures(
                            onDragStart = { swipeTotal = 0f },
                            onHorizontalDrag = { _, dragAmount ->
                                swipeTotal += dragAmount
                            },
                            onDragEnd = {
                                if (swipeTotal > 80f) {
                                    onTabSelected(MainTab.Results)
                                } else if (swipeTotal < -80f) {
                                    onTabSelected(MainTab.History)
                                }
                                swipeTotal = 0f
                            }
                        )
                    }
            ) {
                when (state.selectedTab) {
                    MainTab.Results -> {
                        LaunchedEffect(state.selectedTab, state.aiResults.size, state.history.size) {
                            onDiscoverViewed()
                        }
                        val showAi = state.results.isEmpty() && state.aiResults.isNotEmpty() && state.history.isNotEmpty()
                        val showAiHint = state.results.isEmpty() && state.aiResults.isEmpty() && state.history.isNotEmpty()
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.isSearching) {
                                Text(
                                    text = "Searching...",
                                    color = Color(0xFF7EE8C3),
                                    fontSize = 12.sp
                                )
                            } else if (state.results.isNotEmpty()) {
                                Text(
                                    text = "Search results",
                                    color = Color(0xFF7EE8C3),
                                    fontSize = 12.sp
                                )
                            } else if (showAi) {
                                Text(
                                    text = "AI recommended movies",
                                    color = Color(0xFF7EE8C3),
                                    fontSize = 12.sp
                                )
                            }
                            if (showAiHint) {
                                Text(
                                    text = "Swipe down to get AI recommendation movies.",
                                    color = Color(0xFF9AA6B2),
                                    fontSize = 12.sp
                                )
                            }
                            if (state.discoverStatus.isNotBlank() && !state.isSearching && state.results.isEmpty()) {
                                Text(
                                    text = state.discoverStatus,
                                    color = Color(0xFF9AA6B2),
                                    fontSize = 12.sp
                                )
                            }
                            if (state.results.isEmpty() && state.history.isEmpty()) {
                                Text(
                                    text = "Watch some movies to get AI recommendations.",
                                    color = Color(0xFF9AA6B2),
                                    fontSize = 12.sp
                                )
                            }
                            if (state.aiStale && state.aiResults.isNotEmpty()) {
                                Text(
                                    text = "AI list is old. Swipe down to refresh.",
                                    color = Color(0xFF9AA6B2),
                                    fontSize = 12.sp
                                )
                            }
                            if (state.aiRefreshing) {
                                Text(
                                    text = "AI recommendations loading...",
                                    color = Color(0xFF9AA6B2),
                                    fontSize = 12.sp
                                )
                            }
                            val pullState = rememberPullRefreshState(
                                refreshing = state.aiRefreshing,
                                onRefresh = onRefreshAi
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pullRefresh(pullState)
                            ) {
                                ResultList(
                                    items = if (showAi) state.aiResults else state.results,
                                    onItemClick = onResultClick
                                )
                                PullRefreshIndicator(
                                    refreshing = state.aiRefreshing,
                                    state = pullState,
                                    modifier = Modifier.align(Alignment.TopCenter),
                                    contentColor = Color(0xFFF2C14E)
                                )
                            }
                        }
                    }
                    MainTab.History -> {
                        val pullState = rememberPullRefreshState(
                            refreshing = state.historyRefreshing,
                            onRefresh = onRefreshHistory
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pullRefresh(pullState)
                        ) {
                            HistoryList(
                                items = state.history,
                                availableServers = state.availableServers,
                                onItemClick = onHistoryClick,
                                onItemRemove = onRemoveHistoryItem
                            )
                            if (state.history.isNotEmpty()) {
                                ClearHistoryButton(
                                    onClick = { showClearHistoryDialog = true },
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp)
                                )
                            }
                            PullRefreshIndicator(
                                refreshing = state.historyRefreshing,
                                state = pullState,
                                modifier = Modifier.align(Alignment.TopCenter),
                                contentColor = Color(0xFFF2C14E)
                            )
                        }
                    }
                }
            }

            Text(
                text = if (state.isSearching) "" else state.actionStatus,
                color = Color(0xFF7EE8C3),
                fontSize = 11.sp,
                modifier = Modifier.height(18.dp)
            )
        }

        if (showClearHistoryDialog) {
            AlertDialog(
                onDismissRequest = { showClearHistoryDialog = false },
                title = { Text("Clear all history?") },
                text = { Text("This will remove all history items.", color = Color(0xFFE7ECF2)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onClearHistory()
                            showClearHistoryDialog = false
                        }
                    ) {
                        Text("Clear", color = Color(0xFFFF5E5E))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearHistoryDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun ClearHistoryButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(34.dp)
            .background(Color(0x1AFF4D4D), shape = RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = "Clear history",
            tint = Color(0xFFFF4D4D),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun HeaderCard(appVersion: String, onMenuClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1016)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Open menu",
                    tint = Color(0xFFF2C14E)
                )
            }
            Image(
                painter = painterResource(id = R.drawable.logo_moviemax),
                contentDescription = "MovieMAX logo",
                modifier = Modifier
                    .width(34.dp)
                    .height(34.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MovieMAX",
                    color = Color(0xFFF2C14E),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = "BDIX cinema hub",
                    color = Color(0xFFB5BEC9),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun AppDrawer(appVersion: String, dbVersion: String?) {
    val context = LocalContext.current
    ModalDrawerSheet(
        drawerContainerColor = Color(0xFF0C1016),
        drawerContentColor = Color(0xFFE7ECF2)
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .fillMaxHeight()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            DrawerSectionTitle("About")
            Text(text = "MovieMAX - BDIX cinema hub")
            Text(text = "Developed by: Bakisofts Lab")
            Text(text = "Developer: Abdullah Al Baki")
            Text(text = "App Version: $appVersion")
            Text(text = "DB Version: ${dbVersion ?: "Unknown"}")

            DrawerSectionTitle("Help")
            Text(
                text = "• Connect servers first. If no server is connected, search will be disabled.\n" +
                    "• Search by movie name (optional year filter) to get results.\n" +
                    "• Discover tab shows AI recommendations. Swipe down to refresh.\n" +
                    "• If history is empty, AI suggestions will not appear.\n" +
                    "• Database updates automatically when a new DB is available.\n" +
                    "• Use the internal player for resume, tracks and subtitle controls.",
                fontSize = 12.sp,
                color = Color(0xFFB5BEC9)
            )

            DrawerSectionTitle("External Links")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LinkButton(
                    label = "Telegram",
                    url = "https://t.me/BakiSoftLabs",
                    context = context,
                    modifier = Modifier.weight(1f)
                )
                LinkButton(
                    label = "GitHub",
                    url = "https://github.com/abdullahal-baki",
                    context = context,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "MovieMAX v$appVersion",
                fontSize = 11.sp,
                color = Color(0xFF6D7785)
            )
        }
    }
}

@Composable
fun DrawerSectionTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = Color(0xFFF2C14E),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = Color(0xFFF2C14E),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun LinkButton(
    label: String,
    url: String,
    context: android.content.Context,
    modifier: Modifier = Modifier
) {
    Button(
        modifier = modifier,
        onClick = {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse(url)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF1C2432),
            contentColor = Color(0xFF7EE8C3)
        ),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column {
            Text(text = label, fontWeight = FontWeight.Bold)
            Text(text = url, fontSize = 12.sp)
        }
    }
}

@Composable
fun ResultList(items: List<ResultItem>, onItemClick: (ResultItem) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 120.dp)
    ) {
        if (items.isEmpty()) {
            item { Spacer(modifier = Modifier.height(400.dp)) }
        } else {
            items(items) { item ->
                ResultRow(item = item, onClick = { onItemClick(item) })
            }
        }
    }
}

@Composable
fun HistoryList(
    items: List<HistoryItem>,
    availableServers: Set<String>,
    onItemClick: (HistoryItem) -> Unit,
    onItemRemove: (String) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<HistoryItem?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { item ->
            val isAvailable = availableServers.any { server ->
                item.link.contains(server, ignoreCase = true)
            }
            HistoryRow(
                item = item,
                isAvailable = isAvailable,
                onClick = { onItemClick(item) },
                onLongPress = { pendingDelete = item }
            )
        }
    }
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete history item?") },
            text = { Text(pendingDelete?.title ?: "", color = Color(0xFFE7ECF2)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val link = pendingDelete?.link
                        if (!link.isNullOrBlank()) {
                            onItemRemove(link)
                        }
                        pendingDelete = null
                    }
                ) {
                    Text("Delete", color = Color(0xFFFF5E5E))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ResultRow(item: ResultItem, onClick: () -> Unit) {
    MoviePosterCard(
        title = item.title,
        info = null,
        isAvailable = null,
        posterUrl = item.posterLink,
        onClick = onClick,
        onLongPress = null
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun HistoryRow(
    item: HistoryItem,
    isAvailable: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    MoviePosterCard(
        title = item.title,
        info = item.info,
        isAvailable = isAvailable,
        posterUrl = item.posterLink,
        onClick = onClick,
        onLongPress = onLongPress
    )
}

@Composable
fun PosterThumb(posterUrl: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = ImageRequest.Builder(context)
        .data(posterUrl)
        .crossfade(true)
        .build()

    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1C2432))
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
            is AsyncImagePainter.State.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1C2432)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFFF2C14E),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1C2432)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color(0xFF8A97A6)
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun MoviePosterCard(
    title: String,
    info: String?,
    isAvailable: Boolean?,
    posterUrl: String?,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?
) {
    val modifier = if (onLongPress != null) {
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
    } else {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    }

    val qualityBadge = remember(title) { extractQualityBadge(title) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121A24)),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .height(150.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(posterUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                    is AsyncImagePainter.State.Loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF1C2432)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFFF2C14E),
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF1C2432)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color(0xFF8A97A6)
                            )
                        }
                    }
                }
            }

            if (qualityBadge != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF1B2A3B), Color(0xFF0F1622))
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = qualityBadge,
                        color = Color(0xFFF2C14E),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xCC0B0F14))
                        )
                    )
                    .padding(10.dp)
            ) {
                Column {
                    Text(
                        text = title,
                        color = Color(0xFFF2F4F7),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!info.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isAvailable != null) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(
                                            if (isAvailable) Color(0xFF45D483) else Color(0xFFFF5E5E),
                                            shape = RoundedCornerShape(50)
                                        )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = info,
                                color = Color(0xFFB6C2CF),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun extractQualityBadge(title: String): String? {
    val text = title.lowercase()
    return when {
        text.contains("480p") || text.contains("sd") -> "SD"
        text.contains("2160p") || text.contains("4k") -> "UHD"
        text.contains("1080p") || text.contains("fhd") -> "FHD"
        text.contains("720p") || text.contains("hd") -> "HD"
        else -> null
    }
}
