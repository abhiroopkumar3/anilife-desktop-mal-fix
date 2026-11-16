package me.enivzoy.anilife.app.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.enivzoy.anilife.core.model.AnimeCard
import me.enivzoy.anilife.core.net.AniListClient
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A backup as the Android app writes it: the shape, not anyone's data. */
private val androidBackup = """
{
    "format": "anilili-backup",
    "version": 1,
    "createdAt": 1794787825907,
    "appVersion": "0.1.3",
    "settings": {
        "autoplay": false,
        "prefer_dub": true,
        "subtitles_by_default": false,
        "auto_skip_intro_outro": true,
        "skip_button_seconds": 30,
        "preferred_subtitle_language": "fra",
        "caption_text_scale": 100,
        "home_tab": "library"
    },
    "watchlist": [
        {"anilistId": 101, "title": "First Show", "cover": "https://img/101.jpg", "format": "TV", "averageScore": 80, "addedAt": 5},
        {"anilistId": 102, "title": "#Hash Show", "cover": null, "addedAt": 4},
        {"title": "No id, skipped"}
    ],
    "history": [
        {"anilistId": 101, "title": "First Show", "cover": null, "episodeNumber": 7.0, "provider": "anikoto",
         "category": "sub", "positionMs": 0, "durationMs": 0, "updatedAt": 900, "watchedEpisodes": [1.0, 2.0],
         "fromRemote": false, "resumePoints": {"3": {"positionMs": 10, "durationMs": 20}}},
        {"anilistId": 102, "title": "#Hash Show", "cover": null, "episodeNumber": 2.0, "provider": "x",
         "category": "dub", "positionMs": 600000, "durationMs": 1440000, "updatedAt": 800}
    ],
    "searchHistory": ["one", "two", "three"],
    "customLists": [{"name": "Favs"}],
    "watched": [],
    "credentials": {"kdf": "pbkdf2", "salt": "x", "iv": "y", "ciphertext": "z", "services": ["anilist"]},
    "someFutureField": true
}
""".trimIndent()

class AppBackupTest {
    @Test
    fun readsAnAndroidBackupIncludingSectionsThisAppHasNoPlaceFor() {
        val document = AppBackup.decode(androidBackup)

        assertEquals(2, document.watchlist.size)
        assertEquals("#Hash Show", document.watchlist[1].title)
        assertNull(document.watchlist[1].cover)
        assertEquals(2, document.history.size)
        assertEquals(7.0, document.history[0].episodeNumber)
        assertEquals(3, document.searchHistory.size)
        assertEquals(1, document.customListCount)
        assertEquals(listOf("anilist"), document.loginServices)
        assertEquals("0.1.3", document.appVersion)
        assertEquals("8 settings · 2 saved · 1 list · 2 continue watching · 3 searches · 1 login", document.describe())
    }

    @Test
    fun aBackupWrittenHereReadsBackIdenticallyAndKeepsTheAndroidShape() {
        val original = BackupDocument(
            createdAt = 1234,
            appVersion = "1.0.7",
            settings = BackupSettings.toBackup(AppSettings(audio = "dub", subtitleLanguage = "ja")),
            watchlist = listOf(BackupWatchlistEntry(5, "Five \"quoted\" & <odd>", null, "MOVIE", 77, 99)),
            history = listOf(
                BackupHistoryEntry(5, "Five", "c.jpg", 3.5, null, "", "sub", 1500, 1_440_000, 77, false),
            ),
        )

        val text = AppBackup.encode(original)
        val root = Json.parseToJsonElement(text).jsonObject

        // Fields the Android decoder requires are always present, even when empty or null.
        assertEquals("anilili-backup", root["format"]!!.jsonPrimitive.content)
        assertEquals(1, root["version"]!!.jsonPrimitive.content.toInt())
        for (key in listOf("settings", "watchlist", "history", "searchHistory", "customLists", "watched", "credentials")) {
            assertTrue(key in root, "missing $key")
        }
        val savedRow = root["watchlist"]!!.jsonArray[0].jsonObject
        assertTrue("cover" in savedRow)
        val historyRow = root["history"]!!.jsonArray[0].jsonObject
        for (key in listOf("provider", "category", "episodeNumber", "positionMs", "durationMs", "updatedAt")) {
            assertTrue(key in historyRow, "history row is missing $key")
        }

        val decoded = AppBackup.decode(text)
        assertEquals(original.watchlist, decoded.watchlist)
        assertEquals(original.history, decoded.history)
        assertEquals(original.settings, decoded.settings)
    }

