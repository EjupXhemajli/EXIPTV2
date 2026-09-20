package de.exiptv.hd.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.exiptv.hd.core.Diagnostics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class StartScreen { HOME, LIVE, MOVIES, SERIES }

enum class BufferProfile(val label: String, val minMs: Int, val maxMs: Int, val playbackMs: Int, val rebufferMs: Int) {
    /** Für schnelle Verbindungen: kurzer Vorlauf, der Sender startet fast sofort. */
    RESPONSIVE("Schnellstart", 4_000, 20_000, 1_200, 2_500),

    /** Standard: trägt kurze Aussetzer, ohne den Senderwechsel spürbar zu bremsen. */
    BALANCED("Ausgewogen", 12_000, 45_000, 2_000, 4_000),

    /** Für unruhige Leitungen und WLAN am Rand der Reichweite. */
    STABLE("Stabil", 25_000, 90_000, 3_500, 7_000),
}

enum class AspectMode(val label: String) {
    FIT("Einpassen"),
    FILL("Ausfüllen"),
    ZOOM("Zoom"),
    STRETCH("Strecken"),
}

enum class DecoderMode(val label: String) {
    HARDWARE("Hardware bevorzugen"),
    SOFTWARE("Software erzwingen"),
}

/**
 * Der komplette Einstellungszustand als ein unveränderliches Objekt.
 *
 * Ein einziger Flow für alles bedeutet: Die Oberfläche abonniert genau eine
 * Quelle, und jede Schreiboperation erzeugt genau eine neue Instanz. Kein Screen
 * muss einzelne Schlüssel kennen, und nichts kann teilweise aktualisiert bei den
 * Abonnenten ankommen.
 */
data class AppSettings(
    // Wiedergabe
    val bufferProfile: BufferProfile = BufferProfile.BALANCED,
    val decoderMode: DecoderMode = DecoderMode.HARDWARE,
    val aspectMode: AspectMode = AspectMode.FIT,
    val forceHttp1: Boolean = false,
    val streamTimeoutSeconds: Int = 20,
    val autoRetry: Boolean = true,
    val maxRetries: Int = 8,
    val subtitlesEnabled: Boolean = false,
    val preferredAudioLanguages: String = "deu,ger,de,mul",
    val preferredSubtitleLanguages: String = "deu,ger,de",
    val resumePlayback: Boolean = true,
    val keepScreenOn: Boolean = true,

    // Oberfläche
    val startScreen: StartScreen = StartScreen.HOME,
    val hideAdult: Boolean = true,
    val showChannelNumbers: Boolean = true,
    val showClock: Boolean = true,
    val posterSizeStep: Int = 1,
    val startSound: Boolean = false,

    // Programmzeitschrift
    val epgEnabled: Boolean = true,
    val epgOffsetMinutes: Int = 0,
    val epgDaysAhead: Int = 3,
    val epgRefreshHours: Int = 12,
    val lastEpgSyncAt: Long = 0L,

    // Katalog
    val autoSyncOnStart: Boolean = true,
    val syncIntervalHours: Int = 24,

    // Einmalige Abläufe
    val setupCompleted: Boolean = false,
) {
    val adultMax: Int get() = if (hideAdult) 0 else 1
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "exiptv_settings")

class SettingsStore(context: Context) {

    private val store = context.applicationContext.dataStore

    private object Keys {
        val bufferProfile = stringPreferencesKey("buffer_profile")
        val decoderMode = stringPreferencesKey("decoder_mode")
        val aspectMode = stringPreferencesKey("aspect_mode")
        val forceHttp1 = booleanPreferencesKey("force_http1")
        val streamTimeout = intPreferencesKey("stream_timeout")
        val autoRetry = booleanPreferencesKey("auto_retry")
        val maxRetries = intPreferencesKey("max_retries")
        val subtitlesEnabled = booleanPreferencesKey("subtitles_enabled")
        val audioLanguages = stringPreferencesKey("audio_languages")
        val subtitleLanguages = stringPreferencesKey("subtitle_languages")
        val resumePlayback = booleanPreferencesKey("resume_playback")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")

        val startScreen = stringPreferencesKey("start_screen")
        val hideAdult = booleanPreferencesKey("hide_adult")
        val showChannelNumbers = booleanPreferencesKey("show_channel_numbers")
        val showClock = booleanPreferencesKey("show_clock")
        val posterSizeStep = intPreferencesKey("poster_size_step")
        val startSound = booleanPreferencesKey("start_sound")

        val epgEnabled = booleanPreferencesKey("epg_enabled")
        val epgOffset = intPreferencesKey("epg_offset_minutes")
        val epgDaysAhead = intPreferencesKey("epg_days_ahead")
        val epgRefreshHours = intPreferencesKey("epg_refresh_hours")
        val lastEpgSyncAt = longPreferencesKey("last_epg_sync_at")

        val autoSyncOnStart = booleanPreferencesKey("auto_sync_on_start")
        val syncIntervalHours = intPreferencesKey("sync_interval_hours")

        val setupCompleted = booleanPreferencesKey("setup_completed")
    }

