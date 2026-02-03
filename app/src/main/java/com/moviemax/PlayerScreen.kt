package com.moviemax

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class DragMode { None, Seek, Volume, Brightness }

@UnstableApi
@Composable
fun InternalPlayerScreen(
    url: String,
    title: String,
    onClose: () -> Unit,
    startPositionMs: Long,
    savedAudioLabel: String?,
    savedAudioLanguage: String?,
    savedAudioGroupIndex: Int?,
    savedAudioTrackIndex: Int?,
    savedSubtitleLabel: String?,
    savedSubtitleLanguage: String?,
    savedSubtitleGroupIndex: Int?,
    savedSubtitleTrackIndex: Int?,
    savedSubtitleEnabled: Boolean,
    savedSubtitleUri: String?,
    onOpenExternal: (String) -> Unit,
    onProgress: (Long, Long) -> Unit,
    onTrackSelectionChanged: (
        String?,
        String?,
        Int?,
        Int?,
        String?,
        String?,
        Int?,
        Int?,
        Boolean,
        String?
    ) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build()
    }

    var durationMs by remember { mutableStateOf(0L) }
    var positionMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableStateOf(0f) }
    var isSeeking by remember { mutableStateOf(false) }
    var overlayText by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var speed by remember { mutableStateOf(1f) }
    var speedMenuExpanded by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var autoSubtitleApplied by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var textTracks by remember { mutableStateOf<List<TextTrackOption>>(emptyList()) }
    var externalSubtitleUri by remember { mutableStateOf(savedSubtitleUri) }
    var externalSubtitleLabel by remember { mutableStateOf(savedSubtitleLabel) }
    var lastAudioInfo by remember { mutableStateOf<SelectedTrackInfo?>(null) }
    var lastSubtitleInfo by remember { mutableStateOf<SelectedTrackInfo?>(null) }
    var lastSubtitleEnabled by remember { mutableStateOf<Boolean?>(null) }
    var appliedSavedSelection by remember { mutableStateOf(false) }
    var lastTracks by remember { mutableStateOf<Tracks?>(null) }

    BackHandler { onClose() }

    val subtitlePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            val mimeType = guessSubtitleMimeType(context, uri)
            val displayName = queryDisplayName(context.contentResolver, uri) ?: "External Subtitle"
            val currentPos = exoPlayer.currentPosition
            val playNow = exoPlayer.playWhenReady
            val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(mimeType)
                .setLanguage("en")
                .setLabel(displayName)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            val newItem = MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setSubtitleConfigurations(listOf(subtitleConfig))
                .build()
            externalSubtitleUri = uri.toString()
            externalSubtitleLabel = displayName
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
            exoPlayer.setMediaItem(newItem, currentPos)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = playNow
            overlayText = "Subtitle loaded"
            onTrackSelectionChanged(
                lastAudioInfo?.label,
                lastAudioInfo?.language,
                lastAudioInfo?.groupIndex,
                lastAudioInfo?.trackIndex,
                displayName,
                "en",
                null,
                null,
                true,
                externalSubtitleUri
            )
        }
    }

    DisposableEffect(url) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = activity?.window?.let { window ->
            WindowInsetsControllerCompat(window, window.decorView).also {
                it.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        isBuffering = true

        val paramsBuilder = exoPlayer.trackSelectionParameters.buildUpon()
        if (!savedAudioLanguage.isNullOrBlank()) {
            paramsBuilder.setPreferredAudioLanguage(savedAudioLanguage)
        }
        if (!savedSubtitleLanguage.isNullOrBlank()) {
            paramsBuilder.setPreferredTextLanguage(savedSubtitleLanguage)
        }
        paramsBuilder.setSelectUndeterminedTextLanguage(true)
        paramsBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !savedSubtitleEnabled)
        exoPlayer.trackSelectionParameters = paramsBuilder.build()

        val mediaItem = if (!externalSubtitleUri.isNullOrBlank() && savedSubtitleEnabled) {
            val subUri = Uri.parse(externalSubtitleUri)
            val mime = guessSubtitleMimeType(context, subUri)
            val label = savedSubtitleLabel ?: "External Subtitle"
            val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(subUri)
                .setMimeType(mime)
                .setLanguage(savedSubtitleLanguage ?: "en")
                .setLabel(label)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setSubtitleConfigurations(listOf(subtitleConfig))
                .build()
        } else {
            MediaItem.fromUri(url)
        }
        exoPlayer.setMediaItem(mediaItem, startPositionMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
            }

            override fun onTracksChanged(tracks: Tracks) {
                lastTracks = tracks
                textTracks = buildTextTrackOptions(tracks)
                if (!appliedSavedSelection) {
                    applySavedTrackSelection(
                        exoPlayer,
                        tracks,
                        savedAudioLabel,
                        savedAudioLanguage,
                        savedAudioGroupIndex,
                        savedAudioTrackIndex,
                        savedSubtitleLabel,
                        savedSubtitleLanguage,
                        savedSubtitleGroupIndex,
                        savedSubtitleTrackIndex,
                        savedSubtitleEnabled
                    )
                    appliedSavedSelection = true
                }
                val audioInfo = getSelectedTrackInfo(tracks, C.TRACK_TYPE_AUDIO)
                var subtitleInfo = getSelectedTrackInfo(tracks, C.TRACK_TYPE_TEXT)
                var subtitleEnabled = subtitleInfo != null &&
                    !exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                if (subtitleInfo == null &&
                    savedSubtitleEnabled &&
                    !externalSubtitleUri.isNullOrBlank() &&
                    !externalSubtitleLabel.isNullOrBlank()
                ) {
                    applyExternalSubtitleSelection(exoPlayer, tracks, externalSubtitleLabel!!)
                    subtitleInfo = getSelectedTrackInfo(tracks, C.TRACK_TYPE_TEXT)
                    subtitleEnabled = subtitleInfo != null &&
                        !exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                }
                if (audioInfo != lastAudioInfo ||
                    subtitleInfo != lastSubtitleInfo ||
                    lastSubtitleEnabled != subtitleEnabled
                ) {
                    lastAudioInfo = audioInfo
                    lastSubtitleInfo = subtitleInfo
                    lastSubtitleEnabled = subtitleEnabled
                    val hasExternal = !externalSubtitleUri.isNullOrBlank()
                    val labelMatchesExternal =
                        !externalSubtitleLabel.isNullOrBlank() && subtitleInfo?.label == externalSubtitleLabel
                    val isExternalSelected = subtitleEnabled && hasExternal &&
                        (labelMatchesExternal || subtitleInfo == null || externalSubtitleLabel.isNullOrBlank())
                    onTrackSelectionChanged(
                        audioInfo?.label,
                        audioInfo?.language,
                        audioInfo?.groupIndex,
                    audioInfo?.trackIndex,
                    if (isExternalSelected) (subtitleInfo?.label ?: externalSubtitleLabel) else subtitleInfo?.label,
                    if (isExternalSelected) (subtitleInfo?.language ?: savedSubtitleLanguage ?: "en") else subtitleInfo?.language,
                    subtitleInfo?.groupIndex,
                    subtitleInfo?.trackIndex,
                    subtitleEnabled,
                    if (isExternalSelected) externalSubtitleUri else null
                )
                }
                val hasSavedSubtitleSelection = savedSubtitleEnabled &&
                    (!savedSubtitleLabel.isNullOrBlank() ||
                        !savedSubtitleLanguage.isNullOrBlank() ||
                        (savedSubtitleGroupIndex != null && savedSubtitleTrackIndex != null) ||
                        !savedSubtitleUri.isNullOrBlank())
                if (autoSubtitleApplied || hasSavedSubtitleSelection) return
                val textGroup = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT }
                if (textGroup != null && !textGroup.isSelected && textGroup.length > 0) {
                    val override = TrackSelectionOverride(
                        textGroup.mediaTrackGroup,
                        listOf(0)
                    )
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .addOverride(override)
                        .build()
                    autoSubtitleApplied = true
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            val finalPos = exoPlayer.currentPosition
            val finalDur = exoPlayer.duration
            if (finalDur > 0) {
                onProgress(finalPos, finalDur)
            }
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.window?.let { window ->
                WindowInsetsControllerCompat(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            if (!isSeeking) {
                positionMs = max(0L, exoPlayer.currentPosition)
                durationMs = max(0L, exoPlayer.duration)
            }
            delay(500)
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(2000)
            val dur = exoPlayer.duration
            if (dur > 0) {
                onProgress(exoPlayer.currentPosition, dur)
            }
        }
    }

    val scope = rememberCoroutineScope()
    var hideJob by remember { mutableStateOf<Job?>(null) }

    fun scheduleHideControls() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(3000)
            if (isPlaying) {
                controlsVisible = false
            }
        }
    }

    LaunchedEffect(speed) {
        exoPlayer.playbackParameters = PlaybackParameters(speed)
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            scheduleHideControls()
        } else {
            controlsVisible = true
        }
    }

    LaunchedEffect(overlayText) {
        if (overlayText == "Subtitle loaded") {
            delay(3000)
            if (overlayText == "Subtitle loaded") {
                overlayText = null
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F14))
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        if (size.width == 0 || size.height == 0) return@detectDragGestures
                        dragState.start(startOffset, size, exoPlayer, audioManager, activity)
                        controlsVisible = true
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val result = dragState.update(
                            dragAmount = dragAmount,
                            size = size,
                            player = exoPlayer,
                            audioManager = audioManager,
                            activity = activity
                        )
                        if (result.isNotBlank()) {
                            overlayText = result
                        }
                    },
                    onDragEnd = {
                        dragState.reset()
                        if (overlayText != null) {
                            scope.launch {
                                delay(800)
                                overlayText = null
                            }
                        }
                        controlsVisible = true
                        scheduleHideControls()
                    },
                    onDragCancel = {
                        dragState.reset()
                        overlayText = null
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        controlsVisible = !controlsVisible
                        if (controlsVisible) {
                            scheduleHideControls()
                        }
                    },
                    onDoubleTap = { offset ->
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val third = width / 3f
                        val pos = exoPlayer.currentPosition
                        val dur = exoPlayer.duration.takeIf { it > 0 } ?: 0L
                        when {
                            offset.x < third -> {
                                val newPos = (pos - 10_000L).coerceAtLeast(0L)
                                exoPlayer.seekTo(newPos)
                                overlayText = "⏪ 10s"
                            }
                            offset.x > third * 2f -> {
                                val newPos = if (dur > 0) {
                                    (pos + 10_000L).coerceAtMost(dur)
                                } else {
                                    pos + 10_000L
                                }
                                exoPlayer.seekTo(newPos)
                                overlayText = "⏩ 10s"
                            }
                            else -> {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                overlayText = if (exoPlayer.isPlaying) "▶" else "⏸"
                            }
                        }
                        controlsVisible = true
                        scheduleHideControls()
                        scope.launch {
                            delay(800)
                            overlayText = null
                        }
                    }
                )
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    player = exoPlayer
                }
            },
            update = { it.player = exoPlayer }
        )

        val showControls = controlsVisible || !isPlaying || isBuffering
        if (showControls) {
            PlayerTopBar(
                title = title,
                onClose = onClose,
                onOpenExternal = { onOpenExternal(url) }
            )
            PlayerBottomControls(
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                isSeeking = isSeeking,
                sliderPosition = sliderPosition,
                speed = speed,
                speedMenuExpanded = speedMenuExpanded,
                modifier = Modifier.align(Alignment.BottomCenter),
                onTracks = {
                    TrackSelectionDialogBuilder(
                        context,
                        "Tracks",
                        exoPlayer,
                        C.TRACK_TYPE_AUDIO
                    ).build().show()
                },
                onPlayPause = {
                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                },
                onSeekChange = {
                    isSeeking = true
                    sliderPosition = it
                },
                onSeekFinished = {
                    val newPos = sliderPosition.toLong()
                    exoPlayer.seekTo(newPos)
                    isSeeking = false
                },
                onSpeedMenuToggle = { speedMenuExpanded = !speedMenuExpanded },
                onSpeedSelected = { selected ->
                    speed = selected
                    speedMenuExpanded = false
                },
                onSpeedDismiss = { speedMenuExpanded = false },
                onGuide = { showGuide = true },
                onSubtitleClick = { showSubtitleDialog = true }
            )
        }

        if (overlayText != null) {
            OverlayHint(text = overlayText ?: "")
        }
        if (isBuffering) {
            BufferingOverlay()
        }
        if (showGuide) {
            GuideDialog(onDismiss = { showGuide = false })
        }
        if (showSubtitleDialog) {
            val externalLabel = externalSubtitleLabel ?: "Custom subtitle"
            val externalAlreadyInTracks = textTracks.any { it.label == externalLabel }
            val externalSelected =
                !externalSubtitleUri.isNullOrBlank() &&
                    (lastSubtitleInfo?.label == null || lastSubtitleInfo?.label == externalSubtitleLabel) &&
                    ((lastSubtitleEnabled == true) || (savedSubtitleEnabled && lastSubtitleInfo == null))
            SubtitleDialog(
                tracks = textTracks,
                externalLabel = externalLabel,
                externalVisible = !externalSubtitleUri.isNullOrBlank() && !externalAlreadyInTracks,
                externalSelected = externalSelected,
                onSelect = { option ->
                    applySubtitleSelection(exoPlayer, option)
                    val label = option?.label
                    val lang = option?.group?.getTrackFormat(option.trackIndex)?.language
                    if (option == null || label != externalSubtitleLabel) {
                        externalSubtitleUri = null
                        externalSubtitleLabel = null
                    }
                    onTrackSelectionChanged(
                        lastAudioInfo?.label,
                        lastAudioInfo?.language,
                        lastAudioInfo?.groupIndex,
                        lastAudioInfo?.trackIndex,
                        label,
                        lang,
                        option?.groupIndex,
                        option?.trackIndex,
                        option != null,
                        externalSubtitleUri
                    )
                },
                onSelectExternal = {
                    val label = externalSubtitleLabel ?: "Custom subtitle"
                    val tracksSnapshot = lastTracks
                    if (tracksSnapshot != null) {
                        applyExternalSubtitleSelection(exoPlayer, tracksSnapshot, label)
                    } else {
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .build()
                    }
                    onTrackSelectionChanged(
                        lastAudioInfo?.label,
                        lastAudioInfo?.language,
                        lastAudioInfo?.groupIndex,
                        lastAudioInfo?.trackIndex,
                        label,
                        savedSubtitleLanguage ?: "en",
                        null,
                        null,
                        true,
                        externalSubtitleUri
                    )
                },
                onLoadExternal = {
                    subtitlePicker.launch(
                        arrayOf(
                            "text/*",
                            "application/x-subrip",
                            "text/vtt",
                            "application/octet-stream"
                        )
                    )
                },
                onDismiss = { showSubtitleDialog = false }
            )
        }
    }
}

