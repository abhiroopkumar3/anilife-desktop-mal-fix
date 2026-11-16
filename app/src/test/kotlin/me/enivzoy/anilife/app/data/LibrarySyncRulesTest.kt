package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.AnimeCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun card(id: Long) = AnimeCard(id = id, title = "Title $id")
private fun save(id: Long, addedAt: Long = 10) = LocalSave(card(id), addedAt)

class WatchlistRemovalTest {
    @Test
    fun `unsaving on another device clears the save here`() {
        val merged = mergeWatchlistEntries(listOf(save(1), save(2)), listOf(save(2)), setOf(2), setOf(1, 2))
        assertEquals(listOf(2L), merged.map { it.anime.id })
    }

    @Test
    fun `a save the account has never seen is never dropped`() {
        val merged = mergeWatchlistEntries(listOf(save(1), save(99)), listOf(save(1)), setOf(1), setOf(1))
        assertEquals(listOf(1L, 99L), merged.map { it.anime.id })
    }

    @Test
    fun `the first refresh after a fresh install can only add`() {
        val merged = mergeWatchlistEntries(listOf(save(1), save(2)), listOf(save(3)), setOf(3), emptySet())
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.anime.id })
    }

    @Test
    fun `a title moved to another list keeps its save`() {
        val merged = mergeWatchlistEntries(listOf(save(1)), emptyList(), setOf(1), setOf(1))
        assertEquals(listOf(1L), merged.map { it.anime.id })
    }

    @Test
    fun `an empty snapshot is treated as no information, not as an emptied account`() {
        val merged = mergeWatchlistEntries(listOf(save(1), save(2)), emptyList(), emptySet(), setOf(1, 2))
        assertEquals(listOf(1L, 2L), merged.map { it.anime.id })
    }

    @Test
    fun `catalogue native saves survive because the account never knew them`() {
        val merged = mergeWatchlistEntries(listOf(save(-4242), save(1)), emptyList(), setOf(7), setOf(1, 7))
        assertEquals(listOf(-4242L), merged.map { it.anime.id })
    }

    @Test
    fun `adding still works alongside removing`() {
        val merged = mergeWatchlistEntries(
            listOf(save(1), save(2)), listOf(save(2), save(3)), setOf(2, 3), setOf(1, 2), addedAt = 99,
        )
        assertEquals(listOf(2L, 3L), merged.map { it.anime.id })
        assertEquals(99L, merged.last().addedAt)
    }

    @Test
    fun `a save keeps the date it was first added`() {
        val merged = mergeWatchlistEntries(listOf(save(1, addedAt = 5)), listOf(save(1, addedAt = 500)), setOf(1), setOf(1))
        assertEquals(5L, merged.single().addedAt)
    }
}

class RemoteProgressReconcileTest {
    private fun local(episode: Double, position: Double = 0.0, duration: Double = 1400.0, updatedAt: Long = 1_000) =
        ContinueEntry(card(21), ContinueProgress(episode, position, duration), updatedAt)

    @Test
    fun `episodes finished on another device move this row forward`() {
        val updated = continueAfterRemoteProgress(local(3.0), 8, 5_000, 1000)
        assertEquals(9.0, updated?.progress?.episode)
    }

    @Test
    fun `a row further along than the account is left alone`() {
        assertNull(continueAfterRemoteProgress(local(12.0), 8, 5_000, 1000))
    }

    @Test
    fun `a resume position belonging to a finished episode is dropped`() {
        val updated = continueAfterRemoteProgress(local(3.0, position = 600.0), 8, 5_000, 1000)
        assertEquals(0.0, updated?.progress?.position)
    }

    @Test
    fun `an advanced row is timestamped when the other device watched it`() {
        assertEquals(5_000L, continueAfterRemoteProgress(local(3.0, updatedAt = 1_000), 8, 5_000, 1000)?.updatedAt)
    }

    @Test
    fun `a stale account timestamp never ages this row backwards`() {
        assertEquals(9_000L, continueAfterRemoteProgress(local(3.0, updatedAt = 9_000), 8, 5_000, 1000)?.updatedAt)
    }

