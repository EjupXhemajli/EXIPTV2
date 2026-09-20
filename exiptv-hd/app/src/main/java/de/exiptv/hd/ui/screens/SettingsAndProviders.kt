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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.exiptv.hd.AppGraph
import de.exiptv.hd.core.Clock
import de.exiptv.hd.core.Diagnostics
import de.exiptv.hd.data.db.ProviderEntity
import de.exiptv.hd.data.db.ProviderType
import de.exiptv.hd.data.settings.AspectMode
import de.exiptv.hd.data.settings.BufferProfile
import de.exiptv.hd.data.settings.DecoderMode
import de.exiptv.hd.data.settings.StartScreen
import de.exiptv.hd.ui.AppViewModel
import de.exiptv.hd.ui.Badge
import de.exiptv.hd.ui.EmptyState
import de.exiptv.hd.ui.FilterChip
import de.exiptv.hd.ui.GraphViewModelFactory
import de.exiptv.hd.ui.PrimaryButton
import de.exiptv.hd.ui.ProviderTestResult
import de.exiptv.hd.ui.ProviderViewModel
import de.exiptv.hd.ui.SecondaryButton
import de.exiptv.hd.ui.SectionHeader
import de.exiptv.hd.ui.focusCard
import de.exiptv.hd.ui.theme.Brand
import de.exiptv.hd.ui.theme.LocalDimens
import kotlinx.coroutines.launch

// ===========================================================================
// Bausteine
// ===========================================================================

@Composable
private fun SettingRow(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Brand.Surface)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Brand.TextPrimary)
            if (description.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.TextTertiary,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        control()
    }
}

@Composable
private fun ToggleControl(checked: Boolean, onToggle: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .width(54.dp)
            .height(30.dp)
            .clip(shape)
            .background(if (checked) Brand.gradient else androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(Brand.SurfaceHighest, Brand.SurfaceHighest)
            ))
            .focusCard(onClick = { onToggle(!checked) }, shape = shape, scaleOnFocus = 1.08f),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .size(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color.White)
        )
    }
}

