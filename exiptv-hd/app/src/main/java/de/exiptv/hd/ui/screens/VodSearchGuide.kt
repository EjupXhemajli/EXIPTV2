package de.exiptv.hd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.exiptv.hd.AppGraph
import de.exiptv.hd.core.Clock
import de.exiptv.hd.data.db.ContentKind
import de.exiptv.hd.data.db.EpisodeEntity
import de.exiptv.hd.data.repo.PlaybackItem
import de.exiptv.hd.data.repo.PosterItem
import de.exiptv.hd.data.repo.SortMode
import de.exiptv.hd.data.repo.toPoster
import de.exiptv.hd.ui.Badge
import de.exiptv.hd.ui.EmptyState
import de.exiptv.hd.ui.FilterChip
import de.exiptv.hd.ui.GraphViewModelFactory
import de.exiptv.hd.ui.GuideViewModel
import de.exiptv.hd.ui.LoadingState
import de.exiptv.hd.ui.PrimaryButton
import de.exiptv.hd.ui.RemoteImage
import de.exiptv.hd.ui.SearchViewModel
import de.exiptv.hd.ui.SectionHeader
import de.exiptv.hd.ui.SeriesDetailViewModel
import de.exiptv.hd.ui.VodViewModel
import de.exiptv.hd.ui.focusCard
import de.exiptv.hd.ui.theme.Brand
import de.exiptv.hd.ui.theme.LocalDimens
import de.exiptv.hd.ui.theme.LocalNowMillis
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// ===========================================================================
// Filme und Serien
// ===========================================================================

