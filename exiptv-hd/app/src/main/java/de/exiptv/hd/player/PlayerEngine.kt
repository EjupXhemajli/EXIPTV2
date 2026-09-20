package de.exiptv.hd.player

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import de.exiptv.hd.core.Diagnostics
import de.exiptv.hd.data.net.HttpEngine
import de.exiptv.hd.data.net.UserAgents
import de.exiptv.hd.data.repo.PlaybackItem
import de.exiptv.hd.data.settings.AppSettings
import de.exiptv.hd.data.settings.DecoderMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Die Wiedergabe.
 *
 * Drei Entscheidungen prägen diese Klasse:
 *
 * 1. **Ein Player für die ganze App.** Eine ExoPlayer-Instanz aufzubauen kostet
 *    auf einer TV-Box spürbar Zeit; sie pro Bildschirm neu zu erzeugen würde
 *    jeden Senderwechsel verzögern. Die Instanz hält aber ausschließlich den
 *    Application-Context und Zustandsflüsse — keine Activity, kein Composable,
 *    nichts, was auslaufen könnte.
 *
 * 2. **Fehler werden eingeordnet, nicht gezählt.** Ein abgelehnter Zugang wird
 *    nicht acht Mal wiederholt, ein Decoder-Fehler führt zum Umschalten auf
 *    Software-Dekodierung statt zu einem weiteren vergeblichen Versuch.
 *
 * 3. **Ein Wachhund auf Frame-Ebene.** IPTV-Server lassen Verbindungen gern offen,
 *    ohne noch Daten zu liefern. ExoPlayer meldet dann keinen Fehler, das Bild
 *    friert einfach ein. Deshalb wird der Zeitpunkt des letzten gerenderten
 *    Frames überwacht.
 */
