package de.exiptv.hd.data.repo

import androidx.compose.runtime.Immutable
import de.exiptv.hd.data.db.ChannelEntity
import de.exiptv.hd.data.db.EpisodeEntity
import de.exiptv.hd.data.db.ItemKind
import de.exiptv.hd.data.db.MovieEntity
import de.exiptv.hd.data.db.SeriesEntity

/**
 * Was der Player abspielt — unabhängig davon, woher es kommt.
 *
 * `@Immutable` ist hier kein Schmuck: Compose darf damit annehmen, dass sich der
 * Inhalt nie ändert, und überspringt beim erneuten Zeichnen alle Vergleiche.
 * Dasselbe gilt für die Listeneinträge weiter unten.
 */
@Immutable
data class PlaybackItem(
    val id: String,
    val kind: ItemKind,
    val title: String,
    val subtitle: String = "",
    val streamUrl: String,
    val logo: String = "",
    val epgKey: String = "",
    val isLive: Boolean,
    val startPositionMs: Long = 0L,
    val seriesId: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val channelNumber: Int = 0,
    val catchupDays: Int = 0,
) {
    companion object {
        fun of(channel: ChannelEntity): PlaybackItem = PlaybackItem(
            id = channel.id,
            kind = ItemKind.CHANNEL,
            title = channel.name,
            subtitle = channel.groupTitle,
            streamUrl = channel.streamUrl,
            logo = channel.logo,
            epgKey = channel.epgKey,
            isLive = true,
            channelNumber = channel.number,
            catchupDays = channel.catchupDays,
        )

        fun of(movie: MovieEntity, startPositionMs: Long = 0L): PlaybackItem = PlaybackItem(
            id = movie.id,
            kind = ItemKind.MOVIE,
            title = movie.name,
            subtitle = movie.year,
            streamUrl = movie.streamUrl,
            logo = movie.logo,
            isLive = false,
            startPositionMs = startPositionMs,
        )

        fun of(
            episode: EpisodeEntity,
            series: SeriesEntity?,
            startPositionMs: Long = 0L,
        ): PlaybackItem = PlaybackItem(
            id = episode.id,
            kind = ItemKind.EPISODE,
            title = series?.name ?: episode.title,
            subtitle = "S${episode.season} · F${episode.episode} · ${episode.title}",
            streamUrl = episode.streamUrl,
            logo = episode.cover.ifEmpty { series?.cover.orEmpty() },
            isLive = false,
            startPositionMs = startPositionMs,
            seriesId = episode.seriesId,
            season = episode.season,
            episode = episode.episode,
        )
    }
}

/** Ein Sender samt aktuell laufender Sendung — genau das, was eine Senderzeile zeigt. */
@Immutable
data class ChannelRow(
    val channel: ChannelEntity,
    val nowTitle: String = "",
    val nowStartAt: Long = 0L,
    val nowEndAt: Long = 0L,
    val nextTitle: String = "",
) {
    /**
     * Fortschritt der laufenden Sendung, berechnet gegen einen übergebenen
     * Zeitpunkt statt gegen `System.currentTimeMillis()`.
     *
     * Der Unterschied ist entscheidend: Eine Uhrzeit, die direkt im Zeichenpfad
     * gelesen wird, ist für Compose kein beobachtbarer Wert — der Balken bliebe
     * stehen, bis die Zeile aus anderem Grund neu gezeichnet wird. Die Zeit kommt
     * deshalb als Zustand von außen und tickt dort.
     */
    fun progress(now: Long): Float {
        if (nowStartAt <= 0L || nowEndAt <= nowStartAt) return 0f
        val span = (nowEndAt - nowStartAt).toFloat()
        val done = (now - nowStartAt).toFloat()
        return (done / span).coerceIn(0f, 1f)
    }

    val hasEpg: Boolean get() = nowTitle.isNotEmpty()
}

/** Gemeinsame Darstellung für Kacheln in Film- und Serienlisten. */
@Immutable
data class PosterItem(
    val id: String,
    val kind: ItemKind,
    val title: String,
    val imageUrl: String,
    val subtitle: String,
    val rating: Double,
)

fun MovieEntity.toPoster(): PosterItem = PosterItem(
    id = id,
    kind = ItemKind.MOVIE,
    title = name,
    imageUrl = logo,
    subtitle = year,
    rating = rating,
)

fun SeriesEntity.toPoster(): PosterItem = PosterItem(
    id = id,
    kind = ItemKind.SERIES,
    title = name,
    imageUrl = cover,
    subtitle = year,
    rating = rating,
)

/** Treffer der übergreifenden Suche, gruppiert nach Art. */
@Immutable
data class SearchResults(
    val channels: List<ChannelEntity> = emptyList(),
    val movies: List<MovieEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList(),
) {
    val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
    val total: Int get() = channels.size + movies.size + series.size
}

/** Ein Eintrag in „Weiterschauen" auf der Startseite. */
@Immutable
data class ContinueItem(
    val itemId: String,
    val kind: ItemKind,
    val title: String,
    val subtitle: String,
    val imageUrl: String,
    val positionMs: Long,
    val durationMs: Long,
) {
    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}
