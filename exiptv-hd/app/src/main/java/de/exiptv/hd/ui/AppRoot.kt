package de.exiptv.hd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.exiptv.hd.AppGraph
import de.exiptv.hd.core.Clock
import de.exiptv.hd.data.db.ContentKind
import de.exiptv.hd.data.repo.PlaybackItem
import de.exiptv.hd.data.settings.StartScreen
import de.exiptv.hd.data.sync.SyncProgress
import de.exiptv.hd.ui.screens.DiagnosticsScreen
import de.exiptv.hd.ui.screens.GuideScreen
import de.exiptv.hd.ui.screens.HomeScreen
import de.exiptv.hd.ui.screens.LiveScreen
import de.exiptv.hd.ui.screens.PlayerScreen
import de.exiptv.hd.ui.screens.ProvidersScreen
import de.exiptv.hd.ui.screens.SearchScreen
import de.exiptv.hd.ui.screens.SeriesDetailScreen
import de.exiptv.hd.ui.screens.SettingsScreen
import de.exiptv.hd.ui.screens.VodScreen
import de.exiptv.hd.ui.theme.Brand
import de.exiptv.hd.ui.theme.LocalDimens
import de.exiptv.hd.ui.theme.LocalIsTelevision
import de.exiptv.hd.ui.theme.LocalNowMillis
import kotlinx.coroutines.delay

/**
 * Das Gerüst der App: Navigation links (oder unten), Inhalt daneben.
 *
 * Hier liegt auch die einzige Stelle, an der die Uhrzeit tickt. Sie wird über
 * [LocalNowMillis] nach unten gereicht, damit Fortschrittsbalken und Uhr
 * tatsächlich laufen, statt nur bei zufälligen Neuzeichnungen zu springen.
 */
