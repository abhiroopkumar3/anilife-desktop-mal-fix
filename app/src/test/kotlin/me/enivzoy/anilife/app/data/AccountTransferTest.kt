package me.enivzoy.anilife.app.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.enivzoy.anilife.core.model.AnimeCard
import me.enivzoy.anilife.core.net.AniListClient
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A signed-in account whose list and answers the test decides. */
private class FakeAccount(
    val service: AccountService? = AccountService.MY_ANIME_LIST,
    var entries: List<AccountEntry> = emptyList(),
    var name: String? = "tester",
    var unreachable: Boolean = false,
) : AccountLists {
    val pushed = mutableListOf<List<AnimeCard>>()
    override fun active() = service
    override fun accountName() = name
    override suspend fun read(): List<AccountEntry> {
        if (unreachable) error("offline")
        return entries
    }
    override suspend fun addPlanned(cards: List<AnimeCard>): PlannedPush {
        if (unreachable) error("offline")
        pushed += cards
        return PlannedPush(added = cards.size)
    }
}

private fun entry(
    malId: Long,
    status: AniListListStatus,
    progress: Int = 0,
    episodes: Int? = 12,
    score: Int = 0,
    format: String? = "TV",
) = AccountEntry(malId, null, "Title $malId", format, episodes, progress, score, status, 0L)

class AccountTransferTest {
    private class Stand(val root: Path, val account: FakeAccount, syncSaved: Boolean = true) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().decodeToString()
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
        val library = LocalLibraryStore(root.resolve("library.properties").toFile())
        val continueStore = ContinueStore(root.resolve("continue.properties").toFile())
        val repository = AniListRepository(
            client = AniListClient(endpoint = "http://127.0.0.1:${server.address.port}/"),
            library = library,
        )
        val transfer = DataTransfer(
            repository, continueStore, appVersion = { "test" }, now = { 5_000L },
            accounts = account, syncSaved = { syncSaved },
        )
        override fun close() {
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }

    private fun stand(account: FakeAccount, syncSaved: Boolean = true) =
        Stand(Files.createTempDirectory("AniLife-account"), account, syncSaved)

    private fun xmlFor(vararg ids: Long) = MalExport.fromEntries(
        null,
        ids.map { MalExportEntry(it, "T$it", "TV", 12, 0, 0, "Plan to Watch") },
        0,
    ).xml

    private fun entriesOf(xml: String) = Regex("<anime>(.*?)</anime>", RegexOption.DOT_MATCHES_ALL)
        .findAll(xml).map { it.groupValues[1] }.toList()

    private fun field(block: String, tag: String) = Regex("<$tag>(.*?)</$tag>").find(block)!!.groupValues[1]

    @Test
    fun anExportStartsWithTheAccountListInAndroidsOrderAndNamesTheAccount() = stand(
        FakeAccount(
            entries = listOf(
                entry(1006, AniListListStatus.PLANNING),
                entry(1005, AniListListStatus.DROPPED, progress = 2),
                entry(1004, AniListListStatus.PAUSED, progress = 4, score = 7),
                entry(1003, AniListListStatus.COMPLETED, progress = 0, episodes = 12, score = 9),
                entry(1002, AniListListStatus.REPEATING, progress = 3),
                entry(1001, AniListListStatus.CURRENT, progress = 5),
            ),
        ),
    ).use { s ->
        val export = runBlocking { s.transfer.buildMalExport() }
        val rows = entriesOf(export.xml)

        assertEquals(listOf("1001", "1002", "1003", "1004", "1005", "1006"), rows.map { field(it, "series_animedb_id") })
        assertEquals(
            listOf("Watching", "Watching", "Completed", "On-Hold", "Dropped", "Plan to Watch"),
            rows.map { field(it, "my_status") },
        )
        assertEquals("1", field(rows[1], "my_rewatching"))
        assertEquals("0", field(rows[0], "my_rewatching"))
        // A finished title with no count recorded counts as all of it watched.
        assertEquals("12", field(rows[2], "my_watched_episodes"))
        assertEquals("9", field(rows[2], "my_score"))
        assertTrue("<user_name>tester</user_name>" in export.xml)
        assertEquals(6, export.exportedCount)
        assertNull(export.note)
    }