class PlayerEngine(
    private val context: Context,
    private val http: HttpEngine,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null

    private var settings: AppSettings = AppSettings()
    private var softwareFallbackActive = false
    private var retryJob: Job? = null
    private var tickerJob: Job? = null
    private var watchdogJob: Job? = null

    private var lastFrameAt: Long = 0L
    private var pendingResumeMs: Long = 0L

    /** Rückmeldung für die Speicherung der Wiedergabeposition. */
    var onPositionUpdate: ((PlaybackItem, Long, Long) -> Unit)? = null

    fun updateSettings(newSettings: AppSettings) {
        val needsRebuild = player != null && (
            newSettings.bufferProfile != settings.bufferProfile ||
                newSettings.forceHttp1 != settings.forceHttp1 ||
                newSettings.streamTimeoutSeconds != settings.streamTimeoutSeconds ||
                newSettings.decoderMode != settings.decoderMode
            )
        settings = newSettings

        trackSelector?.let { selector ->
            selector.parameters = selector.buildUponParameters()
                .setPreferredAudioLanguages(*languages(newSettings.preferredAudioLanguages))
                .setPreferredTextLanguages(*languages(newSettings.preferredSubtitleLanguages))
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !newSettings.subtitlesEnabled)
                .build()
        }

        if (needsRebuild) {
            // Puffer- und Netzwerkeinstellungen lassen sich an einem laufenden
            // Player nicht ändern. Statt sie stillschweigend zu verwerfen, wird
            // der aktuelle Sender mit den neuen Werten neu aufgebaut.
            val current = _state.value.item
            val position = player?.currentPosition ?: 0L
            release()
            if (current != null) play(current.copy(startPositionMs = if (current.isLive) 0L else position))
        }
    }

    // -----------------------------------------------------------------------
    // Aufbau
    // -----------------------------------------------------------------------

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }

        val selector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setPreferredAudioLanguages(*languages(settings.preferredAudioLanguages))
                .setPreferredTextLanguages(*languages(settings.preferredSubtitleLanguages))
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !settings.subtitlesEnabled)
                .setSelectUndeterminedTextLanguage(false)
                .setExceedAudioConstraintsIfNecessary(true)
                .setAllowAudioMixedMimeTypeAdaptiveness(true)
                .setAllowAudioMixedSampleRateAdaptiveness(true)
                .setAllowAudioMixedChannelCountAdaptiveness(true)
                .build()
        }
        trackSelector = selector

        val preferSoftware = softwareFallbackActive || settings.decoderMode == DecoderMode.SOFTWARE
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setMediaCodecSelector(
                if (preferSoftware) {
                    // Den Extension-Modus umzustellen genügt nicht: Ohne
                    // eingebundene ffmpeg-Bibliothek gibt es gar keinen
                    // Extension-Renderer, auf den er ausweichen könnte. Wirksam
                    // wird der Rückfall erst, wenn die Software-Decoder des
                    // Systems in der Rangfolge nach vorne rücken — das rettet in
                    // der Praxis viele HEVC-Sender auf älteren Boxen.
                    MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
                        MediaCodecUtil.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
                            .sortedBy { if (it.softwareOnly) 0 else 1 }
                    }
                } else {
                    MediaCodecSelector.DEFAULT
                }
            )

        val profile = settings.bufferProfile
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.minMs,
                profile.maxMs,
                profile.playbackMs,
                profile.rebufferMs,
            )
            // Bei knappem Speicher lieber weniger Zeit puffern als abstürzen.
            .setTargetBufferBytes(targetBufferBytes())
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(30_000, false)
            .build()

        val httpFactory = OkHttpDataSource.Factory(
            http.player(settings.streamTimeoutSeconds, settings.forceHttp1)
        ).setUserAgent(UserAgents.DEFAULT)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

        val created = ExoPlayer.Builder(context, renderers)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setSeekBackIncrementMs(15_000L)
            .setSeekForwardIncrementMs(15_000L)
            .build()

        created.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        created.setHandleAudioBecomingNoisy(true)
        created.setWakeMode(C.WAKE_MODE_NETWORK)
        created.setVideoFrameMetadataListener { _, _, _, _ ->
            lastFrameAt = SystemClock.elapsedRealtime()
        }
        created.addListener(listener)

        player = created
        _state.value = _state.value.copy(usingSoftwareDecoder = preferSoftware)
        return created
    }

    /**
     * Puffergröße in Bytes, begrenzt auf ein Fünftel des verfügbaren Heaps.
     * Ohne diese Grenze reicht ein einzelner 4K-Sender, um auf einer
     * 1-GB-TV-Box den Speicher zu sprengen.
     */
    private fun targetBufferBytes(): Int {
        val maxHeap = Runtime.getRuntime().maxMemory()
        val fifth = (maxHeap / 5L).coerceAtLeast(8L * 1024 * 1024)
        return min(fifth, 64L * 1024 * 1024).toInt()
    }

    private fun languages(raw: String): Array<String> =
        raw.split(',', ';', ' ')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toTypedArray()

    /** Die Oberfläche hängt ihre Videofläche hier ein. */
    fun playerInstanceOrNull(): ExoPlayer? = player

    fun playerInstance(): ExoPlayer = ensurePlayer()

    // -----------------------------------------------------------------------
    // Wiedergabe
    // -----------------------------------------------------------------------

    fun play(item: PlaybackItem) {
        retryJob?.cancel()
        retryJob = null
        softwareFallbackActive = settings.decoderMode == DecoderMode.SOFTWARE

        val exo = ensurePlayer()
        pendingResumeMs = item.startPositionMs

        _state.value = PlayerState(
            phase = PlaybackPhase.PREPARING,
            item = item,
            usingSoftwareDecoder = softwareFallbackActive,
            volume = _state.value.volume,
            muted = _state.value.muted,
        )

        val mediaItem = MediaItem.Builder()
            .setUri(item.streamUrl)
            .apply {
                mimeTypeFor(item.streamUrl)?.let { setMimeType(it) }
            }
            .build()

        exo.setMediaItem(mediaItem, if (item.isLive) C.TIME_UNSET else item.startPositionMs)
        exo.prepare()
        exo.playWhenReady = true

        lastFrameAt = SystemClock.elapsedRealtime()
        startTicker()
        startWatchdog()
        Diagnostics.info("Start: ${item.title}")
    }

    /**
     * Ein Live-Strom ohne Dateiendung lässt sich von media3 nicht immer eindeutig
     * zuordnen. Ein gesetzter MIME-Typ erspart dem Extraktor das Raten und damit
     * die halbe Sekunde, die er sonst mit Probieren verbringt.
     */
    private fun mimeTypeFor(url: String): String? {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
            else -> null
        }
    }

    fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun seekTo(positionMs: Long) {
        val exo = player ?: return
        if (_state.value.item?.isLive == true) return
        val duration = exo.duration
        val target = if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        exo.seekTo(target)
    }

    fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        seekTo(exo.currentPosition + deltaMs)
    }

    fun setVolume(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        player?.volume = clamped
        _state.value = _state.value.copy(volume = clamped, muted = clamped <= 0f)
    }

    fun toggleMute() {
        val muted = !_state.value.muted
        player?.volume = if (muted) 0f else _state.value.volume
        _state.value = _state.value.copy(muted = muted)
    }

    fun selectTrack(trackType: Int, optionId: String) {
        val exo = player ?: return
        val selector = trackSelector ?: return

        if (optionId.isEmpty()) {
            selector.parameters = selector.buildUponParameters()
                .setTrackTypeDisabled(trackType, true)
                .build()
            refreshTracks(exo.currentTracks)
            return
        }

        for (group in exo.currentTracks.groups) {
            if (group.type != trackType) continue
            for (index in 0 until group.length) {
                if (trackOptionId(group, index) != optionId) continue
                selector.parameters = selector.buildUponParameters()
                    .setTrackTypeDisabled(trackType, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                    .build()
                refreshTracks(exo.currentTracks)
                return
            }
        }
    }

    /** Beendet die Wiedergabe und gibt alle Ressourcen frei. */
    fun release() {
        retryJob?.cancel()
        retryJob = null
        tickerJob?.cancel()
        tickerJob = null
        watchdogJob?.cancel()
        watchdogJob = null

        val exo = player
        player = null
        trackSelector = null
        if (exo != null) {
            savePosition(exo)
            runCatching {
                exo.removeListener(listener)
                exo.playWhenReady = false
                exo.release()
            }
        }
        _state.value = PlayerState(volume = _state.value.volume, muted = _state.value.muted)
    }

    // -----------------------------------------------------------------------
    // Beobachtung
    // -----------------------------------------------------------------------

    private val listener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            val exo = player ?: return
            val phase = when (playbackState) {
                Player.STATE_IDLE -> if (_state.value.phase == PlaybackPhase.ERROR) {
                    PlaybackPhase.ERROR
                } else {
                    PlaybackPhase.IDLE
                }
                Player.STATE_BUFFERING -> PlaybackPhase.BUFFERING
                Player.STATE_READY -> if (exo.playWhenReady) PlaybackPhase.PLAYING else PlaybackPhase.PAUSED
                Player.STATE_ENDED -> PlaybackPhase.ENDED
                else -> _state.value.phase
            }

            if (playbackState == Player.STATE_READY) {
                // Erfolgreich gestartet: Der Wiederholungszähler beginnt von vorn,
                // sonst würde ein Sender, der stundenlang lief, beim nächsten
                // Aussetzer sofort aufgeben.
                _state.value = _state.value.copy(
                    phase = phase,
                    retryAttempt = 0,
                    retryInSeconds = 0,
                    errorMessage = "",
                    durationMs = if (exo.duration > 0L) exo.duration else 0L,
                    isSeekable = exo.isCurrentMediaItemSeekable,
                )
                lastFrameAt = SystemClock.elapsedRealtime()
            } else {
                _state.value = _state.value.copy(phase = phase)
            }

            if (playbackState == Player.STATE_ENDED) {
                savePosition(exo)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) lastFrameAt = SystemClock.elapsedRealtime()
            val current = _state.value
            if (current.phase == PlaybackPhase.PLAYING || current.phase == PlaybackPhase.PAUSED) {
                _state.value = current.copy(
                    phase = if (isPlaying) PlaybackPhase.PLAYING else PlaybackPhase.PAUSED
                )
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handleError(error)
        }

        override fun onTracksChanged(tracks: Tracks) {
            refreshTracks(tracks)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.value = _state.value.copy(
                videoWidth = videoSize.width,
                videoHeight = videoSize.height,
            )
        }
    }

    private fun refreshTracks(tracks: Tracks) {
        val audio = ArrayList<TrackOption>(4)
        val text = ArrayList<TrackOption>(4)
        var videoCodec = ""
        var audioCodec = ""
        var frameRate = 0f
        var bitrate = 0

        for (group in tracks.groups) {
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                val selected = group.isTrackSelected(index)
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> if (selected) {
                        videoCodec = codecLabel(format)
                        frameRate = if (format.frameRate > 0f) format.frameRate else 0f
                        if (format.bitrate > 0) bitrate = format.bitrate / 1000
                    }

                    C.TRACK_TYPE_AUDIO -> {
                        if (selected) audioCodec = codecLabel(format)
                        audio += TrackOption(
                            id = trackOptionId(group, index),
                            label = trackLabel(format, audio.size + 1, isAudio = true),
                            language = format.language.orEmpty(),
                            selected = selected,
                        )
                    }

                    C.TRACK_TYPE_TEXT -> text += TrackOption(
                        id = trackOptionId(group, index),
                        label = trackLabel(format, text.size + 1, isAudio = false),
                        language = format.language.orEmpty(),
                        selected = selected,
                    )
                }
            }
        }

        _state.value = _state.value.copy(
            audioTracks = audio,
            subtitleTracks = text,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            frameRate = frameRate,
            bitrateKbps = if (bitrate > 0) bitrate else _state.value.bitrateKbps,
        )
    }

    private fun trackOptionId(group: Tracks.Group, index: Int): String =
        "${group.type}:${group.mediaTrackGroup.id}:$index"

    private fun codecLabel(format: Format): String =
        format.sampleMimeType?.substringAfterLast('/')?.uppercase().orEmpty()

    private fun trackLabel(format: Format, ordinal: Int, isAudio: Boolean): String {
        val language = format.language?.takeIf { it.isNotEmpty() && it != "und" }
            ?.let { languageName(it) }
        val extra = if (isAudio) {
            listOfNotNull(
                format.label?.takeIf { it.isNotBlank() },
                format.channelCount.takeIf { it > 2 }?.let { "$it Kanäle" },
            ).joinToString(" · ")
        } else {
            format.label?.takeIf { it.isNotBlank() }.orEmpty()
        }
        return listOfNotNull(
            language ?: "Spur $ordinal",
            extra.takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
    }

    private fun languageName(code: String): String = when (code.lowercase().take(3)) {
        "de", "deu", "ger" -> "Deutsch"
        "en", "eng" -> "Englisch"
        "tr", "tur" -> "Türkisch"
        "fr", "fra", "fre" -> "Französisch"
        "es", "spa" -> "Spanisch"
        "it", "ita" -> "Italienisch"
        "ru", "rus" -> "Russisch"
        "ar", "ara" -> "Arabisch"
        "pl", "pol" -> "Polnisch"
        "nl", "nld", "dut" -> "Niederländisch"
        "pt", "por" -> "Portugiesisch"
        "sq", "alb", "sqi" -> "Albanisch"
        "hr", "hrv" -> "Kroatisch"
        "sr", "srp" -> "Serbisch"
        "mul" -> "Mehrsprachig"
        else -> code.uppercase()
    }

    // -----------------------------------------------------------------------
    // Fehlerbehandlung
    // -----------------------------------------------------------------------

    private fun handleError(error: PlaybackException) {
        val item = _state.value.item
        val kind = ErrorClassifier.classify(error)
        val message = ErrorClassifier.message(error)
        Diagnostics.warn("Wiedergabefehler (${error.errorCodeName}): $message")

        if (item == null) {
            _state.value = _state.value.copy(phase = PlaybackPhase.ERROR, errorMessage = message)
            return
        }

        // Decoder-Fehler: einmalig auf Software-Dekodierung umstellen und neu
        // aufbauen. Das rettet in der Praxis viele HEVC-Sender auf älteren Boxen.
        if (kind == ErrorKind.DECODER && !softwareFallbackActive) {
            Diagnostics.info("Wechsel auf Software-Dekodierung")
            softwareFallbackActive = true
            rebuildAndRetry(item, immediate = true)
            return
        }

        val retryable = settings.autoRetry && when (kind) {
            ErrorKind.TRANSIENT -> true
            ErrorKind.UNKNOWN -> _state.value.retryAttempt < 2
            ErrorKind.DECODER -> false
            ErrorKind.ACCESS -> false
            ErrorKind.UNSUPPORTED -> false
        }

        if (!retryable || _state.value.retryAttempt >= settings.maxRetries) {
            _state.value = _state.value.copy(
                phase = PlaybackPhase.ERROR,
                errorMessage = message,
                retryInSeconds = 0,
            )
            return
        }

        val attempt = _state.value.retryAttempt + 1
        val waitMs = retryDelayMs(attempt)
        _state.value = _state.value.copy(
            phase = PlaybackPhase.ERROR,
            errorMessage = message,
            retryAttempt = attempt,
            retryInSeconds = ((waitMs + 999L) / 1000L).toInt(),
        )

        retryJob?.cancel()
        retryJob = scope.launch {
            var remaining = waitMs
            while (remaining > 0) {
                delay(min(remaining, 1_000L))
                remaining -= 1_000L
                _state.value = _state.value.copy(
                    retryInSeconds = ((remaining.coerceAtLeast(0L) + 999L) / 1000L).toInt()
                )
            }
            Diagnostics.info("Neuer Versuch $attempt von ${settings.maxRetries}")
            val exo = player
            if (exo != null) {
                _state.value = _state.value.copy(phase = PlaybackPhase.PREPARING, errorMessage = "")
                exo.prepare()
                exo.playWhenReady = true
                lastFrameAt = SystemClock.elapsedRealtime()
            } else {
                rebuildAndRetry(item, immediate = true)
            }
        }
    }

    /**
     * Wartezeit zwischen Versuchen: schnell beim ersten Mal, danach wachsend.
     *
     * Ein Sender, der gerade neu startet, ist oft nach zwei Sekunden wieder da —
     * darauf zehn Sekunden zu warten, fühlt sich kaputt an. Ein Server, der unter
     * Last steht, braucht dagegen Ruhe, sonst verschärft das Nachfassen das Problem.
     */
    private fun retryDelayMs(attempt: Int): Long = when (attempt) {
        1 -> 1_000L
        2 -> 2_000L
        3 -> 4_000L
        4 -> 7_000L
        else -> 12_000L
    }

    private fun rebuildAndRetry(item: PlaybackItem, immediate: Boolean) {
        val position = if (item.isLive) 0L else (player?.currentPosition ?: item.startPositionMs)
        val keepAttempt = _state.value.retryAttempt
        val exo = player
        player = null
        trackSelector = null
        if (exo != null) {
            runCatching {
                exo.removeListener(listener)
                exo.release()
            }
        }
        retryJob?.cancel()
        retryJob = scope.launch {
            if (!immediate) delay(1_000L)
            play(item.copy(startPositionMs = position))
            _state.value = _state.value.copy(retryAttempt = keepAttempt)
        }
    }

    // -----------------------------------------------------------------------
    // Laufende Beobachtung
    // -----------------------------------------------------------------------

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var sinceSave = 0
            while (true) {
                delay(1_000L)
                val exo = player ?: continue
                val current = _state.value
                if (current.phase == PlaybackPhase.PLAYING || current.phase == PlaybackPhase.PAUSED) {
                    _state.value = current.copy(
                        positionMs = exo.currentPosition.coerceAtLeast(0L),
                        durationMs = if (exo.duration > 0L) exo.duration else current.durationMs,
                        bufferedMs = exo.bufferedPosition.coerceAtLeast(0L),
                        isSeekable = exo.isCurrentMediaItemSeekable,
                    )
                    sinceSave++
                    if (sinceSave >= 15) {
                        sinceSave = 0
                        savePosition(exo)
                    }
                }
            }
        }
    }

    /**
     * Der Wachhund.
     *
     * Er schlägt an, wenn der Player zwar spielen will, aber seit einer Weile kein
     * Bild mehr geliefert hat. Genau dieses Muster erzeugen IPTV-Server, die eine
     * Verbindung offen halten, ohne noch Daten zu senden: kein Fehler, kein Ende
     * des Stroms — das Bild steht einfach.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (true) {
                delay(2_000L)
                val exo = player ?: continue
                val current = _state.value
                val item = current.item ?: continue
                if (!settings.autoRetry) continue
                if (current.phase != PlaybackPhase.PLAYING && current.phase != PlaybackPhase.BUFFERING) continue
                if (!exo.playWhenReady) continue

                val silenceMs = SystemClock.elapsedRealtime() - lastFrameAt
                val limit = settings.streamTimeoutSeconds * 1000L + 5_000L
                if (silenceMs > limit) {
                    Diagnostics.warn("Kein Bild seit ${silenceMs / 1000}s — Sender wird neu aufgebaut")
                    lastFrameAt = SystemClock.elapsedRealtime()
                    val attempt = current.retryAttempt + 1
                    if (attempt > settings.maxRetries) {
                        _state.value = current.copy(
                            phase = PlaybackPhase.ERROR,
                            errorMessage = "Der Sender liefert kein Bild mehr.",
                        )
                        continue
                    }
                    _state.value = current.copy(phase = PlaybackPhase.PREPARING, retryAttempt = attempt)
                    exo.prepare()
                    exo.playWhenReady = true
                }
            }
        }
    }

    private fun savePosition(exo: ExoPlayer) {
        val item = _state.value.item ?: return
        if (item.isLive) return
        val position = exo.currentPosition
        val duration = exo.duration
        if (position <= 0L) return
        onPositionUpdate?.invoke(item, position, if (duration > 0L) duration else 0L)
    }
}
