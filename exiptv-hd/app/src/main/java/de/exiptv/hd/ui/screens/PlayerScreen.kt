package de.exiptv.hd.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import de.exiptv.hd.AppGraph
import de.exiptv.hd.core.Clock
import de.exiptv.hd.data.settings.AspectMode
import de.exiptv.hd.player.PlaybackPhase
import de.exiptv.hd.player.TrackOption
import de.exiptv.hd.ui.Badge
import de.exiptv.hd.ui.GradientProgress
import de.exiptv.hd.ui.focusCard
import de.exiptv.hd.ui.GraphViewModelFactory
import de.exiptv.hd.ui.PlayerViewModel
import de.exiptv.hd.ui.PrimaryButton
import de.exiptv.hd.ui.RemoteImage
import de.exiptv.hd.ui.SecondaryButton
import de.exiptv.hd.ui.theme.Brand
import de.exiptv.hd.ui.theme.LocalNowMillis
import kotlinx.coroutines.delay

/**
 * Der Wiedergabebildschirm.
 *
 * Die Bedienung folgt dem, was von einem Fernseher gewohnt ist: Hoch und runter
 * wechseln den Sender, links und rechts spulen, OK zeigt die Einblendung. Das
 * Overlay verschwindet von selbst, weil ein Fernsehbild mit dauerhafter
 * Einblendung kein Fernsehbild mehr ist.
 */
@Composable
fun PlayerScreen(graph: AppGraph, onBack: () -> Unit) {
    val vm: PlayerViewModel = viewModel(factory = GraphViewModelFactory(graph) { PlayerViewModel(it) })
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val programme by vm.currentProgramme.collectAsStateWithLifecycle()
    val now = LocalNowMillis.current

    var overlayVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var trackSheetVisible by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        vm.loadChannelNeighbourhood()
        runCatching { focusRequester.requestFocus() }
    }

    // Einblendung nach fünf Sekunden Ruhe ausblenden — außer bei Fehler oder Pause,
    // wo der Nutzer die Information braucht.
    LaunchedEffect(lastInteraction, state.phase, trackSheetVisible) {
        if (state.phase == PlaybackPhase.ERROR || state.phase == PlaybackPhase.PAUSED || trackSheetVisible) {
            overlayVisible = true
            return@LaunchedEffect
        }
        overlayVisible = true
        delay(5_000L)
        overlayVisible = false
    }

    /**
     * Wiedergabe anhalten, wenn die App in den Hintergrund geht.
     *
     * Ohne das läuft ein Live-Stream weiter und verbraucht eine der meist knapp
     * bemessenen gleichzeitigen Verbindungen des Anbieters — der Nutzer bekommt
     * dann auf dem nächsten Gerät eine Absage.
     */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> graph.player.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val touch: () -> Unit = { lastInteraction = System.currentTimeMillis() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                touch()
                when (event.key) {
                    Key.DirectionUp, Key.ChannelUp -> {
                        if (state.item?.isLive == true) vm.playRelative(-1)
                        true
                    }

                    Key.DirectionDown, Key.ChannelDown -> {
                        if (state.item?.isLive == true) vm.playRelative(1)
                        true
                    }

                    Key.DirectionLeft, Key.MediaRewind -> {
                        if (state.isSeekable) vm.seekBy(-15_000L)
                        true
                    }

                    Key.DirectionRight, Key.MediaFastForward -> {
                        if (state.isSeekable) vm.seekBy(15_000L)
                        true
                    }

                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> {
                        if (overlayVisible) vm.togglePlayPause() else overlayVisible = true
                        true
                    }

                    Key.MediaPlay -> { vm.togglePlayPause(); true }
                    Key.MediaPause -> { vm.togglePlayPause(); true }
                    Key.Info, Key.Menu -> { trackSheetVisible = !trackSheetVisible; true }
                    else -> false
                }
            },
    ) {
        // Das Videobild selbst. PlayerView ist eine klassische View; beim
        // Verlassen wird der Player abgehängt, damit die Fläche nicht auf eine
        // freigegebene Instanz zeigt.
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    player = graph.player.playerInstance()
                }
            },
            update = { view ->
                view.player = graph.player.playerInstanceOrNull()
                view.resizeMode = when (settings.aspectMode) {
                    AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    AspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                }
                view.keepScreenOn = settings.keepScreenOn
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        if (state.isBusy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = Brand.Magenta,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(40.dp),
                    )
                    if (state.retryAttempt > 0) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "Erneuter Versuch ${state.retryAttempt}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Brand.TextSecondary,
                        )
                    }
                }
            }
        }

        if (state.phase == PlaybackPhase.ERROR) {
            PlaybackError(
                message = state.errorMessage,
                retryInSeconds = state.retryInSeconds,
                onRetry = vm::retryNow,
                onBack = onBack,
            )
        }

        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlayerOverlay(
                title = state.item?.title.orEmpty(),
                subtitle = state.item?.subtitle.orEmpty(),
                logo = state.item?.logo.orEmpty(),
                isLive = state.item?.isLive == true,
                programmeTitle = programme?.title.orEmpty(),
                programmeStart = programme?.startAt ?: 0L,
                programmeEnd = programme?.endAt ?: 0L,
                now = now,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                bufferedMs = state.bufferedMs,
                isPlaying = state.phase == PlaybackPhase.PLAYING,
                resolution = state.resolutionLabel,
                videoCodec = state.videoCodec,
                bitrateKbps = state.bitrateKbps,
                usingSoftware = state.usingSoftwareDecoder,
                channelNumber = state.item?.channelNumber ?: 0,
                onPlayPause = { vm.togglePlayPause(); touch() },
                onTracks = { trackSheetVisible = !trackSheetVisible; touch() },
                hasTracks = state.audioTracks.size > 1 || state.subtitleTracks.isNotEmpty(),
            )
        }

        if (trackSheetVisible) {
            TrackSheet(
                audio = state.audioTracks,
                subtitles = state.subtitleTracks,
                onSelectAudio = { vm.selectAudioTrack(it); touch() },
                onSelectSubtitle = { vm.selectSubtitleTrack(it); touch() },
                onClose = { trackSheetVisible = false },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun PlaybackError(
    message: String,
    retryInSeconds: Int,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(40.dp),
        ) {
            Text(
                text = "Wiedergabe unterbrochen",
                style = MaterialTheme.typography.titleLarge,
                color = Brand.TextPrimary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = Brand.TextSecondary,
                modifier = Modifier.width(520.dp),
            )
            if (retryInSeconds > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Nächster Versuch in $retryInSeconds Sekunden",
                    style = MaterialTheme.typography.labelLarge,
                    color = Brand.TextTertiary,
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(label = "Erneut versuchen", onClick = onRetry)
                SecondaryButton(label = "Zurück", onClick = onBack)
            }
        }
    }
}

