package de.exiptv.hd.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import de.exiptv.hd.AppGraph
import de.exiptv.hd.core.Diagnostics
import de.exiptv.hd.data.db.CategoryEntity
import de.exiptv.hd.data.db.ChannelEntity
import de.exiptv.hd.data.db.ContentKind
import de.exiptv.hd.data.db.EpgEntry
import de.exiptv.hd.data.db.EpisodeEntity
import de.exiptv.hd.data.db.ItemKind
import de.exiptv.hd.data.db.MovieEntity
import de.exiptv.hd.data.db.ProviderEntity
import de.exiptv.hd.data.db.ProviderType
import de.exiptv.hd.data.db.SeriesEntity
import de.exiptv.hd.data.repo.ChannelRow
import de.exiptv.hd.data.repo.ContinueItem
import de.exiptv.hd.data.repo.KeysetPager
import de.exiptv.hd.data.repo.PlaybackItem
import de.exiptv.hd.data.repo.SearchResults
import de.exiptv.hd.data.repo.SortMode
import de.exiptv.hd.data.xtream.XtreamClient
import de.exiptv.hd.data.xtream.XtreamEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Erzeugt ViewModels mit dem Objektgraphen als Abhängigkeit.
 *
 * Bewusst ohne DI-Framework: Der Graph ist ein einziges Objekt, und eine Factory
 * von vier Zeilen ersetzt hier eine komplette Bibliothek samt Codegenerierung.
 */
class GraphViewModelFactory(
    private val graph: AppGraph,
    private val creator: (AppGraph) -> ViewModel,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
        creator(graph) as T
}

// ---------------------------------------------------------------------------
// Anwendungsweiter Zustand
// ---------------------------------------------------------------------------

class AppViewModel(private val graph: AppGraph) : ViewModel() {

    val settings = graph.settings
    val syncProgress = graph.catalogSync.progress
    val epgProgress = graph.epgSync.progress
    val busy: StateFlow<Boolean> = graph.busy.asStateFlow()

    val providers: StateFlow<List<ProviderEntity>> =
        graph.database.providerDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val channelCount: StateFlow<Int> =
        graph.catalog.observeChannelCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val movieCount: StateFlow<Int> =
        graph.catalog.observeMovieCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val seriesCount: StateFlow<Int> =
        graph.catalog.observeSeriesCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun showMessage(text: String) {
        _message.value = text
    }

    fun clearMessage() {
        _message.value = null
    }

    fun syncAll() = graph.startSync()

    fun syncProvider(provider: ProviderEntity) = graph.startSync(provider)

    fun syncEpg() = graph.startEpgSync()

    fun cancelSync() = graph.cancelSync()

    fun clearSyncProgress() = graph.catalogSync.clearProgress()
}

// ---------------------------------------------------------------------------
// Startseite
// ---------------------------------------------------------------------------

class HomeViewModel(private val graph: AppGraph) : ViewModel() {

    private val _continueItems = MutableStateFlow<List<ContinueItem>>(emptyList())
    val continueItems: StateFlow<List<ContinueItem>> = _continueItems.asStateFlow()

    private val _favoriteChannels = MutableStateFlow<List<ChannelRow>>(emptyList())
    val favoriteChannels: StateFlow<List<ChannelRow>> = _favoriteChannels.asStateFlow()

