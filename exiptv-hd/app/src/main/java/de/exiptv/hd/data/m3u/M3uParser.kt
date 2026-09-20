package de.exiptv.hd.data.m3u

import de.exiptv.hd.core.Text
import de.exiptv.hd.data.db.CategoryEntity
import de.exiptv.hd.data.db.ChannelEntity
import de.exiptv.hd.data.db.ContentKind
import de.exiptv.hd.data.db.Stage
import java.io.BufferedReader

/**
 * M3U-Playlisten zeilenweise einlesen.
 *
 * Eine Senderliste mit 80.000 Einträgen ist als Datei schnell 60 MB groß. Sie
 * zuerst vollständig in den Speicher zu holen, scheitert auf TV-Boxen zuverlässig;
 * deshalb liest dieser Parser Zeile für Zeile aus dem Netzwerkstrom und gibt
 * fertige Stapel an den Aufrufer weiter.
 *
 * Überlange Zeilen werden abgeschnitten statt den Speicher zu sprengen: Eine
 * einzelne `#EXTINF`-Zeile über 64 KB ist kein gültiger Eintrag mehr, sondern ein
 * kaputter Server.
 */
class M3uParser(private val providerId: Long) {

    companion object {
        private const val MAX_LINE = 64 * 1024
        private val ATTRIBUTE = Regex("([a-zA-Z0-9-]+)=\"([^\"]*)\"")
    }

    data class Result(
        val channels: Int,
        val categories: List<CategoryEntity>,
    )

    /**
     * @param onBatch wird blockierend aufgerufen und schreibt direkt in die
     *        Datenbank — derselbe Aufbau wie beim Xtream-Import.
     */
    fun parse(
        reader: BufferedReader,
        batchSize: Int,
        onBatch: (List<ChannelEntity>) -> Unit,
    ): Result {
        val batch = ArrayList<ChannelEntity>(batchSize)
        val groups = LinkedHashMap<String, String>()
        var total = 0

        var pendingName = ""
        var pendingLogo = ""
        var pendingGroup = ""
        var pendingEpgId = ""
        var pendingNumber = 0
        var pendingCatchupDays = 0
        var hasPending = false

        var line: String?
        while (true) {
            line = reader.readLine() ?: break
            if (line.length > MAX_LINE) line = line.substring(0, MAX_LINE)
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            when {
                trimmed.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attributes = ATTRIBUTE.findAll(trimmed)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }

                    pendingName = trimmed.substringAfterLast(',', "").trim()
                    pendingLogo = attributes["tvg-logo"].orEmpty()
                    pendingGroup = attributes["group-title"].orEmpty().trim()
                    pendingEpgId = attributes["tvg-id"].orEmpty().ifEmpty { attributes["tvg-name"].orEmpty() }
                    pendingNumber = Text.parseIntOrZero(attributes["tvg-chno"])
                    pendingCatchupDays = Text.parseIntOrZero(
                        attributes["catchup-days"] ?: attributes["timeshift"] ?: attributes["tvg-rec"]
                    )
                    if (pendingName.isEmpty()) pendingName = attributes["tvg-name"].orEmpty()
                    hasPending = pendingName.isNotEmpty()
                }

                // Zusatzzeilen zwischen #EXTINF und der Adresse (User-Agent,
                // Referrer, VLC-Optionen) überspringen wir bewusst.
                trimmed.startsWith("#") -> Unit

                hasPending -> {
                    val url = trimmed
                    if (url.startsWith("http", ignoreCase = true) || url.startsWith("rtmp", ignoreCase = true)) {
                        val groupName = pendingGroup.ifEmpty { "Ohne Gruppe" }
                        val categoryExternal = Text.normalizeKey(groupName).ifEmpty { "sonstige" }
                        // Kein putIfAbsent: das ist auf Android erst ab API 24
                        // verfügbar, die App läuft aber ab API 23.
                        if (!groups.containsKey(categoryExternal)) {
                            groups[categoryExternal] = groupName
                        }

                        batch += ChannelEntity(
                            id = "p$providerId:c:${stableId(url, pendingName)}",
                            providerId = providerId,
                            number = if (pendingNumber > 0) pendingNumber else total + 1,
                            name = pendingName,
                            nameKey = Text.normalizeKey(pendingName),
                            logo = pendingLogo,
                            categoryId = "p$providerId:live:$categoryExternal",
                            groupTitle = groupName,
                            streamUrl = url,
                            epgKey = Text.epgKey(pendingEpgId.ifEmpty { pendingName }),
                            catchupDays = pendingCatchupDays,
                            catchupSource = "",
                            isAdult = Text.looksAdult(pendingName, groupName),
                            sortIndex = total,
                            stage = Stage.IMPORT,
                        )
                        total++

                        if (batch.size >= batchSize) {
                            onBatch(ArrayList(batch))
                            batch.clear()
                        }
                    }
                    hasPending = false
                }
            }
        }

        if (batch.isNotEmpty()) onBatch(ArrayList(batch))

        val categories = groups.entries.mapIndexed { index, (external, name) ->
            CategoryEntity(
                id = "p$providerId:live:$external",
                providerId = providerId,
                kind = ContentKind.LIVE,
                externalId = external,
                name = name,
                sortIndex = index,
                stage = Stage.IMPORT,
            )
        }
        return Result(total, categories)
    }

    /**
     * Stabile ID aus Adresse und Name.
     *
     * M3U-Listen haben keine Kennungen. Würde man laufende Nummern verwenden,
     * bekäme jeder Sender nach einem Import, bei dem ein Eintrag weggefallen ist,
     * eine andere ID — Favoriten und Wiedergabepositionen zeigten dann auf den
     * falschen Sender. Ein Hash über Adresse und Name bleibt dagegen stabil,
     * solange der Eintrag selbst stabil ist.
     */
    private fun stableId(url: String, name: String): String {
        var hash = -0x7ee3623bL // FNV-1a, 64 Bit
        val prime = 0x100000001b3L
        for (ch in url) {
            hash = hash xor ch.code.toLong()
            hash *= prime
        }
        for (ch in name) {
            hash = hash xor ch.code.toLong()
            hash *= prime
        }
        return java.lang.Long.toHexString(hash)
    }
}