@Composable
private fun <T> ChoiceControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            FilterChip(
                label = label(option),
                selected = option == selected,
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
private fun StepperControl(
    value: Int,
    range: IntRange,
    step: Int,
    suffix: String,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(label = "−", onClick = { onChange((value - step).coerceIn(range)) })
        Text(
            text = "$value$suffix",
            style = MaterialTheme.typography.titleMedium,
            color = Brand.TextPrimary,
            modifier = Modifier.width(76.dp),
        )
        SecondaryButton(label = "+", onClick = { onChange((value + step).coerceIn(range)) })
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String = "",
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Brand.TextSecondary)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Brand.SurfaceHigh)
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Brand.TextTertiary)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = Brand.TextPrimary,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                ),
                cursorBrush = SolidColor(Brand.Magenta),
                visualTransformation = if (isPassword) {
                    PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ===========================================================================
// Einstellungen
// ===========================================================================

@Composable
fun SettingsScreen(
    graph: AppGraph,
    appViewModel: AppViewModel,
    onOpenProviders: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val dimens = LocalDimens.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.collectAsStateWithLifecycle()
    val store = graph.settingsStore

    val channelCount by appViewModel.channelCount.collectAsStateWithLifecycle()
    val movieCount by appViewModel.movieCount.collectAsStateWithLifecycle()
    val seriesCount by appViewModel.seriesCount.collectAsStateWithLifecycle()
    val busy by appViewModel.busy.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "catalog-head") { SectionHeader("Katalog") }

        item(key = "catalog-stats") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brand.Surface)
                    .padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                StatBlock("Sender", channelCount)
                StatBlock("Filme", movieCount)
                StatBlock("Serien", seriesCount)
            }
        }

        item(key = "sync") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    label = if (busy) "Abgleich läuft" else "Jetzt abgleichen",
                    onClick = { appViewModel.syncAll() },
                    enabled = !busy,
                )
                SecondaryButton(label = "Nur Programm laden", onClick = { appViewModel.syncEpg() })
                if (busy) {
                    SecondaryButton(label = "Abbrechen", onClick = { appViewModel.cancelSync() })
                }
            }
        }

        item(key = "providers") {
            SettingRow("Anbieter", "Zugänge einrichten, prüfen und abgleichen") {
                SecondaryButton(label = "Öffnen", onClick = onOpenProviders)
            }
        }

        item(key = "autosync") {
            SettingRow("Beim Start abgleichen", "Nur, wenn der Katalog älter als das Intervall ist") {
                ToggleControl(settings.autoSyncOnStart) { scope.launch { store.setAutoSyncOnStart(it) } }
            }
        }

        item(key = "syncinterval") {
            SettingRow("Abgleichintervall", "Wie lange ein Katalog als aktuell gilt") {
                StepperControl(settings.syncIntervalHours, 1..168, 6, " Std.") {
                    scope.launch { store.setSyncIntervalHours(it) }
                }
            }
        }

        item(key = "playback-head") {
            Spacer(Modifier.height(10.dp))
            SectionHeader("Wiedergabe")
        }

        item(key = "buffer") {
            SettingRow(
                title = "Pufferverhalten",
                description = "Schnellstart wechselt zügiger, Stabil trägt unruhige Leitungen",
            ) {
                ChoiceControl(
                    options = BufferProfile.entries.toList(),
                    selected = settings.bufferProfile,
                    label = { it.label },
                    onSelect = { scope.launch { store.setBufferProfile(it) } },
                )
            }
        }

        item(key = "decoder") {
            SettingRow(
                title = "Decoder",
                description = "Software hilft, wenn einzelne Sender schwarz bleiben oder ruckeln",
            ) {
                ChoiceControl(
                    options = DecoderMode.entries.toList(),
                    selected = settings.decoderMode,
                    label = { it.label },
                    onSelect = { scope.launch { store.setDecoderMode(it) } },
                )
            }
        }

        item(key = "aspect") {
            SettingRow("Bildformat", "") {
                ChoiceControl(
                    options = AspectMode.entries.toList(),
                    selected = settings.aspectMode,
                    label = { it.label },
                    onSelect = { scope.launch { store.setAspectMode(it) } },
                )
            }
        }

        item(key = "timeout") {
            SettingRow(
                title = "Zeitlimit je Sender",
                description = "Danach gilt ein stehendes Bild als Ausfall und wird neu aufgebaut",
            ) {
                StepperControl(settings.streamTimeoutSeconds, 5..120, 5, " Sek.") {
                    scope.launch { store.setStreamTimeout(it) }
                }
            }
        }

        item(key = "retry") {
            SettingRow("Automatisch erneut versuchen", "Bei Verbindungsabbrüchen") {
                ToggleControl(settings.autoRetry) { scope.launch { store.setAutoRetry(it) } }
            }
        }

        item(key = "http1") {
            SettingRow(
                title = "HTTP/1.1 erzwingen",
                description = "Hilft bei Anbietern, die mit HTTP/2 Probleme machen",
            ) {
                ToggleControl(settings.forceHttp1) { scope.launch { store.setForceHttp1(it) } }
            }
        }

        item(key = "resume") {
            SettingRow("Wiedergabe fortsetzen", "Filme und Folgen an der letzten Stelle weiterspielen") {
                ToggleControl(settings.resumePlayback) { scope.launch { store.setResumePlayback(it) } }
            }
        }

        item(key = "subs") {
            SettingRow("Untertitel", "Standardmäßig einschalten, wenn vorhanden") {
                ToggleControl(settings.subtitlesEnabled) { scope.launch { store.setSubtitlesEnabled(it) } }
            }
        }

        item(key = "audiolang") {
            SettingRow("Bevorzugte Tonsprachen", "Sprachkürzel, durch Komma getrennt") {
                Box(Modifier.width(240.dp)) {
                    LabeledField(
                        label = "",
                        value = settings.preferredAudioLanguages,
                        onValueChange = { scope.launch { store.setAudioLanguages(it) } },
                    )
                }
            }
        }

        item(key = "ui-head") {
            Spacer(Modifier.height(10.dp))
            SectionHeader("Darstellung")
        }

        item(key = "startscreen") {
            SettingRow("Startbildschirm", "") {
                ChoiceControl(
                    options = StartScreen.entries.toList(),
                    selected = settings.startScreen,
                    label = {
                        when (it) {
                            StartScreen.HOME -> "Start"
                            StartScreen.LIVE -> "Live-TV"
                            StartScreen.MOVIES -> "Filme"
                            StartScreen.SERIES -> "Serien"
                        }
                    },
                    onSelect = { scope.launch { store.setStartScreen(it) } },
                )
            }
        }

        item(key = "adult") {
            SettingRow("Erwachseneninhalte ausblenden", "Wirkt sofort in allen Listen") {
                ToggleControl(settings.hideAdult) { scope.launch { store.setHideAdult(it) } }
            }
        }

        item(key = "numbers") {
            SettingRow("Sendernummern anzeigen", "") {
                ToggleControl(settings.showChannelNumbers) {
                    scope.launch { store.setShowChannelNumbers(it) }
                }
            }
        }

        item(key = "clock") {
            SettingRow("Uhr einblenden", "") {
                ToggleControl(settings.showClock) { scope.launch { store.setShowClock(it) } }
            }
        }

        item(key = "poster") {
            SettingRow("Kachelgröße", "") {
                ChoiceControl(
                    options = listOf(0, 1, 2),
                    selected = settings.posterSizeStep,
                    label = { listOf("Klein", "Mittel", "Groß")[it] },
                    onSelect = { scope.launch { store.setPosterSizeStep(it) } },
                )
            }
        }

        item(key = "epg-head") {
            Spacer(Modifier.height(10.dp))
            SectionHeader("Programmzeitschrift")
        }

        item(key = "epg-on") {
            SettingRow("Programmdaten laden", "") {
                ToggleControl(settings.epgEnabled) { scope.launch { store.setEpgEnabled(it) } }
            }
        }

        item(key = "epg-days") {
            SettingRow("Tage im Voraus", "Mehr Tage bedeuten eine größere Datenbank") {
                StepperControl(settings.epgDaysAhead, 1..7, 1, " Tage") {
                    scope.launch { store.setEpgDaysAhead(it) }
                }
            }
        }

        item(key = "epg-offset") {
            SettingRow(
                title = "Zeitversatz",
                description = "Falls das Programm des Anbieters verschoben ist",
            ) {
                StepperControl(settings.epgOffsetMinutes, -720..720, 30, " Min.") {
                    scope.launch { store.setEpgOffset(it) }
                }
            }
        }

        item(key = "epg-last") {
            SettingRow(
                title = "Zuletzt geladen",
                description = if (settings.lastEpgSyncAt > 0L) {
                    Clock.full(settings.lastEpgSyncAt)
                } else {
                    "Noch nie"
                },
            ) {
                SecondaryButton(label = "Jetzt laden", onClick = { appViewModel.syncEpg() })
            }
        }

        item(key = "system-head") {
            Spacer(Modifier.height(10.dp))
            SectionHeader("System")
        }

        item(key = "diagnostics") {
            SettingRow("Diagnose", "Protokoll der letzten Vorgänge") {
                SecondaryButton(label = "Öffnen", onClick = onOpenDiagnostics)
            }
        }

        item(key = "clear-history") {
            SettingRow("Verlauf löschen", "Entfernt alle gemerkten Wiedergabepositionen") {
                SecondaryButton(
                    label = "Löschen",
                    onClick = { scope.launch { graph.catalog.clearHistory() } },
                )
            }
        }

        item(key = "about") {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "EXIPTV ${de.exiptv.hd.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.TextTertiary,
            )
        }
    }
}