    private val _recentChannels = MutableStateFlow<List<ChannelRow>>(emptyList())
    val recentChannels: StateFlow<List<ChannelRow>> = _recentChannels.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        // Ein einziger kombinierter Fluss statt dreier einzeln abonnierter:
        // Ändern sich Verlauf oder Favoriten, wird genau einmal neu aufgebaut.
        viewModelScope.launch {
            combine(
                graph.catalog.observeContinueWatching(12),
                graph.catalog.observeContinueSeries(12),
                graph.catalog.observeFavoriteIds(),
            ) { singles, series, favoriteIds ->
                Triple(singles, series, favoriteIds)
            }.collect { (singles, series, _) ->
                val merged = (singles + series)
                    .sortedByDescending { it.updatedAt }
                    .take(16)
                    .map { history ->
                        ContinueItem(
                            itemId = history.itemId,
                            kind = history.kind,
                            title = history.name,
                            subtitle = if (history.kind == ItemKind.EPISODE) {
                                "S${history.season} · F${history.episode}"
                            } else {
                                ""
                            },
                            imageUrl = history.logo,
                            positionMs = history.positionMs,
                            durationMs = history.durationMs,
                        )
                    }
                _continueItems.value = merged
                refreshFavorites()
                _loading.value = false
            }
        }
    }

    private suspend fun refreshFavorites() {
        val channels = graph.catalog.favoriteChannels().take(24)
        _favoriteChannels.value = graph.catalog.withEpg(channels, graph.settings.value.epgOffsetMinutes)
    }

    fun loadRecentChannels() {
        viewModelScope.launch {
            val pageChannels = withContext(Dispatchers.IO) {
                graph.database.contentDao().channelsAfter(
                    afterSort = Int.MIN_VALUE,
                    afterId = "",
                    adultMax = graph.settings.value.adultMax,
                    limit = 24,
                )
            }
            _recentChannels.value = graph.catalog.withEpg(pageChannels, graph.settings.value.epgOffsetMinutes)
        }
    }

    suspend fun resolveContinue(item: ContinueItem): PlaybackItem? = when (item.kind) {
        ItemKind.MOVIE -> graph.catalog.movie(item.itemId)?.let {
            PlaybackItem.of(it, item.positionMs)
        }

        ItemKind.EPISODE -> graph.catalog.episode(item.itemId)?.let { episode ->
            PlaybackItem.of(episode, graph.catalog.series(episode.seriesId), item.positionMs)
        }

        ItemKind.CHANNEL -> graph.catalog.channel(item.itemId)?.let { PlaybackItem.of(it) }

        ItemKind.SERIES -> null
    }

    fun removeFromHistory(itemId: String) {
        viewModelScope.launch { graph.catalog.removeFromHistory(itemId) }
    }
}

// ---------------------------------------------------------------------------
// Live-Sender
// ---------------------------------------------------------------------------

class LiveViewModel(private val graph: AppGraph) : ViewModel() {

    private val _categories = MutableStateFlow<List<CategoryEntity>>(emptyList())
    val categories: StateFlow<List<CategoryEntity>> = _categories.asStateFlow()

    private val _selectedCategory = MutableStateFlow("")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _rows = MutableStateFlow<List<ChannelRow>>(emptyList())
    val rows: StateFlow<List<ChannelRow>> = _rows.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val favoriteIds: StateFlow<Set<String>> =
        graph.catalog.observeFavoriteIds()
            .map { it.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private var pager: KeysetPager<ChannelEntity>? = null
    private var epgJob: Job? = null

    init {
        viewModelScope.launch {
            graph.catalog.observeCategories(ContentKind.LIVE).collect { _categories.value = it }
        }
        rebuildPager()
    }

    private fun rebuildPager() {
        pager?.cancel()
        val created = graph.catalog.channelPager(
            scope = viewModelScope,
            categoryId = _selectedCategory.value,
            adultMax = graph.settings.value.adultMax,
        )
        pager = created
        _loading.value = true

        // Die Programmdaten werden einmal je geladener Seite angehängt, nicht je
        // sichtbarer Zeile. Eine Abfrage für sechzig Sender statt sechzig Abfragen.
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            created.items.collect { channels ->
                _rows.value = graph.catalog.withEpg(channels, graph.settings.value.epgOffsetMinutes)
                if (channels.isNotEmpty()) _loading.value = false
            }
        }
        viewModelScope.launch {
            created.loading.collect { isLoading ->
                if (!isLoading && created.items.value.isEmpty()) _loading.value = false
            }
        }
        created.reset()
    }

    fun selectCategory(categoryId: String) {
        if (_selectedCategory.value == categoryId) return
        _selectedCategory.value = categoryId
        rebuildPager()
    }

    fun onItemVisible(index: Int) {
        pager?.onItemVisible(index)
    }

    fun refresh() = rebuildPager()

    fun toggleFavorite(channel: ChannelEntity) {
        viewModelScope.launch { graph.catalog.toggleFavorite(channel.id, ItemKind.CHANNEL) }
    }

    /** Sendernummer über die Zifferntasten — das schnellste Umschalten, das es gibt. */
    suspend fun channelByNumber(number: Int): ChannelEntity? = graph.catalog.channelByNumber(number)

