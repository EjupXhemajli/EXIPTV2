package de.exiptv.hd

import android.content.Context
import de.exiptv.hd.core.Diagnostics
import de.exiptv.hd.data.db.ExIptvDatabase
import de.exiptv.hd.data.db.ProviderEntity
import de.exiptv.hd.data.net.HttpEngine
import de.exiptv.hd.data.repo.CatalogRepository
import de.exiptv.hd.data.settings.AppSettings
import de.exiptv.hd.data.settings.SettingsStore
import de.exiptv.hd.data.sync.CatalogSync
import de.exiptv.hd.data.sync.EpgSync
import de.exiptv.hd.data.sync.SyncProgress
import de.exiptv.hd.player.PlayerEngine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Der Objektgraph der App — von Hand verdrahtet statt mit einem DI-Framework.
 *
 * Für eine App dieser Größe ist das die schlichtere und schnellere Lösung: keine
 * zusätzliche Annotationsverarbeitung im Build, keine Reflexion zur Laufzeit,
 * kein verstecktes Verhalten. Vor allem aber ist hier auf einen Blick sichtbar,
 * was wie lange lebt — und genau das war in der Vorgängerversion das Problem, wo
 * ein anwendungsweiter Container auch Oberflächenzustand und Activity-Kontext
 * festhielt.
 *
 * Die Regel dieser Klasse: Hier liegt nur, was wirklich so lange leben muss wie
 * der Prozess. Alles, was zu einem Bildschirm gehört, gehört in dessen ViewModel.
 */
class AppGraph(private val context: Context) {

    /**
     * Anwendungsweiter Gültigkeitsbereich für Arbeit, die einen Bildschirmwechsel
     * überdauern muss — ein laufender Katalog-Abgleich etwa soll nicht abbrechen,
     * nur weil der Nutzer weiterblättert.
     *
     * Ein SupervisorJob sorgt dafür, dass ein gescheiterter Auftrag die übrigen
     * nicht mitreißt; der Handler fängt ab, was sonst den Prozess beenden würde.
     */
    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                Diagnostics.error("Unbehandelter Fehler im Hintergrund", throwable)
            }
    )

    val database: ExIptvDatabase by lazy { ExIptvDatabase.build(context) }

    val http: HttpEngine by lazy { HttpEngine() }

    val settingsStore: SettingsStore by lazy { SettingsStore(context) }

    val settings: StateFlow<AppSettings> by lazy {
        settingsStore.settings.stateIn(appScope, SharingStarted.Eagerly, AppSettings())
    }

    val catalog: CatalogRepository by lazy {
        CatalogRepository(
            contentDao = database.contentDao(),
            userDao = database.userDataDao(),
            epgDao = database.epgDao(),
            providerDao = database.providerDao(),
            http = http,
        )
    }

    val catalogSync: CatalogSync by lazy {
        CatalogSync(http, database.contentDao(), database.providerDao())
    }

    val epgSync: EpgSync by lazy {
        EpgSync(http, database.epgDao(), database.providerDao(), context.cacheDir)
    }

    val player: PlayerEngine by lazy {
        PlayerEngine(context, http, appScope).also { engine ->
            engine.onPositionUpdate = { item, position, duration ->
                appScope.launch { catalog.savePosition(item, position, duration) }
            }
        }
    }

    /** Läuft gerade irgendein Hintergrundabgleich? Steuert die Anzeige in der Kopfzeile. */
    val busy = MutableStateFlow(false)

    private var syncJob: Job? = null

    /**
     * Startet einen Abgleich im anwendungsweiten Bereich.
     *
     * Bewusst nicht im ViewModel: Ein Import dauert Minuten, und der Nutzer soll
     * in dieser Zeit weiterschauen können, ohne dass ein Bildschirmwechsel den
     * Vorgang abbricht. Weil hier ausschließlich Anwendungsobjekte beteiligt sind,
     * hält dieser Auftrag nichts fest, was mit dem Bildschirm verschwinden müsste.
     */
    fun startSync(provider: ProviderEntity? = null, withEpg: Boolean = true) {
        if (syncJob?.isActive == true) {
            Diagnostics.info("Abgleich läuft bereits")
            return
        }
        syncJob = appScope.launch {
            busy.value = true
            try {
                if (provider != null) {
                    catalogSync.sync(provider)
                } else {
                    catalogSync.syncAll()
                }
                if (withEpg && settings.value.epgEnabled) {
                    val keys = catalog.allEpgKeys()
                    if (keys.isNotEmpty()) {
                        epgSync.sync(keys, settings.value.epgDaysAhead)
                        settingsStore.setLastEpgSyncAt(System.currentTimeMillis())
                    }
                }
            } finally {
                busy.value = false
            }
        }
    }

    fun cancelSync() {
        syncJob?.cancel()
        syncJob = null
        busy.value = false
    }

    /** Nur die Programmzeitschrift auffrischen, ohne den Katalog anzufassen. */
    fun startEpgSync() {
        if (syncJob?.isActive == true) return
        syncJob = appScope.launch {
            busy.value = true
            try {
                val keys = catalog.allEpgKeys()
                if (keys.isNotEmpty()) {
                    epgSync.sync(keys, settings.value.epgDaysAhead)
                    settingsStore.setLastEpgSyncAt(System.currentTimeMillis())
                }
            } finally {
                busy.value = false
            }
        }
    }

    /**
     * Beim Start: Abgleich nur, wenn er fällig ist.
     *
     * Bei jedem Öffnen der App stur zu synchronisieren, belastet den Anbieter und
     * verzögert den ersten Sender. Das Intervall aus den Einstellungen entscheidet.
     */
    fun syncIfDue() {
        appScope.launch {
            val current = settingsStore.settings.first()
            if (!current.autoSyncOnStart) return@launch

            val providers = database.providerDao().enabled()
            if (providers.isEmpty()) return@launch

            val intervalMs = current.syncIntervalHours * 60L * 60L * 1000L
            val now = System.currentTimeMillis()
            val due = providers.filter { now - it.lastSyncAt > intervalMs }
            if (due.isEmpty()) {
                // Katalog ist aktuell — dann wenigstens prüfen, ob das Programm alt ist.
                if (current.epgEnabled && now - current.lastEpgSyncAt > current.epgRefreshHours * 60L * 60L * 1000L) {
                    startEpgSync()
                }
                return@launch
            }
            Diagnostics.info("${due.size} Anbieter werden abgeglichen")
            startSync()
        }
    }

    fun syncProgress(): StateFlow<SyncProgress> = catalogSync.progress

    fun shutdown() {
        cancelSync()
        player.release()
        http.shutdown()
    }
}
