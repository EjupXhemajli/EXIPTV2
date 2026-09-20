package de.exiptv.hd.data.repo

import de.exiptv.hd.core.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Blätterung über einen Schlüssel statt über einen Zähler.
 *
 * Der Pager merkt sich vom letzten geladenen Eintrag das Paar aus Sortierwert und
 * ID und fragt die nächste Seite als „alles danach" ab. Die Datenbank springt
 * damit über den Index direkt an die richtige Stelle, statt bei jeder Seite die
 * übersprungenen Zeilen erneut zu durchlaufen. Bei 80.000 Sendern ist das der
 * Unterschied zwischen gleichmäßigem Scrollen und einer Liste, die nach unten hin
 * immer zäher wird.
 *
 * Der Zustand liegt bewusst hier und nicht in der Oberfläche: Ein Pager überlebt
 * damit jede Recomposition, und die Screens müssen sich keine Ladelogik merken.
 */
class KeysetPager<T>(
    private val scope: CoroutineScope,
    private val pageSize: Int = 60,
    /**
     * Startwert des Sortierschlüssels. Bei aufsteigender Sortierung ist das
     * [Int.MIN_VALUE], bei absteigender (etwa „neueste zuerst") [Int.MAX_VALUE] —
     * die Abfrage vergleicht dann mit `<` statt mit `>`.
     */
    private val initialKey: Int = Int.MIN_VALUE,
    private val sortOf: (T) -> Int,
    private val idOf: (T) -> String,
    private val loadPage: suspend (afterSort: Int, afterId: String, limit: Int) -> List<T>,
) {

    private val _items = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _endReached = MutableStateFlow(false)
    val endReached: StateFlow<Boolean> = _endReached.asStateFlow()

    private var lastSort: Int = initialKey
    private var lastId: String = ""
    private var job: Job? = null

    /** Lädt die erste Seite neu. Wird bei Kategorie- oder Sortierwechsel aufgerufen. */
    fun reset() {
        job?.cancel()
        lastSort = initialKey
        lastId = ""
        _items.value = emptyList()
        _endReached.value = false
        loadMore()
    }

    /**
     * Lädt die nächste Seite, falls nicht bereits eine Ladung läuft. Mehrfach
     * aufzurufen ist gefahrlos — die Oberfläche darf beim Scrollen großzügig
     * danach fragen.
     */
    fun loadMore() {
        if (_loading.value || _endReached.value) return
        val currentJob = job
        if (currentJob != null && currentJob.isActive) return

        _loading.value = true
        job = scope.launch {
            try {
                val page = loadPage(lastSort, lastId, pageSize)
                if (page.isEmpty()) {
                    _endReached.value = true
                } else {
                    val last = page.last()
                    lastSort = sortOf(last)
                    lastId = idOf(last)
                    _items.value = _items.value + page
                    if (page.size < pageSize) _endReached.value = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Diagnostics.error("Seite konnte nicht geladen werden", e)
                _endReached.value = true
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Soll beim aktuellen Scrollstand nachgeladen werden?
     * Die Schwelle liegt bei einer halben Seite Vorlauf, damit der Nutzer das
     * Nachladen nie erreicht.
     */
    fun onItemVisible(index: Int) {
        if (index >= _items.value.size - pageSize / 2) loadMore()
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