private val dragState = DragState()

private class DragState {
    private var mode: DragMode = DragMode.None
    private var startOffset: Offset = Offset.Zero
    private var startPositionMs: Long = 0L
    private var startVolume: Int = 0
    private var startBrightness: Float = 0.5f
    private var ignore: Boolean = false
    private var totalDx: Float = 0f
    private var totalDy: Float = 0f

    fun start(
        offset: Offset,
        size: IntSize,
        player: Player,
        audioManager: AudioManager,
        activity: Activity?
    ) {
        ignore = offset.y > size.height * 0.75f
        if (ignore) {
            mode = DragMode.None
            return
        }
        mode = DragMode.None
        startOffset = offset
        totalDx = 0f
        totalDy = 0f
        startPositionMs = player.currentPosition
        startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val current = activity?.window?.attributes?.screenBrightness ?: -1f
        startBrightness = if (current in 0f..1f) current else 0.5f
    }

    fun update(
        dragAmount: Offset,
        size: IntSize,
        player: Player,
        audioManager: AudioManager,
        activity: Activity?
    ): String {
        if (ignore) return ""
        if (size.width == 0 || size.height == 0) return ""
        totalDx += dragAmount.x
        totalDy += dragAmount.y
        val dx = totalDx
        val dy = totalDy

        if (mode == DragMode.None) {
            if (abs(dx) < 8f && abs(dy) < 8f) return ""
            mode = if (abs(dx) > abs(dy)) {
                DragMode.Seek
            } else {
                if (startOffset.x < size.width / 2f) DragMode.Brightness else DragMode.Volume
            }
        }

        return when (mode) {
            DragMode.Seek -> {
                val duration = max(1L, player.duration)
                val delta = (dx / size.width) * duration
                val newPos = (startPositionMs + delta).toLong().coerceIn(0L, duration)
                player.seekTo(newPos)
                "Seek: ${formatTime(newPos)}"
            }
            DragMode.Volume -> {
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val delta = (-dy / size.height) * maxVol
                val newVol = (startVolume + delta).roundToInt().coerceIn(0, maxVol)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                val percent = (newVol.toFloat() / maxVol * 100f).roundToInt()
                "Volume: $percent%"
            }
            DragMode.Brightness -> {
                val delta = (-dy / size.height)
                val newBrightness = (startBrightness + delta).coerceIn(0.05f, 1f)
                activity?.window?.attributes = activity?.window?.attributes?.apply {
                    screenBrightness = newBrightness
                }
                val percent = (newBrightness * 100f).roundToInt()
                "Brightness: $percent%"
            }
            else -> ""
        }
    }

