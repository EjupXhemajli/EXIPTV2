package de.exiptv.hd.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import de.exiptv.hd.R
import de.exiptv.hd.ui.theme.Brand
import de.exiptv.hd.ui.theme.LocalDimens
import kotlinx.coroutines.delay

/**
 * Die Uhrzeit als beobachtbarer Zustand.
 *
 * `System.currentTimeMillis()` mitten im Zeichenpfad zu lesen, sieht harmlos aus,
 * ist aber für Compose kein Zustand: Der Wert ändert sich, ohne dass irgendetwas
 * davon erfährt. Ein Fortschrittsbalken, der so gebaut ist, steht still und
 * springt nur, wenn die Zeile zufällig aus anderem Grund neu gezeichnet wird —
 * genau dieser Fehler steckte in der Vorgängerversion.
 *
 * Hier tickt die Zeit an einer einzigen Stelle und wird über
 * [de.exiptv.hd.ui.theme.LocalNowMillis] verteilt.
 */
@Composable
fun rememberTicker(periodMs: Long = 1_000L): State<Long> {
    val state = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            state.longValue = System.currentTimeMillis()
            delay(periodMs)
        }
    }
    return state
}

/**
 * Der Fokus-Modifier — das wichtigste Bedienelement einer Fernseh-App.
 *
 * Auf einem Fernseher gibt es keinen Zeiger; die einzige Rückmeldung darüber, wo
 * man gerade ist, ist die Darstellung des fokussierten Elements. Deshalb wirken
 * hier drei Mittel zusammen: eine leichte Vergrößerung, ein Rand im Markenverlauf
 * und ein Schatten, der das Element vom Hintergrund abhebt. Alles animiert, damit
 * das Auge der Bewegung folgen kann, statt sie zu suchen.
 */
@Composable
fun Modifier.focusCard(
    onClick: () -> Unit,
    enabled: Boolean = true,
    shape: RoundedCornerShape = RoundedCornerShape(LocalDimens.current.cornerRadius),
    scaleOnFocus: Float = LocalDimens.current.focusScale,
    onFocusChanged: (Boolean) -> Unit = {},
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) scaleOnFocus else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "focusScale",
    )
    val borderAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "focusBorder",
    )
    val interaction = remember { MutableInteractionSource() }

    return this
        .scale(scale)
        .then(
            if (borderAlpha > 0.01f) {
                Modifier
                    .shadow(elevation = (14 * borderAlpha).dp, shape = shape, clip = false)
                    .border(
                        width = 2.dp,
                        brush = Brush.linearGradient(
                            listOf(
                                Brand.Violet.copy(alpha = borderAlpha),
                                Brand.Magenta.copy(alpha = borderAlpha),
                                Brand.Blue.copy(alpha = borderAlpha),
                            )
                        ),
                        shape = shape,
                    )
            } else {
                Modifier
            }
        )
        .onFocusChanged {
            focused = it.isFocused
            onFocusChanged(it.isFocused)
        }
        .focusable(enabled = enabled, interactionSource = interaction)
        .clickable(
            enabled = enabled,
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        )
}

/**
 * Bild mit Platzhalter.
 *
 * Coil bekommt die Zielgröße über die Layout-Beschränkungen und skaliert bereits
 * beim Dekodieren — eine Senderliste lädt damit keine 1000-Pixel-Logos in
 * 50-Pixel-Kacheln, was auf Geräten mit wenig Speicher sonst schnell teuer wird.
 */
@Composable
fun RemoteImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    fallbackInitial: String = "",
) {
    if (url.isBlank()) {
        InitialPlaceholder(fallbackInitial, modifier)
        return
    }

    val context = LocalContext.current
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(url)
            .crossfade(180)
            .build()
    )
    val state = painter.state

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (state) {
            is AsyncImagePainter.State.Success -> Image(
                painter = painter,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )

            is AsyncImagePainter.State.Error -> if (fallbackInitial.isNotEmpty()) {
                InitialPlaceholder(fallbackInitial, Modifier.fillMaxSize())
            } else {
                Icon(
                    imageVector = Icons.Filled.BrokenImage,
                    contentDescription = null,
                    tint = Brand.TextTertiary,
                    modifier = Modifier.size(20.dp),
                )
            }

            else -> Box(
                Modifier
                    .fillMaxSize()
                    .background(Brand.SurfaceHigh)
            )
        }
    }
}

/** Platzhalter aus dem Anfangsbuchstaben — ruhiger als ein generisches Symbol. */
@Composable
fun InitialPlaceholder(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Brand.gradientSoft),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.take(2).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = Brand.TextSecondary,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun AppLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.exiptv_logo),
        contentDescription = "EXIPTV",
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Brand.TextPrimary,
        )
        trailing?.invoke()
    }
}

/** Kleine Markierung: LIVE, 4K, Jahreszahl, Bewertung. */
@Composable
fun Badge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Brand.SurfaceHighest,
    textColor: Color = Brand.TextPrimary,
) {
    if (text.isEmpty()) return
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1,
        )
    }
}

/** Auswahlchip für Kategorien und Filter. */
@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (selected) {
                    Modifier.background(Brand.gradient)
                } else {
                    Modifier.background(Brand.SurfaceHigh)
                }
            )
            .focusCard(onClick = onClick, shape = shape, scaleOnFocus = 1.06f)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else Brand.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (enabled) Brand.gradient else Brush.linearGradient(listOf(Brand.SurfaceHigh, Brand.SurfaceHigh)))
            .focusCard(onClick = onClick, enabled = enabled, shape = shape, scaleOnFocus = 1.05f)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.invoke()
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) Color.White else Brand.TextTertiary,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Brand.SurfaceHigh)
            .focusCard(onClick = onClick, enabled = enabled, shape = shape, scaleOnFocus = 1.05f)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) Brand.TextPrimary else Brand.TextTertiary,
            maxLines = 1,
        )
    }
}

/** Dünner Fortschrittsbalken im Markenverlauf. */
@Composable
fun GradientProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
) {
    val safe = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(Brand.SurfaceHighest),
    ) {
        Box(
            Modifier
                .fillMaxWidth(safe)
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(Brand.gradient)
        )
    }
}

@Composable
fun LoadingState(label: String = "Wird geladen", modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            color = Brand.Violet,
            strokeWidth = 3.dp,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Brand.TextSecondary)
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Brand.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.TextSecondary,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** Punkt, der Aktivität anzeigt — etwa einen laufenden Abgleich in der Kopfzeile. */
@Composable
fun ActivityDot(active: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(300),
        label = "activityDot",
    )
    if (alpha < 0.01f) return
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(Brand.Violet.copy(alpha = alpha))
    )
}

@Composable
fun VerticalSpacer(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun HorizontalSpacer(width: Dp) = Spacer(Modifier.width(width))