    @Test
    fun savedTitlesTheAccountAlreadyHasAreNotWrittenTwiceAndTheRestFollowIt() = stand(
        FakeAccount(entries = listOf(entry(1001, AniListListStatus.COMPLETED, progress = 12, score = 8))),
    ).use { s ->
        s.library.addAll(
            listOf(
                AnimeCard(id = 1, title = "On the account", malId = 1001, episodes = 12),
                AnimeCard(id = 20, title = "Only saved here", malId = 1020, episodes = 12),
            ),
        )
        s.continueStore.upsert(AnimeCard(id = 20, title = "Only saved here", malId = 1020), 4.0, 10.0, 1440.0)
        s.continueStore.upsert(AnimeCard(id = 21, title = "Only in continue", malId = 1021, episodes = 12), 2.0, 10.0, 1440.0)

        val rows = entriesOf(runBlocking { s.transfer.buildMalExport() }.xml)

        assertEquals(listOf("1001", "1020", "1021"), rows.map { field(it, "series_animedb_id") })
        // The account's own record of the first title wins over the PC's.
        assertEquals("Completed", field(rows[0], "my_status"))
        assertEquals("8", field(rows[0], "my_score"))
        assertEquals("Watching", field(rows[1], "my_status"))
        assertEquals("4", field(rows[1], "my_watched_episodes"))
        assertEquals("2", field(rows[2], "my_watched_episodes"))
    }

    @Test
    fun anUnreachableAccountFallsBackToTheTitlesSavedHereAndSaysSo() = stand(
        FakeAccount(unreachable = true),
    ).use { s ->
        s.library.add(AnimeCard(id = 1, title = "Saved", malId = 1001, episodes = 12))

        val export = runBlocking { s.transfer.buildMalExport() }

        assertEquals(1, export.exportedCount)
        assertTrue("<user_name>Anilife</user_name>" in export.xml)
        assertTrue(export.note.orEmpty().contains("MyAnimeList could not be reached"))
    }

    @Test
    fun withNoAccountTheExportIsExactlyWhatItWasBefore() = stand(FakeAccount(service = null)).use { s ->
        s.library.add(AnimeCard(id = 1, title = "Saved", malId = 1001, episodes = 12, format = "TV"))

        val export = runBlocking { s.transfer.buildMalExport() }

        assertNull(export.note)
        assertEquals(
            MalExport.fromEntries(null, listOf(MalExportEntry(1001, "Saved", "TV", 12, 0, 0, "Plan to Watch")), 0, java.time.LocalDate.now()).xml,
            export.xml,
        )
    }

    @Test
    fun anImportAddsTheSavedTitlesToTheAccountsPlanToWatchAndReportsIt() = stand(FakeAccount()).use { s ->
        s.library.add(AnimeCard(id = 11, title = "Already here", malId = 1011))

        val summary = runBlocking { s.transfer.importMalXml(xmlFor(1010, 1011).toByteArray()) }

        assertEquals(setOf(10L, 11L), s.account.pushed.single().map { it.id }.toSet())
        assertEquals("2 added to MyAnimeList Plan to Watch.", summary.accountNote)
        assertEquals(1, summary.added)
    }

    @Test
    fun aRestoreAddsTheSavedTitlesToTheAccountToo() = stand(FakeAccount()).use { s ->
        val backup = AppBackup.decode(
            """{"format":"anilili-backup","version":1,"watchlist":[
                {"anilistId":101,"title":"First","cover":null,"addedAt":5},
                {"anilistId":102,"title":"Second","cover":null,"addedAt":4}]}""",
        )

        val summary = runBlocking { s.transfer.restore(backup, applySettings = {}) }

        assertEquals(setOf(101L, 102L), s.account.pushed.single().map { it.id }.toSet())
        assertEquals("2 added to MyAnimeList Plan to Watch.", summary.accountNote)
    }

    @Test
    fun withTheSettingOffNothingIsSentToTheAccount() = stand(FakeAccount(), syncSaved = false).use { s ->
        val summary = runBlocking { s.transfer.importMalXml(xmlFor(1010).toByteArray()) }

        assertTrue(s.account.pushed.isEmpty())
        assertNull(summary.accountNote)
        assertEquals(1, summary.added)
    }

    @Test
    fun withNoAccountNothingIsSentAndTheImportIsUnchanged() = stand(FakeAccount(service = null)).use { s ->
        val summary = runBlocking { s.transfer.importMalXml(xmlFor(1010).toByteArray()) }

        assertTrue(s.account.pushed.isEmpty())
        assertEquals(MalImportSummary(1, 1, 0, 0), summary)
    }

    @Test
    fun anAccountThatCannotBeReachedDoesNotUndoTheImport() = stand(FakeAccount(unreachable = true)).use { s ->
        val summary = runBlocking { s.transfer.importMalXml(xmlFor(1010).toByteArray()) }

        assertEquals(1, summary.added)
        assertTrue(summary.accountNote.orEmpty().contains("could not be reached"))
        assertEquals(setOf(10L), s.library.entries.value.map { it.id }.toSet())
    }

