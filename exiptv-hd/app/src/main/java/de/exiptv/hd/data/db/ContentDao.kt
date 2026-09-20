package de.exiptv.hd.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Alle Listenabfragen arbeiten mit Keyset-Blättern statt mit OFFSET.
 *
 * `LIMIT ? OFFSET ?` muss die übersprungenen Zeilen jedes Mal durchlaufen — beim
 * 500. Blatt einer Senderliste ist das eine halbe Tabelle pro Seite. Stattdessen
 * merkt sich der Pager das letzte Paar aus Sortierwert und ID und fragt „alles
 * danach" ab. Zusammen mit dem Index auf `(stage, categoryId, sortIndex, id)`
 * kostet damit das tausendste Blatt exakt so viel wie das erste.
 */
@Dao
interface ContentDao {

    // -- Kategorien ---------------------------------------------------------

    @Query(
        """
        SELECT * FROM categories
        WHERE stage = 0 AND kind = :kind
        ORDER BY sortIndex, name
        """
    )
    fun observeCategories(kind: ContentKind): Flow<List<CategoryEntity>>

    @Query("SELECT COUNT(*) FROM channels WHERE stage = 0 AND categoryId = :categoryId")
    suspend fun countChannelsInCategory(categoryId: String): Int

    // -- Sender -------------------------------------------------------------

    @Query(
        """
        SELECT * FROM channels
        WHERE stage = 0
          AND isAdult <= :adultMax
          AND (sortIndex > :afterSort OR (sortIndex = :afterSort AND id > :afterId))
          AND categoryId NOT IN (SELECT categoryId FROM hidden_categories)
        ORDER BY sortIndex, id
        LIMIT :limit
        """
    )
    suspend fun channelsAfter(afterSort: Int, afterId: String, adultMax: Int, limit: Int): List<ChannelEntity>

