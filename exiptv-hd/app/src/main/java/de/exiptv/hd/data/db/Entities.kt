package de.exiptv.hd.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/**
 * Warum jede Inhaltstabelle eine `stage`-Spalte hat
 * ------------------------------------------------
 * Ein Katalog-Import ersetzt sechsstellige Zeilenzahlen. Würde man dafür löschen
 * und neu einfügen, müsste das in einer Transaktion passieren — und die hielte die
 * einzige Schreibverbindung von SQLite minutenlang besetzt, während im Hintergrund
 * noch HTTP läuft. Genau daran krankte die Vorgängerversion: ein laufender Sync
 * blockierte jedes Setzen eines Favoriten.
 *
 * Stattdessen schreibt der Import in dieselbe Tabelle, nur mit `stage = 1`, in
 * kleinen Stapeln und ganz ohne umschließende Transaktion. Erst wenn alle Daten
 * vollständig und geprüft vorliegen, tauscht [ContentDao.promoteStage] in einer
 * Transaktion von wenigen Sekunden:
 *
 *     DELETE FROM channels WHERE providerId = :p AND stage = 0
 *     UPDATE channels SET stage = 0 WHERE providerId = :p AND stage = 1
 *
 * Alle Lesezugriffe filtern auf `stage = 0` und sehen deshalb immer einen
 * vollständigen Katalog — entweder den alten oder den neuen, nie eine Mischung.
 * `stage` ist zugleich die führende Spalte jedes Index, kostet also nichts.
 */
object Stage {
    const val ACTIVE = 0
    const val IMPORT = 1
}

enum class ProviderType { XTREAM, M3U }

enum class ContentKind { LIVE, VOD, SERIES }

enum class ItemKind { CHANNEL, MOVIE, SERIES, EPISODE }

class DbConverters {
    @TypeConverter fun providerTypeToString(v: ProviderType): String = v.name
    @TypeConverter fun stringToProviderType(v: String): ProviderType =
        runCatching { ProviderType.valueOf(v) }.getOrDefault(ProviderType.XTREAM)

    @TypeConverter fun contentKindToString(v: ContentKind): String = v.name
    @TypeConverter fun stringToContentKind(v: String): ContentKind =
        runCatching { ContentKind.valueOf(v) }.getOrDefault(ContentKind.LIVE)

    @TypeConverter fun itemKindToString(v: ItemKind): String = v.name
    @TypeConverter fun stringToItemKind(v: String): ItemKind =
        runCatching { ItemKind.valueOf(v) }.getOrDefault(ItemKind.CHANNEL)
}

// ---------------------------------------------------------------------------
// Anbieter
// ---------------------------------------------------------------------------

@Entity(
    tableName = "providers",
    indices = [Index(value = ["sortIndex"])],
)
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val type: ProviderType,
    /** Basis-URL ohne Pfad, z. B. `http://panel.example.com:8080`. */
    val url: String,
    val username: String = "",
    val password: String = "",
    /** Optionale XMLTV-Adresse; leer bedeutet „Xtream-EPG verwenden". */
    val epgUrl: String = "",
    val userAgent: String = "",
    val enabled: Boolean = true,
    val sortIndex: Int = 0,
    val lastSyncAt: Long = 0L,
    /** IDLE, RUNNING, OK, FAILED — als Text, damit neue Zustände keine Migration brauchen. */
    val lastSyncStatus: String = "IDLE",
    val lastSyncMessage: String = "",
    val expiresAt: Long = 0L,
    val maxConnections: Int = 0,
    val activeConnections: Int = 0,
)

// ---------------------------------------------------------------------------
// Kategorien
// ---------------------------------------------------------------------------

@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["stage", "kind", "providerId", "sortIndex"], name = "idx_cat_main"),
        Index(value = ["providerId"], name = "idx_cat_provider"),
    ],
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val providerId: Long,
    val kind: ContentKind,
    val externalId: String,
    val name: String,
    val sortIndex: Int = 0,
    @ColumnInfo(defaultValue = "0") val stage: Int = Stage.ACTIVE,
)

// ---------------------------------------------------------------------------
// Live-Sender
// ---------------------------------------------------------------------------

@Entity(
    tableName = "channels",
    indices = [
        Index(value = ["stage", "sortIndex", "id"], name = "idx_ch_sort"),
        Index(value = ["stage", "categoryId", "sortIndex", "id"], name = "idx_ch_cat"),
        Index(value = ["stage", "isAdult", "sortIndex", "id"], name = "idx_ch_adult"),
        Index(value = ["stage", "nameKey"], name = "idx_ch_name"),
        Index(value = ["stage", "number"], name = "idx_ch_number"),
        Index(value = ["stage", "epgKey"], name = "idx_ch_epg"),
        Index(value = ["providerId"], name = "idx_ch_provider"),
    ],
)
data class ChannelEntity(
    @PrimaryKey val id: String,
    val providerId: Long,
    val number: Int,
    val name: String,
    /** Kleingeschriebener, diakritikfreier Name — die einzige Spalte, gegen die gesucht wird. */
    val nameKey: String,
    val logo: String = "",
    val categoryId: String = "",
    val groupTitle: String = "",
    val streamUrl: String,
    /** Normalisierter EPG-Schlüssel; verbindet den Sender mit [EpgEntry.channelKey]. */
    val epgKey: String = "",
    val catchupDays: Int = 0,
    val catchupSource: String = "",
    val isAdult: Boolean = false,
    val sortIndex: Int = 0,
    @ColumnInfo(defaultValue = "0") val stage: Int = Stage.ACTIVE,
)

