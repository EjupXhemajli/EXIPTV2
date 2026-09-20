package de.exiptv.hd.core

import java.text.Normalizer

/**
 * Normalisierung von Namen für Suche und Sortierung.
 *
 * Jeder Titel wird beim Import einmal durch [normalizeKey] geschickt und als
 * `nameKey` abgelegt; jede Suchanfrage durchläuft dieselbe Funktion. Das hat drei
 * Effekte auf einmal: „Kanal" findet „KANÄLE", die Suche ist indexfähig, weil sie
 * ohne führendes Platzhalterzeichen auskommt, und `%` sowie `_` verschwinden aus
 * der Eingabe, bevor sie in einem LIKE-Muster landen könnten.
 */
object Text {

    private val COMBINING = Regex("\\p{Mn}+")
    private val NON_KEY = Regex("[^a-z0-9 ]+")
    private val MULTI_SPACE = Regex(" {2,}")

    /** Erzeugt den Suchschlüssel: klein, ohne Diakritika, nur Buchstaben, Ziffern, Leerzeichen. */
    fun normalizeKey(input: String): String {
        if (input.isEmpty()) return ""
        val lower = input.lowercase()
        // Deutsche Umlaute zuerst ausschreiben — die Unicode-Zerlegung würde aus
        // „ö" sonst „o" machen, während Nutzer „oe" tippen.
        val expanded = StringBuilder(lower.length + 4)
        for (ch in lower) {
            when (ch) {
                'ä' -> expanded.append("ae")
                'ö' -> expanded.append("oe")
                'ü' -> expanded.append("ue")
                'ß' -> expanded.append("ss")
                else -> expanded.append(ch)
            }
        }
        val decomposed = Normalizer.normalize(expanded, Normalizer.Form.NFD)
        return COMBINING.replace(decomposed, "")
            .replace(NON_KEY, " ")
            .let { MULTI_SPACE.replace(it, " ") }
            .trim()
    }

    /**
     * Schlüssel für die Zuordnung Sender ↔ EPG. XMLTV-Kennungen und
     * Xtream-`epg_channel_id` weichen oft nur in Schreibweise oder Trennzeichen
     * voneinander ab.
     */
    fun epgKey(input: String): String =
        normalizeKey(input).replace(" ", "")

    /** Entfernt Qualitätskürzel, die in Senderlisten die Sortierung durcheinanderbringen. */
    private val QUALITY_SUFFIX = Regex(
        "\\s*[\\[(]?\\b(uhd|4k|fhd|hd|sd|hevc|h265|h264|raw|backup|alt|multi|vip)\\b[\\])]?\\s*",
        RegexOption.IGNORE_CASE,
    )

    fun stripQuality(name: String): String =
        QUALITY_SUFFIX.replace(name, " ").replace(MULTI_SPACE, " ").trim().ifEmpty { name.trim() }

    /** Jahreszahl aus einem Titel oder Feld ziehen; 0, wenn keine plausible gefunden wird. */
    fun parseYear(raw: String?): Int {
        if (raw.isNullOrEmpty()) return 0
        val m = Regex("(19|20)\\d{2}").find(raw) ?: return 0
        return m.value.toIntOrNull()?.takeIf { it in 1900..2100 } ?: 0
    }

    fun parseDoubleOrZero(raw: String?): Double {
        if (raw.isNullOrEmpty()) return 0.0
        return raw.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
    }

    fun parseIntOrZero(raw: String?): Int {
        if (raw.isNullOrEmpty()) return 0
        return raw.trim().toIntOrNull() ?: raw.trim().toDoubleOrNull()?.toInt() ?: 0
    }

    fun parseLongOrZero(raw: String?): Long {
        if (raw.isNullOrEmpty()) return 0L
        return raw.trim().toLongOrNull() ?: raw.trim().toDoubleOrNull()?.toLong() ?: 0L
    }

    /**
     * Erkennt Erwachseneninhalte an Kategorie- und Titelmerkmalen. Läuft einmal
     * beim Import und landet als Boolean-Spalte in der Datenbank; die Listen
     * filtern danach über einen Index statt über zwei Dutzend GLOB-Ausdrücke pro
     * Blatt, wie es die Vorgängerversion tat.
     */
    /** Eindeutig genug, um auch innerhalb eines Wortes zu zählen. */
    private val ADULT_SUBSTRINGS = listOf(
        "xxx", "porn", "brazzers", "hustler", "penthouse", "dorcel", "redlight", "nsfw",
    )

    /** Mehrdeutig — zählt nur als eigenständiges Wort, sonst trifft es „Sex and the City". */
    private val ADULT_WORDS = listOf(
        "adult", "adults", "erotic", "erotik", "erotica", "sex", "sexy", "18",
        "playboy", "vivid", "fsk18", "ab18",
    )

    fun looksAdult(vararg fields: String?): Boolean {
        for (field in fields) {
            if (field.isNullOrEmpty()) continue
            val key = normalizeKey(field)
            if (key.isEmpty()) continue
            if (ADULT_SUBSTRINGS.any { key.contains(it) }) return true
            val padded = " $key "
            if (ADULT_WORDS.any { padded.contains(" $it ") }) return true
        }
        return false
    }
}
