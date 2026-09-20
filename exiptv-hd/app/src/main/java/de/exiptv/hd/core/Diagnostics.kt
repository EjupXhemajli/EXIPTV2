package de.exiptv.hd.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Protokoll für die Diagnoseansicht in den Einstellungen.
 *
 * Wichtig ist hier vor allem, was NICHT hineingerät: Wiedergabeadressen enthalten
 * bei Xtream Benutzername und Passwort im Pfad. [redact] entfernt sie, bevor eine
 * Zeile geschrieben wird — auch dann, wenn eine Ausnahme die Adresse in ihrer
 * Meldung trägt.
 */
object Diagnostics {

    private const val TAG = "EXIPTV"
    private const val MAX_LINES = 300

    private val lines = CopyOnWriteArrayList<String>()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val clock = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)
    }

    /** Ersetzt Zugangsdaten in Xtream-Adressen durch Platzhalter. */
    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var out = text
        // .../live/BENUTZER/PASSWORT/1234.ts
        out = Regex("/(live|movie|series)/[^/\\s]+/[^/\\s]+/")
            .replace(out) { "/${it.groupValues[1]}/***/***/" }
        // ...player_api.php?username=...&password=...
        out = Regex("([?&](?:username|password|pass|user))=[^&\\s]*", RegexOption.IGNORE_CASE)
            .replace(out) { "${it.groupValues[1]}=***" }
        return out
    }

    fun info(message: String) = write("·", message)

    fun warn(message: String) = write("!", message)

    fun error(message: String, t: Throwable? = null) {
        write("✕", if (t == null) message else "$message — ${describe(t)}")
    }

    /** Kurzbeschreibung einer Ausnahme, die für die Fehlersuche im Feld taugt. */
    fun describe(t: Throwable): String {
        val parts = mutableListOf<String>()
        var cur: Throwable? = t
        var depth = 0
        while (depth < 4) {
            val current = cur ?: break
            val msg = current.message?.takeIf { it.isNotBlank() }
            parts += if (msg == null) current.javaClass.simpleName else "${current.javaClass.simpleName}: $msg"
            val next = current.cause
            cur = if (next === current) null else next
            depth++
        }
        return redact(parts.joinToString(" ← "))
    }

    private fun write(marker: String, message: String) {
        val clean = redact(message)
        val line = "${clock.get()!!.format(Date())} $marker $clean"
        lines.add(line)
        while (lines.size > MAX_LINES) lines.removeAt(0)
        _log.value = lines.toList()
        Log.i(TAG, "$marker $clean")
    }

    fun clear() {
        lines.clear()
        _log.value = emptyList()
    }
}

/**
 * Zeitformatierung ohne java.time — das stünde unter minSdk 23 nur mit
 * Library-Desugaring zur Verfügung, und dafür eine weitere Build-Abhängigkeit
 * einzuziehen wäre für ein paar Uhrzeiten nicht verhältnismäßig.
 *
 * SimpleDateFormat ist nicht threadsicher, deshalb liegt jede Instanz in einem
 * ThreadLocal. Genau diese Falle stand in der Vorgängerversion als gemeinsam
 * genutztes Feld offen.
 */
object Clock {

    private fun local(pattern: String) = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat(pattern, Locale.GERMANY)
    }

    private val hourMinute = local("HH:mm")
    private val dayMonth = local("dd.MM.")
    private val dayMonthYear = local("dd.MM.yyyy")
    private val weekdayShort = local("EE")
    private val weekdayDateTime = local("EE dd.MM. HH:mm")

    /** Zeitstempel im Format, das die Catchup-Endpunkte der Panels erwarten. */
    private val epgStamp = local("yyyy-MM-dd:HH-mm")

    fun time(ms: Long): String = hourMinute.get()!!.format(Date(ms))
    fun date(ms: Long): String = dayMonth.get()!!.format(Date(ms))
    fun dateFull(ms: Long): String = dayMonthYear.get()!!.format(Date(ms))
    fun weekday(ms: Long): String = weekdayShort.get()!!.format(Date(ms))
    fun full(ms: Long): String = weekdayDateTime.get()!!.format(Date(ms))
    fun epgTimestamp(ms: Long): String = epgStamp.get()!!.format(Date(ms))

    /** Mitternacht des Tages, in dem [ms] liegt — Basis für die EPG-Tagesleiste. */
    fun startOfDay(ms: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun plusDays(ms: Long, days: Int): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms
        cal.add(Calendar.DAY_OF_YEAR, days)
        return cal.timeInMillis
    }

    /** Dauer als „1 Std. 42 Min." oder „42 Min."; für Laufzeiten und Restzeiten. */
    fun duration(ms: Long): String {
        if (ms <= 0L) return ""
        val totalMinutes = (ms / 60_000L).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "$hours Std. $minutes Min."
            hours > 0 -> "$hours Std."
            else -> "$minutes Min."
        }
    }

    /** Positionsanzeige im Player: „1:04:12" oder „4:12". */
    fun position(ms: Long): String {
        val safe = if (ms < 0L) 0L else ms
        val totalSeconds = safe / 1000L
        val h = totalSeconds / 3600L
        val m = (totalSeconds % 3600L) / 60L
        val s = totalSeconds % 60L
        return if (h > 0L) {
            String.format(Locale.GERMANY, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.GERMANY, "%d:%02d", m, s)
        }
    }
}