// ---------------------------------------------------------------------------
// Filme
// ---------------------------------------------------------------------------

@Entity(
    tableName = "movies",
    indices = [
        Index(value = ["stage", "sortIndex", "id"], name = "idx_mv_sort"),
        Index(value = ["stage", "categoryId", "sortIndex", "id"], name = "idx_mv_cat"),
        Index(value = ["stage", "yearOrder", "id"], name = "idx_mv_year"),
        Index(value = ["stage", "categoryId", "yearOrder", "id"], name = "idx_mv_cat_year"),
        Index(value = ["stage", "nameKey"], name = "idx_mv_name"),
        Index(value = ["providerId"], name = "idx_mv_provider"),
    ],
)
data class MovieEntity(
    @PrimaryKey val id: String,
    val providerId: Long,
    val name: String,
    val nameKey: String,
    val logo: String = "",
    val categoryId: String = "",
    val streamUrl: String,
    val containerExt: String = "",
    val year: String = "",
    /** Jahr als Zahl für schnelles Sortieren; 0 bedeutet unbekannt. */
    val yearOrder: Int = 0,
    val rating: Double = 0.0,
    val plot: String = "",
    val durationMin: Int = 0,
    val addedAt: Long = 0L,
    val isAdult: Boolean = false,
    val sortIndex: Int = 0,
    @ColumnInfo(defaultValue = "0") val stage: Int = Stage.ACTIVE,
)

// ---------------------------------------------------------------------------
// Serien und Episoden
// ---------------------------------------------------------------------------

@Entity(
    tableName = "series",
    indices = [
        Index(value = ["stage", "sortIndex", "id"], name = "idx_sr_sort"),
        Index(value = ["stage", "categoryId", "sortIndex", "id"], name = "idx_sr_cat"),
        Index(value = ["stage", "yearOrder", "id"], name = "idx_sr_year"),
        Index(value = ["stage", "categoryId", "yearOrder", "id"], name = "idx_sr_cat_year"),
        Index(value = ["stage", "nameKey"], name = "idx_sr_name"),
        Index(value = ["providerId"], name = "idx_sr_provider"),
    ],
)
data class SeriesEntity(
    @PrimaryKey val id: String,
    val providerId: Long,
    val name: String,
    val nameKey: String,
    val cover: String = "",
    val categoryId: String = "",
    val plot: String = "",
    val year: String = "",
    val yearOrder: Int = 0,
    val rating: Double = 0.0,
    val isAdult: Boolean = false,
    val sortIndex: Int = 0,
    /** Externe Serien-ID beim Anbieter; für das Nachladen der Episoden. */
    val externalId: String = "",
    @ColumnInfo(defaultValue = "0") val stage: Int = Stage.ACTIVE,
)

@Entity(
    tableName = "episodes",
    indices = [
        Index(value = ["stage", "seriesId", "season", "episode"], name = "idx_ep_series"),
        Index(value = ["providerId"], name = "idx_ep_provider"),
    ],
)
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val seriesId: String,
    val providerId: Long,
    val season: Int,
    val episode: Int,
    val title: String,
    val streamUrl: String,
    val containerExt: String = "",
    val plot: String = "",
    val cover: String = "",
    val durationMin: Int = 0,
    val airDate: String = "",
    @ColumnInfo(defaultValue = "0") val stage: Int = Stage.ACTIVE,
)

// ---------------------------------------------------------------------------
// Programmzeitschrift
// ---------------------------------------------------------------------------

@Entity(
    tableName = "epg",
    indices = [
        Index(value = ["channelKey", "startAt"], unique = true, name = "idx_epg_key_start"),
        Index(value = ["startAt"], name = "idx_epg_start"),
        Index(value = ["endAt"], name = "idx_epg_end"),
    ],
)
data class EpgEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val channelKey: String,
    val startAt: Long,
    val endAt: Long,
    val title: String,
    val description: String = "",
)

// ---------------------------------------------------------------------------
// Nutzerdaten — überleben jeden Katalog-Import
// ---------------------------------------------------------------------------

@Entity(
    tableName = "favorites",
    indices = [Index(value = ["kind", "addedAt"], name = "idx_fav_kind")],
)
data class FavoriteEntity(
    @PrimaryKey val itemId: String,
    val kind: ItemKind,
    val addedAt: Long,
)

@Entity(
    tableName = "history",
    indices = [
        Index(value = ["updatedAt"], name = "idx_hist_updated"),
        Index(value = ["kind", "updatedAt"], name = "idx_hist_kind"),
        Index(value = ["seriesId", "updatedAt"], name = "idx_hist_series"),
    ],
)
data class HistoryEntity(
    @PrimaryKey val itemId: String,
    val kind: ItemKind,
    val name: String,
    val logo: String = "",
    /**
     * Die Wiedergabeadresse wird bewusst NICHT gespeichert: bei Xtream enthält sie
     * Benutzername und Passwort, und eine Historie überlebt das Löschen eines
     * Anbieters. Die Adresse wird beim Fortsetzen frisch aus dem Katalog geholt.
     */
    val seriesId: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long,
)

/** Vom Nutzer ausgeblendete Kategorien. Klein und selten geändert. */
@Entity(tableName = "hidden_categories")
data class HiddenCategoryEntity(
    @PrimaryKey val categoryId: String,
)
