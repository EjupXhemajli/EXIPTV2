package de.exiptv.hd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.exiptv.hd.AppGraph
import de.exiptv.hd.core.Clock
import de.exiptv.hd.data.db.ChannelEntity
import de.exiptv.hd.data.repo.ChannelRow
import de.exiptv.hd.data.repo.ContinueItem
import de.exiptv.hd.data.repo.PlaybackItem
import de.exiptv.hd.ui.Badge
import de.exiptv.hd.ui.EmptyState
import de.exiptv.hd.ui.FilterChip
import de.exiptv.hd.ui.GradientProgress
import de.exiptv.hd.ui.GraphViewModelFactory
import de.exiptv.hd.ui.HomeViewModel
import de.exiptv.hd.ui.LiveViewModel
import de.exiptv.hd.ui.LoadingState
import de.exiptv.hd.ui.PrimaryButton
import de.exiptv.hd.ui.RemoteImage
import de.exiptv.hd.ui.Route
import de.exiptv.hd.ui.SectionHeader
import de.exiptv.hd.ui.focusCard
import de.exiptv.hd.ui.theme.Brand
import de.exiptv.hd.ui.theme.LocalDimens
import de.exiptv.hd.ui.theme.LocalNowMillis
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// ===========================================================================
// Startseite
// ===========================================================================