@Composable
private fun StatBlock(label: String, value: Int) {
    Column {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineMedium,
            color = Brand.TextPrimary,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = Brand.TextTertiary)
    }
}

// ===========================================================================
// Anbieter
// ===========================================================================

@Composable
fun ProvidersScreen(graph: AppGraph, onDone: () -> Unit) {
    val vm: ProviderViewModel = viewModel(factory = GraphViewModelFactory(graph) { ProviderViewModel(it) })
    val dimens = LocalDimens.current
    val scope = rememberCoroutineScope()

    val providers by vm.providers.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf(providers.isEmpty()) }

    if (editing) {
        ProviderForm(
            vm = vm,
            onCancel = {
                editing = false
                if (providers.isEmpty()) onDone()
            },
            onSaved = { id ->
                editing = false
                scope.launch {
                    val saved = graph.database.providerDao().byId(id)
                    if (saved != null) vm.syncNow(saved)
                }
            },
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "head") {
            SectionHeader("Anbieter") {
                PrimaryButton(
                    label = "Hinzufügen",
                    onClick = {
                        vm.startNew()
                        editing = true
                    },
                )
            }
        }

        if (providers.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = "Kein Anbieter eingerichtet",
                    message = "Trage einen Xtream-Zugang oder eine M3U-Playlist ein, " +
                        "dann füllt sich der Katalog.",
                )
            }
        }

        items(providers, key = { it.id }) { provider ->
            ProviderCard(
                provider = provider,
                onEdit = {
                    vm.edit(provider)
                    editing = true
                },
                onToggle = { vm.setEnabled(provider, it) },
                onSync = { vm.syncNow(provider) },
                onDelete = { vm.delete(provider) },
            )
        }

        if (providers.isNotEmpty()) {
            item(key = "done") {
                Spacer(Modifier.height(10.dp))
                PrimaryButton(label = "Fertig", onClick = onDone)
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderEntity,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSync: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Brand.Surface)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = Brand.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Badge(if (provider.type == ProviderType.XTREAM) "Xtream" else "M3U")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    // Die Adresse wird ohne Zugangsdaten angezeigt.
                    text = Diagnostics.redact(provider.url),
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ToggleControl(provider.enabled, onToggle)
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val statusColor = when (provider.lastSyncStatus) {
                "OK" -> Brand.Success
                "FAILED" -> Brand.Danger
                "RUNNING" -> Brand.Warning
                else -> Brand.TextTertiary
            }
            val statusLabel = when (provider.lastSyncStatus) {
                "OK" -> "Abgeglichen"
                "FAILED" -> "Fehlgeschlagen"
                "RUNNING" -> "Läuft"
                else -> "Noch nicht abgeglichen"
            }
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(statusColor))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (provider.lastSyncAt > 0L) {
                        "$statusLabel · ${Clock.full(provider.lastSyncAt)}"
                    } else {
                        statusLabel
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 1,
                )
                if (provider.lastSyncMessage.isNotEmpty()) {
                    Text(
                        text = provider.lastSyncMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.TextTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (provider.expiresAt > 0L) {
                    Text(
                        text = "Laufzeit bis ${Clock.dateFull(provider.expiresAt)}" +
                            if (provider.maxConnections > 0) {
                                " · ${provider.maxConnections} Verbindungen"
                            } else {
                                ""
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.TextTertiary,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(label = "Abgleichen", onClick = onSync)
            SecondaryButton(label = "Bearbeiten", onClick = onEdit)
            if (confirmDelete) {
                PrimaryButton(label = "Wirklich löschen", onClick = onDelete)
                SecondaryButton(label = "Abbrechen", onClick = { confirmDelete = false })
            } else {
                SecondaryButton(label = "Löschen", onClick = { confirmDelete = true })
            }
        }
    }
}

@Composable
private fun ProviderForm(
    vm: ProviderViewModel,
    onCancel: () -> Unit,
    onSaved: (Long) -> Unit,
) {
    val dimens = LocalDimens.current
    val scope = rememberCoroutineScope()
    val form by vm.form.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionHeader(if (form.id == 0L) "Anbieter hinzufügen" else "Anbieter bearbeiten")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("Xtream-Zugang", form.type == ProviderType.XTREAM) {
                vm.update { it.copy(type = ProviderType.XTREAM) }
            }
            FilterChip("M3U-Playlist", form.type == ProviderType.M3U) {
                vm.update { it.copy(type = ProviderType.M3U) }
            }
        }

        LabeledField(
            label = "Name",
            value = form.name,
            onValueChange = { value -> vm.update { it.copy(name = value) } },
            placeholder = "Frei wählbar",
        )

        LabeledField(
            label = if (form.type == ProviderType.XTREAM) "Serveradresse" else "Playlist-Adresse",
            value = form.url,
            onValueChange = { value -> vm.update { it.copy(url = value) } },
            keyboardType = KeyboardType.Uri,
            placeholder = if (form.type == ProviderType.XTREAM) {
                "http://server.example:8080"
            } else {
                "http://server.example/get.php?..."
            },
        )

        if (form.type == ProviderType.XTREAM) {
            LabeledField(
                label = "Benutzername",
                value = form.username,
                onValueChange = { value -> vm.update { it.copy(username = value) } },
            )
            LabeledField(
                label = "Passwort",
                value = form.password,
                onValueChange = { value -> vm.update { it.copy(password = value) } },
                isPassword = true,
            )
            Text(
                text = "Eine vollständige player_api- oder get.php-Adresse darf auch direkt " +
                    "in das Adressfeld — Benutzername und Passwort werden dann daraus übernommen.",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.TextTertiary,
            )
        }

        LabeledField(
            label = "EPG-Adresse (optional)",
            value = form.epgUrl,
            onValueChange = { value -> vm.update { it.copy(epgUrl = value) } },
            keyboardType = KeyboardType.Uri,
            placeholder = "Leer lassen für die EPG des Anbieters",
        )

        LabeledField(
            label = "Client-Kennung (optional)",
            value = form.userAgent,
            onValueChange = { value -> vm.update { it.copy(userAgent = value) } },
            placeholder = "Nur nötig, wenn der Anbieter danach filtert",
        )

        when (val result = test) {
            is ProviderTestResult.Success -> StatusLine(result.message, Brand.Success, true)
            is ProviderTestResult.Failure -> StatusLine(result.message, Brand.Danger, false)
            ProviderTestResult.Testing -> StatusLine("Verbindung wird geprüft …", Brand.TextSecondary, false)
            ProviderTestResult.Idle -> Unit
        }

        Spacer(Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(
                label = "Verbindung prüfen",
                onClick = { vm.testConnection() },
                enabled = form.url.isNotBlank(),
            )
            PrimaryButton(
                label = "Speichern und abgleichen",
                enabled = form.url.isNotBlank(),
                onClick = {
                    scope.launch {
                        val id = vm.save()
                        if (id > 0L) onSaved(id)
                    }
                },
            )
            SecondaryButton(label = "Abbrechen", onClick = onCancel)
        }
    }
}

