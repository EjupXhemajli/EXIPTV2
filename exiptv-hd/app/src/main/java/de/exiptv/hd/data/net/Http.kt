package de.exiptv.hd.data.net

import de.exiptv.hd.core.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Ein Anbieter akzeptiert manchmal nur bestimmte Clients. Reihenfolge = Versuchsreihenfolge. */
object UserAgents {
    const val DEFAULT = "EXIPTV/1.0 (Android; Media3)"
    const val VLC = "VLC/3.0.20 LibVLC/3.0.20"
    const val BROWSER =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    val fallbackOrder = listOf(DEFAULT, VLC, BROWSER)

    fun resolve(providerUserAgent: String): String =
        providerUserAgent.trim().ifEmpty { DEFAULT }
}

class HttpStatusException(val code: Int, val url: String) :
    IOException("Server antwortete mit HTTP $code")

/**
 * Der gesamte HTTP-Verkehr der App läuft über **einen** Verbindungspool und
 * **einen** Dispatcher.
 *
 * Die Vorgängerversion legte für Katalog, EPG, Metadaten und Wiedergabe je eigene
 * OkHttp-Instanzen an — sechs Thread-Pools, sechs Socket-Pools, und jede Anfrage
 * baute wegen `Connection: close` zusätzlich TCP und TLS neu auf. Hier teilen sich
 * alle Zwecke die Infrastruktur; unterschiedliche Zeitlimits entstehen über
 * `newBuilder()`, das Pool und Dispatcher übernimmt.
 *
 * Ebenso bewusst: Es wird **kein** `Accept-Encoding` gesetzt. Dann ergänzt OkHttp
 * transparent gzip und entpackt selbst. Eine Senderliste mit 50.000 Einträgen
 * kommt so als wenige Megabyte statt als mehrere Dutzend über die Leitung.
 */
class HttpEngine {

    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 24
                maxRequestsPerHost = 6
            }
        )
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Katalogabruf: großzügiges Lesefenster, aber ein hartes Gesamtlimit. */
    val catalog: OkHttpClient = base.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    /** Kurze Abfragen (Kontostatus, Serien-Details, Erreichbarkeitstest). */
    val quick: OkHttpClient = base.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    /** EPG-Dateien sind groß und werden auf Platte gestreamt. */
    val epg: OkHttpClient = base.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.MINUTES)
        .build()

    /**
     * Wiedergabe. Kein Gesamtlimit — ein Live-Stream läuft per Definition endlos.
     * Das Lesefenster kommt aus den Einstellungen, damit ein hängender Server
     * erkannt wird, statt das Bild einfrieren zu lassen.
     */
    fun player(readTimeoutSeconds: Int, forceHttp1: Boolean): OkHttpClient =
        base.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds.toLong().coerceIn(5L, 120L), TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .apply { if (forceHttp1) protocols(listOf(Protocol.HTTP_1_1)) }
            .build()

    fun request(url: String, userAgent: String): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .build()

    /**
     * Führt eine Anfrage aus und übergibt die Antwort an [block].
     *
     * Die Antwort wird in jedem Fall geschlossen — auch wenn [block] wirft, auch
     * wenn ein Wiederholungsversuch folgt. Genau hier leckte die Vorgängerversion:
     * Bei zwei aufeinanderfolgenden 403-Antworten überschrieb sie die erste
     * Referenz, ohne sie zu schließen.
     *
     * Wiederholt wird mit wachsendem Abstand über [delay] — abbrechbar, im
     * Gegensatz zu einem blockierenden `Thread.sleep`. Bei 403 und 451 wechselt
     * der Versuch zusätzlich die Client-Kennung, weil manche Panels nach ihr filtern.
     */
    suspend fun <T> fetch(
        url: String,
        userAgent: String,
        client: OkHttpClient = catalog,
        attempts: Int = 3,
        block: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        var lastStatus = 0
        val agents = buildList {
            add(UserAgents.resolve(userAgent))
            UserAgents.fallbackOrder.forEach { if (!contains(it)) add(it) }
        }

        for (attempt in 0 until attempts) {
            coroutineContext.ensureActive()
            if (attempt > 0) delay(backoffMillis(attempt))

            val agent = agents[attempt.coerceAtMost(agents.lastIndex)]
            try {
                client.newCall(request(url, agent)).execute().use { response ->
                    if (response.isSuccessful) {
                        return@withContext block(response)
                    }
                    lastStatus = response.code
                    // 401/403/451 können am User-Agent liegen — weiter versuchen.
                    // 404 und 5xx ebenfalls, Panels antworten unter Last sprunghaft.
                    lastError = HttpStatusException(response.code, url)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastError = e
            }
        }

        val error = lastError ?: IOException("Keine Antwort vom Anbieter")
        Diagnostics.warn(
            "Abruf fehlgeschlagen nach $attempts Versuchen" +
                (if (lastStatus != 0) " (HTTP $lastStatus)" else "") +
                ": ${Diagnostics.redact(url)}"
        )
        throw error
    }

    private fun backoffMillis(attempt: Int): Long = when (attempt) {
        1 -> 600L
        2 -> 1_800L
        else -> 3_500L
    }

    /** Prüft, ob eine Adresse überhaupt antwortet — für den Anbieter-Assistenten. */
    suspend fun reachable(url: String, userAgent: String): Boolean = runCatching {
        fetch(url, userAgent, client = quick, attempts = 1) { it.code in 200..499 }
    }.getOrDefault(false)

    fun shutdown() {
        runCatching {
            base.dispatcher.executorService.shutdown()
            base.connectionPool.evictAll()
        }
    }
}
