package me.enivzoy.anilife.app.data

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the exact files this app writes, so a later change to the Library (or anything else that
 * touches the saved-titles file) cannot quietly change what Android receives or reads.
 *
 * The small fixtures in `golden/` are cut from files this app wrote. The full-size checks at the
 * bottom run only when `ANILIFE_REAL_FILES` points at a folder holding real exports.
 */
class GoldenCompatibilityTest {
    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/golden/$name")) { "Missing golden file $name" }
            .readBytes().decodeToString()

    @Test
    fun aBackupThisAppWroteReadsBackAndIsWrittenOutByteForByte() {
        val golden = resource("windows-backup.json")

        val document = AppBackup.decode(golden)

        assertEquals(15, document.watchlist.size)
        assertEquals(7, document.history.size)
        assertEquals(golden, AppBackup.encode(document))
    }

    @Test
    fun aMalExportThisAppWroteIsWrittenOutByteForByte() {
        val golden = resource("windows-mal-export.xml")

        val entries = parseGoldenEntries(golden)
        val rewritten = MalExport.fromEntries(null, entries, 0, LocalDate.of(2026, 11, 16))

        assertEquals(15, entries.size)
        assertEquals(golden, rewritten.xml)
        assertEquals("Anilife-mal-export-20261116.xml", rewritten.fileName)
    }

    @Test
    fun theGoldenExportStillImportsEntryForEntry() {
        val parsed = MalImport.parse(resource("windows-mal-export.xml").toByteArray())

        assertEquals(15, parsed.size)
        assertEquals(7, parsed.count { it.status == "Watching" })
        assertEquals(8, parsed.count { it.status == "Plan to Watch" })
    }

    @Test
    fun theSettingsWrittenAreTheSixTheFileAlwaysHadPlusTheOneNewSharedSetting() {
        val golden = AppBackup.decode(resource("windows-backup.json")).settings
        val settings = AppSettings(
            autoplayNextEpisode = false,
            audio = "sub",
            subtitlesByDefault = true,
            autoSkipIntro = false,
            autoSkipOutro = false,
            skipButtonSeconds = 30,
            subtitleLanguage = "en",
        )

        val written = BackupSettings.toBackup(settings)

        // Every key and value in the file from before is still written exactly as it was.
        golden.forEach { (key, value) -> assertEquals(value, written[key], key) }
        assertEquals(setOf("sync_saved_to_anilist"), written.keys - golden.keys)
        assertEquals(kotlinx.serialization.json.JsonPrimitive(true), written["sync_saved_to_anilist"])
    }

    // Full-size checks against real files; skipped unless ANILIFE_REAL_FILES is set.

    private fun real(name: String): String? =
        System.getenv("ANILIFE_REAL_FILES")?.let { File(it, name).takeIf(File::exists)?.readText() }

    @Test
    fun theRealAndroidBackupStillRestoresWithTheSameCounts() {
        val text = real("Anilife-backup-20261115.json")
        assumeTrue(text != null)

        val document = AppBackup.decode(text!!)

        assertEquals(1163, document.watchlist.size)
        assertEquals(7, document.history.size)
        // The six shared settings it always had, plus "sync saved titles", which both apps now share.
        assertEquals(7, BackupSettings.applyTo(AppSettings(), document.settings).second)
    }

    @Test
    fun theRealWindowsBackupIsWrittenOutByteForByte() {
        val text = real("Anilife-backup-20261116.json")
        assumeTrue(text != null)

        assertEquals(text, AppBackup.encode(AppBackup.decode(text!!)))
    }

    @Test
    fun theRealXmlFilesStillImportAllEntriesAndTheWindowsOneIsWrittenOutByteForByte() {
        val android = real("Anilife-mal-export-20261115.xml")
        val windows = real("Anilife-mal-export-20261116.xml")
        assumeTrue(android != null && windows != null)

        assertEquals(1163, MalImport.parse(android!!.toByteArray()).size)
        assertEquals(1163, MalImport.parse(windows!!.toByteArray()).size)
        val rewritten = MalExport.fromEntries(null, parseGoldenEntries(windows), 0, LocalDate.of(2026, 11, 16))
        assertEquals(windows, rewritten.xml)
    }

    /** Reads every field the writer emits, so rewriting them must give the file back unchanged. */
    private fun parseGoldenEntries(xml: String): List<MalExportEntry> {
        fun block(tag: String, text: String) =
            Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(text)!!.groupValues[1]
        fun unescape(value: String) = value
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
        return Regex("<anime>(.*?)</anime>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { match ->
            val text = match.groupValues[1]
            MalExportEntry(
                malId = block("series_animedb_id", text).toLong(),
                title = unescape(block("series_title", text)),
                format = block("series_type", text),
                episodes = block("series_episodes", text).toInt().takeIf { it > 0 },
                watchedEpisodes = block("my_watched_episodes", text).toInt(),
                score = block("my_score", text).toInt(),
                status = unescape(block("my_status", text)),
                rewatching = block("my_rewatching", text) == "1",
            )
        }.toList()
    }
}