@Composable
fun AppRoot(graph: AppGraph, onExit: () -> Unit) {
    val appViewModel: AppViewModel = viewModel(
        factory = GraphViewModelFactory(graph) { AppViewModel(it) }
    )
    val settings by appViewModel.settings.collectAsStateWithLifecycle()
    val providers by appViewModel.providers.collectAsStateWithLifecycle()
    val isTelevision = LocalIsTelevision.current

    val startRoute = when (settings.startScreen) {
        StartScreen.LIVE -> Route.Live
        StartScreen.MOVIES -> Route.Movies
        StartScreen.SERIES -> Route.Series
        StartScreen.HOME -> Route.Home
    }
    val navigator = rememberNavigator(startRoute)
    val now by rememberTicker(1_000L)

    // Beim ersten Start ohne Anbieter führt der Weg direkt in die Einrichtung —
    // eine leere Senderliste wäre kein hilfreicher erster Eindruck.
    var initialRoutingDone by remember { mutableStateOf(false) }
    LaunchedEffect(providers.isEmpty(), settings.setupCompleted) {
        if (!initialRoutingDone && providers.isEmpty()) {
            navigator.switchTo(Route.Providers)
            initialRoutingDone = true
        }
    }

    BackHandler(enabled = true) {
        if (!navigator.back()) onExit()
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalNowMillis provides now) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brand.Background)
        ) {
            val current = navigator.current

            if (current == Route.Player) {
                // Der Player bekommt den ganzen Bildschirm — ohne Navigationsleiste,
                // ohne Ränder. Alles andere wäre bei einem Fernsehbild falsch.
                PlayerScreen(
                    graph = graph,
                    onBack = { navigator.back() },
                )
            } else if (isTelevision) {
                Row(Modifier.fillMaxSize()) {
                    NavigationRail(
                        current = current,
                        busy = appViewModel.busy.collectAsStateWithLifecycle().value,
                        showClock = settings.showClock,
                        onSelect = navigator::switchTo,
                    )
                    Box(Modifier.weight(1f)) {
                        RouteContent(graph, navigator, appViewModel)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        RouteContent(graph, navigator, appViewModel)
                    }
                    BottomBar(current = current, onSelect = navigator::switchTo)
                }
            }

            SyncBanner(
                progress = appViewModel.syncProgress.collectAsStateWithLifecycle().value,
                epgLabel = appViewModel.epgProgress.collectAsStateWithLifecycle().value,
                onDismiss = appViewModel::clearSyncProgress,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

@Composable
private fun RouteContent(graph: AppGraph, navigator: Navigator, appViewModel: AppViewModel) {
    val openPlayer: (PlaybackItem) -> Unit = { item ->
        graph.player.play(item)
        navigator.openPlayer()
    }

    when (val route = navigator.current) {
        Route.Home -> HomeScreen(
            graph = graph,
            onPlay = openPlayer,
            onOpenSeries = { navigator.push(Route.SeriesDetail(it)) },
            onNavigate = navigator::switchTo,
        )

        Route.Live -> LiveScreen(graph = graph, onPlay = openPlayer)

        Route.Movies -> VodScreen(
            graph = graph,
            kind = ContentKind.VOD,
            onPlay = openPlayer,
            onOpenSeries = {},
        )

        Route.Series -> VodScreen(
            graph = graph,
            kind = ContentKind.SERIES,
            onPlay = openPlayer,
            onOpenSeries = { navigator.push(Route.SeriesDetail(it)) },
        )

        Route.Guide -> GuideScreen(graph = graph, onPlay = openPlayer)

        Route.Search -> SearchScreen(
            graph = graph,
            onPlay = openPlayer,
            onOpenSeries = { navigator.push(Route.SeriesDetail(it)) },
        )

        Route.Settings -> SettingsScreen(
            graph = graph,
            appViewModel = appViewModel,
            onOpenProviders = { navigator.push(Route.Providers) },
            onOpenDiagnostics = { navigator.push(Route.Diagnostics) },
        )

        Route.Providers -> ProvidersScreen(
            graph = graph,
            onDone = { navigator.switchTo(Route.Home) },
        )

        Route.Diagnostics -> DiagnosticsScreen(graph = graph, onBack = { navigator.back() })

        is Route.SeriesDetail -> SeriesDetailScreen(
            graph = graph,
            seriesId = route.seriesId,
            onPlay = openPlayer,
            onBack = { navigator.back() },
        )

        is Route.MovieDetail -> HomeScreen(
            graph = graph,
            onPlay = openPlayer,
            onOpenSeries = { navigator.push(Route.SeriesDetail(it)) },
            onNavigate = navigator::switchTo,
        )

        Route.Player -> Unit // oben gesondert behandelt
    }
}

private data class NavEntry(val route: Route, val label: String, val icon: ImageVector)

private val navEntries = listOf(
    NavEntry(Route.Home, "Start", Icons.Filled.Home),
    NavEntry(Route.Live, "Live-TV", Icons.Filled.LiveTv),
    NavEntry(Route.Movies, "Filme", Icons.Filled.Movie),
    NavEntry(Route.Series, "Serien", Icons.Filled.Tv),
    NavEntry(Route.Guide, "Programm", Icons.Filled.CalendarMonth),
    NavEntry(Route.Search, "Suche", Icons.Filled.Search),
    NavEntry(Route.Settings, "Einstellungen", Icons.Filled.Settings),
)

/**
 * Die Navigationsleiste für Fernseher.
 *
 * Sie klappt auf, sobald einer ihrer Einträge den Fokus hat, und wieder zu, wenn
 * der Fokus in den Inhalt wandert. Das gibt dem Inhalt die volle Breite, ohne dass
 * der Nutzer je raten muss, wo er sich befindet.
 */
@Composable
private fun NavigationRail(
    current: Route,
    busy: Boolean,
    showClock: Boolean,
    onSelect: (Route) -> Unit,
) {
    val dimens = LocalDimens.current
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(
        targetValue = if (expanded) dimens.sidebarWidth else dimens.sidebarWidthCollapsed,
        animationSpec = tween(180),
        label = "railWidth",
    )
    val now = LocalNowMillis.current

    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(Brand.Surface)
            .padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppLogo(
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .height(26.dp)
                .fillMaxWidth()
        )
        Spacer(Modifier.height(26.dp))

        navEntries.forEach { entry ->
            NavigationItem(
                entry = entry,
                selected = current == entry.route,
                expanded = expanded,
                onFocused = { expanded = it },
                onClick = { onSelect(entry.route) },
            )
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.weight(1f))

        if (busy) {
            ActivityDot(active = true)
            Spacer(Modifier.height(10.dp))
        }
        if (showClock && now > 0L) {
            Text(
                text = Clock.time(now),
                style = MaterialTheme.typography.labelLarge,
                color = Brand.TextSecondary,
            )
        }
    }
}

@Composable
private fun NavigationItem(
    entry: NavEntry,
    selected: Boolean,
    expanded: Boolean,
    onFocused: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(shape)
            .background(if (selected) Brand.SurfaceHighest else Color.Transparent)
            .focusCard(onClick = onClick, shape = shape, scaleOnFocus = 1.03f, onFocusChanged = onFocused)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = entry.label,
            tint = if (selected) Brand.Magenta else Brand.TextSecondary,
            modifier = Modifier.size(22.dp),
        )
        AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) Brand.TextPrimary else Brand.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun BottomBar(current: Route, onSelect: (Route) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brand.Surface)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navEntries.forEach { entry ->
            val selected = current == entry.route
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .focusCard(onClick = { onSelect(entry.route) }, scaleOnFocus = 1.05f)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = entry.label,
                    tint = if (selected) Brand.Magenta else Brand.TextTertiary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) Brand.TextPrimary else Brand.TextTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Die Anzeige eines laufenden Abgleichs.
 *
 * Bewusst klein und in der Ecke: Ein Import dauert Minuten, aber er blockiert
 * nichts — der Nutzer soll in dieser Zeit weiterschauen können und nur beiläufig
 * mitbekommen, dass im Hintergrund etwas passiert.
 */
@Composable
private fun SyncBanner(
    progress: SyncProgress,
    epgLabel: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = when (progress) {
        is SyncProgress.Running -> buildString {
            append(progress.providerName)
            append(" · ")
            append(progress.step)
            val counted = progress.channels + progress.movies + progress.series
            if (counted > 0) append(" · $counted")
        }

        is SyncProgress.Done -> "${progress.providerName}: ${progress.channels} Sender, " +
            "${progress.movies} Filme, ${progress.series} Serien"

        is SyncProgress.Failed -> "${progress.providerName}: ${progress.message}"

        SyncProgress.Idle -> epgLabel
    }

    LaunchedEffect(progress) {
        if (progress is SyncProgress.Done) {
            delay(6_000L)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = !text.isNullOrEmpty(),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.padding(18.dp),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(
                    when (progress) {
                        is SyncProgress.Failed -> Brand.Danger.copy(alpha = 0.16f)
                        is SyncProgress.Done -> Brand.Success.copy(alpha = 0.14f)
                        else -> Brand.SurfaceHigh
                    }
                )
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            Text(
                text = text.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = when (progress) {
                    is SyncProgress.Failed -> Brand.Danger
                    is SyncProgress.Done -> Brand.Success
                    else -> Brand.TextSecondary
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
