package de.exiptv.hd.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {

    @Query("SELECT * FROM providers ORDER BY sortIndex, id")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY sortIndex, id")
    suspend fun all(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE enabled = 1 ORDER BY sortIndex, id")
    suspend fun enabled(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): ProviderEntity?

    @Query("SELECT COUNT(*) FROM providers")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(provider: ProviderEntity): Long

    @Update
    suspend fun update(provider: ProviderEntity)

    @Delete
    suspend fun delete(provider: ProviderEntity)

    @Query(
        """
        UPDATE providers
        SET lastSyncStatus = :status, lastSyncMessage = :message, lastSyncAt = :at
        WHERE id = :id
        """
    )
    suspend fun setSyncState(id: Long, status: String, message: String, at: Long)

    @Query(
        """
        UPDATE providers
        SET expiresAt = :expiresAt, maxConnections = :maxConnections, activeConnections = :activeConnections
        WHERE id = :id
        """
    )
    suspend fun setAccountInfo(id: Long, expiresAt: Long, maxConnections: Int, activeConnections: Int)
}

@Dao
interface EpgDao {

    /**
     * Die Sendungen mehrerer Sender für ein Zeitfenster. Der Aufrufer teilt die
     * Senderliste in Blöcke, damit die IN-Liste nicht über SQLites Parametergrenze
     * von 999 wächst.
     */
    @Query(
        """
        SELECT * FROM epg
        WHERE channelKey IN (:channelKeys) AND endAt > :from AND startAt < :to
        ORDER BY channelKey, startAt
        """
    )
    suspend fun inWindow(channelKeys: List<String>, from: Long, to: Long): List<EpgEntry>

    @Query(
        """
        SELECT * FROM epg
        WHERE channelKey = :channelKey AND endAt > :now
        ORDER BY startAt
        LIMIT :limit
        """
    )
    suspend fun upcoming(channelKey: String, now: Long, limit: Int): List<EpgEntry>

    @Query(
        """
        SELECT * FROM epg
        WHERE channelKey = :channelKey AND startAt <= :now AND endAt > :now
        LIMIT 1
        """
    )
    suspend fun currentFor(channelKey: String, now: Long): EpgEntry?

    @Query("SELECT COUNT(*) FROM epg")
    suspend fun count(): Int

    @Query("SELECT MAX(endAt) FROM epg")
    suspend fun latestEnd(): Long?

    /**
     * Blockierend, wie der gesamte Import-Pfad: Der XMLTV-Parser schreibt jeden
     * fertigen Stapel unmittelbar weg, und das geschieht ohnehin auf Dispatchers.IO.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertBlocking(entries: List<EpgEntry>)

    @Query("DELETE FROM epg WHERE endAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM epg")
    suspend fun deleteAll()
}

@Dao
interface UserDataDao {

    // -- Favoriten ----------------------------------------------------------

    @Query("SELECT itemId FROM favorites")
    fun observeFavoriteIds(): Flow<List<String>>

    @Query("SELECT itemId FROM favorites ORDER BY addedAt DESC")
    suspend fun favoriteIdsOnce(): List<String>

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC LIMIT :limit")
    fun observeFavorites(limit: Int): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE itemId = :itemId)")
    suspend fun isFavorite(itemId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE itemId = :itemId")
    suspend fun removeFavorite(itemId: String)

    // -- Verlauf ------------------------------------------------------------
    //
    // „Zuletzt gesehen" ist bewusst eine flache Abfrage ohne korrelierte
    // Unterabfrage: In der Vorgängerversion lief für jede Zeile ein eigener Scan
    // über den gesamten Verlauf — bei jeder gespeicherten Wiedergabeposition neu.
    // Hier übernimmt GROUP BY die Gruppierung pro Serie, gestützt auf den Index
    // auf (seriesId, updatedAt).

    @Query(
        """
        SELECT * FROM history
        WHERE kind = 'MOVIE' OR kind = 'CHANNEL'
        ORDER BY updatedAt DESC
        LIMIT :limit
        """
    )
    fun observeRecentSingles(limit: Int): Flow<List<HistoryEntity>>

    @Query(
        """
        SELECT * FROM history
        WHERE kind = 'EPISODE' AND seriesId <> ''
          AND updatedAt = (
              SELECT MAX(h2.updatedAt) FROM history h2
              WHERE h2.seriesId = history.seriesId AND h2.kind = 'EPISODE'
          )
        ORDER BY updatedAt DESC
        LIMIT :limit
        """
    )
    fun observeRecentSeries(limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE itemId = :itemId LIMIT 1")
    suspend fun historyFor(itemId: String): HistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putHistory(entity: HistoryEntity)

    @Query("DELETE FROM history WHERE itemId = :itemId")
    suspend fun removeHistory(itemId: String)

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    /**
     * Hält den Verlauf klein. Ohne diese Begrenzung wächst die Tabelle unbegrenzt,
     * und genau das machte in der Vorgängerversion die Startseite mit der Zeit
     * immer langsamer.
     */
    @Query(
        """
        DELETE FROM history WHERE itemId IN (
            SELECT itemId FROM history ORDER BY updatedAt DESC LIMIT -1 OFFSET :keep
        )
        """
    )
    suspend fun trimHistory(keep: Int)

    // -- Ausgeblendete Kategorien -------------------------------------------

    @Query("SELECT categoryId FROM hidden_categories")
    fun observeHiddenCategories(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hideCategory(entity: HiddenCategoryEntity)

    @Query("DELETE FROM hidden_categories WHERE categoryId = :categoryId")
    suspend fun unhideCategory(categoryId: String)

    @Query("DELETE FROM hidden_categories")
    suspend fun clearHiddenCategories()

    @Transaction
    suspend fun setCategoryHidden(categoryId: String, hidden: Boolean) {
        if (hidden) hideCategory(HiddenCategoryEntity(categoryId)) else unhideCategory(categoryId)
    }
}