    @Test
    fun refusesFilesThatAreNotBackupsAndSaysWhichKindOfWrong() {
        assertEquals("The chosen file is empty.", assertFailsWith<BackupFormatException> { AppBackup.decode("  ") }.message)
        assertTrue(
            assertFailsWith<BackupFormatException> { AppBackup.decode("<?xml version=\"1.0\"?><myanimelist/>") }
                .message!!.contains("MyAnimeList"),
        )
        assertTrue(
            assertFailsWith<BackupFormatException> { AppBackup.decode("not json at all") }.message!!.contains("not the JSON"),
        )
        assertTrue(
            assertFailsWith<BackupFormatException> { AppBackup.decode("""{"format":"AniLife_sync"}""") }
                .message!!.contains("AniLife_sync"),
        )
        assertTrue(
            assertFailsWith<BackupFormatException> { AppBackup.decode("""{"format":"anilili-backup","version":2}""") }
                .message!!.contains("newer version"),
        )
    }

    @Test
    fun aDocumentWithMissingSectionsRestoresAsEmptyOnes() {
        val document = AppBackup.decode("""{"format":"anilili-backup","version":1}""")

        assertEquals(0, document.watchlist.size)
        assertEquals("nothing", document.describe())
    }

    @Test
    fun sharedSettingsAreTranslatedBetweenTheTwoApps() {
        val document = AppBackup.decode(androidBackup)

        val (applied, count) = BackupSettings.applyTo(AppSettings(), document.settings)

        assertEquals(6, count)
        assertEquals(false, applied.autoplayNextEpisode)
        assertEquals("dub", applied.audio)
        assertEquals(false, applied.subtitlesByDefault)
        assertTrue(applied.autoSkipIntro && applied.autoSkipOutro)
        assertEquals(30, applied.skipButtonSeconds)
        assertEquals("fr", applied.subtitleLanguage)
        // Settings the file has no opinion on are left exactly as they were.
        assertEquals(AppSettings().volume, applied.volume)
        assertEquals(AppSettings().themeCss, applied.themeCss)

        val sent = BackupSettings.toBackup(applied)
        assertEquals("fra", sent["preferred_subtitle_language"]!!.jsonPrimitive.content)
        assertEquals(true, sent["prefer_dub"]!!.jsonPrimitive.content.toBoolean())
        assertNull(sent["home_tab"])
    }

    @Test
    fun anUnknownLanguageOrWrongTypedValueIsIgnoredRatherThanApplied() {
        val values = Json.parseToJsonElement("""{"preferred_subtitle_language":"tam","autoplay":"yes","skip_button_seconds":9999}""").jsonObject

        val (applied, count) = BackupSettings.applyTo(AppSettings(), values)

        assertEquals(1, count)
        assertEquals(AppSettings().subtitleLanguage, applied.subtitleLanguage)
        assertEquals(AppSettings().autoplayNextEpisode, applied.autoplayNextEpisode)
        assertEquals(300, applied.skipButtonSeconds)
    }
}

class MalTransferTest {
    private fun entry(id: Long, title: String = "T$id", status: String = "Watching", watched: Int = 3) =
        MalExportEntry(id, title, "TV", 12, watched, 0, status)

