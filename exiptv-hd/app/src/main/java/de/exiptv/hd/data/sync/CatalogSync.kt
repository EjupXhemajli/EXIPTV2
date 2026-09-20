package de.exiptv.hd.data.sync

import de.exiptv.hd.core.Diagnostics
import de.exiptv.hd.data.db.ContentDao
import de.exiptv.hd.data.db.ContentKind
import de.exiptv.hd.data.db.ProviderDao
import de.exiptv.hd.data.db.ProviderEntity
import de.exiptv.hd.data.db.ProviderType
import de.exiptv.hd.data.m3u.M3uParser
import de.exiptv.hd.data.net.HttpEngine
import de.exiptv.hd.data.xtream.XtreamClient
import de.exiptv.hd.data.xtream.XtreamEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException

sealed interface SyncProgress {
    data object Idle : SyncProgress

    data class Running(
        val providerName: String,
        val step: String,
        val channels: Int = 0,
        val movies: Int = 0,
        val series: Int = 0,
    ) : SyncProgress

    data class Done(
        val providerName: String,
        val channels: Int,
        val movies: Int,
        val series: Int,
        val durationMs: Long,
    ) : SyncProgress

    data class Failed(val providerName: String, val message: String) : SyncProgress
}

data class SyncOutcome(
    val success: Boolean,
    val channels: Int = 0,
    val movies: Int = 0,
    val series: Int = 0,
    val message: String = "",
)

/**
 * Katalog-Import.
 *
 * Der zentrale Unterschied zur Vorgängerversion steckt in dem, was hier NICHT
 * passiert: Zu keinem Zeitpunkt läuft eine Netzwerkanfrage innerhalb einer
 * Datenbanktransaktion. Der Import schreibt fortlaufend in die Import-Stufe
 * (`stage = 1`), was die Datenbank in Millisekunden-Häppchen beschäftigt, und
 * ganz zum Schluss schaltet [ContentDao.promoteStage] in einer einzigen kurzen
 * Transaktion um.
 *
 * Daraus folgt eine Eigenschaft, die sich im Alltag deutlich bemerkbar macht:
 * Während ein Sync läuft, bleibt die App vollständig bedienbar — Sender schauen,
 * Favoriten setzen, Positionen speichern. Und bricht der Sync ab, bleibt der
 * bisherige Katalog unangetastet, statt dass der Nutzer vor einer leeren Liste steht.
 */
