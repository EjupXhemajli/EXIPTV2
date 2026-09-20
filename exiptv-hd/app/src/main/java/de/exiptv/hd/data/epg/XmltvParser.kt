package de.exiptv.hd.data.epg

import android.util.Xml
import de.exiptv.hd.core.Text
import de.exiptv.hd.data.db.EpgEntry
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone

/**
 * XMLTV-Dateien streamend einlesen.
 *
 * Eine Woche Programm für 500 Sender sind unkomprimiert schnell 200 MB. Der
 * Pull-Parser des Systems läuft ereignisbasiert über den Strom, sodass nie mehr
 * als eine Sendung gleichzeitig im Speicher liegt. Der Aufrufer bekommt fertige
 * Stapel und schreibt sie direkt weg.
 */
class XmltvParser {

    /** Zuordnung XMLTV-Kennung → normalisierter Senderschlüssel. */
    fun parse(
        input: InputStream,
        knownChannelKeys: Set<String>,
        windowStart: Long,
        windowEnd: Long,
        batchSize: Int,
        onBatch: (List<EpgEntry>) -> Unit,
    ): Int {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        // XMLTV nennt Sender über <channel id="...">; die Anzeigenamen darin sind
        // oft die einzige Brücke zu einer Senderliste ohne tvg-id.
        val aliasToKey = HashMap<String, String>(1024)
        val batch = ArrayList<EpgEntry>(batchSize)
        var total = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> readChannel(parser, knownChannelKeys, aliasToKey)
                    "programme" -> {
                        val entry = readProgramme(parser, aliasToKey, knownChannelKeys)
                        if (entry != null && entry.endAt > windowStart && entry.startAt < windowEnd) {
                            batch += entry
                            total++
                            if (batch.size >= batchSize) {
                                onBatch(ArrayList(batch))
                                batch.clear()
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }

        if (batch.isNotEmpty()) onBatch(ArrayList(batch))
        return total
    }

    private fun readChannel(
        parser: XmlPullParser,
        knownChannelKeys: Set<String>,
        aliasToKey: MutableMap<String, String>,
    ) {
        val rawId = parser.getAttributeValue(null, "id").orEmpty()
        if (rawId.isEmpty()) {
            skipTag(parser)
            return
        }
        val idKey = Text.epgKey(rawId)
        val aliases = ArrayList<String>(4)
        aliases += idKey

        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "display-name") {
                        // nextText() liest den Inhalt und konsumiert das END_TAG
                        // gleich mit — die Tiefe bleibt deshalb unverändert.
                        val name = parser.nextText().orEmpty()
                        if (name.isNotEmpty()) aliases += Text.epgKey(name)
                    } else {
                        depth++
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }

        // Der erste Alias, der zu einem tatsächlich vorhandenen Sender passt,
        // gewinnt. Alle Aliase zeigen danach auf denselben Schlüssel.
        val matched = aliases.firstOrNull { it.isNotEmpty() && knownChannelKeys.contains(it) }
        val target = matched ?: idKey
        for (alias in aliases) {
            if (alias.isNotEmpty() && !aliasToKey.containsKey(alias)) aliasToKey[alias] = target
        }
    }

    private fun readProgramme(
        parser: XmlPullParser,
        aliasToKey: Map<String, String>,
        knownChannelKeys: Set<String>,
    ): EpgEntry? {
        val rawChannel = parser.getAttributeValue(null, "channel").orEmpty()
        val start = parseXmltvTime(parser.getAttributeValue(null, "start"))
        val stop = parseXmltvTime(parser.getAttributeValue(null, "stop"))

        val aliasKey = Text.epgKey(rawChannel)
        val channelKey = aliasToKey[aliasKey] ?: aliasKey

        var title = ""
        var description = ""

        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "title" -> {
                            if (title.isEmpty()) title = parser.nextText().orEmpty().trim()
                            continue
                        }
                        "desc" -> {
                            if (description.isEmpty()) description = parser.nextText().orEmpty().trim()
                            continue
                        }
                        else -> depth++
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> depth = 0
            }
        }

        if (channelKey.isEmpty() || start <= 0L || stop <= start) return null
        if (knownChannelKeys.isNotEmpty() && !knownChannelKeys.contains(channelKey)) return null

        return EpgEntry(
            channelKey = channelKey,
            startAt = start,
            endAt = stop,
            title = title.ifEmpty { "Sendung" },
            description = description,
        )
    }

    private fun skipTag(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /**
     * XMLTV-Zeitstempel: `20260919203000 +0200`, manchmal ohne Zeitzone,
     * manchmal nur auf die Minute genau. Alle drei Formen kommen in der Praxis vor.
     */
    fun parseXmltvTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val text = raw.trim()
        val digits = StringBuilder(14)
        var i = 0
        while (i < text.length && digits.length < 14) {
            val c = text[i]
            if (c.isDigit()) digits.append(c) else if (c == '+' || c == '-') break
            i++
        }
        if (digits.length < 8) return 0L
        while (digits.length < 14) digits.append('0')

        val year = digits.substring(0, 4).toIntOrNull() ?: return 0L
        val month = digits.substring(4, 6).toIntOrNull() ?: return 0L
        val day = digits.substring(6, 8).toIntOrNull() ?: return 0L
        val hour = digits.substring(8, 10).toIntOrNull() ?: 0
        val minute = digits.substring(10, 12).toIntOrNull() ?: 0
        val second = digits.substring(12, 14).toIntOrNull() ?: 0

        // Zeitzonenversatz am Ende, z. B. „+0200"
        var offsetMinutes = 0
        var hasOffset = false
        // Erst hinter dem Datumsblock suchen, damit ein Bindestrich innerhalb des
        // Datums nicht als Vorzeichen des Zeitzonenversatzes gelesen wird.
        val signIndex = text.indexOfLast { it == '+' || it == '-' }
        if (signIndex >= 8) {
            val sign = if (text[signIndex] == '-') -1 else 1
            val offsetDigits = text.substring(signIndex + 1).filter { it.isDigit() }
            if (offsetDigits.length >= 4) {
                val oh = offsetDigits.substring(0, 2).toIntOrNull() ?: 0
                val om = offsetDigits.substring(2, 4).toIntOrNull() ?: 0
                offsetMinutes = sign * (oh * 60 + om)
                hasOffset = true
            }
        }

        val cal = Calendar.getInstance(if (hasOffset) TimeZone.getTimeZone("UTC") else TimeZone.getDefault())
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        var ms = cal.timeInMillis
        if (hasOffset) ms -= offsetMinutes * 60_000L
        return ms
    }
}