    @Test
    fun exportMatchesTheAndroidFileEntryForEntry() {
        val file = MalExport.fromEntries(
            username = null,
            entries = listOf(entry(5, "Tom & \"Jerry\" <1>'s", watched = 12, status = "Completed")),
            skippedCount = 2,
            date = LocalDate.of(2026, 11, 15),
        )

        assertEquals("Anilife-mal-export-20261115.xml", file.fileName)
        assertEquals(1, file.exportedCount)
        assertEquals(2, file.skippedCount)
        assertTrue(file.xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<myanimelist>\n  <myinfo>\n    <user_id>0</user_id>\n    <user_name>Anilife</user_name>"))
        assertTrue("<series_title>Tom &amp; &quot;Jerry&quot; &lt;1&gt;&apos;s</series_title>" in file.xml)
        assertTrue("<my_status>Completed</my_status>" in file.xml)
        assertTrue("<update_on_import>1</update_on_import>" in file.xml)
    }

    @Test
    fun anExportReadsBackAsTheSameEntries() {
        val file = MalExport.fromEntries(null, listOf(entry(5, "A & B"), entry(6, status = "Plan to Watch", watched = 0)), 0)

        val parsed = MalImport.parse(file.xml.toByteArray())

        assertEquals(listOf(5L, 6L), parsed.map { it.malId })
        assertEquals("A & B", parsed[0].title)
        assertEquals(3, parsed[0].watchedEpisodes)
        assertEquals("Plan to Watch", parsed[1].status)
    }

    @Test
    fun statusFromLocalProgressFollowsTheAndroidRule() {
        assertEquals("Plan to Watch", MalExport.statusFromLocal(0, 12))
        assertEquals("Watching", MalExport.statusFromLocal(3, 12))
        assertEquals("Completed", MalExport.statusFromLocal(12, 12))
        assertEquals("Watching", MalExport.statusFromLocal(30, null))
    }

    @Test
    fun importReadsGzipNumericStatusesAndSkipsDuplicatesAndBadIds() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <myanimelist>
              <anime><series_animedb_id>10</series_animedb_id><series_title>Ten</series_title><my_watched_episodes>4</my_watched_episodes><my_status>2</my_status></anime>
              <anime><series_animedb_id>10</series_animedb_id><series_title>Ten again</series_title></anime>
              <anime><series_animedb_id>0</series_animedb_id><series_title>Zero</series_title></anime>
              <anime><series_animedb_id>x</series_animedb_id><series_title>Bad</series_title></anime>
              <anime><series_animedb_id>11</series_animedb_id><series_title>Eleven</series_title><my_status>On-Hold</my_status></anime>
            </myanimelist>
        """.trimIndent()
        val gzipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(xml.toByteArray()) } }.toByteArray()

        val parsed = MalImport.parse(gzipped)

        assertEquals(listOf(10L, 11L), parsed.map { it.malId })
        assertEquals("Completed", parsed[0].status)
        assertEquals(4, parsed[0].watchedEpisodes)
        assertEquals("On-Hold", parsed[1].status)
    }

    @Test
    fun importRefusesDoctypesUtf16AndFilesThatAreNotMalExports() {
        assertFailsWith<IllegalArgumentException> {
            MalImport.parse("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e \"y\">]><myanimelist/>".toByteArray())
        }
        assertFailsWith<IllegalArgumentException> { MalImport.parse("<a/>".toByteArray(Charsets.UTF_16)) }
        assertFailsWith<IllegalArgumentException> { MalImport.parse("<other/>".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { MalImport.parse("{\"format\":\"anilili-backup\"}".toByteArray()) }
    }
}

class DataTransferTest {
    /** A stand-in for AniList: id n is "Show n" with MAL id n + 1000, unless it is above 900_000. */
    private class FakeAniList : AutoCloseable {
        val requests = mutableListOf<String>()
        var failing = false
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().decodeToString()
                requests += body
                if (failing) {
                    exchange.sendResponseHeaders(500, -1)
                    exchange.close()
                    return@createContext
                }
                val root = Json.parseToJsonElement(body).jsonObject
                val byMal = "idMal_in" in root["query"]!!.jsonPrimitive.content
                val ids = root["variables"]!!.jsonObject["ids"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() }
                val media = ids.mapNotNull { requested ->
                    val id = if (byMal) requested - 1000 else requested
                    if (id <= 0 || id > 900_000) null else """
                        {"id":$id,"idMal":${id + 1000},"format":"TV","episodes":12,"averageScore":70,
                         "title":{"english":"Show $id","romaji":"Show $id","native":null},
                         "coverImage":{"extraLarge":"https://img/$id.jpg","color":null}}
                    """.trimIndent()
                }
                val response = """{"data":{"Page":{"media":[${media.joinToString(",")}]}}}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        val client get() = AniListClient(endpoint = "http://127.0.0.1:${server.address.port}/")
        override fun close() = server.stop(0)
    }