@Composable
private fun PlayerOverlay(
    title: String,
    subtitle: String,
    logo: String,
    isLive: Boolean,
    programmeTitle: String,
    programmeStart: Long,
    programmeEnd: Long,
    now: Long,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    isPlaying: Boolean,
    resolution: String,
    videoCodec: String,
    bitrateKbps: Int,
    usingSoftware: Boolean,
    channelNumber: Int,
    onPlayPause: () -> Unit,
    onTracks: () -> Unit,
    hasTracks: Boolean,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brand.scrimBottom)
            .padding(horizontal = 40.dp, vertical = 30.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (logo.isNotEmpty()) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.08f)),
                ) {
                    RemoteImage(
                        url = logo,
                        contentDescription = null,
                        fallbackInitial = title,
                        modifier = Modifier.fillMaxSize().padding(5.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
            }

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLive) {
                        Badge("LIVE", color = Brand.Live, textColor = Color.White)
                        Spacer(Modifier.width(8.dp))
                    }
                    if (channelNumber > 0) {
                        Badge(channelNumber.toString())
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val secondary = programmeTitle.ifEmpty { subtitle }
                if (secondary.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Brand.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (resolution.isNotEmpty()) Badge(resolution)
                if (videoCodec.isNotEmpty()) Badge(videoCodec)
                if (bitrateKbps > 0) Badge("${bitrateKbps / 1000} Mbit/s")
                if (usingSoftware) Badge("Software", textColor = Brand.Warning)
            }
        }

        Spacer(Modifier.height(18.dp))

        if (isLive && programmeEnd > programmeStart) {
            val progress = ((now - programmeStart).toFloat() / (programmeEnd - programmeStart).toFloat())
                .coerceIn(0f, 1f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Clock.time(programmeStart),
                    style = MaterialTheme.typography.labelLarge,
                    color = Brand.TextTertiary,
                )
                Spacer(Modifier.width(12.dp))
                GradientProgress(progress = progress, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text(
                    text = Clock.time(programmeEnd),
                    style = MaterialTheme.typography.labelLarge,
                    color = Brand.TextTertiary,
                )
            }
        } else if (durationMs > 0L) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Clock.position(positionMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = Brand.TextSecondary,
                )
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    // Der Pufferstand liegt hinter dem Fortschritt — so ist auf einen
                    // Blick erkennbar, wie viel Vorlauf noch da ist.
                    GradientProgress(
                        progress = (bufferedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                        height = 3.dp,
                    )
                    GradientProgress(
                        progress = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                        height = 3.dp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = Clock.position(durationMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = Brand.TextSecondary,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(Brand.gradient)
                    .focusCard(onClick = onPlayPause, scaleOnFocus = 1.1f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Abspielen",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            if (hasTracks) {
                SecondaryButton(label = "Ton & Untertitel", onClick = onTracks)
            }
        }
    }
}

@Composable
private fun TrackSheet(
    audio: List<TrackOption>,
    subtitles: List<TrackOption>,
    onSelectAudio: (String) -> Unit,
    onSelectSubtitle: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(340.dp)
            .padding(24.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brand.Surface.copy(alpha = 0.97f))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Tonspur", style = MaterialTheme.typography.titleLarge, color = Brand.TextPrimary)
        if (audio.isEmpty()) {
            Text(
                "Keine Auswahl verfügbar",
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.TextTertiary,
            )
        }
        audio.forEach { option ->
            TrackRow(option.label, option.selected) { onSelectAudio(option.id) }
        }

        if (subtitles.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Untertitel", style = MaterialTheme.typography.titleLarge, color = Brand.TextPrimary)
            TrackRow("Aus", subtitles.none { it.selected }) { onSelectSubtitle("") }
            subtitles.forEach { option ->
                TrackRow(option.label, option.selected) { onSelectSubtitle(option.id) }
            }
        }

        Spacer(Modifier.height(8.dp))
        SecondaryButton(label = "Schließen", onClick = onClose, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Brand.SurfaceHighest else Color.Transparent)
            .focusCard(onClick = onClick, scaleOnFocus = 1.02f)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) Brand.Magenta else Brand.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
