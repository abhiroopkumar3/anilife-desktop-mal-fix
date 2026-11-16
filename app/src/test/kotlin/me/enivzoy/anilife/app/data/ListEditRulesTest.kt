package me.enivzoy.anilife.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListEditRulesTest {
    @Test
    fun `the six destinations come in Android's order`() {
        assertEquals(
            listOf("CURRENT", "REPEATING", "PLANNING", "PAUSED", "COMPLETED", "DROPPED"),
            LIST_DESTINATIONS.map { it.status },
        )
    }

    @Test
    fun `only Planning keeps the saved row, and only Watching keeps Continue Watching`() {
        assertTrue(savedRowSurvivesMoveTo("PLANNING"))
        assertFalse(savedRowSurvivesMoveTo("COMPLETED"))
        assertTrue(moveKeepsContinueRow("CURRENT"))
        assertTrue(moveKeepsContinueRow("REPEATING"))
        assertFalse(moveKeepsContinueRow("PAUSED"))
    }

    @Test
    fun `starting a rewatch of a finished title counts from zero and finishing one counts it`() {
        assertEquals(RewatchMove(resetProgress = true), planRewatchMove("COMPLETED", 2, "REPEATING"))
        assertEquals(RewatchMove(repeat = 3), planRewatchMove("REPEATING", 2, "COMPLETED"))
        assertEquals(RewatchMove(), planRewatchMove("CURRENT", 0, "COMPLETED"))
        assertFalse(RewatchMove().touchesRewatch)
    }

    @Test
    fun `a rewatch write sends back the dates the entry has and leaves out the rest`() {
        val entry = ListSnapshot(1, "REPEATING", 4, 1, startedAt = ListDate(2024, 3, 1))
        assertEquals(listOf("startedAt" to ListDate(2024, 3, 1)), preservedListDates(entry))
        val (declarations, arguments, values) = rewatchArguments(entry, 2)
        assertEquals(", \$repeat: Int, \$startedAt: FuzzyDateInput", declarations)
        assertEquals(", repeat: \$repeat, startedAt: \$startedAt", arguments)
        assertEquals(setOf("repeat", "startedAt"), values.keys)
        assertEquals(Triple("", "", emptyMap()), rewatchArguments(null, null))
    }

    @Test
    fun `scores go out as whole points of a hundred`() {
        assertEquals(70, aniListScoreRaw(7))
        assertEquals(0, aniListScoreRaw(0))
        assertEquals("8", formatListScore(8.0))
        assertEquals("7.5", formatListScore(7.5))
        assertEquals("Masterpiece", listScoreLabel(10))
        assertNull(listScoreLabel(0))
    }

    @Test
    fun `a MyAnimeList entry is read, and a rewatching flag wins over its status`() {
        val root = Json.parseToJsonElement(
            """{"num_episodes":12,"my_list_status":{"status":"completed","score":8,"num_episodes_watched":12,"is_rewatching":true,"num_times_rewatched":1}}""",
        ).jsonObject
        val parsed = parseMalAnimeStatus(root)
        assertEquals(12, parsed.totalEpisodes)
        assertEquals("REPEATING", malEntryToAniListStatus(parsed.entry!!))
        assertNull(parseMalAnimeStatus(Json.parseToJsonElement("""{"num_episodes":0}""").jsonObject).entry)
    }

    @Test
    fun `an answer that differs from the request is reported, and a rewatch count is not held to the figure`() {
        val confirmed = MalEntryStatus("completed", 8, 12, false, 1)
        assertNull(malWriteMismatch(MalWrite(status = "completed", score = 8), confirmed))
        assertEquals(
            "MyAnimeList confirmed score 8 after requesting 9",
            malWriteMismatch(MalWrite(score = 9), confirmed),
        )
        assertEquals(
            "MyAnimeList confirmed 12 watched episodes after requesting 0",
            malWriteMismatch(MalWrite(progress = 0), confirmed),
        )
        assertNull(malWriteMismatch(MalWrite(progress = 0, strictProgress = false), confirmed))
    }
}