    private class Fixture(
        val root: Path,
        val aniList: FakeAniList,
        val accounts: AccountLists = NoAccountLists,
        syncSaved: Boolean = true,
    ) : AutoCloseable {
        val library = LocalLibraryStore(root.resolve("library.properties").toFile())
        val continueStore = ContinueStore(root.resolve("continue.properties").toFile())
        val repository = AniListRepository(client = aniList.client, library = library)
        val transfer = DataTransfer(
            repository, continueStore, appVersion = { "test" }, now = { 5_000L },
            accounts = accounts, syncSaved = { syncSaved },
        )
        override fun close() {
            aniList.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun fixture() = Fixture(Files.createTempDirectory("AniLife-data"), FakeAniList())

    @Test
    fun restoreFillsTheLibraryAndContinueWatchingFromAnAndroidBackup() = fixture().use { f ->
        val applied = mutableListOf<AppSettings>()

        val summary = runBlocking {
            f.transfer.restore(AppBackup.decode(androidBackup), applySettings = { transform -> applied += transform(AppSettings()) })
        }

        assertEquals(6, summary.settings)
        assertEquals(2, summary.watchlist)
        assertEquals(2, summary.history)
        assertEquals("fr", applied.single().subtitleLanguage)
        // Full details came from AniList, so the MAL id and episode count are known.
        val saved = f.library.entries.value.associateBy { it.id }
        assertEquals(1101L, saved.getValue(101).malId)
        assertEquals(12, saved.getValue(101).episodes)
        // The row with no recorded length gets a typical one; the one with a real clock keeps it.
        val rows = f.continueStore.entries.value.associateBy { it.anime.id }
        assertEquals(1440.0, rows.getValue(101).progress.duration)
        assertEquals(0.0, rows.getValue(101).progress.position)
        assertEquals(7.0, rows.getValue(101).progress.episode)
        assertEquals(600.0, rows.getValue(102).progress.position)
        assertEquals(900L, rows.getValue(101).updatedAt)
        // What the file held that this app has no place for is said out loud.
        assertTrue(summary.notRestored.any { "custom list" in it })
        assertTrue(summary.notRestored.any { "Android-only setting" in it })
        assertTrue(summary.notRestored.any { "login" in it })
        assertTrue(summary.warnings.isEmpty())
    }

    @Test
    fun restoringTheSameBackupTwiceChangesNothingTheSecondTime() = fixture().use { f ->
        val document = AppBackup.decode(androidBackup)
        runBlocking { f.transfer.restore(document, applySettings = {}) }

        val second = runBlocking { f.transfer.restore(document, applySettings = {}) }

        assertEquals(0, second.watchlist)
        assertEquals(0, second.history)
        assertEquals(2, f.library.entries.value.size)
    }

    @Test
    fun restoreKeepsWhatIsAlreadyHereAndTheNewerHistoryRow() = fixture().use { f ->
        f.library.add(AnimeCard(id = 101, title = "My own title", malId = 1101))
        f.continueStore.upsert(AnimeCard(id = 101, title = "First Show"), episode = 9.0, position = 100.0, duration = 1440.0)
        val newer = f.continueStore.entries.value.single().updatedAt

        val summary = runBlocking { f.transfer.restore(AppBackup.decode(androidBackup), applySettings = {}) }

        assertEquals(1, summary.watchlist) // only the show that was not already saved
        assertEquals("My own title", f.library.entries.value.first { it.id == 101L }.title)
        // The backup's row for show 101 is older than the one watched just now, so this one wins.
        assertEquals(9.0, f.continueStore.entries.value.first { it.anime.id == 101L }.progress.episode)
        assertEquals(newer, f.continueStore.entries.value.first { it.anime.id == 101L }.updatedAt)
    }

    @Test
    fun restoreStillWorksOfflineWithSparseDetailsAndSaysSo() = fixture().use { f ->
        f.aniList.failing = true

        val summary = runBlocking { f.transfer.restore(AppBackup.decode(androidBackup), applySettings = {}) }

        assertEquals(2, summary.watchlist)
        assertNull(f.library.entries.value.first { it.id == 101L }.malId)
        assertEquals("https://img/101.jpg", f.library.entries.value.first { it.id == 101L }.cover)
        assertEquals(1, summary.warnings.size)
    }

    @Test
    fun exportListsSavedAndContinueTitlesWithTheirProgress() = fixture().use { f ->
        f.library.addAll(
            listOf(
                AnimeCard(id = 1, title = "Has progress", malId = 1001, episodes = 12, format = "TV"),
                AnimeCard(id = 2, title = "Finished", malId = 1002, episodes = 12, format = "MOVIE"),
                AnimeCard(id = 3, title = "No progress", malId = 1003, episodes = 12),
                AnimeCard(id = 4, title = "Sparse card", episodes = null), // MAL id is learned from AniList
                AnimeCard(id = 950_000, title = "Unknown to AniList"),
            ),
        )
        f.continueStore.upsert(AnimeCard(id = 1, title = "Has progress", malId = 1001), 5.0, 10.0, 1440.0)
        f.continueStore.upsert(AnimeCard(id = 2, title = "Finished", malId = 1002), 12.0, 10.0, 1440.0)
        f.continueStore.upsert(AnimeCard(id = 7, title = "Only in continue", malId = 1007, episodes = 24), 2.0, 10.0, 1440.0)

        val export = runBlocking { f.transfer.buildMalExport() }
        val parsed = MalImport.parse(export.xml.toByteArray()).associateBy { it.malId }

        assertEquals(5, export.exportedCount)
        assertEquals(1, export.skippedCount)
        assertEquals("Watching" to 5, parsed.getValue(1001).let { it.status to it.watchedEpisodes })
        assertEquals("Completed", parsed.getValue(1002).status)
        assertEquals("Plan to Watch", parsed.getValue(1003).status)
        assertEquals("Watching", parsed.getValue(1007).status)
        assertNotNull(parsed[1004]) // found by id on AniList
        // What was learned is kept, so the next export does not need the network.
        assertEquals(1004L, f.library.entries.value.first { it.id == 4L }.malId)
    }

    @Test
    fun importAddsOnlyNewTitlesAndCountsWhatAniListCouldNotFind() = fixture().use { f ->
        f.library.add(AnimeCard(id = 11, title = "Already here", malId = 1011))
        val xml = MalExport.fromEntries(
            null,
            listOf(
                MalExportEntry(1010, "Ten", "TV", 12, 1, 0, "Watching"),
                MalExportEntry(1011, "Eleven", "TV", 12, 0, 0, "Plan to Watch"),
                MalExportEntry(1_950_000, "Nowhere", "TV", 12, 0, 0, "Dropped"),
            ),
            0,
        ).xml

        val summary = runBlocking { f.transfer.importMalXml(xml.toByteArray()) }

        assertEquals(MalImportSummary(totalEntries = 3, added = 1, alreadySaved = 1, unmatched = 1), summary)
        assertEquals(setOf(10L, 11L), f.library.entries.value.map { it.id }.toSet())
        assertEquals("Already here", f.library.entries.value.first { it.id == 11L }.title)
    }

    @Test
    fun aFailedLookupDuringImportLeavesTheLibraryUntouched() = fixture().use { f ->
        f.aniList.failing = true
        val xml = MalExport.fromEntries(null, listOf(MalExportEntry(1010, "Ten", "TV", 12, 1, 0, "Watching")), 0).xml

        assertFailsWith<Exception> { runBlocking { f.transfer.importMalXml(xml.toByteArray()) } }

        assertTrue(f.library.entries.value.isEmpty())
    }

    @Test
    fun largeListsAreLookedUpFiftyAtATime() = fixture().use { f ->
        val entries = (1L..120L).map { MalExportEntry(1000 + it, "T$it", "TV", 12, 0, 0, "Plan to Watch") }

        val summary = runBlocking { f.transfer.importMalXml(MalExport.fromEntries(null, entries, 0).xml.toByteArray()) }

        assertEquals(120, summary.added)
        assertEquals(3, f.aniList.requests.size)
    }

    @Test
    fun aBackupMadeHereRestoresIntoAFreshInstall() {
        fixture().use { source ->
            source.library.addAll(listOf(AnimeCard(id = 1, title = "One", malId = 1001), AnimeCard(id = 2, title = "Two")))
            source.continueStore.upsert(AnimeCard(id = 1, title = "One"), 4.0, 300.0, 1440.0)
            val text = AppBackup.encode(source.transfer.createBackup(AppSettings(audio = "dub")))

            fixture().use { target ->
                val summary = runBlocking { target.transfer.restore(AppBackup.decode(text), applySettings = {}) }

                assertEquals(2, summary.watchlist)
                assertEquals(1, summary.history)
                assertEquals(300.0, target.continueStore.entries.value.single().progress.position)
                assertEquals(4.0, target.continueStore.entries.value.single().progress.episode)
            }
        }
    }
}