    override fun onCleared() {
        pager?.cancel()
        super.onCleared()
    }
}

// ---------------------------------------------------------------------------
// Filme und Serien
// ---------------------------------------------------------------------------

class VodViewModel(private val graph: AppGraph, private val kind: ContentKind) : ViewModel() {

    private val _categories = MutableStateFlow<List<CategoryEntity>>(emptyList())
    val categories: StateFlow<List<CategoryEntity>> = _categories.asStateFlow()

    private val _selectedCategory = MutableStateFlow("")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _sort = MutableStateFlow(SortMode.DEFAULT)
    val sort: StateFlow<SortMode> = _sort.asStateFlow()

    private val _movies = MutableStateFlow<List<MovieEntity>>(emptyList())
    val movies: StateFlow<List<MovieEntity>> = _movies.asStateFlow()

    private val _series = MutableStateFlow<List<SeriesEntity>>(emptyList())
    val series: StateFlow<List<SeriesEntity>> = _series.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var moviePager: KeysetPager<MovieEntity>? = null
    private var seriesPager: KeysetPager<SeriesEntity>? = null

    init {
        viewModelScope.launch {
            graph.catalog.observeCategories(kind).collect { _categories.value = it }
        }
        rebuild()
    }

    private fun rebuild() {
        _loading.value = true
        moviePager?.cancel()
        seriesPager?.cancel()

        if (kind == ContentKind.VOD) {
            val pager = graph.catalog.moviePager(
                scope = viewModelScope,
                categoryId = _selectedCategory.value,
                sort = _sort.value,
                adultMax = graph.settings.value.adultMax,
            )
            moviePager = pager
            viewModelScope.launch {
                pager.items.collect {
                    _movies.value = it
                    if (it.isNotEmpty()) _loading.value = false
                }
            }
            viewModelScope.launch {
                pager.loading.collect { if (!it && pager.items.value.isEmpty()) _loading.value = false }
            }
            pager.reset()
        } else {
            val pager = graph.catalog.seriesPager(
                scope = viewModelScope,
                categoryId = _selectedCategory.value,
                sort = _sort.value,
                adultMax = graph.settings.value.adultMax,
            )
            seriesPager = pager
            viewModelScope.launch {
                pager.items.collect {
                    _series.value = it
                    if (it.isNotEmpty()) _loading.value = false
                }
            }
            viewModelScope.launch {
                pager.loading.collect { if (!it && pager.items.value.isEmpty()) _loading.value = false }
            }
            pager.reset()
        }
    }

    fun selectCategory(categoryId: String) {
        if (_selectedCategory.value == categoryId) return
        _selectedCategory.value = categoryId
        rebuild()
    }

    fun setSort(mode: SortMode) {
        if (_sort.value == mode) return
        _sort.value = mode
        rebuild()
    }

    fun onItemVisible(index: Int) {
        moviePager?.onItemVisible(index)
        seriesPager?.onItemVisible(index)
    }

    suspend fun playbackFor(movieId: String): PlaybackItem? {
        val movie = graph.catalog.movie(movieId) ?: return null
        val resume = if (graph.settings.value.resumePlayback) {
            graph.catalog.resumePosition(movieId)
        } else {
            0L
        }
        return PlaybackItem.of(movie, resume)
    }

    override fun onCleared() {
        moviePager?.cancel()
        seriesPager?.cancel()
        super.onCleared()
    }
}

// ---------------------------------------------------------------------------
// Seriendetails
// ---------------------------------------------------------------------------

class SeriesDetailViewModel(private val graph: AppGraph) : ViewModel() {

    private val _series = MutableStateFlow<SeriesEntity?>(null)
    val series: StateFlow<SeriesEntity?> = _series.asStateFlow()

    private val _episodes = MutableStateFlow<List<EpisodeEntity>>(emptyList())
    val episodes: StateFlow<List<EpisodeEntity>> = _episodes.asStateFlow()

    private val _seasons = MutableStateFlow<List<Int>>(emptyList())
    val seasons: StateFlow<List<Int>> = _seasons.asStateFlow()

    private val _selectedSeason = MutableStateFlow(0)
    val selectedSeason: StateFlow<Int> = _selectedSeason.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error.asStateFlow()

