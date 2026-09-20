package de.exiptv.hd.data.net

import android.util.JsonReader
import android.util.JsonToken
import java.io.Reader

/**
 * Streaming-Zugriff auf Xtream-Antworten.
 *
 * `get_live_streams` liefert bei größeren Anbietern zweistellige Megabyte in einem
 * einzigen JSON-Array. Es als String einzulesen und danach zu parsen bedeutet den
 * doppelten Betrag im Speicher, bevor die erste Zeile in der Datenbank landet —
 * auf einer TV-Box mit 1,5 GB RAM ist das der Unterschied zwischen „läuft" und
 * „OutOfMemory".
 *
 * Deshalb wird direkt aus dem Socket gelesen: ein Objekt zur Zeit, sofort in eine
 * Entität umgewandelt, stapelweise geschrieben, dann verworfen. Der Spitzenbedarf
 * hängt nur von der Stapelgröße ab, nicht von der Katalogmenge.
 */
object JsonStream {

    /**
     * Läuft über ein Array flacher Objekte und ruft [onObject] für jedes auf.
     * Ein einzelnes fehlerhaftes Objekt beendet den Durchlauf nicht.
     *
     * @return Anzahl der gelesenen Objekte.
     */
    fun forEachObject(source: Reader, onObject: (Map<String, String>) -> Unit): Int {
        var count = 0
        JsonReader(source).use { reader ->
            reader.isLenient = true
            when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                            reader.skipValue()
                            continue
                        }
                        onObject(readFlatObject(reader))
                        count++
                    }
                    reader.endArray()
                }
                // Manche Panels antworten mit einem Objekt, dessen Werte die
                // eigentlichen Einträge sind — oder mit einer Fehlermeldung.
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        reader.nextName()
                        when (reader.peek()) {
                            JsonToken.BEGIN_OBJECT -> {
                                onObject(readFlatObject(reader))
                                count++
                            }
                            JsonToken.BEGIN_ARRAY -> {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                        onObject(readFlatObject(reader))
                                        count++
                                    } else {
                                        reader.skipValue()
                                    }
                                }
                                reader.endArray()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        return count
    }

    /** Liest genau ein Objekt der obersten Ebene, z. B. die Antwort auf `get_account_info`. */
    fun readObject(source: Reader): Map<String, String> =
        JsonReader(source).use { reader ->
            reader.isLenient = true
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return emptyMap()
            readFlatObject(reader)
        }

    /**
     * Liest ein Objekt und macht alle Werte zu Strings.
     *
     * Xtream ist hier notorisch unzuverlässig: `num` kommt mal als Zahl, mal als
     * String, `rating` mal als `"8.4"`, mal als `8.4`, mal als `null`. Alles als
     * String zu lesen und erst danach gezielt zu konvertieren, macht den Import
     * gegen diese Schwankungen immun.
     *
     * Verschachtelte Objekte werden mit `eltern.kind`-Schlüsseln flachgeklopft,
     * Arrays zu einer kommagetrennten Liste zusammengefasst.
     */
    fun readFlatObject(reader: JsonReader, prefix: String = "", depth: Int = 0): Map<String, String> {
        val out = HashMap<String, String>(24)
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            val key = if (prefix.isEmpty()) name else "$prefix.$name"
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT ->
                    if (depth < 2) out.putAll(readFlatObject(reader, key, depth + 1)) else reader.skipValue()
                JsonToken.BEGIN_ARRAY -> out[key] = readArrayAsString(reader)
                else -> out[key] = readScalar(reader)
            }
        }
        reader.endObject()
        return out
    }

    fun readScalar(reader: JsonReader): String = when (reader.peek()) {
        JsonToken.NULL -> { reader.nextNull(); "" }
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        // nextString() liefert bei NUMBER die Rohdarstellung — genau das wollen wir.
        JsonToken.NUMBER, JsonToken.STRING -> reader.nextString()
        else -> { reader.skipValue(); "" }
    }

    private fun readArrayAsString(reader: JsonReader): String {
        val parts = ArrayList<String>(4)
        reader.beginArray()
        while (reader.hasNext()) {
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT, JsonToken.BEGIN_ARRAY -> reader.skipValue()
                else -> parts += readScalar(reader)
            }
            if (parts.size > 32) break
        }
        while (reader.hasNext()) reader.skipValue()
        reader.endArray()
        return parts.filter { it.isNotEmpty() }.joinToString(",")
    }
}
