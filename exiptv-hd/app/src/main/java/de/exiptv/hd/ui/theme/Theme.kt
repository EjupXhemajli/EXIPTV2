package de.exiptv.hd.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Die Farben stammen aus dem Logo — dem Bogen, der von Violett über Magenta nach
 * Blau läuft. Sie sind die einzige Quelle der Markenfarbe in der App; sonst gibt
 * es nur Graustufen und den Verlauf.
 *
 * Das Ergebnis ist bewusst zurückhaltend: Farbe markiert, was gerade wichtig ist
 * — den Fokus, den laufenden Sender, einen Fortschritt. Eine Oberfläche, die
 * überall leuchtet, hat keine Mittel mehr, um etwas hervorzuheben.
 */
object Brand {
    val Violet = Color(0xFF8A2BF0)
    val Magenta = Color(0xFFD64BF0)
    val Blue = Color(0xFF1E7BF5)

    val Background = Color(0xFF07070C)
    val Surface = Color(0xFF12121C)
    val SurfaceHigh = Color(0xFF1B1B29)
    val SurfaceHighest = Color(0xFF24243A)
    val Outline = Color(0xFF2E2E42)

    val TextPrimary = Color(0xFFF2F2F7)
    val TextSecondary = Color(0xFFA9A9BC)
    val TextTertiary = Color(0xFF6E6E85)

    val Success = Color(0xFF3DD68C)
    val Warning = Color(0xFFF5B841)
    val Danger = Color(0xFFF2545B)
    val Live = Color(0xFFF2545B)

    /** Der Markenverlauf: für Fokusränder, aktive Zustände und Fortschrittsbalken. */
    val gradient = Brush.linearGradient(listOf(Violet, Magenta, Blue))
    val gradientSoft = Brush.linearGradient(
        listOf(Violet.copy(alpha = 0.22f), Blue.copy(alpha = 0.22f))
    )

    /** Verlauf über dem Videobild, damit Bedienelemente auf jedem Motiv lesbar bleiben. */
    val scrim = Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.82f),
        0.4f to Color.Black.copy(alpha = 0.35f),
        1f to Color.Transparent,
    )
    val scrimBottom = Brush.verticalGradient(
        0f to Color.Transparent,
        0.45f to Color.Black.copy(alpha = 0.5f),
        1f to Color.Black.copy(alpha = 0.92f),
    )
}

private val ColorScheme = darkColorScheme(
    primary = Brand.Violet,
    onPrimary = Color.White,
    primaryContainer = Brand.SurfaceHighest,
    onPrimaryContainer = Brand.TextPrimary,
    secondary = Brand.Blue,
    onSecondary = Color.White,
    tertiary = Brand.Magenta,
    background = Brand.Background,
    onBackground = Brand.TextPrimary,
    surface = Brand.Surface,
    onSurface = Brand.TextPrimary,
    surfaceVariant = Brand.SurfaceHigh,
    onSurfaceVariant = Brand.TextSecondary,
    outline = Brand.Outline,
    error = Brand.Danger,
    onError = Color.White,
)

/**
 * Maße, die sich nach der Gerätebauart richten.
 *
 * Ein Fernseher steht drei Meter entfernt, ein Telefon dreißig Zentimeter. Dieselbe
 * Schriftgröße kann nicht für beides stimmen. Statt überall im Code Verzweigungen
 * zu verteilen, liegen die Werte an einer Stelle und kommen über einen
 * CompositionLocal in die Oberfläche.
 */
@Immutable
data class Dimens(
    val screenPadding: Dp,
    val sectionSpacing: Dp,
    val cardSpacing: Dp,
    val posterWidth: Dp,
    val posterHeight: Dp,
    val channelRowHeight: Dp,
    val logoSize: Dp,
    val cornerRadius: Dp,
    val focusScale: Float,
    val sidebarWidth: Dp,
    val sidebarWidthCollapsed: Dp,
    val titleSize: TextUnit,
    val headlineSize: TextUnit,
    val bodySize: TextUnit,
    val labelSize: TextUnit,
) {
    companion object {
        fun forTelevision(posterStep: Int): Dimens {
            val scale = when (posterStep) {
                0 -> 0.85f
                2 -> 1.18f
                else -> 1f
            }
            return Dimens(
                screenPadding = 40.dp,
                sectionSpacing = 30.dp,
                cardSpacing = 16.dp,
                posterWidth = (150 * scale).dp,
                posterHeight = (225 * scale).dp,
                channelRowHeight = 76.dp,
                logoSize = 52.dp,
                cornerRadius = 14.dp,
                focusScale = 1.09f,
                sidebarWidth = 232.dp,
                sidebarWidthCollapsed = 84.dp,
                titleSize = 30.sp,
                headlineSize = 21.sp,
                bodySize = 16.sp,
                labelSize = 13.sp,
            )
        }

        fun forHandheld(posterStep: Int): Dimens {
            val scale = when (posterStep) {
                0 -> 0.85f
                2 -> 1.15f
                else -> 1f
            }
            return Dimens(
                screenPadding = 16.dp,
                sectionSpacing = 22.dp,
                cardSpacing = 10.dp,
                posterWidth = (116 * scale).dp,
                posterHeight = (174 * scale).dp,
                channelRowHeight = 66.dp,
                logoSize = 44.dp,
                cornerRadius = 12.dp,
                focusScale = 1.04f,
                sidebarWidth = 200.dp,
                sidebarWidthCollapsed = 72.dp,
                titleSize = 22.sp,
                headlineSize = 17.sp,
                bodySize = 14.sp,
                labelSize = 11.sp,
            )
        }
    }
}

val LocalDimens: ProvidableCompositionLocal<Dimens> =
    staticCompositionLocalOf { Dimens.forHandheld(1) }

/**
 * Ist dieses Gerät ein Fernseher?
 *
 * Bewusst ein `compositionLocalOf` und kein `staticCompositionLocalOf`: Der Wert
 * ändert sich praktisch nie, aber wenn doch, sollen nur die Stellen neu gezeichnet
 * werden, die ihn wirklich lesen. Bei der statischen Variante wäre es der gesamte
 * Baum.
 */
val LocalIsTelevision = compositionLocalOf { false }

/** Sekundengenaue Uhrzeit als beobachtbarer Zustand — siehe [de.exiptv.hd.ui.rememberTicker]. */
val LocalNowMillis = compositionLocalOf { 0L }

private fun typography(dimens: Dimens) = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = dimens.titleSize,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = dimens.headlineSize,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = dimens.bodySize,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = dimens.bodySize,
        lineHeight = dimens.bodySize * 1.45f,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = dimens.bodySize * 0.92f,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = dimens.labelSize,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = dimens.labelSize * 0.9f,
        letterSpacing = 0.4.sp,
    ),
)

@Composable
fun ExIptvTheme(
    isTelevision: Boolean,
    posterSizeStep: Int,
    content: @Composable () -> Unit,
) {
    // Die App ist durchgehend dunkel — ein heller Modus wäre auf einem Fernseher
    // im abgedunkelten Raum eine Zumutung und wird deshalb gar nicht erst angeboten.
    val dimens = if (isTelevision) {
        Dimens.forTelevision(posterSizeStep)
    } else {
        Dimens.forHandheld(posterSizeStep)
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalDimens provides dimens,
        LocalIsTelevision provides isTelevision,
    ) {
        MaterialTheme(
            colorScheme = ColorScheme,
            typography = typography(dimens),
            content = content,
        )
    }
}