    fun load(seriesId: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = ""
            val entity = graph.catalog.series(seriesId)
            _series.value = entity
            if (entity == null) {
                _error.value = "Diese Serie ist nicht mehr im Katalog."
                _loading.value = false
                return@launch
            }
            val list = graph.catalog.episodes(seriesId)
            _episodes.value = list
            val seasonNumbers = list.map { it.season }.distinct().sorted()
            _seasons.value = seasonNumbers
            _selectedSeason.value = seasonNumbers.firstOrNull() ?: 0
            if (list.isEmpty()) {
                _error.value = "Zu dieser Serie hat der Anbieter keine Folgen geliefert."
            }
            _loading.value = false
        }
    }

    fun selectSeason(season: Int) {
        _selectedSeason.value = season
    }

    fun episodesOfSelectedSeason(): List<EpisodeEntity> {
        val season = _selectedSeason.value
        return _episodes.value.filter { it.season == season }
    }

    suspend fun playbackFor(episode: EpisodeEntity): PlaybackItem {
        val resume = if (graph.settings.value.resumePlayback) {
            graph.catalog.resumePosition(episode.id)
        } else {
            0L
        }
        return PlaybackItem.of(episode, _series.value, resume)
    }

    /** Die nächste Folge — für den automatischen Übergang am Ende einer Episode. */
    fun nextEpisode(current: EpisodeEntity): EpisodeEntity? {
        val all = _episodes.value
        val index = all.indexOfFirst { it.id == current.id }
        return if (index >= 0 && index + 1 < all.size) all[index + 1] else null
    }
}

// ---------------------------------------------------------------------------
// Suche
// ---------------------------------------------------------------------------

class SearchViewModel(private val graph: AppGraph) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow(SearchResults())
    val results: StateFlow<SearchResults> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val searchJob: Job = viewModelScope.launch {
        // Ohne Entprellung löst jeder Tastendruck auf der Fernbedienung eine
        // eigene Datenbankabfrage aus. 300 ms sind kurz genug, dass es sofort
        // wirkt, und lang genug, dass eine zügige Eingabe nur einmal sucht.
        _query
            .debounce(300L)
            .distinctUntilChanged()
            .collect { raw ->
                if (raw.length < 2) {
                    _results.value = SearchResults()
                    _searching.value = false
                    return@collect
                }
                _searching.value = true
                _results.value = graph.catalog.search(raw, graph.settings.value.adultMax)
                _searching.value = false
            }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun clear() {
        _query.value = ""
        _results.value = SearchResults()
    }

    suspend fun playbackFor(movie: MovieEntity): PlaybackItem {
        val resume = if (graph.settings.value.resumePlayback) {
            graph.catalog.resumePosition(movie.id)
        } else {
            0L
        }
        return PlaybackItem.of(movie, resume)
    }

    override fun onCleared() {
        searchJob.cancel()
        super.onCleared()
    }
}

// ---------------------------------------------------------------------------
// Programmzeitschrift
// ---------------------------------------------------------------------------

class GuideViewModel(private val graph: AppGraph) : ViewModel() {

    private val _channels = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val channels: StateFlow<List<ChannelEntity>> = _channels.asStateFlow()

    private val _selectedChannel = MutableStateFlow<ChannelEntity?>(null)
    val selectedChannel: StateFlow<ChannelEntity?> = _selectedChannel.asStateFlow()

    private val _programme = MutableStateFlow<List<EpgEntry>>(emptyList())
    val programme: StateFlow<List<EpgEntry>> = _programme.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _hasEpg = MutableStateFlow(true)
    val hasEpg: StateFlow<Boolean> = _hasEpg.asStateFlow()

    init {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                graph.database.contentDao().channelsAfter(
                    afterSort = Int.MIN_VALUE,
                    afterId = "",
                    adultMax = graph.settings.value.adultMax,
                    limit = 300,
                )
            }
            _channels.value = list
            _hasEpg.value = graph.catalog.epgCount() > 0
            list.firstOrNull()?.let { select(it) }
            _loading.value = false
        }
    }

    fun select(channel: ChannelEntity) {
        _selectedChannel.value = channel
        viewModelScope.launch {
            _programme.value = graph.catalog.upcoming(channel.epgKey, graph.settings.value.epgOffsetMinutes, 60)
        }
    }

    fun playbackFor(channel: ChannelEntity): PlaybackItem = PlaybackItem.of(channel)
}

