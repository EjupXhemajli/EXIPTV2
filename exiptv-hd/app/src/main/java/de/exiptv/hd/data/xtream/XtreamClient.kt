package de.exiptv.hd.data.xtream

import de.exiptv.hd.core.Diagnostics
import de.exiptv.hd.core.Text
import de.exiptv.hd.data.db.CategoryEntity
import de.exiptv.hd.data.db.ChannelEntity
import de.exiptv.hd.data.db.ContentKind
import de.exiptv.hd.data.db.EpisodeEntity
import de.exiptv.hd.data.db.MovieEntity
import de.exiptv.hd.data.db.SeriesEntity
import de.exiptv.hd.data.db.Stage
import de.exiptv.hd.data.net.HttpEngine
import de.exiptv.hd.data.net.JsonStream
import java.io.IOException
import java.net.URLEncoder

/**
 * Zerlegt, was Nutzer tatsächlich in ein Adressfeld eintippen.
 *
 * In der Praxis landet dort alles: die nackte Panel-Adresse, eine vollständige
 * `player_api.php`-URL mit Zugangsdaten, oder ein `get.php`-M3U-Link. Alle drei
 * Formen führen hier zum selben Ergebnis, statt den Nutzer raten zu lassen,
 * welche Form die App erwartet.
 */
data class XtreamEndpoint(
    val baseUrl: String,
    val username: String,
    val password: String,
) {
    companion object {
        fun parse(rawUrl: String, fallbackUser: String = "", fallbackPassword: String = ""): XtreamEndpoint {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return XtreamEndpoint("", fallbackUser, fallbackPassword)

            val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
                trimmed
            } else {
                "http://$trimmed"
            }

            val uri = runCatching { java.net.URI(withScheme) }.getOrNull()
                ?: return XtreamEndpoint(withScheme.trimEnd('/'), fallbackUser, fallbackPassword)

            val port = if (uri.port > 0) ":${uri.port}" else ""
            val host = uri.host ?: return XtreamEndpoint(withScheme.trimEnd('/'), fallbackUser, fallbackPassword)
            val base = "${uri.scheme}://$host$port"

            val query = uri.rawQuery.orEmpty()
            val params = query.split('&')
                .mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0) null else {
                        val k = part.substring(0, idx).lowercase()
                        val v = runCatching { java.net.URLDecoder.decode(part.substring(idx + 1), "UTF-8") }
                            .getOrDefault(part.substring(idx + 1))
                        k to v
                    }
                }
                .toMap()

            return XtreamEndpoint(
                baseUrl = base,
                username = params["username"] ?: fallbackUser,
                password = params["password"] ?: fallbackPassword,
            )
        }
    }

    val isComplete: Boolean
        get() = baseUrl.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()
}

/** Was `get_account_info` über den Zugang verrät. */
data class XtreamAccount(
    val status: String,
    val expiresAt: Long,
    val maxConnections: Int,
    val activeConnections: Int,
    val serverProtocol: String,
    val timezone: String,
) {
    val isActive: Boolean get() = status.isEmpty() || status.equals("Active", ignoreCase = true)
}

/** Wie viel ein Import je Inhaltstyp geliefert hat. */
data class ImportCounts(
    var categories: Int = 0,
    var channels: Int = 0,
    var movies: Int = 0,
    var series: Int = 0,
    var episodes: Int = 0,
)