    fun reset() {
        mode = DragMode.None
        ignore = false
        totalDx = 0f
        totalDy = 0f
    }
}

@Composable
private fun PlayerTopBar(
    title: String,
    onClose: () -> Unit,
    onOpenExternal: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = onClose) {
            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Text(
            text = title,
            color = Color.White,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        IconButton(onClick = onOpenExternal) {
            Icon(imageVector = Icons.Default.OpenInNew, contentDescription = "Open external", tint = Color.White)
        }
    }
}

@Composable
private fun PlayerBottomControls(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    isSeeking: Boolean,
    sliderPosition: Float,
    speed: Float,
    speedMenuExpanded: Boolean,
    modifier: Modifier = Modifier,
    onTracks: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekChange: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onSpeedMenuToggle: () -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onSpeedDismiss: () -> Unit,
    onGuide: () -> Unit,
    onSubtitleClick: () -> Unit
) {
    val maxDuration = max(1L, durationMs)
    val sliderValue = if (isSeeking) sliderPosition else positionMs.toFloat()

    val speedOptions = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x66000000))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = formatTime(positionMs), color = Color.White, fontSize = 12.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Slider(
                value = sliderValue.coerceIn(0f, maxDuration.toFloat()),
                onValueChange = onSeekChange,
                onValueChangeFinished = onSeekFinished,
                valueRange = 0f..maxDuration.toFloat(),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = formatTime(durationMs), color = Color.White, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ControlIconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White
                )
            }
            ControlIconButton(onClick = onTracks) {
                Icon(
                    imageVector = Icons.Default.Audiotrack,
                    contentDescription = null,
                    tint = Color.White
                )
            }
            ControlIconButton(onClick = onSubtitleClick) {
                Icon(
                    imageVector = Icons.Default.Subtitles,
                    contentDescription = null,
                    tint = Color.White
                )
            }
            Box {
                ControlPillButton(
                    text = "${speed}x",
                    onClick = onSpeedMenuToggle
                )
                DropdownMenu(
                    expanded = speedMenuExpanded,
                    onDismissRequest = onSpeedDismiss
                ) {
                    speedOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(text = "${option}x") },
                            onClick = { onSpeedSelected(option) }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            ControlIconButton(onClick = onGuide) {
                Text(text = "!", color = Color(0xFFE7ECF2), fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun OverlayHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .background(Color(0xAA000000), shape = MaterialTheme.shapes.medium)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(text = text, color = Color.White, fontSize = 14.sp)
        }
    }
}

