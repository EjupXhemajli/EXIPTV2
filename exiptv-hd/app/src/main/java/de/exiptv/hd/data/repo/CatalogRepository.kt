package de.exiptv.hd.data.repo

import de.exiptv.hd.core.Diagnostics
import de.exiptv.hd.core.Text
import de.exiptv.hd.data.db.CategoryEntity
import de.exiptv.hd.data.db.ChannelEntity
import de.exiptv.hd.data.db.ContentDao
import de.exiptv.hd.data.db.ContentKind
import de.exiptv.hd.data.db.EpgDao
import de.exiptv.hd.data.db.EpgEntry
import de.exiptv.hd.data.db.EpisodeEntity
import de.exiptv.hd.data.db.FavoriteEntity
import de.exiptv.hd.data.db.HistoryEntity
import de.exiptv.hd.data.db.ItemKind
import de.exiptv.hd.data.db.MovieEntity
import de.exiptv.hd.data.db.ProviderDao
import de.exiptv.hd.data.db.SeriesEntity
import de.exiptv.hd.data.db.UserDataDao
import de.exiptv.hd.data.net.HttpEngine
import de.exiptv.hd.data.xtream.XtreamClient
import de.exiptv.hd.data.xtream.XtreamEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

enum class SortMode(val label: String) {
    DEFAULT("Anbieterreihenfolge"),
    NEWEST("Neueste zuerst"),
}

/**
 * Der einzige Zugang der Oberfläche zum Katalog.
 *
 * Kein Screen spricht direkt mit einem DAO. Das hält die SQL-Abfragen an einer
 * Stelle zusammen und sorgt dafür, dass Entscheidungen wie „Erwachseneninhalte
 * ausblenden" oder „Suchbegriff normalisieren" nicht an fünf Stellen halb
 * umgesetzt werden.
 */