    @Test
    fun `the row is not advanced past the end of the series`() {
        assertNull(continueAfterRemoteProgress(local(12.0), 12, 5_000, 12))
    }

    @Test
    fun `an unknown episode count still allows the row to advance`() {
        assertEquals(9.0, continueAfterRemoteProgress(local(3.0), 8, 5_000, null)?.progress?.episode)
    }

    @Test
    fun `an account that has watched nothing changes nothing`() {
        assertNull(continueAfterRemoteProgress(local(3.0), 0, 5_000, 1000))
    }

    @Test
    fun `a caught-up row on an airing series stays on the last aired episode`() {
        assertNull(continueAfterRemoteProgress(local(10.0), 10, 5_000, 26, airedEpisodes = 10))
    }

    @Test
    fun `the row advances once the next episode has aired`() {
        assertEquals(11.0, continueAfterRemoteProgress(local(10.0), 10, 5_000, 26, airedEpisodes = 11)?.progress?.episode)
    }

    @Test
    fun `an unknown aired count leaves the planned run in charge`() {
        assertEquals(9.0, continueAfterRemoteProgress(local(3.0), 8, 5_000, 1000, airedEpisodes = null)?.progress?.episode)
    }

    @Test
    fun `a row behind a caught-up account moves to the latest aired episode`() {
        val updated = continueAfterRemoteProgress(local(5.0, position = 300.0), 8, 5_000, 12, airedEpisodes = 8)
        assertEquals(8.0, updated?.progress?.episode)
        assertEquals(0.0, updated?.progress?.position)
        assertEquals(5_000L, updated?.updatedAt)
    }

    @Test
    fun `a row behind an airing series still catches up to the account`() {
        assertEquals(9.0, continueAfterRemoteProgress(local(3.0), 8, 5_000, 26, airedEpisodes = 10)?.progress?.episode)
    }

    @Test
    fun `the row an account points at is the next episode, the latest aired, or none`() {
        assertEquals(9, remoteRowEpisode(8, 12, 10))
        assertEquals(9, remoteRowEpisode(8, null, null))
        assertEquals(8, remoteRowEpisode(8, 12, 8))
        assertEquals(8, remoteRowEpisode(9, 12, 8))
        assertNull(remoteRowEpisode(12, 12, null))
    }

    @Test
    fun `only a move off Watching since the last refresh takes a row off`() {
        val previous = mapOf(1L to "CURRENT", 2L to "REPEATING", 3L to "CURRENT", 4L to "COMPLETED", 5L to "CURRENT", 6L to "PLANNING", 7L to "CURRENT")
        val current = mapOf(1L to "COMPLETED", 2L to "DROPPED", 3L to "PAUSED", 4L to "COMPLETED", 5L to "CURRENT", 6L to "COMPLETED")
        assertEquals(setOf(1L, 2L, 3L), remoteMovesOffWatching(previous, current))
    }

    @Test
    fun `a first refresh moves nothing off`() {
        assertEquals(emptySet<Long>(), remoteMovesOffWatching(emptyMap(), mapOf(1L to "COMPLETED")))
    }

    @Test
    fun `a watched final episode on a completed title is a leftover`() {
        assertTrue(isFinishedSeriesLeftover(local(13.0, position = 1350.0), "COMPLETED", 13))
    }

    @Test
    fun `a rewatch on an earlier episode is not a leftover`() {
        assertFalse(isFinishedSeriesLeftover(local(4.0, position = 1350.0), "COMPLETED", 13))
    }

    @Test
    fun `an unfinished final episode is not a leftover`() {
        assertFalse(isFinishedSeriesLeftover(local(13.0, position = 600.0), "COMPLETED", 13))
    }

    @Test
    fun `only a completed title with a known length can leave one`() {
        val row = local(13.0, position = 1350.0)
        assertFalse(isFinishedSeriesLeftover(row, "CURRENT", 13))
        assertFalse(isFinishedSeriesLeftover(row, "COMPLETED", null))
        assertFalse(isFinishedSeriesLeftover(row, "COMPLETED", 0))
    }
}