@Composable
fun VodScreen(
    graph: AppGraph,
    kind: ContentKind,
    onPlay: (PlaybackItem) -> Unit,
    onOpenSeries: (String) -> Unit,
) {
    val vm: VodViewModel = viewModel(
        key = "vod-${kind.name}",
        factory = GraphViewModelFactory(graph) { VodViewModel(it, kind) },
    )
    val dimens = LocalDimens.current
    val scope = rememberCoroutineScope()

    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selectedCategory.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val movies by vm.movies.collectAsStateWithLifecycle()
    val series by vm.series.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { vm.onItemVisible(it) }
    }

    val posters: List<PosterItem> = remember(movies, series, kind) {
        if (kind == ContentKind.VOD) movies.map { it.toPoster() } else series.map { it.toPoster() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.screenPadding, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "all") {
                    FilterChip("Alle", selected.isEmpty()) { vm.selectCategory("") }
                }
                items(categories, key = { it.id }) { category ->
                    FilterChip(category.name, selected == category.id) { vm.selectCategory(category.id) }
                }
            }
            Spacer(Modifier.width(12.dp))
            FilterChip(
                label = if (sort == SortMode.NEWEST) "Neueste" else "Standard",
                selected = sort == SortMode.NEWEST,
                onClick = {
                    vm.setSort(if (sort == SortMode.NEWEST) SortMode.DEFAULT else SortMode.NEWEST)
                },
            )
        }

        when {
            loading && posters.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingState()
            }

            posters.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = if (kind == ContentKind.VOD) "Keine Filme" else "Keine Serien",
                    message = "Hier ist noch nichts. Ein Abgleich mit dem Anbieter füllt den Katalog.",
                )
            }

            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = dimens.posterWidth),
                contentPadding = PaddingValues(
                    start = dimens.screenPadding,
                    end = dimens.screenPadding,
                    bottom = dimens.screenPadding,
                ),
                horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing),
                verticalArrangement = Arrangement.spacedBy(dimens.cardSpacing),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(posters, key = { it.id }) { poster ->
                    PosterCard(
                        poster = poster,
                        onClick = {
                            if (kind == ContentKind.SERIES) {
                                onOpenSeries(poster.id)
                            } else {
                                scope.launch { vm.playbackFor(poster.id)?.let(onPlay) }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun PosterCard(poster: PosterItem, onClick: () -> Unit) {
    val dimens = LocalDimens.current
    Column(
        modifier = Modifier
            .width(dimens.posterWidth)
            .clip(RoundedCornerShape(dimens.cornerRadius))
            .background(Brand.Surface)
            .focusCard(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(dimens.posterHeight)
                .background(Brand.SurfaceHigh),
        ) {
            RemoteImage(
                url = poster.imageUrl,
                contentDescription = poster.title,
                contentScale = ContentScale.Crop,
                fallbackInitial = poster.title,
                modifier = Modifier.fillMaxSize(),
            )
            if (poster.rating > 0.0) {
                Badge(
                    text = String.format(java.util.Locale.GERMANY, "%.1f", poster.rating),
                    color = Color.Black.copy(alpha = 0.66f),
                    textColor = Brand.Warning,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }
        }
        Column(Modifier.padding(horizontal = 9.dp, vertical = 8.dp)) {
            Text(
                text = poster.title,
                style = MaterialTheme.typography.titleMedium,
                color = Brand.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (poster.subtitle.isNotEmpty()) {
                Text(
                    text = poster.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.TextTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

// ===========================================================================
// Seriendetails
// ===========================================================================

@Composable
fun SeriesDetailScreen(
    graph: AppGraph,
    seriesId: String,
    onPlay: (PlaybackItem) -> Unit,
    onBack: () -> Unit,
) {
    val vm: SeriesDetailViewModel = viewModel(
        key = "series-$seriesId",
        factory = GraphViewModelFactory(graph) { SeriesDetailViewModel(it) },
    )
    val dimens = LocalDimens.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(seriesId) { vm.load(seriesId) }

    val series by vm.series.collectAsStateWithLifecycle()
    val seasons by vm.seasons.collectAsStateWithLifecycle()
    val selectedSeason by vm.selectedSeason.collectAsStateWithLifecycle()
    val allEpisodes by vm.episodes.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    val episodes = remember(allEpisodes, selectedSeason) {
        allEpisodes.filter { it.season == selectedSeason }
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingState("Folgen werden geladen")
        }
        return
    }

    val entity = series
    if (entity == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                title = "Serie nicht gefunden",
                message = error.ifEmpty { "Diese Serie ist nicht mehr im Katalog." },
                action = { PrimaryButton("Zurück", onBack) },
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "head") {
            Row {
                Box(
                    Modifier
                        .width(dimens.posterWidth)
                        .height(dimens.posterHeight)
                        .clip(RoundedCornerShape(dimens.cornerRadius))
                        .background(Brand.SurfaceHigh)
                ) {
                    RemoteImage(
                        url = entity.cover,
                        contentDescription = entity.name,
                        contentScale = ContentScale.Crop,
                        fallbackInitial = entity.name,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = entity.name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Brand.TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (entity.year.isNotEmpty()) Badge(entity.year)
                        if (entity.rating > 0.0) {
                            Badge(
                                String.format(java.util.Locale.GERMANY, "%.1f", entity.rating),
                                textColor = Brand.Warning,
                            )
                        }
                        Badge("${allEpisodes.size} Folgen")
                    }
                    if (entity.plot.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = entity.plot,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Brand.TextSecondary,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (allEpisodes.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton(
                            label = "Erste Folge abspielen",
                            onClick = {
                                scope.launch { onPlay(vm.playbackFor(allEpisodes.first())) }
                            },
                            icon = {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
            }
        }

        if (error.isNotEmpty() && allEpisodes.isEmpty()) {
            item(key = "error") {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = Brand.Danger)
            }
        }

        if (seasons.size > 1) {
            item(key = "seasons") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasons, key = { it }) { season ->
                        FilterChip(
                            label = if (season == 0) "Folgen" else "Staffel $season",
                            selected = season == selectedSeason,
                            onClick = { vm.selectSeason(season) },
                        )
                    }
                }
            }
        }

        items(episodes, key = { it.id }) { episode ->
            EpisodeRow(
                episode = episode,
                onClick = { scope.launch { onPlay(vm.playbackFor(episode)) } },
            )
        }
    }
}

@Composable
private fun EpisodeRow(episode: EpisodeEntity, onClick: () -> Unit) {
    val dimens = LocalDimens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimens.cornerRadius))
            .background(Brand.Surface)
            .focusCard(onClick = onClick, scaleOnFocus = 1.02f)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 54.dp, height = 40.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Brand.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = episode.episode.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = Brand.TextSecondary,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.titleMedium,
                color = Brand.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (episode.plot.isNotEmpty()) {
                Text(
                    text = episode.plot,
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.TextTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (episode.durationMin > 0) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = "${episode.durationMin} Min.",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.TextTertiary,
            )
        }
    }
}

// ===========================================================================
// Suche
// ===========================================================================

@Composable
fun SearchScreen(
    graph: AppGraph,
    onPlay: (PlaybackItem) -> Unit,
    onOpenSeries: (String) -> Unit,
) {
    val vm: SearchViewModel = viewModel(factory = GraphViewModelFactory(graph) { SearchViewModel(it) })
    val dimens = LocalDimens.current
    val scope = rememberCoroutineScope()

    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Der Fokus kann hier ins Leere laufen, wenn der Bildschirm gerade erst
        // aufgebaut wird — deshalb abgesichert statt ungeprüft angefordert.
        runCatching { focusRequester.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = dimens.screenPadding)
    ) {
        Spacer(Modifier.height(dimens.screenPadding))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Brand.Surface)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = Brand.TextTertiary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Sender, Filme oder Serien suchen",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Brand.TextTertiary,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = vm::setQuery,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Brand.TextPrimary,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    ),
                    cursorBrush = SolidColor(Brand.Magenta),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        when {
            query.length < 2 -> EmptyState(
                title = "Suchen",
                message = "Ab zwei Zeichen wird gesucht — in Sendern, Filmen und Serien gleichzeitig.",
                modifier = Modifier.fillMaxHeight(),
            )

            searching && results.isEmpty -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { LoadingState("Wird gesucht") }

            results.isEmpty -> EmptyState(
                title = "Nichts gefunden",
                message = "Zu „$query“ gibt es keine Treffer.",
                modifier = Modifier.fillMaxHeight(),
            )

            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = dimens.screenPadding),
            ) {
                if (results.channels.isNotEmpty()) {
                    item(key = "ch-head") { SectionHeader("Sender (${results.channels.size})") }
                    item(key = "ch-row") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing)) {
                            items(results.channels, key = { it.id }) { channel ->
                                SearchTile(
                                    title = channel.name,
                                    subtitle = channel.groupTitle,
                                    imageUrl = channel.logo,
                                    onClick = { onPlay(PlaybackItem.of(channel)) },
                                )
                            }
                        }
                    }
                }

                if (results.movies.isNotEmpty()) {
                    item(key = "mv-head") { SectionHeader("Filme (${results.movies.size})") }
                    item(key = "mv-row") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing)) {
                            items(results.movies, key = { it.id }) { movie ->
                                PosterCard(
                                    poster = movie.toPoster(),
                                    onClick = { scope.launch { onPlay(vm.playbackFor(movie)) } },
                                )
                            }
                        }
                    }
                }

                if (results.series.isNotEmpty()) {
                    item(key = "sr-head") { SectionHeader("Serien (${results.series.size})") }
                    item(key = "sr-row") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing)) {
                            items(results.series, key = { it.id }) { series ->
                                PosterCard(
                                    poster = series.toPoster(),
                                    onClick = { onOpenSeries(series.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchTile(
    title: String,
    subtitle: String,
    imageUrl: String,
    onClick: () -> Unit,
) {
    val dimens = LocalDimens.current
    Column(
        modifier = Modifier
            .width(dimens.posterWidth)
            .clip(RoundedCornerShape(dimens.cornerRadius))
            .background(Brand.Surface)
            .focusCard(onClick = onClick)
            .padding(11.dp),
    ) {
        Box(
            Modifier
                .size(dimens.logoSize)
                .clip(RoundedCornerShape(7.dp))
                .background(Brand.SurfaceHigh),
        ) {
            RemoteImage(
                url = imageUrl,
                contentDescription = title,
                fallbackInitial = title,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Brand.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Brand.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ===========================================================================
// Programmzeitschrift
// ===========================================================================

@Composable
fun GuideScreen(graph: AppGraph, onPlay: (PlaybackItem) -> Unit) {
    val vm: GuideViewModel = viewModel(factory = GraphViewModelFactory(graph) { GuideViewModel(it) })
    val dimens = LocalDimens.current
    val now = LocalNowMillis.current

    val channels by vm.channels.collectAsStateWithLifecycle()
    val selected by vm.selectedChannel.collectAsStateWithLifecycle()
    val programme by vm.programme.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val hasEpg by vm.hasEpg.collectAsStateWithLifecycle()

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingState() }
        return
    }

    if (!hasEpg) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                title = "Kein Programm vorhanden",
                message = "Es wurden noch keine Programmdaten geladen. In den Einstellungen " +
                    "lässt sich die Programmzeitschrift abrufen.",
            )
        }
        return
    }

    Row(Modifier.fillMaxSize()) {
        // Links die Sender, rechts deren Programm. Für eine Fernbedienung ist das
        // die einzige Aufteilung, die ohne horizontales Suchen auskommt.
        LazyColumn(
            modifier = Modifier
                .width(dimens.posterWidth * 1.7f)
                .fillMaxHeight()
                .background(Brand.Surface),
            contentPadding = PaddingValues(vertical = 14.dp, horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            state = rememberLazyListState(),
        ) {
            items(channels, key = { it.id }) { channel ->
                val isSelected = selected?.id == channel.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isSelected) Brand.SurfaceHighest else Color.Transparent)
                        .focusCard(
                            onClick = { onPlay(vm.playbackFor(channel)) },
                            scaleOnFocus = 1.02f,
                            onFocusChanged = { focused -> if (focused) vm.select(channel) },
                        )
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Brand.SurfaceHigh),
                    ) {
                        RemoteImage(
                            url = channel.logo,
                            contentDescription = channel.name,
                            fallbackInitial = channel.name,
                            modifier = Modifier.fillMaxSize().padding(3.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) Brand.TextPrimary else Brand.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 22.dp, vertical = 16.dp)
        ) {
            Text(
                text = selected?.name.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                color = Brand.TextPrimary,
            )
            Spacer(Modifier.height(12.dp))

            if (programme.isEmpty()) {
                EmptyState(
                    title = "Kein Programm",
                    message = "Für diesen Sender liegen keine Sendungen vor.",
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(programme, key = { it.id }) { entry ->
                        val isRunning = entry.startAt <= now && entry.endAt > now
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (isRunning) Brand.SurfaceHigh else Color.Transparent)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Column(Modifier.width(96.dp)) {
                                Text(
                                    text = Clock.time(entry.startAt),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isRunning) Brand.Magenta else Brand.TextSecondary,
                                )
                                Text(
                                    text = Clock.weekday(entry.startAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Brand.TextTertiary,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = entry.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Brand.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (entry.description.isNotEmpty()) {
                                    Text(
                                        text = entry.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Brand.TextTertiary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
