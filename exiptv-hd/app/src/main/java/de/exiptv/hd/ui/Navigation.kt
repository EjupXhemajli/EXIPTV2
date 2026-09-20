package de.exiptv.hd.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList

/**
 * Die Bildschirme der App.
 *
 * Jede Route kann sich als Zeichenkette speichern und daraus wiederherstellen.
 * Das ist der Grund, warum der Nutzer nach einem Prozesstod — auf TV-Boxen
 * passiert das ständig, weil das System Hintergrundprozesse aggressiv beendet —
 * dort weitermacht, wo er war, statt wieder auf dem Startbildschirm zu landen.
 * In der Vorgängerversion lag der gesamte Navigationszustand in einem Singleton
 * und war nach jedem solchen Ereignis weg.
 */
sealed class Route(val id: String) {
    data object Home : Route("home")
    data object Live : Route("live")
    data object Movies : Route("movies")
    data object Series : Route("series")
    data object Search : Route("search")
    data object Settings : Route("settings")
    data object Providers : Route("providers")
    data object Diagnostics : Route("diagnostics")
    data object Player : Route("player")
    data object Guide : Route("guide")

    data class SeriesDetail(val seriesId: String) : Route("seriesDetail")
    data class MovieDetail(val movieId: String) : Route("movieDetail")

    fun encode(): String = when (this) {
        is SeriesDetail -> "seriesDetail|$seriesId"
        is MovieDetail -> "movieDetail|$movieId"
        else -> id
    }

    companion object {
        fun decode(raw: String): Route {
            val parts = raw.split('|', limit = 2)
            return when (parts[0]) {
                "home" -> Home
                "live" -> Live
                "movies" -> Movies
                "series" -> Series
                "search" -> Search
                "settings" -> Settings
                "providers" -> Providers
                "diagnostics" -> Diagnostics
                "player" -> Player
                "guide" -> Guide
                "seriesDetail" -> SeriesDetail(parts.getOrElse(1) { "" })
                "movieDetail" -> MovieDetail(parts.getOrElse(1) { "" })
                else -> Home
            }
        }

        /** Die Ziele, die in der Hauptnavigation auftauchen. */
        val primary = listOf(Home, Live, Movies, Series, Guide, Search, Settings)
    }
}

/**
 * Ein bewusst kleiner Navigationsstapel statt einer Navigationsbibliothek.
 *
 * Die App hat ein knappes Dutzend Ziele und keine Deep Links. Ein eigener Stapel
 * ist hier nicht nur schlanker, sondern auch besser steuerbar: Auf einem Fernseher
 * muss die Zurück-Taste sehr genau das tun, was der Nutzer erwartet, und genau
 * diese Regeln stehen hier sichtbar an einer Stelle.
 */
class Navigator(initial: List<Route>) {

    private val stack: SnapshotStateList<Route> = initial.ifEmpty { listOf(Route.Home) }.toMutableStateList()

    val current: Route get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    /**
     * Wechselt den Hauptbereich. Der Stapel wird dabei zurückgesetzt, damit die
     * Zurück-Taste aus jedem Hauptbereich zur Startseite führt und nicht durch
     * eine lange Kette vorher besuchter Bereiche.
     */
    fun switchTo(route: Route) {
        if (stack.size == 1 && stack[0] == route) return
        stack.clear()
        if (route != Route.Home) stack.add(Route.Home)
        stack.add(route)
    }

    fun push(route: Route) {
        if (stack.lastOrNull() == route) return
        stack.add(route)
    }

    /** Öffnet den Player, ohne den bisherigen Weg zu verlieren. */
    fun openPlayer() {
        if (stack.lastOrNull() == Route.Player) return
        stack.add(Route.Player)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun backToRoot() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun encode(): String = stack.joinToString("\n") { it.encode() }

    companion object {
        /**
         * Als eine einzelne Zeichenkette gespeichert, nicht als Liste: Was in ein
         * Bundle darf, ist eng begrenzt, und ein String ist die Form, bei der es
         * garantiert keine Überraschung gibt.
         */
        val Saver: Saver<Navigator, String> = Saver(
            save = { it.encode() },
            restore = { encoded ->
                Navigator(encoded.split('\n').filter { it.isNotEmpty() }.map(Route::decode))
            },
        )
    }
}

@Composable
fun rememberNavigator(start: Route): Navigator =
    rememberSaveable(saver = Navigator.Saver) { Navigator(listOf(start)) }

/**
 * Merkt sich je Bereich, wo der Nutzer zuletzt war.
 *
 * Aus der Senderliste in den Player und zurück — und die Liste steht wieder an
 * derselben Stelle. Ohne das fühlt sich eine Fernseh-App sofort billig an.
 */
class ScrollMemory {
    private val positions = HashMap<String, Pair<Int, Int>>()

    fun save(key: String, index: Int, offset: Int) {
        positions[key] = index to offset
    }

    fun load(key: String): Pair<Int, Int> = positions[key] ?: (0 to 0)
}

@Composable
fun rememberScrollMemory(): ScrollMemory = remember { ScrollMemory() }