class XtreamClient(
    private val http: HttpEngine,
    private val endpoint: XtreamEndpoint,
    private val userAgent: String,
) {

    private fun api(action: String, extra: String = ""): String {
        val u = URLEncoder.encode(endpoint.username, "UTF-8")
        val p = URLEncoder.encode(endpoint.password, "UTF-8")
        val base = "${endpoint.baseUrl}/player_api.php?username=$u&password=$p"
        return if (action.isEmpty()) base else "$base&action=$action$extra"
    }

    fun xmltvUrl(): String {
        val u = URLEncoder.encode(endpoint.username, "UTF-8")
        val p = URLEncoder.encode(endpoint.password, "UTF-8")
        return "${endpoint.baseUrl}/xmltv.php?username=$u&password=$p"
    }

    // -- Kontostatus --------------------------------------------------------

    suspend fun account(): XtreamAccount = http.fetch(api(""), userAgent, client = http.quick) { response ->
        val body = response.body ?: throw IOException("Leere Antwort auf die Kontoabfrage")
        val map = JsonStream.readObject(body.charStream())
        XtreamAccount(
            status = map["user_info.status"].orEmpty(),
            expiresAt = Text.parseLongOrZero(map["user_info.exp_date"]) * 1000L,
            maxConnections = Text.parseIntOrZero(map["user_info.max_connections"]),
            activeConnections = Text.parseIntOrZero(map["user_info.active_cons"]),
            serverProtocol = map["server_info.server_protocol"].orEmpty(),
            timezone = map["server_info.timezone"].orEmpty(),
        )
    }

    // -- Kategorien ---------------------------------------------------------

    suspend fun categories(providerId: Long, kind: ContentKind): List<CategoryEntity> {
        val action = when (kind) {
            ContentKind.LIVE -> "get_live_categories"
            ContentKind.VOD -> "get_vod_categories"
            ContentKind.SERIES -> "get_series_categories"
        }
        val out = ArrayList<CategoryEntity>(64)
        http.fetch<Unit>(api(action), userAgent) { response ->
            val body = response.body ?: return@fetch
            var index = 0
            JsonStream.forEachObject(body.charStream()) { obj ->
                val external = obj["category_id"].orEmpty()
                val name = obj["category_name"].orEmpty().trim()
                if (external.isNotEmpty() && name.isNotEmpty()) {
                    out += CategoryEntity(
                        id = categoryId(providerId, kind, external),
                        providerId = providerId,
                        kind = kind,
                        externalId = external,
                        name = name,
                        sortIndex = index++,
                        stage = Stage.IMPORT,
                    )
                }
            }
        }
        return out
    }

    // -- Live-Sender --------------------------------------------------------

    /**
     * Streamt die Senderliste und übergibt sie in Stapeln von [batchSize].
     *
     * [onBatch] schreibt blockierend in die Datenbank; der gesamte Aufruf läuft
     * auf Dispatchers.IO. Dadurch überlappen Netzwerk und Datenbankschreiben
     * natürlich, und im Speicher liegt nie mehr als ein Stapel.
     */
    suspend fun streamLiveChannels(
        providerId: Long,
        categoryNames: Map<String, String>,
        startNumber: Int,
        batchSize: Int,
        onBatch: (List<ChannelEntity>) -> Unit,
    ): Int {
        var total = 0
        var number = startNumber
        val batch = ArrayList<ChannelEntity>(batchSize)

        http.fetch<Unit>(api("get_live_streams"), userAgent) { response ->
            val body = response.body ?: return@fetch
            JsonStream.forEachObject(body.charStream()) { obj ->
                val streamId = obj["stream_id"].orEmpty()
                val name = obj["name"].orEmpty().trim()
                if (streamId.isEmpty() || name.isEmpty()) return@forEachObject

                val externalCategory = obj["category_id"].orEmpty()
                val categoryName = categoryNames[externalCategory].orEmpty()
                val declaredNumber = Text.parseIntOrZero(obj["num"])
                val epgRaw = obj["epg_channel_id"].orEmpty().ifEmpty { name }

                batch += ChannelEntity(
                    id = itemId(providerId, "c", streamId),
                    providerId = providerId,
                    number = if (declaredNumber > 0) declaredNumber else ++number,
                    name = name,
                    nameKey = Text.normalizeKey(name),
                    logo = obj["stream_icon"].orEmpty(),
                    categoryId = if (externalCategory.isEmpty()) {
                        ""
                    } else {
                        categoryId(providerId, ContentKind.LIVE, externalCategory)
                    },
                    groupTitle = categoryName,
                    streamUrl = liveUrl(streamId),
                    epgKey = Text.epgKey(epgRaw),
                    catchupDays = Text.parseIntOrZero(obj["tv_archive_duration"]),
                    catchupSource = obj["tv_archive"].orEmpty(),
                    isAdult = Text.looksAdult(name, categoryName),
                    sortIndex = total,
                    stage = Stage.IMPORT,
                )
                total++

                if (batch.size >= batchSize) {
                    onBatch(ArrayList(batch))
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) onBatch(ArrayList(batch))
        return total
    }

    // -- Filme --------------------------------------------------------------

    suspend fun streamMovies(
        providerId: Long,
        categoryNames: Map<String, String>,
        batchSize: Int,
        onBatch: (List<MovieEntity>) -> Unit,
    ): Int {
        var total = 0
        val batch = ArrayList<MovieEntity>(batchSize)

        http.fetch<Unit>(api("get_vod_streams"), userAgent) { response ->
            val body = response.body ?: return@fetch
            JsonStream.forEachObject(body.charStream()) { obj ->
                val streamId = obj["stream_id"].orEmpty()
                val name = obj["name"].orEmpty().trim()
                if (streamId.isEmpty() || name.isEmpty()) return@forEachObject

                val externalCategory = obj["category_id"].orEmpty()
                val categoryName = categoryNames[externalCategory].orEmpty()
                val ext = obj["container_extension"].orEmpty().ifEmpty { "mp4" }
                val year = Text.parseYear(obj["year"] ?: obj["releaseDate"] ?: obj["release_date"] ?: name)

                batch += MovieEntity(
                    id = itemId(providerId, "m", streamId),
                    providerId = providerId,
                    name = name,
                    nameKey = Text.normalizeKey(name),
                    logo = obj["stream_icon"].orEmpty().ifEmpty { obj["cover"].orEmpty() },
                    categoryId = if (externalCategory.isEmpty()) {
                        ""
                    } else {
                        categoryId(providerId, ContentKind.VOD, externalCategory)
                    },
                    streamUrl = vodUrl(streamId, ext),
                    containerExt = ext,
                    year = if (year > 0) year.toString() else "",
                    yearOrder = year,
                    rating = Text.parseDoubleOrZero(obj["rating"]),
                    plot = obj["plot"].orEmpty(),
                    durationMin = Text.parseIntOrZero(obj["episode_run_time"]),
                    addedAt = Text.parseLongOrZero(obj["added"]) * 1000L,
                    isAdult = Text.looksAdult(name, categoryName),
                    sortIndex = total,
                    stage = Stage.IMPORT,
                )
                total++

                if (batch.size >= batchSize) {
                    onBatch(ArrayList(batch))
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) onBatch(ArrayList(batch))
        return total
    }

    // -- Serien -------------------------------------------------------------

    suspend fun streamSeries(
        providerId: Long,
        categoryNames: Map<String, String>,
        batchSize: Int,
        onBatch: (List<SeriesEntity>) -> Unit,
    ): Int {
        var total = 0
        val batch = ArrayList<SeriesEntity>(batchSize)

        http.fetch<Unit>(api("get_series"), userAgent) { response ->
            val body = response.body ?: return@fetch
            JsonStream.forEachObject(body.charStream()) { obj ->
                val seriesId = obj["series_id"].orEmpty()
                val name = obj["name"].orEmpty().trim()
                if (seriesId.isEmpty() || name.isEmpty()) return@forEachObject

                val externalCategory = obj["category_id"].orEmpty()
                val categoryName = categoryNames[externalCategory].orEmpty()
                val year = Text.parseYear(obj["year"] ?: obj["releaseDate"] ?: obj["release_date"] ?: name)

                batch += SeriesEntity(
                    id = itemId(providerId, "s", seriesId),
                    providerId = providerId,
                    name = name,
                    nameKey = Text.normalizeKey(name),
                    cover = obj["cover"].orEmpty(),
                    categoryId = if (externalCategory.isEmpty()) {
                        ""
                    } else {
                        categoryId(providerId, ContentKind.SERIES, externalCategory)
                    },
                    plot = obj["plot"].orEmpty(),
                    year = if (year > 0) year.toString() else "",
                    yearOrder = year,
                    rating = Text.parseDoubleOrZero(obj["rating"]),
                    isAdult = Text.looksAdult(name, categoryName),
                    sortIndex = total,
                    externalId = seriesId,
                    stage = Stage.IMPORT,
                )
                total++

                if (batch.size >= batchSize) {
                    onBatch(ArrayList(batch))
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) onBatch(ArrayList(batch))
        return total
    }

    /**
     * Episoden einer Serie.
     *
     * Bewusst erst beim Öffnen der Serie und nicht beim Katalog-Import: Ein
     * Anbieter mit 8.000 Serien bräuchte 8.000 Einzelabfragen, was jeden Sync
     * unbrauchbar lang machen würde. Die Episoden landen danach in der Datenbank
     * und stehen beim nächsten Mal sofort bereit.
     */
    suspend fun episodesOf(providerId: Long, seriesRowId: String, externalSeriesId: String): List<EpisodeEntity> {
        val out = ArrayList<EpisodeEntity>(64)
        http.fetch<Unit>(
            api("get_series_info", "&series_id=${URLEncoder.encode(externalSeriesId, "UTF-8")}"),
            userAgent,
            client = http.quick,
        ) { response ->
            val body = response.body ?: return@fetch
            val reader = android.util.JsonReader(body.charStream())
            reader.isLenient = true
            reader.use {
                if (it.peek() != android.util.JsonToken.BEGIN_OBJECT) return@use
                it.beginObject()
                while (it.hasNext()) {
                    when (it.nextName()) {
                        "episodes" -> readEpisodeSeasons(it, providerId, seriesRowId, out)
                        else -> it.skipValue()
                    }
                }
                it.endObject()
            }
        }
        out.sortWith(compareBy({ it.season }, { it.episode }))
        return out
    }

    private fun readEpisodeSeasons(
        reader: android.util.JsonReader,
        providerId: Long,
        seriesRowId: String,
        out: MutableList<EpisodeEntity>,
    ) {
        when (reader.peek()) {
            // Üblich: { "1": [ ... ], "2": [ ... ] }
            android.util.JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    val seasonName = reader.nextName()
                    val season = Text.parseIntOrZero(seasonName)
                    readEpisodeArray(reader, providerId, seriesRowId, season, out)
                }
                reader.endObject()
            }
            // Manche Panels liefern stattdessen ein flaches Array.
            android.util.JsonToken.BEGIN_ARRAY -> readEpisodeArray(reader, providerId, seriesRowId, 0, out)
            else -> reader.skipValue()
        }
    }

    private fun readEpisodeArray(
        reader: android.util.JsonReader,
        providerId: Long,
        seriesRowId: String,
        seasonHint: Int,
        out: MutableList<EpisodeEntity>,
    ) {
        if (reader.peek() != android.util.JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return
        }
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != android.util.JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            val obj = JsonStream.readFlatObject(reader)
            val episodeId = obj["id"].orEmpty()
            if (episodeId.isEmpty()) continue
            val ext = obj["container_extension"].orEmpty().ifEmpty { "mp4" }
            val season = Text.parseIntOrZero(obj["season"]).takeIf { it > 0 } ?: seasonHint
            val number = Text.parseIntOrZero(obj["episode_num"])
            val title = obj["title"].orEmpty().trim().ifEmpty { "Folge $number" }

            out += EpisodeEntity(
                id = itemId(providerId, "e", episodeId),
                seriesId = seriesRowId,
                providerId = providerId,
                season = season,
                episode = number,
                title = title,
                streamUrl = seriesUrl(episodeId, ext),
                containerExt = ext,
                plot = obj["info.plot"].orEmpty().ifEmpty { obj["plot"].orEmpty() },
                cover = obj["info.movie_image"].orEmpty().ifEmpty { obj["info.cover_big"].orEmpty() },
                durationMin = Text.parseIntOrZero(obj["info.duration_secs"]).let { if (it > 0) it / 60 else 0 },
                airDate = obj["info.releasedate"].orEmpty().ifEmpty { obj["info.air_date"].orEmpty() },
                stage = Stage.ACTIVE,
            )
        }
        reader.endArray()
    }

    // -- Adressen -----------------------------------------------------------

    private fun credentialsPath(): String {
        val u = URLEncoder.encode(endpoint.username, "UTF-8")
        val p = URLEncoder.encode(endpoint.password, "UTF-8")
        return "$u/$p"
    }

    fun liveUrl(streamId: String): String = "${endpoint.baseUrl}/live/${credentialsPath()}/$streamId.ts"

    fun liveUrlHls(streamId: String): String = "${endpoint.baseUrl}/live/${credentialsPath()}/$streamId.m3u8"

    fun vodUrl(streamId: String, ext: String): String =
        "${endpoint.baseUrl}/movie/${credentialsPath()}/$streamId.$ext"

    fun seriesUrl(episodeId: String, ext: String): String =
        "${endpoint.baseUrl}/series/${credentialsPath()}/$episodeId.$ext"

    /**
     * Catchup-Adresse für eine vergangene Sendung.
     * `timeshift.php` ist das Format, das die verbreiteten Panels verstehen.
     */
    fun catchupUrl(streamId: String, startMs: Long, durationMinutes: Int): String {
        val start = de.exiptv.hd.core.Clock.epgTimestamp(startMs)
        return "${endpoint.baseUrl}/timeshift/${credentialsPath()}/$durationMinutes/$start/$streamId.ts"
    }

    companion object {
        fun categoryId(providerId: Long, kind: ContentKind, externalId: String): String =
            "p$providerId:${kind.name.lowercase()}:$externalId"

        fun itemId(providerId: Long, prefix: String, externalId: String): String =
            "p$providerId:$prefix:$externalId"
    }
}

/** Hilfsfunktion für den Umgang mit Ausnahmen im Import; hält Aufrufstellen kurz. */
internal inline fun <T> guarded(what: String, block: () -> T): T? =
    try {
        block()
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Diagnostics.warn("$what übersprungen: ${Diagnostics.describe(e)}")
        null
    }
