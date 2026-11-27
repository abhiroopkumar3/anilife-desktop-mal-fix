package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.AiringTime
import me.enivzoy.anilife.core.model.AnimeCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which Continue Watching rows Home takes off until an episode is out. Android's
 * `ContinueWatchingVisibilityTest` (22 cases), over Windows' `ContinueEntry`.
 *
 * The instants are 2026-09-24 09:00 UTC (now) and an episode airing 2026-09-25 01:30 UTC.
 * The one Android case that has no Windows twin is "a row with a stored resume point keeps its
 * place": Windows keeps one resume point per title, so it is the same case as a part-watched row.
 */
class ContinueVisibilityTest {
    /** AniList 21, whose episode 1177 is the one airing tomorrow in these tests. */
    private val title = 21L
    private val nowSec = 1_790_240_400L
    private val airsTomorrowAt = 1_790_299_800L
    private val airsNextWeekAt = 1_790_758_800L

    private val tomorrow: UpcomingAiring = mapOf(title to mapOf(1177 to airsTomorrowAt))

    /** A row on the last episode out: watched, nothing left in progress, nothing to resume. */
    private fun caughtUp(episode: Double = 1176.0) = ContinueEntry(
        anime = AnimeCard(id = title, title = "ONE PIECE"),
        progress = ContinueProgress(episode, 0.0, 0.0),
        updatedAt = nowSec * 1000,
        watched = setOf(episode),
    )

    @Test
    fun `a caught-up row steps aside when the next episode is tomorrow`() {
        assertTrue(hideFromContinueUntilAired(caughtUp(), tomorrow, nowSec))
    }

    @Test
    fun `a row advanced onto tomorrows episode steps aside too`() {
        val advanced = caughtUp(episode = 1177.0).copy(watched = setOf(1176.0))
        assertTrue(hideFromContinueUntilAired(advanced, tomorrow, nowSec))
    }

    @Test
    fun `a caught-up row with nothing recorded steps aside`() {
        assertTrue(hideFromContinueUntilAired(caughtUp().copy(watched = emptySet()), tomorrow, nowSec))
    }

    @Test
    fun `a row seeded from the account steps aside`() {
        assertTrue(hideFromContinueUntilAired(caughtUp().copy(watched = emptySet(), fromRemote = true), tomorrow, nowSec))
    }

    @Test
    fun `the row returns once the episode has been carried`() {
        assertFalse(hideFromContinueUntilAired(caughtUp(), tomorrow, airsTomorrowAt + 2 * 60 * 60))
    }

    @Test
    fun `an episode that has only just aired is not out yet`() {
        assertTrue(hideFromContinueUntilAired(caughtUp(), tomorrow, airsTomorrowAt + 60 * 60))
    }

    @Test
    fun `a part-watched episode is never taken off the row`() {
        val midway = caughtUp().copy(progress = ContinueProgress(1176.0, 300.0, 1440.0))
        assertFalse(hideFromContinueUntilAired(midway, tomorrow, nowSec))
    }

    @Test
    fun `a part-watched row on tomorrows episode stays`() {
        val midway = caughtUp(episode = 1177.0).copy(
            watched = setOf(1176.0),
            progress = ContinueProgress(1177.0, 300.0, 1440.0),
        )
        assertFalse(hideFromContinueUntilAired(midway, tomorrow, nowSec))
    }

    @Test
    fun `a row seeded from the account with a resume point keeps its place`() {
        val seeded = caughtUp().copy(watched = emptySet(), fromRemote = true, progress = ContinueProgress(1176.0, 60.0, 1440.0))
        assertFalse(hideFromContinueUntilAired(seeded, tomorrow, nowSec))
    }

    @Test
    fun `a row behind the schedule is left alone`() {
        assertFalse(hideFromContinueUntilAired(caughtUp(episode = 1000.0), tomorrow, nowSec))
    }

    @Test
    fun `a rewatch in progress is left alone`() {
        val rewatch = caughtUp(episode = 1000.0).copy(progress = ContinueProgress(1000.0, 90.0, 1440.0))
        assertFalse(hideFromContinueUntilAired(rewatch, tomorrow, nowSec))
    }

