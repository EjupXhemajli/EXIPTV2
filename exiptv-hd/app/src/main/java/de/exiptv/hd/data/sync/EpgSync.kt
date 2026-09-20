package de.exiptv.hd.data.sync

import de.exiptv.hd.core.Clock
import de.exiptv.hd.core.Diagnostics
import de.exiptv.hd.data.db.EpgDao
import de.exiptv.hd.data.db.ProviderDao
import de.exiptv.hd.data.db.ProviderEntity
import de.exiptv.hd.data.db.ProviderType
import de.exiptv.hd.data.epg.XmltvParser
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
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Programmzeitschrift aus XMLTV.
 *
 * Zwei Dinge geschehen hier bewusst getrennt: Erst wandert die Datei vollständig
 * auf die Platte, dann wird sie geparst. Das entkoppelt das Zeitlimit der
 * Netzwerkverbindung vom Parsen — bei mehreren hundert Megabyte über eine
 * schwankende Leitung ist sonst genau das die Stelle, an der ein Timeout zuschlägt,
 * nachdem schon 90 % geladen waren.
 */
class EpgSync(
    private val http: HttpEngine,
    private val epgDao: EpgDao,
    private val providerDao: ProviderDao,
    private val cacheDir: File,
) {

    companion object {
        private const val BATCH_SIZE = 2_000
        private const val MAX_EPG_BYTES = 512L * 1024 * 1024
    }

    private val mutex = Mutex()

    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    val isRunning: Boolean get() = mutex.isLocked

    /**
     * @param knownChannelKeys Schlüssel der vorhandenen Sender. Sendungen ohne
     *        passenden Sender werden verworfen, statt die Tabelle mit Programm zu
     *        füllen, das niemand sehen kann — bei manchen Anbietern ist das die
     *        Hälfte der Datei.
     */
    suspend fun sync(
        knownChannelKeys: Set<String>,
        daysAhead: Int,
        keepPastDays: Int = 1,
    ): Int = mutex.withLock {
        val providers = withContext(Dispatchers.IO) { providerDao.enabled() }
        if (providers.isEmpty()) return@withLock 0

        val now = System.currentTimeMillis()
        val windowStart = Clock.plusDays(now, -keepPastDays)
        val windowEnd = Clock.plusDays(now, daysAhead)
        var imported = 0

        for (provider in providers) {
            val url = epgUrlFor(provider)
            if (url.isEmpty()) continue
            try {
                _progress.value = "Programm von ${provider.name} wird geladen"
                imported += importFrom(url, provider, knownChannelKeys, windowStart, windowEnd)
            } catch (e: CancellationException) {
                _progress.value = null
                throw e
            } catch (e: Throwable) {
                Diagnostics.warn("EPG von ${provider.name} fehlgeschlagen: ${Diagnostics.describe(e)}")
            }
        }

        // Aufräumen läuft unabhängig davon, ob ein Import erfolgreich war.
        // Sonst wächst die Tabelle endlos, sobald ein Anbieter dauerhaft ausfällt.
        withContext(Dispatchers.IO) {
            runCatching { epgDao.deleteOlderThan(windowStart) }
        }

        _progress.value = null
        imported
    }

    private fun epgUrlFor(provider: ProviderEntity): String {
        if (provider.epgUrl.isNotBlank()) return provider.epgUrl.trim()
        if (provider.type != ProviderType.XTREAM) return ""
        val endpoint = XtreamEndpoint.parse(provider.url, provider.username, provider.password)
        if (!endpoint.isComplete) return ""
        return XtreamClient(http, endpoint, provider.userAgent).xmltvUrl()
    }

    private suspend fun importFrom(
        url: String,
        provider: ProviderEntity,
        knownChannelKeys: Set<String>,
        windowStart: Long,
        windowEnd: Long,
    ): Int = withContext(Dispatchers.IO) {
        val temp = File(cacheDir, "epg_${provider.id}.xml")
        try {
            download(url, provider.userAgent, temp)
            _progress.value = "Programm von ${provider.name} wird ausgewertet"

            var count = 0
            openPossiblyGzipped(temp).use { stream ->
                XmltvParser().parse(
                    input = stream,
                    knownChannelKeys = knownChannelKeys,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    batchSize = BATCH_SIZE,
                ) { batch ->
                    epgDao.insertBlocking(batch)
                    count += batch.size
                    _progress.value = "Programm von ${provider.name}: $count Sendungen"
                }
            }
            Diagnostics.info("EPG ${provider.name}: $count Sendungen übernommen")
            count
        } finally {
            runCatching { temp.delete() }
        }
    }

    private suspend fun download(url: String, userAgent: String, target: File) {
        http.fetch<Unit>(url, userAgent, client = http.epg, attempts = 2) { response ->
            val body = response.body ?: throw IOException("Leere EPG-Antwort")
            val declared = body.contentLength()
            if (declared > MAX_EPG_BYTES) {
                throw IOException("Die EPG-Datei ist größer als ${MAX_EPG_BYTES / (1024 * 1024)} MB")
            }
            target.parentFile?.mkdirs()
            var written = 0L
            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written > MAX_EPG_BYTES) {
                            throw IOException("Die EPG-Datei ist unerwartet groß und wurde abgebrochen")
                        }
                    }
                }
            }
        }
    }

    /**
     * Viele Panels liefern `xmltv.php` als gepackte Datei, ohne das im
     * Content-Encoding anzukündigen — OkHttp kann dann nicht transparent
     * entpacken. Die Magic Bytes sind die einzige verlässliche Auskunft.
     */
    private fun openPossiblyGzipped(file: File): InputStream {
        val raw = BufferedInputStream(file.inputStream(), 64 * 1024)
        raw.mark(2)
        val b0 = raw.read()
        val b1 = raw.read()
        raw.reset()
        val isGzip = b0 == 0x1f && b1 == 0x8b
        return if (isGzip) GZIPInputStream(raw, 64 * 1024) else raw
    }
}