@Composable
private fun BufferingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x55000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color(0xFFF2C14E))
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Buffering...", color = Color.White, fontSize = 12.sp)
        }
    }
}

@Composable
private fun GuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Controls", fontSize = 20.sp, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "Swipe left/right = seek\nRight side swipe = volume\nLeft side swipe = brightness",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = Color(0xFFE7ECF2)
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    )
}

private data class TextTrackOption(
    val group: Tracks.Group,
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val isSelected: Boolean
)

private data class SelectedTrackInfo(
    val label: String?,
    val language: String?,
    val groupIndex: Int,
    val trackIndex: Int
)

private fun buildTextTrackOptions(tracks: Tracks): List<TextTrackOption> {
    val options = mutableListOf<TextTrackOption>()
    tracks.groups.forEachIndexed { groupIndex, group ->
        if (group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
        for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            val label = when {
                !format.label.isNullOrBlank() -> format.label!!
                !format.language.isNullOrBlank() -> format.language!!.uppercase()
                else -> "Subtitle ${i + 1}"
            }
            options.add(
                TextTrackOption(
                    group = group,
                    groupIndex = groupIndex,
                    trackIndex = i,
                    label = label,
                    isSelected = group.isTrackSelected(i)
                )
            )
        }
    }
    return options
}

