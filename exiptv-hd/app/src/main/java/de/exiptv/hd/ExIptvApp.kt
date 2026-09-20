package de.exiptv.hd

import android.app.Application
import de.exiptv.hd.core.Diagnostics
import kotlinx.coroutines.launch

/**
 * Der Einstiegspunkt.
 *
 * `onCreate` bleibt absichtlich kurz: Jede Millisekunde hier verzögert den ersten
 * Bildaufbau, und auf einer TV-Box ist das der Eindruck, den die App als Erstes
 * hinterlässt. Alles, was nicht sofort gebraucht wird — Datenbank, Netzwerk,
 * Player —, entsteht erst beim ersten Zugriff über [AppGraph]; die
 * Gerätemerkmale werden im Hintergrund ermittelt statt blockierend, wie es die
 * Vorgängerversion mit ihrer Codec-Abfrage im Hauptthread tat.
 */
class ExIptvApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)

        installCrashLogger()

        // Gerätemerkmale und der fällige Abgleich laufen nebenläufig an.
        graph.appScope.launch {
            DeviceProfile.detect(this@ExIptvApp)
        }
        graph.syncIfDue()
    }

    /**
     * Fängt Abstürze ab, um die letzten Protokollzeilen zu sichern, und gibt die
     * Ausnahme dann an den Standardmechanismus weiter — die App wird also
     * regulär beendet, statt einen unbrauchbaren Zustand weiterlaufen zu lassen.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                Diagnostics.error("Absturz in ${thread.name}", throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    override fun onTerminate() {
        // Wird auf echten Geräten praktisch nie gerufen, schadet aber nicht.
        runCatching { graph.shutdown() }
        super.onTerminate()
    }
}
