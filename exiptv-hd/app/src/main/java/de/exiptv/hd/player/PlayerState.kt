package de.exiptv.hd.player

import androidx.compose.runtime.Immutable
import androidx.media3.common.PlaybackException
import de.exiptv.hd.data.repo.PlaybackItem

@Immutable
data class TrackOption(
    val id: String,
    val label: String,
    val language: String,
    val selected: Boolean,
)

enum class PlaybackPhase { IDLE, PREPARING, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

@Immutable
data class PlayerState(
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val item: PlaybackItem? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val isSeekable: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val frameRate: Float = 0f,
    val videoCodec: String = "",
    val audioCodec: String = "",
    val bitrateKbps: Int = 0,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val errorMessage: String = "",
    val retryAttempt: Int = 0,
    val retryInSeconds: Int = 0,
    val usingSoftwareDecoder: Boolean = false,
    val volume: Float = 1f,
    val muted: Boolean = false,
) {
    val isBusy: Boolean get() = phase == PlaybackPhase.PREPARING || phase == PlaybackPhase.BUFFERING
    val isActive: Boolean get() = phase == PlaybackPhase.PLAYING || phase == PlaybackPhase.PAUSED
    val resolutionLabel: String
        get() = when {
            videoHeight >= 2000 -> "4K"
            videoHeight >= 1000 -> "Full HD"
            videoHeight >= 700 -> "HD"
            videoHeight > 0 -> "SD"
            else -> ""
        }
}

/**
 * Einordnung von Wiedergabefehlern.
 *
 * Nicht jeder Fehler verdient dieselbe Reaktion: Eine abgerissene Verbindung
 * sollte einfach erneut versucht werden, ein abgelehnter Zugang niemals — sonst
 * hämmert die App gegen einen Server, der ohnehin nein sagt, und der Nutzer sieht
 * minutenlang einen Ladekreis statt einer Erklärung. Ein Decoder-Fehler wiederum
 * ist keine Netzwerksache, sondern ein Hinweis, es mit Software-Dekodierung
 * erneut zu probieren.
 */
enum class ErrorKind {
    /** Verbindung, Zeitüberschreitung, abgerissener Strom — erneut versuchen. */
    TRANSIENT,

    /** Zugang abgelehnt oder Sender nicht vorhanden — Wiederholen bringt nichts. */
    ACCESS,

    /** Decoder scheiterte — mit Software-Dekodierung erneut versuchen. */
    DECODER,

    /** Format wird nicht unterstützt — abbrechen und erklären. */
    UNSUPPORTED,

    /** Unbekannt: einmal wiederholen, dann aufgeben. */
    UNKNOWN,
}

object ErrorClassifier {

    fun classify(error: PlaybackException): ErrorKind = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
        -> ErrorKind.TRANSIENT

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        -> ErrorKind.ACCESS

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        -> ErrorKind.DECODER

        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> ErrorKind.UNSUPPORTED

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        -> ErrorKind.TRANSIENT

        else -> ErrorKind.UNKNOWN
    }

    /** Text für die Oberfläche — erklärt, was zu tun ist, statt einen Fehlercode zu zeigen. */
    fun message(error: PlaybackException): String = when (classify(error)) {
        ErrorKind.TRANSIENT ->
            "Die Verbindung zum Sender ist abgerissen."

        ErrorKind.ACCESS -> when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "Der Anbieter hat die Wiedergabe abgelehnt. Oft sind alle erlaubten " +
                    "Verbindungen belegt — andere Geräte schließen und erneut versuchen."
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "Diesen Sender gibt es beim Anbieter nicht mehr. Ein Katalog-Abgleich hilft."
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                "Die Adresse ist unverschlüsselt und wurde vom System blockiert."
            else ->
                "Der Zugang wurde abgelehnt. Bitte Zugangsdaten und Laufzeit prüfen."
        }

        ErrorKind.DECODER ->
            "Der Hardware-Decoder dieses Geräts kommt mit dem Sender nicht zurecht."

        ErrorKind.UNSUPPORTED ->
            "Dieses Format kann das Gerät nicht wiedergeben."

        ErrorKind.UNKNOWN ->
            "Die Wiedergabe ist unerwartet beendet worden."
    }
}
