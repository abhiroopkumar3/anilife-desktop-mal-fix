package me.enivzoy.anilife.app.data

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * MyAnimeList XML export and import, matching the Android and TV apps entry for entry so a list
 * made on one opens on the other and on MyAnimeList's own import page.
 */
data class MalExportEntry(
    val malId: Long,
    val title: String,
    val format: String?,
    val episodes: Int?,
    val watchedEpisodes: Int,
    val score: Int,
    val status: String,
    val rewatching: Boolean = false,
)

data class MalExportFile(
    val fileName: String,
    val xml: String,
    val exportedCount: Int,
    val skippedCount: Int,
    /** Something to tell the viewer about how the file was made, such as an account that was unreachable. */
    val note: String? = null,
)

object MalExport {
    fun fromEntries(
        username: String?,
        entries: List<MalExportEntry>,
        skippedCount: Int,
        date: LocalDate = LocalDate.now(),
    ): MalExportFile {
        val fileName = "Anilife-mal-export-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}.xml"
        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8" ?>""")
            appendLine("<myanimelist>")
            appendLine("  <myinfo>")
            appendLine("    <user_id>0</user_id>")
            appendLine("    <user_name>${xml(username ?: "Anilife")}</user_name>")
            appendLine("    <user_export_type>1</user_export_type>")
            appendLine("  </myinfo>")
            entries.forEach { entry ->
                appendLine("  <anime>")
                appendLine("    <series_animedb_id>${entry.malId}</series_animedb_id>")
                appendLine("    <series_title>${xml(entry.title)}</series_title>")
                appendLine("    <series_type>${xml(format(entry.format))}</series_type>")
                appendLine("    <series_episodes>${entry.episodes ?: 0}</series_episodes>")
                appendLine("    <my_id>0</my_id>")
                appendLine("    <my_watched_episodes>${entry.watchedEpisodes.coerceAtLeast(0)}</my_watched_episodes>")
                appendLine("    <my_start_date>0000-00-00</my_start_date>")
                appendLine("    <my_finish_date>0000-00-00</my_finish_date>")
                appendLine("    <my_score>${entry.score.coerceIn(0, 10)}</my_score>")
                appendLine("    <my_status>${xml(entry.status)}</my_status>")
                appendLine("    <my_comments></my_comments>")
                appendLine("    <my_times_watched>0</my_times_watched>")
                appendLine("    <my_rewatch_value></my_rewatch_value>")
                appendLine("    <my_tags></my_tags>")
                appendLine("    <my_rewatching>${if (entry.rewatching) 1 else 0}</my_rewatching>")
                appendLine("    <my_rewatching_ep>0</my_rewatching_ep>")
                appendLine("    <update_on_import>1</update_on_import>")
                appendLine("  </anime>")
            }
            appendLine("</myanimelist>")
        }
        return MalExportFile(fileName, xml, entries.size, skippedCount)
    }

    /** An account list status as the XML spells it, and whether the title is being rewatched. */
    fun statusFromAccount(status: AniListListStatus): Pair<String, Boolean> = when (status) {
        AniListListStatus.CURRENT -> "Watching" to false
        AniListListStatus.REPEATING -> "Watching" to true
        AniListListStatus.COMPLETED -> "Completed" to false
        AniListListStatus.PAUSED -> "On-Hold" to false
        AniListListStatus.DROPPED -> "Dropped" to false
        AniListListStatus.PLANNING -> "Plan to Watch" to false
    }

    /** One account list entry as an export row; a finished title with no count recorded counts as all watched. */
    fun entryFromAccount(entry: AccountEntry): MalExportEntry? {
        val malId = entry.malId ?: return null
        val (status, rewatching) = statusFromAccount(entry.status)
        val watched = if (status == "Completed" && entry.progress <= 0 && entry.episodes != null) entry.episodes else entry.progress
        return MalExportEntry(
            malId = malId,
            title = entry.title,
            format = entry.format,
            episodes = entry.episodes,
            watchedEpisodes = watched,
            score = entry.score,
            status = status,
            rewatching = rewatching,
        )
    }

    fun statusFromLocal(progress: Int, episodes: Int?): String = when {
        progress <= 0 -> "Plan to Watch"
        episodes != null && episodes > 0 && progress >= episodes -> "Completed"
        else -> "Watching"
    }

    private fun format(value: String?): String = when (value?.uppercase(Locale.US)) {
        "MOVIE" -> "Movie"
        "SPECIAL" -> "Special"
        "OVA" -> "OVA"
        "ONA" -> "ONA"
        "MUSIC" -> "Music"
        else -> "TV"
    }

    private fun xml(value: String): String = buildString {
        value.forEach { ch ->
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch == '\'' -> append("&apos;")
                // Characters XML 1.0 cannot carry at all would make the whole file unreadable.
                ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> Unit
                else -> append(ch)
            }
        }
    }
}