    @Test
    fun titlesAlreadyOnTheAccountAreNeverAddedAgainAndFailuresDoNotStopTheRest() {
        val cards = listOf(
            AnimeCard(id = 1, title = "On the list", malId = 1001),
            AnimeCard(id = 2, title = "New", malId = 1002),
            AnimeCard(id = 3, title = "No id", malId = null),
            AnimeCard(id = 4, title = "Fails", malId = 1004),
            AnimeCard(id = 5, title = "Also new", malId = 1005),
        )
        val sent = mutableListOf<Long>()

        val result = runBlocking {
            planMissingTitles(cards, 0L, isThere = { it.malId == 1001L }, id = { it.malId }) { id ->
                if (id == 1004L) error("rejected")
                sent += id
            }
        }

        assertEquals(listOf(1002L, 1005L), sent)
        assertEquals(PlannedPush(added = 2, alreadyThere = 1, withoutId = 1, failed = 1), result)
    }
}

class MalListPagesTest {
    private fun page(vararg ids: Int, next: String? = null): JsonObject {
        val data = ids.joinToString(",") { id ->
            """{"node":{"id":$id,"title":"T$id","num_episodes":12,"media_type":"tv"},
                "list_status":{"status":"watching","score":8,"num_episodes_watched":3,"is_rewatching":false,
                               "updated_at":"2024-05-01T10:00:00+00:00","tags":["a","b"]}}"""
        }
        val root = """{"data":[$data],"paging":{${next?.let { "\"next\":\"$it\"" }.orEmpty()}}}"""
        return Json.parseToJsonElement(root).jsonObject
    }

    @Test
    fun aListLongerThanOnePageIsReadToTheEnd() {
        val pages = mapOf(
            "first" to page(1, 2, next = "second"),
            "second" to page(3, 4, next = "third"),
            "third" to page(5),
        )
        val asked = mutableListOf<String>()

        val entries = runBlocking { MalListPages.readAll("first") { url -> asked += url; pages.getValue(url) } }

        assertEquals(listOf("first", "second", "third"), asked)
        assertEquals(listOf(1L, 2, 3, 4, 5), entries.map { it.id })
    }

    @Test
    fun aPageWithoutANextLinkEndsTheRead() {
        val entries = runBlocking { MalListPages.readAll("only") { page(1) } }

        assertEquals(1, entries.size)
    }

    @Test
    fun aNeverEndingChainIsStopped() {
        var calls = 0

        runBlocking { MalListPages.readAll("loop") { calls++; page(calls, next = "loop") } }

        assertEquals(10, calls)
    }

    @Test
    fun everyFieldTheExportNeedsIsRead() {
        val entry = MalListPages.parse(page(7)).single()

        assertEquals(7L, entry.id)
        assertEquals(12, entry.episodes)
        assertEquals("tv", entry.mediaType)
        assertEquals(listOf("a", "b"), entry.tags)
        assertEquals(8, entry.score)
        assertEquals(3, entry.watched)
        assertEquals(1_714_557_600L, entry.updatedAt)
    }

    @Test
    fun aStampThatCannotBeReadIsZeroAndUnknownCountsAreAbsent() {
        assertEquals(0L, MalListPages.epochSeconds("not a date"))
        assertEquals(0L, MalListPages.epochSeconds(null))
        val root = Json.parseToJsonElement(
            """{"data":[{"node":{"id":9,"title":"X","num_episodes":0},"list_status":{"status":"completed","is_rewatching":true}}]}""",
        ).jsonObject
        val entry = MalListPages.parse(root).single()
        assertNull(entry.episodes)
        assertTrue(entry.rewatching)
    }

    @Test
    fun theFirstRequestAsksForTheExtraFieldsAndIsAValidAddress() {
        val url = MalListPages.firstUrl("https://api.myanimelist.net/v2")

        URI.create(url)
        assertTrue("limit=1000" in url)
        assertTrue("nsfw=true" in url)
        val fields = java.net.URLDecoder.decode(url.substringAfter("fields=").substringBefore("&"), "UTF-8")
        assertEquals("list_status{status,score,num_episodes_watched,is_rewatching,updated_at,tags},num_episodes,media_type", fields)
    }

    @Test
    fun anAccountAnimeMayBeLeftOutOfAnExportIfItHasNoMalId() {
        assertNull(MalExport.entryFromAccount(AccountEntry(null, 5, "No MAL id", "TV", 12, 1, 0, AniListListStatus.CURRENT, 0)))
    }
}