// ---------------------------------------------------------------------------
// Anbieter
// ---------------------------------------------------------------------------

data class ProviderFormState(
    val id: Long = 0L,
    val name: String = "",
    val type: ProviderType = ProviderType.XTREAM,
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val epgUrl: String = "",
    val userAgent: String = "",
)

sealed interface ProviderTestResult {
    data object Idle : ProviderTestResult
    data object Testing : ProviderTestResult
    data class Success(val message: String) : ProviderTestResult
    data class Failure(val message: String) : ProviderTestResult
}

class ProviderViewModel(private val graph: AppGraph) : ViewModel() {

    val providers: StateFlow<List<ProviderEntity>> =
        graph.database.providerDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _form = MutableStateFlow(ProviderFormState())
    val form: StateFlow<ProviderFormState> = _form.asStateFlow()

    private val _test = MutableStateFlow<ProviderTestResult>(ProviderTestResult.Idle)
    val test: StateFlow<ProviderTestResult> = _test.asStateFlow()

    fun startNew() {
        _form.value = ProviderFormState()
        _test.value = ProviderTestResult.Idle
    }

    fun edit(provider: ProviderEntity) {
        _form.value = ProviderFormState(
            id = provider.id,
            name = provider.name,
            type = provider.type,
            url = provider.url,
            username = provider.username,
            password = provider.password,
            epgUrl = provider.epgUrl,
            userAgent = provider.userAgent,
        )
        _test.value = ProviderTestResult.Idle
    }

    fun update(block: (ProviderFormState) -> ProviderFormState) {
        _form.value = block(_form.value)
        _test.value = ProviderTestResult.Idle
    }

    /**
     * Prüft die Zugangsdaten, bevor sie gespeichert werden.
     *
     * Der Nutzer erfährt sofort, ob Adresse und Konto stimmen — statt es erst
     * nach einem gescheiterten Abgleich zu merken. Bei Xtream kommt dabei gleich
     * die Laufzeit des Zugangs zurück.
     */
    fun testConnection() {
        viewModelScope.launch {
            val current = _form.value
            _test.value = ProviderTestResult.Testing
            try {
                when (current.type) {
                    ProviderType.XTREAM -> {
                        val endpoint = XtreamEndpoint.parse(current.url, current.username, current.password)
                        if (!endpoint.isComplete) {
                            _test.value = ProviderTestResult.Failure(
                                "Adresse, Benutzername oder Passwort fehlen."
                            )
                            return@launch
                        }
                        val account = XtreamClient(graph.http, endpoint, current.userAgent).account()
                        _test.value = if (account.isActive) {
                            val expiry = if (account.expiresAt > 0L) {
                                " · gültig bis ${de.exiptv.hd.core.Clock.dateFull(account.expiresAt)}"
                            } else {
                                ""
                            }
                            val connections = if (account.maxConnections > 0) {
                                " · ${account.activeConnections}/${account.maxConnections} Verbindungen"
                            } else {
                                ""
                            }
                            ProviderTestResult.Success("Verbindung steht$expiry$connections")
                        } else {
                            ProviderTestResult.Failure("Der Zugang ist nicht aktiv (${account.status}).")
                        }
                    }

                    ProviderType.M3U -> {
                        val reachable = graph.http.reachable(current.url, current.userAgent)
                        _test.value = if (reachable) {
                            ProviderTestResult.Success("Die Playlist ist erreichbar.")
                        } else {
                            ProviderTestResult.Failure("Die Adresse antwortet nicht.")
                        }
                    }
                }
            } catch (e: Throwable) {
                _test.value = ProviderTestResult.Failure(Diagnostics.describe(e))
            }
        }
    }