class CatalogSync(
    private val http: HttpEngine,
    private val contentDao: ContentDao,
    private val providerDao: ProviderDao,
) {

    companion object {
        /**
         * Stapelgröße beim Schreiben. 1.500 Zeilen sind groß genug, damit der
         * Aufwand pro Transaktion nicht ins Gewicht fällt, und klein genug, dass
         * ein Stapel auch auf schwacher Hardware in deutlich unter einer Sekunde
         * durchläuft — die Obergrenze für spürbares Stocken in der Oberfläche.
         */
        const val BATCH_SIZE = 1_500

        /** Obergrenze für eine heruntergeladene Playlist. */
        const val MAX_PLAYLIST_BYTES = 256L * 1024 * 1024
    }

    private val mutex = Mutex()

    private val _progress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    /** Läuft gerade ein Import? Die Oberfläche blendet danach ihre Sync-Schaltflächen aus. */
    val isRunning: Boolean get() = mutex.isLocked

    suspend fun syncAll(): List<SyncOutcome> {
        val providers = withContext(Dispatchers.IO) { providerDao.enabled() }
        if (providers.isEmpty()) return emptyList()
        return providers.map { sync(it) }
    }

    suspend fun sync(provider: ProviderEntity): SyncOutcome = mutex.withLock {
        val startedAt = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            providerDao.setSyncState(provider.id, "RUNNING", "", startedAt)
        }
        _progress.value = SyncProgress.Running(provider.name, "Verbindung wird geprüft")

        try {
            val outcome = withContext(Dispatchers.IO) {
                // Reste eines abgebrochenen Laufs entfernen, bevor neu geschrieben wird.
                contentDao.discardStage(provider.id)
                when (provider.type) {
                    ProviderType.XTREAM -> syncXtream(provider)
                    ProviderType.M3U -> syncM3u(provider)
                }
            }

            val duration = System.currentTimeMillis() - startedAt
            withContext(Dispatchers.IO) {
                providerDao.setSyncState(provider.id, "OK", outcome.message, System.currentTimeMillis())
            }
            _progress.value = SyncProgress.Done(
                providerName = provider.name,
                channels = outcome.channels,
                movies = outcome.movies,
                series = outcome.series,
                durationMs = duration,
            )
            Diagnostics.info(
                "Sync „${provider.name}“ fertig in ${duration / 1000}s — " +
                    "${outcome.channels} Sender, ${outcome.movies} Filme, ${outcome.series} Serien"
            )
            outcome
        } catch (e: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                contentDao.discardStage(provider.id)
                providerDao.setSyncState(provider.id, "IDLE", "Abgebrochen", System.currentTimeMillis())
            }
            _progress.value = SyncProgress.Idle
            throw e
        } catch (e: Throwable) {
            val message = Diagnostics.describe(e)
            Diagnostics.error("Sync „${provider.name}“ fehlgeschlagen", e)
            withContext(Dispatchers.IO) {
                // Wichtig: Nur die Import-Stufe wird verworfen. Der bisherige
                // Katalog bleibt vollständig erhalten und nutzbar.
                contentDao.discardStage(provider.id)
                providerDao.setSyncState(provider.id, "FAILED", message, System.currentTimeMillis())
            }
            _progress.value = SyncProgress.Failed(provider.name, message)
            SyncOutcome(success = false, message = message)
        }
    }

    // -----------------------------------------------------------------------
    // Xtream
    // -----------------------------------------------------------------------

    private suspend fun syncXtream(provider: ProviderEntity): SyncOutcome {
        val endpoint = XtreamEndpoint.parse(provider.url, provider.username, provider.password)
        if (!endpoint.isComplete) {
            throw IOException("Adresse, Benutzername oder Passwort fehlen")
        }
        val client = XtreamClient(http, endpoint, provider.userAgent)

        val account = client.account()
        if (!account.isActive) {
            throw IOException("Der Zugang ist beim Anbieter nicht aktiv (Status: ${account.status})")
        }
        providerDao.setAccountInfo(
            provider.id,
            account.expiresAt,
            account.maxConnections,
            account.activeConnections,
        )

        // -- Kategorien ------------------------------------------------------
        step(provider, "Kategorien werden geladen")
        val categoryNames = HashMap<String, String>(256)
        var categoryCount = 0
        for (kind in listOf(ContentKind.LIVE, ContentKind.VOD, ContentKind.SERIES)) {
            val categories = client.categories(provider.id, kind)
            if (categories.isNotEmpty()) {
                categories.chunked(BATCH_SIZE).forEach { contentDao.insertCategories(it) }
                categories.forEach { categoryNames[it.externalId] = it.name }
                categoryCount += categories.size
            }
        }

        // -- Sender ----------------------------------------------------------
        step(provider, "Sender werden geladen")
        // Gezählt wird, was tatsächlich geschrieben wurde — nicht, was der
        // Parser gesehen hat. Nur das ist die Zahl, die am Ende in der
        // Datenbank steht.
        var channels = 0
        client.streamLiveChannels(
            providerId = provider.id,
            categoryNames = categoryNames,
            startNumber = 0,
            batchSize = BATCH_SIZE,
        ) { batch ->
            contentDao.insertChannels(batch)
            channels += batch.size
            step(provider, "Sender werden geladen", channels = channels)
        }

        // -- Filme -----------------------------------------------------------
        step(provider, "Filme werden geladen", channels = channels)
        var movies = 0
        client.streamMovies(
            providerId = provider.id,
            categoryNames = categoryNames,
            batchSize = BATCH_SIZE,
        ) { batch ->
            contentDao.insertMovies(batch)
            movies += batch.size
            step(provider, "Filme werden geladen", channels = channels, movies = movies)
        }

        // -- Serien ----------------------------------------------------------
        step(provider, "Serien werden geladen", channels = channels, movies = movies)
        var series = 0
        client.streamSeries(
            providerId = provider.id,
            categoryNames = categoryNames,
            batchSize = BATCH_SIZE,
        ) { batch ->
            contentDao.insertSeries(batch)
            series += batch.size
            step(provider, "Serien werden geladen", channels = channels, movies = movies, series = series)
        }

        verifyAndPromote(provider, channels, movies, series)

        return SyncOutcome(
            success = true,
            channels = channels,
            movies = movies,
            series = series,
            message = "$channels Sender, $movies Filme, $series Serien, $categoryCount Kategorien",
        )
    }

    // -----------------------------------------------------------------------
    // M3U
    // -----------------------------------------------------------------------

    private suspend fun syncM3u(provider: ProviderEntity): SyncOutcome {
        step(provider, "Playlist wird geladen")
        val parser = M3uParser(provider.id)
        var channels = 0
        var categories = emptyList<de.exiptv.hd.data.db.CategoryEntity>()

        http.fetch<Unit>(provider.url, provider.userAgent, client = http.catalog) { response ->
            val body = response.body ?: throw IOException("Leere Antwort")
            val declaredLength = body.contentLength()
            if (declaredLength > MAX_PLAYLIST_BYTES) {
                throw IOException("Die Playlist ist größer als ${MAX_PLAYLIST_BYTES / (1024 * 1024)} MB")
            }
            val reader = BufferedReader(body.charStream(), 64 * 1024)
            val result = parser.parse(reader, BATCH_SIZE) { batch ->
                contentDao.insertChannels(batch)
                channels += batch.size
                step(provider, "Playlist wird gelesen", channels = channels)
            }
            categories = result.categories
        }

        if (categories.isNotEmpty()) {
            categories.chunked(BATCH_SIZE).forEach { contentDao.insertCategories(it) }
        }

        verifyAndPromote(provider, channels, 0, 0)
        return SyncOutcome(
            success = true,
            channels = channels,
            message = "$channels Sender, ${categories.size} Gruppen",
        )
    }

    // -----------------------------------------------------------------------

    /**
     * Prüft die Import-Stufe, bevor sie den bisherigen Katalog ersetzt.
     *
     * Ein Panel, das unter Last mit `[]` antwortet, würde sonst den kompletten
     * Katalog des Nutzers löschen. Ein Import ohne einen einzigen Eintrag gilt
     * deshalb als fehlgeschlagen — der alte Stand bleibt stehen.
     */
    private fun verifyAndPromote(provider: ProviderEntity, channels: Int, movies: Int, series: Int) {
        val staged = contentDao.stagedChannelCount(provider.id) +
            contentDao.stagedMovieCount(provider.id) +
            contentDao.stagedSeriesCount(provider.id)

        if (staged == 0) {
            throw IOException(
                "Der Anbieter hat keine Inhalte geliefert. Der bisherige Katalog bleibt erhalten."
            )
        }
        if (channels + movies + series != staged) {
            Diagnostics.warn("Gelesene und gespeicherte Anzahl weichen ab ($channels/$movies/$series vs. $staged)")
        }

        contentDao.promoteStage(provider.id)
    }

    private fun step(
        provider: ProviderEntity,
        label: String,
        channels: Int = 0,
        movies: Int = 0,
        series: Int = 0,
    ) {
        _progress.value = SyncProgress.Running(provider.name, label, channels, movies, series)
    }

    fun clearProgress() {
        _progress.value = SyncProgress.Idle
    }
}