class CatalogRepository(
    private val contentDao: ContentDao,
    private val userDao: UserDataDao,
    private val epgDao: EpgDao,
    private val providerDao: ProviderDao,
    private val http: HttpEngine,
) {

    companion object {
        private const val SEARCH_LIMIT_PER_KIND = 40
        private const val EPG_CHUNK = 400
    }

    // -- Kategorien ---------------------------------------------------------

    fun observeCategories(kind: ContentKind): Flow<List<CategoryEntity>> = contentDao.observeCategories(kind)

    fun observeHiddenCategories(): Flow<List<String>> = userDao.observeHiddenCategories()

    suspend fun setCategoryHidden(categoryId: String, hidden: Boolean) = withContext(Dispatchers.IO) {
        userDao.setCategoryHidden(categoryId, hidden)
    }

    // -- Blätterung ---------------------------------------------------------

    fun channelPager(scope: CoroutineScope, categoryId: String, adultMax: Int): KeysetPager<ChannelEntity> =
        KeysetPager(
            scope = scope,
            sortOf = { it.sortIndex },
            idOf = { it.id },
        ) { afterSort, afterId, limit ->
            withContext(Dispatchers.IO) {
                if (categoryId.isEmpty()) {
                    contentDao.channelsAfter(afterSort, afterId, adultMax, limit)
                } else {
                    contentDao.channelsOfCategoryAfter(categoryId, afterSort, afterId, adultMax, limit)
                }
            }
        }

    fun moviePager(
        scope: CoroutineScope,
        categoryId: String,
        sort: SortMode,
        adultMax: Int,
    ): KeysetPager<MovieEntity> = when (sort) {
        SortMode.DEFAULT -> KeysetPager(
            scope = scope,
            sortOf = { it.sortIndex },
            idOf = { it.id },
        ) { afterSort, afterId, limit ->
            withContext(Dispatchers.IO) {
                contentDao.moviesByNameAfter(categoryId, afterSort, afterId, adultMax, limit)
            }
        }

        SortMode.NEWEST -> KeysetPager(
            scope = scope,
            initialKey = Int.MAX_VALUE,
            sortOf = { it.yearOrder },
            idOf = { it.id },
        ) { afterYear, afterId, limit ->
            withContext(Dispatchers.IO) {
                contentDao.moviesByYearAfter(categoryId, afterYear, afterId, adultMax, limit)
            }
        }
    }

    fun seriesPager(
        scope: CoroutineScope,
        categoryId: String,
        sort: SortMode,
        adultMax: Int,
    ): KeysetPager<SeriesEntity> = when (sort) {
        SortMode.DEFAULT -> KeysetPager(
            scope = scope,
            sortOf = { it.sortIndex },
            idOf = { it.id },
        ) { afterSort, afterId, limit ->
            withContext(Dispatchers.IO) {
                contentDao.seriesByNameAfter(categoryId, afterSort, afterId, adultMax, limit)
            }
        }

        SortMode.NEWEST -> KeysetPager(
            scope = scope,
            initialKey = Int.MAX_VALUE,
            sortOf = { it.yearOrder },
            idOf = { it.id },
        ) { afterYear, afterId, limit ->
            withContext(Dispatchers.IO) {
                contentDao.seriesByYearAfter(categoryId, afterYear, afterId, adultMax, limit)
            }
        }
    }

    // -- Einzelzugriffe -----------------------------------------------------

    suspend fun channel(id: String): ChannelEntity? = withContext(Dispatchers.IO) { contentDao.channelById(id) }

    suspend fun channelByNumber(number: Int): ChannelEntity? =
        withContext(Dispatchers.IO) { contentDao.channelByNumber(number) }

    suspend fun movie(id: String): MovieEntity? = withContext(Dispatchers.IO) { contentDao.movieById(id) }

    suspend fun series(id: String): SeriesEntity? = withContext(Dispatchers.IO) { contentDao.seriesById(id) }

    fun observeChannelCount(): Flow<Int> = contentDao.observeChannelCount()
    fun observeMovieCount(): Flow<Int> = contentDao.observeMovieCount()
    fun observeSeriesCount(): Flow<Int> = contentDao.observeSeriesCount()

    /**
     * Episoden einer Serie. Liegen sie in der Datenbank, werden sie sofort
     * geliefert; sonst werden sie einmalig beim Anbieter geholt und gespeichert.
     *
     * Beim Katalog-Import bleiben Episoden bewusst außen vor: Ein Anbieter mit
     * 8.000 Serien bräuchte 8.000 Einzelabfragen, was jeden Sync unbrauchbar lang
     * machen würde. Hier kostet es eine Abfrage beim ersten Öffnen einer Serie.
     */
    suspend fun episodes(seriesRowId: String): List<EpisodeEntity> = withContext(Dispatchers.IO) {
        val cached = contentDao.episodesOfSeries(seriesRowId)
        if (cached.isNotEmpty()) return@withContext cached

        val series = contentDao.seriesById(seriesRowId) ?: return@withContext emptyList()
        if (series.externalId.isEmpty()) return@withContext emptyList()
        val provider = providerDao.byId(series.providerId) ?: return@withContext emptyList()

        val endpoint = XtreamEndpoint.parse(provider.url, provider.username, provider.password)
        if (!endpoint.isComplete) return@withContext emptyList()

        val fetched = runCatching {
            XtreamClient(http, endpoint, provider.userAgent)
                .episodesOf(provider.id, seriesRowId, series.externalId)
        }.getOrElse { e ->
            Diagnostics.warn("Episoden von „${series.name}“ nicht abrufbar: ${Diagnostics.describe(e)}")
            emptyList()
        }

        if (fetched.isNotEmpty()) {
            fetched.chunked(500).forEach { contentDao.insertEpisodes(it) }
        }
        fetched
    }

    suspend fun episode(id: String): EpisodeEntity? = withContext(Dispatchers.IO) { contentDao.episodeById(id) }

    // -- Suche --------------------------------------------------------------

    /**
     * Zweistufige Suche: erst Treffer, die mit dem Begriff beginnen — die kann die
     * Datenbank über den Index auf `nameKey` direkt anspringen — danach aufgefüllt
     * mit Treffern, die ihn irgendwo enthalten. Das hält die häufige Eingabe
     * schnell und liefert trotzdem vollständige Ergebnisse.
     */
    suspend fun search(rawQuery: String, adultMax: Int): SearchResults = withContext(Dispatchers.IO) {
        val key = Text.normalizeKey(rawQuery)
        if (key.length < 2) return@withContext SearchResults()

        val prefix = "$key%"
        val contains = "%$key%"
        val limit = SEARCH_LIMIT_PER_KIND

        val channels = merge(
            contentDao.searchChannelsPrefix(prefix, adultMax, limit),
            contentDao.searchChannelsContains(contains, adultMax, limit),
            limit,
        ) { it.id }

        val movies = merge(
            contentDao.searchMoviesPrefix(prefix, adultMax, limit),
            contentDao.searchMoviesContains(contains, adultMax, limit),
            limit,
        ) { it.id }

        val series = merge(
            contentDao.searchSeriesPrefix(prefix, adultMax, limit),
            contentDao.searchSeriesContains(contains, adultMax, limit),
            limit,
        ) { it.id }

        SearchResults(channels, movies, series)
    }

    private inline fun <T> merge(first: List<T>, second: List<T>, limit: Int, idOf: (T) -> String): List<T> {
        if (first.size >= limit) return first
        val seen = HashSet<String>(first.size * 2)
        val out = ArrayList<T>(limit)
        for (item in first) {
            if (seen.add(idOf(item))) out += item
        }
        for (item in second) {
            if (out.size >= limit) break
            if (seen.add(idOf(item))) out += item
        }
        return out
    }

    // -- Favoriten ----------------------------------------------------------

    fun observeFavoriteIds(): Flow<List<String>> = userDao.observeFavoriteIds()

    fun observeFavorites(limit: Int = 200): Flow<List<FavoriteEntity>> = userDao.observeFavorites(limit)

    suspend fun toggleFavorite(itemId: String, kind: ItemKind): Boolean = withContext(Dispatchers.IO) {
        val isFav = userDao.isFavorite(itemId)
        if (isFav) {
            userDao.removeFavorite(itemId)
            false
        } else {
            userDao.addFavorite(FavoriteEntity(itemId, kind, System.currentTimeMillis()))
            true
        }
    }

    suspend fun favoriteChannels(): List<ChannelEntity> = withContext(Dispatchers.IO) {
        val ids = userDao.favoriteIdsOnce()
        if (ids.isEmpty()) return@withContext emptyList()
        // In Blöcken, damit die IN-Liste nicht über SQLites Parametergrenze wächst.
        ids.chunked(500).flatMap { contentDao.channelsByIds(it) }
    }

    // -- Verlauf ------------------------------------------------------------

    fun observeContinueWatching(limit: Int = 20): Flow<List<HistoryEntity>> = userDao.observeRecentSingles(limit)

    fun observeContinueSeries(limit: Int = 20): Flow<List<HistoryEntity>> = userDao.observeRecentSeries(limit)

    suspend fun savePosition(item: PlaybackItem, positionMs: Long, durationMs: Long) = withContext(Dispatchers.IO) {
        if (item.isLive) return@withContext
        if (durationMs > 0L && positionMs > durationMs - 60_000L) {
            // Fast zu Ende gesehen — als erledigt behandeln statt beim Abspann fortzusetzen.
            userDao.removeHistory(item.id)
            return@withContext
        }
        if (positionMs < 30_000L) return@withContext

        userDao.putHistory(
            HistoryEntity(
                itemId = item.id,
                kind = item.kind,
                name = item.title,
                logo = item.logo,
                seriesId = item.seriesId,
                season = item.season,
                episode = item.episode,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = System.currentTimeMillis(),
            )
        )
        userDao.trimHistory(500)
    }

    suspend fun resumePosition(itemId: String): Long = withContext(Dispatchers.IO) {
        userDao.historyFor(itemId)?.positionMs ?: 0L
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) { userDao.clearHistory() }

    suspend fun removeFromHistory(itemId: String) = withContext(Dispatchers.IO) { userDao.removeHistory(itemId) }

    // -- Programmzeitschrift ------------------------------------------------

    /**
     * Reichert eine Senderliste mit der laufenden und der folgenden Sendung an.
     *
     * Die Abfrage läuft in Blöcken von [EPG_CHUNK] Kennungen, weil SQLite höchstens
     * 999 Parameter pro Anweisung erlaubt. Alles Weitere passiert im Speicher —
     * eine Abfrage pro Block statt einer pro Sender.
     */
    suspend fun withEpg(channels: List<ChannelEntity>, offsetMinutes: Int): List<ChannelRow> =
        withContext(Dispatchers.IO) {
            if (channels.isEmpty()) return@withContext emptyList()
            val now = System.currentTimeMillis() + offsetMinutes * 60_000L
            val keys = channels.map { it.epgKey }.filter { it.isNotEmpty() }.distinct()
            if (keys.isEmpty()) return@withContext channels.map { ChannelRow(it) }

            val entries = HashMap<String, MutableList<EpgEntry>>(keys.size * 2)
            for (chunk in keys.chunked(EPG_CHUNK)) {
                val rows = runCatching {
                    epgDao.inWindow(chunk, now - 4 * 60 * 60_000L, now + 8 * 60 * 60_000L)
                }.getOrDefault(emptyList())
                for (row in rows) {
                    entries.getOrPut(row.channelKey) { ArrayList(4) }.add(row)
                }
            }

            channels.map { channel ->
                val list = entries[channel.epgKey]
                if (list.isNullOrEmpty()) {
                    ChannelRow(channel)
                } else {
                    list.sortBy { it.startAt }
                    val current = list.firstOrNull { it.startAt <= now && it.endAt > now }
                    val next = list.firstOrNull { it.startAt > now }
                    ChannelRow(
                        channel = channel,
                        nowTitle = current?.title.orEmpty(),
                        nowStartAt = current?.startAt ?: 0L,
                        nowEndAt = current?.endAt ?: 0L,
                        nextTitle = next?.title.orEmpty(),
                    )
                }
            }
        }

    suspend fun currentProgramme(epgKey: String, offsetMinutes: Int): EpgEntry? = withContext(Dispatchers.IO) {
        if (epgKey.isEmpty()) return@withContext null
        epgDao.currentFor(epgKey, System.currentTimeMillis() + offsetMinutes * 60_000L)
    }

    suspend fun upcoming(epgKey: String, offsetMinutes: Int, limit: Int = 30): List<EpgEntry> =
        withContext(Dispatchers.IO) {
            if (epgKey.isEmpty()) return@withContext emptyList()
            epgDao.upcoming(epgKey, System.currentTimeMillis() + offsetMinutes * 60_000L, limit)
        }

    /** Alle Senderschlüssel — der EPG-Import verwirft damit fremdes Programm. */
    suspend fun allEpgKeys(): Set<String> = withContext(Dispatchers.IO) {
        runCatching { contentDao.allEpgKeys().toHashSet() }.getOrDefault(HashSet())
    }

    suspend fun epgCount(): Int = withContext(Dispatchers.IO) { runCatching { epgDao.count() }.getOrDefault(0) }
}