@Composable
private fun StatusLine(message: String, color: Color, success: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (success) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(message, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

// ===========================================================================
// Diagnose
// ===========================================================================

@Composable
fun DiagnosticsScreen(graph: AppGraph, onBack: () -> Unit) {
    val dimens = LocalDimens.current
    val log by Diagnostics.log.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .padding(dimens.screenPadding)
    ) {
        SectionHeader("Diagnose") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(label = "Leeren", onClick = { Diagnostics.clear() })
                SecondaryButton(label = "Zurück", onClick = onBack)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Zugangsdaten werden in diesen Zeilen automatisch unkenntlich gemacht.",
            style = MaterialTheme.typography.labelSmall,
            color = Brand.TextTertiary,
        )
        Spacer(Modifier.height(14.dp))

        if (log.isEmpty()) {
            EmptyState(title = "Nichts protokolliert", message = "Hier erscheinen Meldungen zu Abgleich und Wiedergabe.")
            return
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(Brand.Surface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            reverseLayout = true,
        ) {
            // Bewusst ohne Schlüssel: Zwei Protokollzeilen können identisch sein,
            // und ein doppelter Schlüssel lässt Compose abstürzen.
            items(log.asReversed()) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        line.contains("✕") -> Brand.Danger
                        line.contains("!") -> Brand.Warning
                        else -> Brand.TextSecondary
                    },
                )
            }
        }
    }
}