    @Query(
        """
        SELECT * FROM channels
        WHERE stage = 0
          AND categoryId = :categoryId
          AND isAdult <= :adultMax
          AND (sortIndex > :afterSort OR (sortIndex = :afterSort AND id > :afterId))
        ORDER BY sortIndex, id
        LIMIT :limit
        """
    )
    suspend fun channelsOfCategoryAfter(
        categoryId: String,
        afterSort: Int,
        afterId: String,
        adultMax: Int,
        limit: Int,
    ): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE stage = 0 AND id = :id LIMIT 1")
    suspend fun channelById(id: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE stage = 0 AND number = :number LIMIT 1")
    suspend fun channelByNumber(number: Int): ChannelEntity?

    @Query("SELECT * FROM channels WHERE stage = 0 AND id IN (:ids)")
    suspend fun channelsByIds(ids: List<String>): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channels WHERE stage = 0")
    fun observeChannelCount(): Flow<Int>

    /** Alle belegten EPG-Schlüssel — der XMLTV-Import verwirft damit fremdes Programm. */
    @Query("SELECT DISTINCT epgKey FROM channels WHERE stage = 0 AND epgKey <> ''")
    suspend fun allEpgKeys(): List<String>

    // -- Filme --------------------------------------------------------------

    @Query(
        """
        SELECT * FROM movies
        WHERE stage = 0
          AND isAdult <= :adultMax
          AND (:categoryId = '' OR categoryId = :categoryId)
          AND (sortIndex > :afterSort OR (sortIndex = :afterSort AND id > :afterId))
          AND categoryId NOT IN (SELECT categoryId FROM hidden_categories)
        ORDER BY sortIndex, id
        LIMIT :limit
        """
    )
    suspend fun moviesByNameAfter(
        categoryId: String,
        afterSort: Int,
        afterId: String,
        adultMax: Int,
        limit: Int,
    ): List<MovieEntity>

    @Query(
        """
        SELECT * FROM movies
        WHERE stage = 0
          AND isAdult <= :adultMax
          AND (:categoryId = '' OR categoryId = :categoryId)
          AND (yearOrder < :afterYear OR (yearOrder = :afterYear AND id > :afterId))
          AND categoryId NOT IN (SELECT categoryId FROM hidden_categories)
        ORDER BY yearOrder DESC, id ASC
        LIMIT :limit
        """
    )
    suspend fun moviesByYearAfter(
        categoryId: String,
        afterYear: Int,
        afterId: String,
        adultMax: Int,
        limit: Int,
    ): List<MovieEntity>

    @Query("SELECT * FROM movies WHERE stage = 0 AND id = :id LIMIT 1")
    suspend fun movieById(id: String): MovieEntity?

    @Query("SELECT COUNT(*) FROM movies WHERE stage = 0")
    fun observeMovieCount(): Flow<Int>

    // -- Serien -------------------------------------------------------------

    @Query(
        """
        SELECT * FROM series
        WHERE stage = 0
          AND isAdult <= :adultMax
          AND (:categoryId = '' OR categoryId = :categoryId)
          AND (sortIndex > :afterSort OR (sortIndex = :afterSort AND id > :afterId))
          AND categoryId NOT IN (SELECT categoryId FROM hidden_categories)
        ORDER BY sortIndex, id
        LIMIT :limit
        """
    )
    suspend fun seriesByNameAfter(
        categoryId: String,
        afterSort: Int,
        afterId: String,
        adultMax: Int,
        limit: Int,
    ): List<SeriesEntity>

    @Query(
        """
        SELECT * FROM series
        WHERE stage = 0
          AND isAdult <= :adultMax
          AND (:categoryId = '' OR categoryId = :categoryId)
          AND (yearOrder < :afterYear OR (yearOrder = :afterYear AND id > :afterId))
          AND categoryId NOT IN (SELECT categoryId FROM hidden_categories)
        ORDER BY yearOrder DESC, id ASC
        LIMIT :limit
        """
    )
    suspend fun seriesByYearAfter(
        categoryId: String,
        afterYear: Int,
        afterId: String,
        adultMax: Int,
        limit: Int,
    ): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE stage = 0 AND id = :id LIMIT 1")
    suspend fun seriesById(id: String): SeriesEntity?

    @Query("SELECT COUNT(*) FROM series WHERE stage = 0")
    fun observeSeriesCount(): Flow<Int>

    @Query("SELECT * FROM episodes WHERE stage = 0 AND seriesId = :seriesId ORDER BY season, episode")
    suspend fun episodesOfSeries(seriesId: String): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE stage = 0 AND id = :id LIMIT 1")
    suspend fun episodeById(id: String): EpisodeEntity?

    @Query("SELECT COUNT(*) FROM episodes WHERE stage = 0 AND seriesId = :seriesId")
    suspend fun episodeCount(seriesId: String): Int

    // -- Suche --------------------------------------------------------------
    //
    // `nameKey` ist kleingeschrieben und von Diakritika befreit. Die Suchanfrage
    // durchläuft dieselbe Normalisierung, wodurch `%` und `_` aus der Eingabe
    // verschwinden, bevor sie ein LIKE-Muster werden könnten. Deshalb braucht
    // keine dieser Abfragen ein ESCAPE.

    @Query(
        """
        SELECT * FROM channels
        WHERE stage = 0 AND isAdult <= :adultMax AND nameKey LIKE :prefix
        ORDER BY nameKey LIMIT :limit
        """
    )
    suspend fun searchChannelsPrefix(prefix: String, adultMax: Int, limit: Int): List<ChannelEntity>

    @Query(
        """
        SELECT * FROM channels
        WHERE stage = 0 AND isAdult <= :adultMax AND nameKey LIKE :contains
        ORDER BY nameKey LIMIT :limit
        """
    )
    suspend fun searchChannelsContains(contains: String, adultMax: Int, limit: Int): List<ChannelEntity>

    @Query(
        """
        SELECT * FROM movies
        WHERE stage = 0 AND isAdult <= :adultMax AND nameKey LIKE :prefix
        ORDER BY nameKey LIMIT :limit
        """
    )
    suspend fun searchMoviesPrefix(prefix: String, adultMax: Int, limit: Int): List<MovieEntity>

    @Query(
        """
        SELECT * FROM movies
        WHERE stage = 0 AND isAdult <= :adultMax AND nameKey LIKE :contains
        ORDER BY nameKey LIMIT :limit
        """
    )
    suspend fun searchMoviesContains(contains: String, adultMax: Int, limit: Int): List<MovieEntity>

    @Query(
        """
        SELECT * FROM series
        WHERE stage = 0 AND isAdult <= :adultMax AND nameKey LIKE :prefix
        ORDER BY nameKey LIMIT :limit
        """
    )
    suspend fun searchSeriesPrefix(prefix: String, adultMax: Int, limit: Int): List<SeriesEntity>

    @Query(
        """
        SELECT * FROM series
        WHERE stage = 0 AND isAdult <= :adultMax AND nameKey LIKE :contains
        ORDER BY nameKey LIMIT :limit
        """
    )
    suspend fun searchSeriesContains(contains: String, adultMax: Int, limit: Int): List<SeriesEntity>

    // -- Import -------------------------------------------------------------
    //
    // Der komplette Import-Pfad ist bewusst blockierend statt `suspend`.
    // Er läuft ohnehin ausschließlich auf Dispatchers.IO, und dort ist Blockieren
    // genau das vorgesehene Verhalten. Der Gewinn: Der Streaming-Parser kann jeden
    // fertigen Stapel unmittelbar schreiben, ohne dass jeder Rückruf durch eine
    // Coroutine-Grenze müsste. Ein Aufruf aus dem Hauptthread löst eine Ausnahme
    // von Room aus — das ist erwünscht, es wäre ein Fehler.
    //
    // Jede insert-Methode läuft in ihrer eigenen impliziten Transaktion und hält
    // die Schreibverbindung dadurch nur Millisekunden.

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertCategories(items: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertChannels(items: List<ChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMovies(items: List<MovieEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSeries(items: List<SeriesEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertEpisodes(items: List<EpisodeEntity>)

    @Query("DELETE FROM categories WHERE providerId = :providerId AND stage = :stage")
    fun clearCategories(providerId: Long, stage: Int)

    @Query("DELETE FROM channels WHERE providerId = :providerId AND stage = :stage")
    fun clearChannels(providerId: Long, stage: Int)

    @Query("DELETE FROM movies WHERE providerId = :providerId AND stage = :stage")
    fun clearMovies(providerId: Long, stage: Int)

    @Query("DELETE FROM series WHERE providerId = :providerId AND stage = :stage")
    fun clearSeries(providerId: Long, stage: Int)

    @Query("DELETE FROM episodes WHERE providerId = :providerId AND stage = :stage")
    fun clearEpisodes(providerId: Long, stage: Int)

    @Query("UPDATE categories SET stage = 0 WHERE providerId = :providerId AND stage = 1")
    fun promoteCategories(providerId: Long)

    @Query("UPDATE channels SET stage = 0 WHERE providerId = :providerId AND stage = 1")
    fun promoteChannels(providerId: Long)

    @Query("UPDATE movies SET stage = 0 WHERE providerId = :providerId AND stage = 1")
    fun promoteMovies(providerId: Long)

    @Query("UPDATE series SET stage = 0 WHERE providerId = :providerId AND stage = 1")
    fun promoteSeries(providerId: Long)

    @Query("UPDATE episodes SET stage = 0 WHERE providerId = :providerId AND stage = 1")
    fun promoteEpisodes(providerId: Long)

    @Query("SELECT COUNT(*) FROM channels WHERE providerId = :providerId AND stage = 1")
    fun stagedChannelCount(providerId: Long): Int

    @Query("SELECT COUNT(*) FROM movies WHERE providerId = :providerId AND stage = 1")
    fun stagedMovieCount(providerId: Long): Int

    @Query("SELECT COUNT(*) FROM series WHERE providerId = :providerId AND stage = 1")
    fun stagedSeriesCount(providerId: Long): Int

    /**
     * Der eigentliche Umschaltmoment. Das Einzige im gesamten Import, das eine
     * Transaktion braucht — und darin steckt ausschließlich SQL, kein Netzwerk,
     * kein Parsen, keine Wartezeit.
     */
    @Transaction
    fun promoteStage(providerId: Long) {
        clearCategories(providerId, Stage.ACTIVE)
        clearChannels(providerId, Stage.ACTIVE)
        clearMovies(providerId, Stage.ACTIVE)
        clearSeries(providerId, Stage.ACTIVE)
        clearEpisodes(providerId, Stage.ACTIVE)
        promoteCategories(providerId)
        promoteChannels(providerId)
        promoteMovies(providerId)
        promoteSeries(providerId)
        promoteEpisodes(providerId)
    }

    /** Räumt eine abgebrochene Import-Stufe auf, ohne den aktiven Katalog zu berühren. */
    @Transaction
    fun discardStage(providerId: Long) {
        clearCategories(providerId, Stage.IMPORT)
        clearChannels(providerId, Stage.IMPORT)
        clearMovies(providerId, Stage.IMPORT)
        clearSeries(providerId, Stage.IMPORT)
        clearEpisodes(providerId, Stage.IMPORT)
    }

    /** Entfernt alle Inhalte eines gelöschten Anbieters, beide Stufen. */
    @Transaction
    fun deleteAllOfProvider(providerId: Long) {
        clearCategories(providerId, Stage.ACTIVE)
        clearCategories(providerId, Stage.IMPORT)
        clearChannels(providerId, Stage.ACTIVE)
        clearChannels(providerId, Stage.IMPORT)
        clearMovies(providerId, Stage.ACTIVE)
        clearMovies(providerId, Stage.IMPORT)
        clearSeries(providerId, Stage.ACTIVE)
        clearSeries(providerId, Stage.IMPORT)
        clearEpisodes(providerId, Stage.ACTIVE)
        clearEpisodes(providerId, Stage.IMPORT)
    }
}
