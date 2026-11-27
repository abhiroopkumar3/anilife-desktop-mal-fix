package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.AnimeCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MalListModelTest {
    @Test
    fun `status words map both ways like Android`() {
        assertEquals("REPEATING", malToAniListStatus("completed", isRewatching = true))
        assertEquals("CURRENT", malToAniListStatus("watching", false))
        assertEquals("COMPLETED", malToAniListStatus("completed", false))
        assertEquals("PAUSED", malToAniListStatus("on_hold", false))
        assertEquals("DROPPED", malToAniListStatus("dropped", false))
        assertEquals("PLANNING", malToAniListStatus("plan_to_watch", false))
        assertEquals("PLANNING", malToAniListStatus(null, false))
        assertEquals("completed", aniListToMalStatus("REPEATING"))
        assertEquals("completed", aniListToMalStatus("COMPLETED"))
        assertEquals("watching", aniListToMalStatus("CURRENT"))
        assertEquals("on_hold", aniListToMalStatus("PAUSED"))
        assertEquals("dropped", aniListToMalStatus("DROPPED"))
        assertEquals("plan_to_watch", aniListToMalStatus("PLANNING"))
    }

    @Test
    fun `MAL timestamps with a numeric offset are read`() {
        assertEquals(1524694991L, malEpochSeconds("2018-04-25T22:23:11+00:00"))
        assertEquals(1524694991L, malEpochSeconds("2018-04-25T22:23:11Z"))
        assertEquals(0L, malEpochSeconds("nonsense"))
        assertEquals(0L, malEpochSeconds(null))
    }

    @Test
    fun `a watched count never passes the season's length`() {
        assertEquals(24, clampRemoteProgress(25, 24))
        assertEquals(25, clampRemoteProgress(25, null))
        assertEquals(25, clampRemoteProgress(25, 0))
        assertEquals(24, remoteEpisodeCeiling(24, 30))
        assertEquals(30, remoteEpisodeCeiling(null, 30))
        assertEquals(30, remoteEpisodeCeiling(0, 30))
        assertNull(remoteEpisodeCeiling(null, null))
    }

    @Test
    fun `rows join to AniList cards and unknown titles are dropped`() {
        val rows = listOf(
            MalListRow(1, "A", 12, "CURRENT", 30, 8.0, 100, listOf("fav")),
            MalListRow(2, "B", 12, "COMPLETED", 12, 0.0, 200, emptyList()),
        )
        val entries = malRowsToEntries(rows, mapOf(1L to AnimeCard(id = 10, title = "A", episodes = null)))
        val only = entries.single()
        assertEquals(10L, only.anime.id)
        assertEquals(12, only.anime.episodes)
        assertEquals(12, only.progress)
        assertEquals(listOf("fav"), only.customLists)
    }

    @Test
    fun `a list page parses rows and the next link`() {
        val body = """{"data":[{"node":{"id":5,"title":"Bleach","num_episodes":366},
          "list_status":{"status":"completed","score":9,"num_episodes_watched":366,"is_rewatching":true,
          "updated_at":"2020-01-02T03:04:05+00:00","tags":["fav","old"]}},
          {"node":{"id":6,"title":"No status"}}],
          "paging":{"next":"https://api.myanimelist.net/v2/users/@me/animelist?offset=1000"}}"""
        val (rows, next) = parseMalListPage(body)
        val row = rows.single()
        assertEquals(5L, row.malId)
        assertEquals("REPEATING", row.status)
        assertEquals(366, row.totalEpisodes)
        assertEquals(9.0, row.score)
        assertEquals(listOf("fav", "old"), row.tags)
        assertEquals("https://api.myanimelist.net/v2/users/@me/animelist?offset=1000", next)
        assertNull(parseMalListPage("""{"data":[]}""").second)
    }
}
