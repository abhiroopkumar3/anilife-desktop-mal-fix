package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.AnimeCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountInsightsTest {
    private fun entry(
        id: Long,
        status: String,
        progress: Int = 0,
        score: Double = 0.0,
        updatedAt: Long = 1_000L + id,
        genres: List<String> = emptyList(),
        format: String? = "TV",
        episodes: Int? = null,
        year: Int? = null,
    ) = ListEntry(
        anime = AnimeCard(id = id, title = "T$id", genres = genres, format = format, episodes = episodes, year = year),
        status = status, progress = progress, score = score, updatedAt = updatedAt,
    )

    @Test
    fun `rewatching folds into watching and counts keep a fixed order`() {
        val insights = accountInsights(
            listOf(entry(1, "CURRENT"), entry(2, "REPEATING"), entry(3, "COMPLETED"), entry(4, "PLANNING")),
            null,
        )
        assertEquals(listOf(2, 1, 1, 0, 0), insights.statusCounts.values.toList())
        assertEquals(ListStatusSlice.entries.toList(), insights.statusCounts.keys.toList())
        assertEquals(4, insights.titles)
    }

    @Test
    fun `account totals win over what the list adds up to, and a zero falls back`() {
        val list = listOf(entry(1, "COMPLETED", progress = 12), entry(2, "CURRENT", progress = 3))
        val fromAccount = accountInsights(list, AccountStats(titles = 159, episodes = 2000, minutesWatched = 600, meanScore = 7.5))
        assertEquals(159, fromAccount.titles)
        assertEquals(2000, fromAccount.episodes)
        assertEquals(7.5, fromAccount.meanScore)
        val empty = accountInsights(list, AccountStats())
        assertEquals(2, empty.titles)
        assertEquals(15, empty.episodes)
        assertNull(empty.meanScore)
    }

    @Test
    fun `planning titles do not vote for genres unless nothing else is started`() {
        val mixed = accountInsights(
            listOf(entry(1, "COMPLETED", genres = listOf("Action")), entry(2, "PLANNING", genres = listOf("Romance", "Romance"))),
            null,
        )
        assertEquals(listOf("Action" to 1), mixed.topGenres)
        val onlyPlanning = accountInsights(listOf(entry(2, "PLANNING", genres = listOf("Romance"))), null)
        assertEquals(listOf("Romance" to 1), onlyPlanning.topGenres)
    }

    @Test
    fun `completion rate, episodes to go and score spread`() {
        val insights = accountInsights(
            listOf(
                entry(1, "COMPLETED", score = 9.0), entry(2, "COMPLETED", score = 9.4),
                entry(3, "DROPPED", score = 3.0),
                entry(4, "CURRENT", progress = 4, episodes = 12),
            ),
            null,
        )
        assertEquals(67, insights.completionRate)
        assertEquals(8, insights.episodesToGo)
        assertEquals(10, insights.scoreSpread.size)
        assertEquals(2, insights.scoreSpread[8].second)
        assertEquals(1, insights.scoreSpread[2].second)
    }

    @Test
    fun `release years go by decade once the span passes a dozen years`() {
        val short = accountInsights(listOf(entry(1, "COMPLETED", year = 2022), entry(2, "COMPLETED", year = 2024)), null)
        assertEquals(listOf("’22" to 1, "’23" to 0, "’24" to 1), short.releaseYears)
        val long = accountInsights(listOf(entry(1, "COMPLETED", year = 1998), entry(2, "COMPLETED", year = 2024)), null)
        assertEquals(listOf("90s" to 1, "00s" to 0, "10s" to 0, "20s" to 1), long.releaseYears)
    }

    @Test
    fun `recent is newest first with one row per title and none without a time`() {
        val insights = accountInsights(
            listOf(entry(1, "COMPLETED", updatedAt = 10), entry(2, "CURRENT", updatedAt = 30), entry(3, "PLANNING", updatedAt = 0)),
            null,
        )
        assertEquals(listOf(2L, 1L), insights.recent.map { it.anime.id })
    }

    @Test
    fun `labels read as Android prints them`() {
        assertEquals("45m", watchTimeLabel(45))
        assertEquals("2h", watchTimeLabel(120))
        assertEquals("2h 5m", watchTimeLabel(125))
        assertEquals("2h 5m", statWatchTime(125))
        assertEquals("1,062 h", statWatchTime(63_720))
        assertEquals("Watched episode 4 of T1", activityHeadline(entry(1, "CURRENT", progress = 4), "T1"))
        assertEquals("Started T1", activityHeadline(entry(1, "CURRENT"), "T1"))
        assertEquals("https://anilist.co/user/a%20b/", accountProfileUrl(ListService.ANILIST, "a b"))
        assertEquals("https://myanimelist.net/profile/abc", accountProfileUrl(ListService.MAL, "abc"))
    }

    @Test
    fun `detail line has the age and a rating when scored`() {
        val now = 10_000_000L
        val e = entry(1, "COMPLETED", score = 8.5, updatedAt = now / 1000 - 7_200)
        assertEquals("2 hours ago · Rated 8.5/10", activityDetail(e, now))
    }
}