private fun applySubtitleSelection(exoPlayer: ExoPlayer, option: TextTrackOption?) {
    val builder = exoPlayer.trackSelectionParameters
        .buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
    if (option == null) {
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
    } else {
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        builder.addOverride(
            TrackSelectionOverride(option.group.mediaTrackGroup, listOf(option.trackIndex))
        )
    }
    exoPlayer.trackSelectionParameters = builder.build()
}

private fun applyExternalSubtitleSelection(
    exoPlayer: ExoPlayer,
    tracks: Tracks,
    label: String
) {
    val builder = exoPlayer.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
    val override = findTrackOverride(
        tracks = tracks,
        type = C.TRACK_TYPE_TEXT,
        label = label,
        language = null,
        groupIndex = null,
        trackIndex = null
    )
    if (override != null) {
        builder.addOverride(override)
    }
    exoPlayer.trackSelectionParameters = builder.build()
}

private fun getSelectedTrackInfo(tracks: Tracks, type: Int): SelectedTrackInfo? {
    tracks.groups.forEachIndexed { groupIndex, group ->
        if (group.type != type) return@forEachIndexed
        for (i in 0 until group.length) {
            if (group.isTrackSelected(i)) {
                val format = group.getTrackFormat(i)
                val label = format.label
                val lang = format.language
                return SelectedTrackInfo(
                    label = label,
                    language = lang,
                    groupIndex = groupIndex,
                    trackIndex = i
                )
            }
        }
    }
    return null
}