    /** @return die gespeicherte Anbieterkennung, oder 0 bei ungültiger Eingabe. */
    suspend fun save(): Long {
        val current = _form.value
        if (current.url.isBlank()) return 0L

        val name = current.name.ifBlank {
            XtreamEndpoint.parse(current.url).baseUrl.removePrefix("http://").removePrefix("https://")
                .ifBlank { "Anbieter" }
        }

        return withContext(Dispatchers.IO) {
            val dao = graph.database.providerDao()
            if (current.id == 0L) {
                dao.insert(
                    ProviderEntity(
                        name = name,
                        type = current.type,
                        url = current.url.trim(),
                        username = current.username.trim(),
                        password = current.password,
                        epgUrl = current.epgUrl.trim(),
                        userAgent = current.userAgent.trim(),
                        sortIndex = dao.count(),
                    )
                )
            } else {
                val existing = dao.byId(current.id) ?: return@withContext 0L
                dao.update(
                    existing.copy(
                        name = name,
                        type = current.type,
                        url = current.url.trim(),
                        username = current.username.trim(),
                        password = current.password,
                        epgUrl = current.epgUrl.trim(),
                        userAgent = current.userAgent.trim(),
                    )
                )
                current.id
            }
        }
    }

    fun setEnabled(provider: ProviderEntity, enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                graph.database.providerDao().update(provider.copy(enabled = enabled))
            }
        }
    }

    /**
     * Löscht einen Anbieter samt seiner Inhalte.
     *
     * Die Vorgängerversion ließ Verlauf und Favoriten zurück — inklusive der darin
     * eingebetteten Zugangsdaten und mit Einträgen, die ins Leere zeigten.
     */
    fun delete(provider: ProviderEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                graph.database.contentDao().deleteAllOfProvider(provider.id)
                graph.database.providerDao().delete(provider)
            }
            Diagnostics.info("Anbieter „${provider.name}“ entfernt")
        }
    }

    fun syncNow(provider: ProviderEntity) = graph.startSync(provider)
}

// ---------------------------------------------------------------------------
// Player
// ---------------------------------------------------------------------------

class PlayerViewModel(private val graph: AppGraph) : ViewModel() {

    val state = graph.player.state
    val settings = graph.settings

    private val _channelList = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val channelList: StateFlow<List<ChannelEntity>> = _channelList.asStateFlow()

    private val _currentProgramme = MutableStateFlow<EpgEntry?>(null)
    val currentProgramme: StateFlow<EpgEntry?> = _currentProgramme.asStateFlow()

    init {
        // Einstellungen an die Wiedergabe durchreichen. Ändert der Nutzer das
        // Pufferprofil, baut die Engine den laufenden Sender selbst neu auf.
        viewModelScope.launch {
            graph.settings.collect { graph.player.updateSettings(it) }
        }
        viewModelScope.launch {
            graph.player.state
                .map { it.item?.epgKey.orEmpty() }
                .distinctUntilChanged()
                .collect { epgKey ->
                    _currentProgramme.value = if (epgKey.isEmpty()) {
                        null
                    } else {
                        graph.catalog.currentProgramme(epgKey, graph.settings.value.epgOffsetMinutes)
                    }
                }
        }
    }

    fun play(item: PlaybackItem) = graph.player.play(item)

    fun togglePlayPause() = graph.player.togglePlayPause()

    fun seekBy(deltaMs: Long) = graph.player.seekBy(deltaMs)

    fun seekTo(positionMs: Long) = graph.player.seekTo(positionMs)

    fun selectAudioTrack(id: String) =
        graph.player.selectTrack(androidx.media3.common.C.TRACK_TYPE_AUDIO, id)

    fun selectSubtitleTrack(id: String) =
        graph.player.selectTrack(androidx.media3.common.C.TRACK_TYPE_TEXT, id)

    fun retryNow() {
        val item = state.value.item ?: return
        graph.player.play(item)
    }

    fun stop() = graph.player.release()

    /** Sender wechseln, ohne den Player zu verlassen — die Liste kommt aus dem Umfeld. */
    fun loadChannelNeighbourhood() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                graph.database.contentDao().channelsAfter(
                    afterSort = Int.MIN_VALUE,
                    afterId = "",
                    adultMax = graph.settings.value.adultMax,
                    limit = 500,
                )
            }
            _channelList.value = list
        }
    }

    fun playRelative(offset: Int) {
        val current = state.value.item ?: return
        val list = _channelList.value
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == current.id }
        if (index < 0) return
        val target = ((index + offset) % list.size + list.size) % list.size
        graph.player.play(PlaybackItem.of(list[target]))
    }

    fun toggleFavorite() {
        val item = state.value.item ?: return
        viewModelScope.launch { graph.catalog.toggleFavorite(item.id, item.kind) }
    }
}