@Composable
fun HomeScreen(
    graph: AppGraph,
    onPlay: (PlaybackItem) -> Unit,
    onOpenSeries: (String) -> Unit,
    onNavigate: (Route) -> Unit,
) {
    val vm: HomeViewModel = viewModel(factory = GraphViewModelFactory(graph) { HomeViewModel(it) })
    val dimens = LocalDimens.current
    val scope = rememberCoroutineScope()

    val continueItems by vm.continueItems.collectAsStateWithLifecycle()
    val favorites by vm.favoriteChannels.collectAsStateWithLifecycle()
    val recent by vm.recentChannels.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadRecentChannels() }

    if (loading && continueItems.isEmpty() && favorites.isEmpty() && recent.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingState()
        }
        return
    }

    if (continueItems.isEmpty() && favorites.isEmpty() && recent.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                title = "Noch nichts da",
                message = "Richte einen Anbieter ein, dann füllt sich diese Seite mit dem, " +
                    "was du zuletzt gesehen hast und was du als Favorit markierst.",
                action = {
                    PrimaryButton(label = "Anbieter einrichten", onClick = { onNavigate(Route.Providers) })
                },
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = dimens.screenPadding,
            end = dimens.screenPadding,
            top = dimens.screenPadding,
            bottom = dimens.screenPadding * 2,
        ),
        verticalArrangement = Arrangement.spacedBy(dimens.sectionSpacing),
    ) {
        if (continueItems.isNotEmpty()) {
            item(key = "continue-header") { SectionHeader("Weiterschauen") }
            item(key = "continue-row") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing)) {
                    items(continueItems, key = { it.itemId }) { item ->
                        ContinueCard(
                            item = item,
                            onClick = {
                                scope.launch { vm.resolveContinue(item)?.let(onPlay) }
                            },
                        )
                    }
                }
            }
        }

        if (favorites.isNotEmpty()) {
            item(key = "fav-header") { SectionHeader("Favoriten") }
            item(key = "fav-row") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing)) {
                    items(favorites, key = { it.channel.id }) { row ->
                        ChannelTile(row = row, onClick = { onPlay(PlaybackItem.of(row.channel)) })
                    }
                }
            }
        }

        if (recent.isNotEmpty()) {
            item(key = "recent-header") {
                SectionHeader("Sender") {
                    Text(
                        text = "Alle anzeigen",
                        style = MaterialTheme.typography.labelLarge,
                        color = Brand.TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .focusCard(onClick = { onNavigate(Route.Live) }, scaleOnFocus = 1.04f)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            item(key = "recent-row") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing)) {
                    items(recent, key = { it.channel.id }) { row ->
                        ChannelTile(row = row, onClick = { onPlay(PlaybackItem.of(row.channel)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(
    item: ContinueItem,
    onClick: () -> Unit,
) {
    val dimens = LocalDimens.current
    Column(
        modifier = Modifier
            .width(dimens.posterWidth * 1.5f)
            .clip(RoundedCornerShape(dimens.cornerRadius))
            .background(Brand.Surface)
            .focusCard(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(dimens.posterWidth * 0.85f)
                .background(Brand.SurfaceHigh)
        ) {
            RemoteImage(
                url = item.imageUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                fallbackInitial = item.title,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.progress > 0f) {
                GradientProgress(
                    progress = item.progress,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        Column(Modifier.padding(10.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = Brand.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotEmpty()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.TextSecondary,
                    maxLines = 1,
                )
            } else if (item.durationMs > 0L) {
                Text(
                    text = "Noch ${Clock.duration(item.durationMs - item.positionMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ChannelTile(row: ChannelRow, onClick: () -> Unit) {
    val dimens = LocalDimens.current
    val now = LocalNowMillis.current

    Column(
        modifier = Modifier
            .width(dimens.posterWidth * 1.15f)
            .clip(RoundedCornerShape(dimens.cornerRadius))
            .background(Brand.Surface)
            .focusCard(onClick = onClick)
            .padding(12.dp),
    ) {
        Box(
            Modifier
                .size(dimens.logoSize)
                .clip(RoundedCornerShape(8.dp))
                .background(Brand.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            RemoteImage(
                url = row.channel.logo,
                contentDescription = row.channel.name,
                fallbackInitial = row.channel.name,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = row.channel.name,
            style = MaterialTheme.typography.titleMedium,
            color = Brand.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (row.hasEpg) {
            Text(
                text = row.nowTitle,
                style = MaterialTheme.typography.labelSmall,
                color = Brand.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            GradientProgress(progress = row.progress(now), modifier = Modifier.fillMaxWidth())
        }
    }
}

// ===========================================================================
// Live-Sender
// ===========================================================================

@Composable
fun LiveScreen(graph: AppGraph, onPlay: (PlaybackItem) -> Unit) {
    val vm: LiveViewModel = viewModel(factory = GraphViewModelFactory(graph) { LiveViewModel(it) })
    val dimens = LocalDimens.current

    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selectedCategory.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val settings by graph.settings.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()

    // Nachgeladen wird anhand der tatsächlich sichtbaren Zeilen. Ein Effekt je
    // Listeneintrag wäre die naheliegende, aber teure Variante: Er liefe bei
    // jeder Neuzeichnung erneut an, obwohl sich am Scrollstand nichts geändert hat.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { vm.onItemVisible(it) }
    }

    Column(Modifier.fillMaxSize()) {
        if (categories.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimens.screenPadding, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "cat-all") {
                    FilterChip(
                        label = "Alle",
                        selected = selected.isEmpty(),
                        onClick = { vm.selectCategory("") },
                    )
                }
                items(categories, key = { it.id }) { category ->
                    FilterChip(
                        label = category.name,
                        selected = selected == category.id,
                        onClick = { vm.selectCategory(category.id) },
                    )
                }
            }
        }

        when {
            loading && rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingState("Sender werden geladen")
            }

            rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "Keine Sender",
                    message = "In dieser Kategorie ist nichts vorhanden. " +
                        "Vielleicht hilft ein Abgleich mit dem Anbieter.",
                )
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = dimens.screenPadding,
                    end = dimens.screenPadding,
                    bottom = dimens.screenPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Stabile Schlüssel: Ohne sie zeichnet Compose beim Nachladen
                // sämtliche sichtbaren Zeilen neu und der Fokus springt.
                items(rows, key = { row -> row.channel.id }) { row ->
                    ChannelListRow(
                        row = row,
                        showNumber = settings.showChannelNumbers,
                        isFavorite = favoriteIds.contains(row.channel.id),
                        onClick = { onPlay(PlaybackItem.of(row.channel)) },
                        onToggleFavorite = { vm.toggleFavorite(row.channel) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelListRow(
    row: ChannelRow,
    showNumber: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val dimens = LocalDimens.current
    val now = LocalNowMillis.current
    val channel: ChannelEntity = row.channel

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(dimens.channelRowHeight)
            .clip(RoundedCornerShape(dimens.cornerRadius))
            .background(Brand.Surface)
            .focusCard(onClick = onClick, scaleOnFocus = 1.02f)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showNumber) {
            Text(
                text = channel.number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = Brand.TextTertiary,
                modifier = Modifier.width(44.dp),
            )
        }

        Box(
            Modifier
                .size(dimens.logoSize)
                .clip(RoundedCornerShape(7.dp))
                .background(Brand.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            RemoteImage(
                url = channel.logo,
                contentDescription = channel.name,
                fallbackInitial = channel.name,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Brand.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isFavorite) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Favorit",
                        tint = Brand.Warning,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (channel.catchupDays > 0) {
                    Spacer(Modifier.width(6.dp))
                    Badge("Replay", color = Brand.SurfaceHighest, textColor = Brand.TextSecondary)
                }
            }

            if (row.hasEpg) {
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.nowTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.nowEndAt > 0L) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "bis ${Clock.time(row.nowEndAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Brand.TextTertiary,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                GradientProgress(
                    progress = row.progress(now),
                    modifier = Modifier.fillMaxWidth(0.55f),
                    height = 2.dp,
                )
            } else if (channel.groupTitle.isNotEmpty()) {
                Text(
                    text = channel.groupTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.TextTertiary,
                    maxLines = 1,
                )
            }
        }

        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(19.dp))
                .focusCard(onClick = onToggleFavorite, scaleOnFocus = 1.12f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = if (isFavorite) "Favorit entfernen" else "Zu Favoriten",
                tint = if (isFavorite) Brand.Warning else Brand.TextTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