private fun applySavedTrackSelection(
    exoPlayer: ExoPlayer,
    tracks: Tracks,
    audioLabel: String?,
    audioLanguage: String?,
    audioGroupIndex: Int?,
    audioTrackIndex: Int?,
    subtitleLabel: String?,
    subtitleLanguage: String?,
    subtitleGroupIndex: Int?,
    subtitleTrackIndex: Int?,
    subtitleEnabled: Boolean
) {
    val builder = exoPlayer.trackSelectionParameters.buildUpon()

    val audioOverride = findTrackOverride(
        tracks,
        C.TRACK_TYPE_AUDIO,
        audioLabel,
        audioLanguage,
        audioGroupIndex,
        audioTrackIndex
    )
    if (audioOverride != null) {
        builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        builder.addOverride(audioOverride)
    }

    if (!subtitleEnabled) {
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
    } else {
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        val textOverride = findTrackOverride(
            tracks,
            C.TRACK_TYPE_TEXT,
            subtitleLabel,
            subtitleLanguage,
            subtitleGroupIndex,
            subtitleTrackIndex
        )
        if (textOverride != null) {
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
            builder.addOverride(textOverride)
        }
    }

    exoPlayer.trackSelectionParameters = builder.build()
}

private fun findTrackOverride(
    tracks: Tracks,
    type: Int,
    label: String?,
    language: String?,
    groupIndex: Int?,
    trackIndex: Int?
): TrackSelectionOverride? {
    if (groupIndex != null && trackIndex != null) {
        val group = tracks.groups.getOrNull(groupIndex)
        if (group != null && group.type == type && trackIndex in 0 until group.length) {
            return TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex))
        }
    }
    tracks.groups.filter { it.type == type }.forEach { group ->
        for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            val labelMatches = !label.isNullOrBlank() && format.label == label
            val languageMatches = !language.isNullOrBlank() && format.language == language
            if (labelMatches || languageMatches) {
                return TrackSelectionOverride(group.mediaTrackGroup, listOf(i))
            }
        }
    }
    return null
}