    @Test
    fun `a title with nothing scheduled tomorrow keeps its place`() {
        assertFalse(hideFromContinueUntilAired(caughtUp(), emptyMap(), nowSec))
    }

    @Test
    fun `another titles episode does not take this row off`() {
        assertFalse(hideFromContinueUntilAired(caughtUp(), mapOf(999L to mapOf(1177 to airsTomorrowAt)), nowSec))
    }

    @Test
    fun `a second episode airing tomorrow does not change the answer`() {
        val double = mapOf(title to mapOf(1177 to airsTomorrowAt, 1178 to airsTomorrowAt + 7 * 24 * 60 * 60))
        assertTrue(hideFromContinueUntilAired(caughtUp(), double, nowSec))
    }

    @Test
    fun `a schedule read yesterday does not hide the row today`() {
        assertFalse(hideFromContinueUntilAired(caughtUp(), tomorrow, airsTomorrowAt + 26 * 60 * 60))
    }

    @Test
    fun `a special behind the frontier is left alone`() {
        assertFalse(hideFromContinueUntilAired(caughtUp(episode = 1174.5), tomorrow, nowSec))
    }

    @Test
    fun `a first episode is a row to hide`() {
        val first = caughtUp(episode = 1.0)
        assertTrue(hideFromContinueUntilAired(first, mapOf(title to mapOf(2 to airsTomorrowAt)), nowSec))
    }

    @Test
    fun `an unwatched latest episode keeps its place`() {
        val waiting = caughtUp().copy(watched = (1..1175).map { it.toDouble() }.toSet())
        assertFalse(hideFromContinueUntilAired(waiting, tomorrow, nowSec))
        assertFalse(hideFromContinueUntilAired(waiting.copy(fromRemote = true), tomorrow, nowSec))
    }

    @Test
    fun `a caught-up row stays aside all week`() {
        val nextWeek: UpcomingAiring = mapOf(title to mapOf(1177 to airsNextWeekAt))
        assertTrue(hideFromContinueUntilAired(caughtUp(), nextWeek, nowSec))
        assertTrue(hideFromContinueUntilAired(caughtUp(), nextWeek, airsNextWeekAt + 60 * 60))
        assertFalse(hideFromContinueUntilAired(caughtUp(), nextWeek, airsNextWeekAt + 2 * 60 * 60))
    }

    @Test
    fun `a hidden row says when it comes back`() {
        assertEquals(airsTomorrowAt + 2 * 60 * 60, returnsToContinueAt(caughtUp(), tomorrow, nowSec))
        assertNull(returnsToContinueAt(caughtUp(episode = 1000.0), tomorrow, nowSec))
    }

    @Test
    fun `an episode already out in the window is not what the row waits for`() {
        val window: UpcomingAiring = mapOf(title to mapOf(1176 to nowSec - 24 * 60 * 60, 1177 to airsNextWeekAt))
        assertTrue(hideFromContinueUntilAired(caughtUp(), window, nowSec))
        assertFalse(hideFromContinueUntilAired(caughtUp(episode = 1175.0), window, nowSec))
        assertFalse(hideFromContinueUntilAired(caughtUp().copy(watched = setOf(1175.0)), window, nowSec))
    }

    @Test
    fun `an episode from last night that is not out yet keeps the row aside`() {
        val lateLastNight = nowSec - 60 * 60
        val window: UpcomingAiring = mapOf(title to mapOf(1177 to lateLastNight))
        assertTrue(hideFromContinueUntilAired(caughtUp(), window, nowSec))
        assertFalse(hideFromContinueUntilAired(caughtUp(), window, lateLastNight + 2 * 60 * 60))
    }

    @Test
    fun `a schedule read folds into one map per title`() {
        val folded = upcomingAiringFrom(
            listOf(
                AiringTime(mediaId = title, episode = 1177, airingAt = 100),
                AiringTime(mediaId = title, episode = 1178, airingAt = 200),
                AiringTime(mediaId = 5, episode = 3, airingAt = 300),
            ),
        )
        assertEquals(mapOf(title to mapOf(1177 to 100L, 1178 to 200L), 5L to mapOf(3 to 300L)), folded)
    }
}
