package me.enivzoy.anilife.app.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Cases the Android app's own tests cover (MalImportTest, AppBackupTest), run against this app, so
 * the two stay in step on what they accept, refuse and call things.
 */
class AndroidParityCasesTest {
    private fun gzip(bytes: ByteArray) =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    @Test
    fun aMalExportPastTheSizeLimitIsRefusedWithTheSameReason() {
        val error = assertFailsWith<IllegalArgumentException> {
            MalImport.parse(ByteArray(MalImport.MAX_SOURCE_BYTES + 1))
        }

        assertEquals("The MAL export is larger than the 16 MB safety limit.", error.message)
    }

    @Test
    fun aGzipThatExpandsPastTheLimitIsRefusedWithTheSameReason() {
        val error = assertFailsWith<IllegalArgumentException> {
            MalImport.parse(gzip(ByteArray(MalImport.MAX_EXPANDED_XML_BYTES + 1)))
        }

        assertEquals("The expanded MAL export is larger than the 32 MB safety limit.", error.message)
    }

    @Test
    fun aDoctypeIsRefusedInsteadOfResolvingAnExternalEntity() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE myanimelist [<!ENTITY local SYSTEM "file:///etc/passwd">]>
            <myanimelist><anime><series_animedb_id>1</series_animedb_id><series_title>&local;</series_title></anime></myanimelist>
        """.trimIndent()

        val error = assertFailsWith<IllegalArgumentException> { MalImport.parse(xml.toByteArray()) }

        assertEquals("MyAnimeList exports with document type or entity declarations are not supported.", error.message)
    }

    @Test
    fun everyStatusSpellingResolvesTheWayAndroidResolvesIt() {
        assertEquals("Watching", MalImport.normalizeStatus("watching"))
        assertEquals("Watching", MalImport.normalizeStatus("1"))
        assertEquals("Completed", MalImport.normalizeStatus("2"))
        assertEquals("On-Hold", MalImport.normalizeStatus("3"))
        assertEquals("On-Hold", MalImport.normalizeStatus("On-Hold"))
        assertEquals("Dropped", MalImport.normalizeStatus("4"))
        assertEquals("Plan to Watch", MalImport.normalizeStatus("Plan to Watch"))
        assertEquals("Plan to Watch", MalImport.normalizeStatus(null))
    }

    @Test
    fun anEntryTheAndroidSampleCallsFullmetalAlchemistImportsWithItsFields() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <myanimelist>
              <anime><series_animedb_id>5114</series_animedb_id><series_title>Fullmetal Alchemist: Brotherhood &amp; more</series_title><series_episodes>64</series_episodes><my_watched_episodes>64</my_watched_episodes><my_status>Completed</my_status></anime>
              <anime><series_animedb_id>21</series_animedb_id><series_title>One Piece</series_title><my_watched_episodes>250</my_watched_episodes><my_status>1</my_status></anime>
              <anime><series_animedb_id>21</series_animedb_id><series_title>One Piece duplicate</series_title><my_status>Watching</my_status></anime>
              <anime><series_animedb_id>0</series_animedb_id><series_title>Broken entry without id</series_title></anime>
            </myanimelist>
        """.trimIndent()

        val entries = MalImport.parse(xml.toByteArray())

        assertEquals(
            listOf(
                MalImportEntry(5114, "Fullmetal Alchemist: Brotherhood & more", 64, "Completed"),
                MalImportEntry(21, "One Piece", 250, "Watching"),
            ),
            entries,
        )
    }

    @Test
    fun theBackupFileNameIsDatedSoTwoBackupsDoNotReadAsOne() {
        assertEquals("Anilife-backup-20260823.json", AppBackup.fileName(LocalDate.of(2026, 8, 23)))
    }

    @Test
    fun describingABackupSkipsTheSectionsItHasNoneOf() {
        val full = BackupDocument(
            settings = buildJsonObject { put("autoplay", false); put("prefer_dub", true) },
            watchlist = listOf(BackupWatchlistEntry(1, "A", null, null, null, 1)),
        )

        assertEquals("2 settings · 1 saved", full.describe())
        assertEquals("1 setting", BackupDocument(settings = buildJsonObject { put("autoplay", true) }).describe())
        assertEquals("nothing", BackupDocument().describe())
    }

    @Test
    fun aRestoreThatChangedNothingReportsAsMuchAndOneThatDidNamesWhatLanded() {
        assertEquals("nothing new", RestoreSummary(0, 0, 0).describe())
        assertEquals("3 settings · 1 continue watching", RestoreSummary(3, 0, 1).describe())
        assertEquals(
            "30 settings",
            RestoreSummary(30, 0, 0, warnings = listOf("A sign-in in that backup was refused.")).describe(),
        )
    }
}