    val settings: Flow<AppSettings> = store.data
        .catch { e ->
            // Eine beschädigte Einstellungsdatei darf die App nicht am Start hindern.
            if (e is IOException) {
                Diagnostics.warn("Einstellungen nicht lesbar, Standardwerte werden verwendet")
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> prefs.toSettings() }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            bufferProfile = enumOf(this[Keys.bufferProfile], defaults.bufferProfile),
            decoderMode = enumOf(this[Keys.decoderMode], defaults.decoderMode),
            aspectMode = enumOf(this[Keys.aspectMode], defaults.aspectMode),
            forceHttp1 = this[Keys.forceHttp1] ?: defaults.forceHttp1,
            streamTimeoutSeconds = (this[Keys.streamTimeout] ?: defaults.streamTimeoutSeconds).coerceIn(5, 120),
            autoRetry = this[Keys.autoRetry] ?: defaults.autoRetry,
            maxRetries = (this[Keys.maxRetries] ?: defaults.maxRetries).coerceIn(0, 20),
            subtitlesEnabled = this[Keys.subtitlesEnabled] ?: defaults.subtitlesEnabled,
            preferredAudioLanguages = this[Keys.audioLanguages] ?: defaults.preferredAudioLanguages,
            preferredSubtitleLanguages = this[Keys.subtitleLanguages] ?: defaults.preferredSubtitleLanguages,
            resumePlayback = this[Keys.resumePlayback] ?: defaults.resumePlayback,
            keepScreenOn = this[Keys.keepScreenOn] ?: defaults.keepScreenOn,
            startScreen = enumOf(this[Keys.startScreen], defaults.startScreen),
            hideAdult = this[Keys.hideAdult] ?: defaults.hideAdult,
            showChannelNumbers = this[Keys.showChannelNumbers] ?: defaults.showChannelNumbers,
            showClock = this[Keys.showClock] ?: defaults.showClock,
            posterSizeStep = (this[Keys.posterSizeStep] ?: defaults.posterSizeStep).coerceIn(0, 2),
            startSound = this[Keys.startSound] ?: defaults.startSound,
            epgEnabled = this[Keys.epgEnabled] ?: defaults.epgEnabled,
            epgOffsetMinutes = (this[Keys.epgOffset] ?: defaults.epgOffsetMinutes).coerceIn(-720, 720),
            epgDaysAhead = (this[Keys.epgDaysAhead] ?: defaults.epgDaysAhead).coerceIn(1, 7),
            epgRefreshHours = (this[Keys.epgRefreshHours] ?: defaults.epgRefreshHours).coerceIn(1, 72),
            lastEpgSyncAt = this[Keys.lastEpgSyncAt] ?: defaults.lastEpgSyncAt,
            autoSyncOnStart = this[Keys.autoSyncOnStart] ?: defaults.autoSyncOnStart,
            syncIntervalHours = (this[Keys.syncIntervalHours] ?: defaults.syncIntervalHours).coerceIn(1, 168),
            setupCompleted = this[Keys.setupCompleted] ?: defaults.setupCompleted,
        )
    }

    private inline fun <reified E : Enum<E>> enumOf(raw: String?, fallback: E): E =
        if (raw == null) fallback else runCatching { enumValueOf<E>(raw) }.getOrDefault(fallback)

    private suspend fun write(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        runCatching { store.edit(block) }
            .onFailure { Diagnostics.error("Einstellung konnte nicht gespeichert werden", it) }
    }

    suspend fun setBufferProfile(v: BufferProfile) = write { it[Keys.bufferProfile] = v.name }
    suspend fun setDecoderMode(v: DecoderMode) = write { it[Keys.decoderMode] = v.name }
    suspend fun setAspectMode(v: AspectMode) = write { it[Keys.aspectMode] = v.name }
    suspend fun setForceHttp1(v: Boolean) = write { it[Keys.forceHttp1] = v }
    suspend fun setStreamTimeout(v: Int) = write { it[Keys.streamTimeout] = v.coerceIn(5, 120) }
    suspend fun setAutoRetry(v: Boolean) = write { it[Keys.autoRetry] = v }
    suspend fun setMaxRetries(v: Int) = write { it[Keys.maxRetries] = v.coerceIn(0, 20) }
    suspend fun setSubtitlesEnabled(v: Boolean) = write { it[Keys.subtitlesEnabled] = v }
    suspend fun setAudioLanguages(v: String) = write { it[Keys.audioLanguages] = v }
    suspend fun setSubtitleLanguages(v: String) = write { it[Keys.subtitleLanguages] = v }
    suspend fun setResumePlayback(v: Boolean) = write { it[Keys.resumePlayback] = v }
    suspend fun setKeepScreenOn(v: Boolean) = write { it[Keys.keepScreenOn] = v }

    suspend fun setStartScreen(v: StartScreen) = write { it[Keys.startScreen] = v.name }
    suspend fun setHideAdult(v: Boolean) = write { it[Keys.hideAdult] = v }
    suspend fun setShowChannelNumbers(v: Boolean) = write { it[Keys.showChannelNumbers] = v }
    suspend fun setShowClock(v: Boolean) = write { it[Keys.showClock] = v }
    suspend fun setPosterSizeStep(v: Int) = write { it[Keys.posterSizeStep] = v.coerceIn(0, 2) }
    suspend fun setStartSound(v: Boolean) = write { it[Keys.startSound] = v }

    suspend fun setEpgEnabled(v: Boolean) = write { it[Keys.epgEnabled] = v }
    suspend fun setEpgOffset(v: Int) = write { it[Keys.epgOffset] = v.coerceIn(-720, 720) }
    suspend fun setEpgDaysAhead(v: Int) = write { it[Keys.epgDaysAhead] = v.coerceIn(1, 7) }
    suspend fun setEpgRefreshHours(v: Int) = write { it[Keys.epgRefreshHours] = v.coerceIn(1, 72) }
    suspend fun setLastEpgSyncAt(v: Long) = write { it[Keys.lastEpgSyncAt] = v }

    suspend fun setAutoSyncOnStart(v: Boolean) = write { it[Keys.autoSyncOnStart] = v }
    suspend fun setSyncIntervalHours(v: Int) = write { it[Keys.syncIntervalHours] = v.coerceIn(1, 168) }

    suspend fun setSetupCompleted(v: Boolean) = write { it[Keys.setupCompleted] = v }
}
