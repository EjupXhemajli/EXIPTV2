package de.exiptv.hd

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import de.exiptv.hd.ui.AppRoot
import de.exiptv.hd.ui.theme.ExIptvTheme

/**
 * Die einzige Activity der App.
 *
 * Sie tut absichtlich wenig: Gerätebauart feststellen, Compose starten,
 * Bild-in-Bild anmelden. Alles Weitere gehört in den Objektgraphen oder in die
 * Bildschirme — eine Activity, die Zustand hält, ist die häufigste Ursache für
 * Speicherlecks in Android-Apps.
 */
class MainActivity : ComponentActivity() {

    private val graph: AppGraph
        get() = (application as ExIptvApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Die schnelle Erkennung reicht für den ersten Bildaufbau; die
        // vollständige läuft parallel im Hintergrund weiter.
        val isTelevision = DeviceProfile.quickDetect(this)

        setContent {
            val settings by graph.settings.collectAsState()

            ExIptvTheme(
                isTelevision = isTelevision || DeviceProfile.isTelevision,
                posterSizeStep = settings.posterSizeStep,
            ) {
                AppRoot(
                    graph = graph,
                    onExit = { finish() },
                )
            }
        }
    }

    /**
     * Bild-in-Bild beim Verlassen — aber nur, wenn tatsächlich etwas läuft.
     *
     * Ohne diese Prüfung würde die App auch aus einer Senderliste heraus in ein
     * winziges leeres Fenster schrumpfen. Auf Fernsehern ist Bild-in-Bild
     * zudem selten sinnvoll, deshalb bleibt es dort aus.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (DeviceProfile.isTelevision) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return

        val state = graph.player.state.value
        val player = graph.player.playerInstanceOrNull()
        if (player == null || state.item == null) return
        if (state.videoWidth <= 0 || state.videoHeight <= 0) return

        runCatching {
            val ratio = Rational(state.videoWidth, state.videoHeight)
            // Android akzeptiert nur Seitenverhältnisse zwischen etwa 1:2,39 und 2,39:1.
            val value = state.videoWidth.toFloat() / state.videoHeight.toFloat()
            if (value < 0.42f || value > 2.39f) return@runCatching
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(ratio).build()
            )
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(
                androidx.lifecycle.Lifecycle.State.STARTED
            )
        ) {
            // Der Nutzer hat das kleine Fenster geschlossen — dann soll auch die
            // Wiedergabe enden und die Verbindung zum Anbieter frei werden.
            graph.player.release()
        }
    }

    override fun onDestroy() {
        // Nur beim endgültigen Beenden freigeben. Bei einer Drehung oder einem
        // Themenwechsel wird die Activity ebenfalls zerstört — dort würde ein
        // Freigeben den laufenden Sender grundlos abwürgen.
        if (isFinishing) {
            graph.player.release()
        }
        super.onDestroy()
    }
}