@Composable
private fun SubtitleDialog(
    tracks: List<TextTrackOption>,
    externalLabel: String,
    externalVisible: Boolean,
    externalSelected: Boolean,
    onSelect: (TextTrackOption?) -> Unit,
    onSelectExternal: () -> Unit,
    onLoadExternal: () -> Unit,
    onDismiss: () -> Unit
) {
    val noneSelected = !externalSelected && tracks.none { it.isSelected }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subtitles", fontSize = 18.sp) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = noneSelected, onClick = { onSelect(null); onDismiss() })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Off", fontSize = 13.sp, color = Color(0xFFE7ECF2))
                    }
                    if (externalVisible) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = externalSelected,
                                onClick = { onSelectExternal(); onDismiss() }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(externalLabel, fontSize = 13.sp, color = Color(0xFFE7ECF2))
                        }
                    }
                    tracks.forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = option.isSelected,
                                onClick = { onSelect(option); onDismiss() }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(option.label, fontSize = 13.sp, color = Color(0xFFE7ECF2))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = { onLoadExternal(); onDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C2432)),
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Load subtitle file", color = Color(0xFFE7ECF2), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", fontSize = 13.sp) }
        }
    )
}

@Composable
private fun ControlIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0xFF1C2432))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun ControlPillButton(
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0xFF1C2432))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = Color(0xFFE7ECF2), fontSize = 11.sp)
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = (ms / 1000).toInt()
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) {
        String.format("%02d:%02d:%02d", h, m, s)
    } else {
        String.format("%02d:%02d", m, s)
    }
}

private fun guessSubtitleMimeType(context: Context, uri: Uri): String {
    val resolver = context.contentResolver
    val type = resolver.getType(uri)
    if (!type.isNullOrBlank()) return type
    val name = queryDisplayName(resolver, uri) ?: uri.lastPathSegment.orEmpty()
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        "ttml", "dfxp", "xml" -> "application/ttml+xml"
        else -> "text/vtt"
    }
}

private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? {
    return try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)
            } else {
                null
            }
        }
    } catch (_: Exception) {
        null
    }
}