/** One `<anime>` entry from a MyAnimeList XML export. */
data class MalImportEntry(
    val malId: Long,
    val title: String,
    val watchedEpisodes: Int,
    val status: String,
)

/** What an import run did, for the result line. */
data class MalImportSummary(
    val totalEntries: Int,
    val added: Int,
    val alreadySaved: Int,
    val unmatched: Int,
    /** What happened to the signed-in account's Plan to Watch, when this import touched it. */
    val accountNote: String? = null,
)

object MalImport {
    const val MAX_SOURCE_BYTES = 16 * 1024 * 1024
    const val MAX_EXPANDED_XML_BYTES = 32 * 1024 * 1024

    /**
     * Parses a MyAnimeList XML export. MAL serves exports gzipped (`.xml.gz`), so both raw XML and
     * gzip are accepted. Throws on files that are not a MAL export at all; entries without a usable
     * MAL id are skipped.
     */
    fun parse(bytes: ByteArray): List<MalImportEntry> {
        require(bytes.size <= MAX_SOURCE_BYTES) { "The MAL export is larger than the 16 MB safety limit." }
        val xmlBytes = if (isGzip(bytes)) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { stream ->
                stream.readLimited(
                    maxBytes = MAX_EXPANDED_XML_BYTES,
                    tooLargeMessage = "The expanded MAL export is larger than the 32 MB safety limit.",
                )
            }
        } else {
            bytes
        }
        rejectUnsafeDeclarations(xmlBytes)
        val factory = DocumentBuilderFactory.newInstance().apply {
            // The file comes from outside the app; never resolve external entities.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { isXIncludeAware = false }
            isExpandEntityReferences = false
            isNamespaceAware = false
            isValidating = false
        }
        val document = try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
        } catch (error: org.xml.sax.SAXException) {
            throw IllegalArgumentException("That file is not a MyAnimeList export.", error)
        }
        require(document.documentElement?.tagName == "myanimelist") { "That file is not a MyAnimeList export." }
        val nodes = document.getElementsByTagName("anime")
        val seen = mutableSetOf<Long>()
        return buildList {
            for (i in 0 until nodes.length) {
                val anime = nodes.item(i) as? Element ?: continue
                val malId = anime.text("series_animedb_id")?.toLongOrNull()?.takeIf { it > 0 } ?: continue
                if (!seen.add(malId)) continue
                add(
                    MalImportEntry(
                        malId = malId,
                        title = anime.text("series_title").orEmpty(),
                        watchedEpisodes = anime.text("my_watched_episodes")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                        status = normalizeStatus(anime.text("my_status")),
                    ),
                )
            }
        }
    }

    /** Old exports and some third-party tools write the numeric status codes. */
    internal fun normalizeStatus(raw: String?): String =
        when (raw?.trim()?.lowercase(Locale.US)?.replace(" ", "")) {
            "watching", "1" -> "Watching"
            "completed", "2" -> "Completed"
            "on-hold", "onhold", "3" -> "On-Hold"
            "dropped", "4" -> "Dropped"
            else -> "Plan to Watch"
        }

    private fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()

    private fun rejectUnsafeDeclarations(bytes: ByteArray) {
        // MAL exports are UTF-8. Reject NUL-containing encodings so a UTF-16 declaration cannot hide
        // its markup from this ASCII-compatible scan and then be interpreted by the parser.
        require(bytes.none { it == 0.toByte() }) { "Unsupported MyAnimeList XML encoding." }
        val markup = String(bytes, Charsets.ISO_8859_1).uppercase(Locale.US)
        require("<!DOCTYPE" !in markup && "<!ENTITY" !in markup) {
            "MyAnimeList exports with document type or entity declarations are not supported."
        }
    }

    private fun Element.text(tag: String): String? =
        getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun InputStream.readLimited(maxBytes: Int, tooLargeMessage: String): ByteArray {
        val output = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_SIZE, maxBytes))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { tooLargeMessage }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
