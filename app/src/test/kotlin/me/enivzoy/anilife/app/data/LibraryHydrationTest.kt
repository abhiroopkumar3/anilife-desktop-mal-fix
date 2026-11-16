package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.AnimeCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryHydrationTest {
    private fun card(id: Long, episodes: Int? = 12) = AnimeCard(id = id, title = "T$id", episodes = episodes)
    private fun entry(id: Long, status: String, progress: Int = 0, hidden: Boolean = false, episodes: Int? = 12) =
        ListEntry(card(id, episodes), status, progress, updatedAt = 100, hiddenFromStatusLists = hidden)
    private fun row(id: Long, episode: Double, position: Double = 0.0, duration: Double = 1400.0) =
        ContinueEntry(card(id), ContinueProgress(episode, position, duration), 50_000)
    private fun hydrate(
        remote: List<ListEntry>,
        saves: List<LocalSave> = emptyList(),
        rows: List<ContinueEntry> = emptyList(),
        previous: Map<Long, String> = emptyMap(),
    ) = hydrateFromAccount(remote, saves, rows, previous, nowMillis = 9_000)

    @Test
    fun `planning titles become saves with the refresh time`() {
        val result = hydrate(listOf(entry(1, "PLANNING")))
        assertEquals(listOf(1L), result.saves.map { it.anime.id })
        assertEquals(9_000L, result.saves.single().addedAt)
    }

    @Test
    fun `titles hidden from status lists are ignored`() {
        val result = hydrate(listOf(entry(1, "PLANNING", hidden = true), entry(2, "CURRENT", hidden = true)))
        assertTrue(result.saves.isEmpty())
        assertTrue(result.continueUpserts.isEmpty())
        assertTrue(result.snapshot.isEmpty())
    }

    @Test
    fun `a title removed from the account since the last refresh is unsaved here`() {
        val result = hydrate(
            remote = listOf(entry(2, "PLANNING")),
            saves = listOf(LocalSave(card(1), 5), LocalSave(card(2), 5)),
            previous = mapOf(1L to "PLANNING", 2L to "PLANNING"),
        )
        assertEquals(listOf(2L), result.saves.map { it.anime.id })
    }

    @Test
    fun `a watching title with no row is seeded on the next episode`() {
        val upsert = hydrate(listOf(entry(1, "CURRENT", progress = 4))).continueUpserts.single()
        assertEquals(5.0, upsert.progress.episode)
        assertEquals(0.0, upsert.progress.position)
        assertEquals(100_000L, upsert.updatedAt)
    }

    @Test
    fun `a watching title with nothing watched starts on episode one`() {
        assertEquals(1.0, hydrate(listOf(entry(1, "CURRENT", progress = 0))).continueUpserts.single().progress.episode)
    }

    @Test
    fun `completed and dropped titles are not seeded`() {
        val result = hydrate(listOf(entry(1, "COMPLETED", 12), entry(2, "DROPPED", 3), entry(3, "PAUSED", 3)))
        assertTrue(result.continueUpserts.isEmpty())
    }

    @Test
    fun `an existing row moves forward and never back`() {
        val ahead = hydrate(listOf(entry(1, "CURRENT", progress = 8)), rows = listOf(row(1, 3.0)))
        assertEquals(9.0, ahead.continueUpserts.single().progress.episode)
        val behind = hydrate(listOf(entry(1, "CURRENT", progress = 2)), rows = listOf(row(1, 6.0)))
        assertTrue(behind.continueUpserts.isEmpty())
    }

    @Test
    fun `a title moved off Watching since the last refresh loses its row`() {
        val result = hydrate(
            remote = listOf(entry(1, "COMPLETED", 12)),
            rows = listOf(row(1, 5.0)),
            previous = mapOf(1L to "CURRENT"),
        )
        assertEquals(setOf(1L), result.continueRemovals)
    }

    @Test
    fun `a first refresh removes no rows`() {
        val result = hydrate(listOf(entry(1, "COMPLETED", 12)), rows = listOf(row(1, 5.0)))
        assertTrue(result.continueRemovals.isEmpty())
    }

    @Test
    fun `a finished series leaves no row behind`() {
        val result = hydrate(
            remote = listOf(entry(1, "COMPLETED", 12)),
            rows = listOf(row(1, 12.0, position = 1350.0)),
            previous = mapOf(1L to "COMPLETED"),
        )
        assertEquals(setOf(1L), result.continueRemovals)
    }

    @Test
    fun `a caught-up airing title rests on the latest aired episode`() {
        val airing = AnimeCard(
            id = 1, title = "T1", episodes = 26,
            nextAiring = me.enivzoy.anilife.core.model.NextAiring(episode = 11, airingAt = 0),
        )
        val result = hydrate(listOf(ListEntry(airing, "CURRENT", 10, updatedAt = 100)))
        assertEquals(10.0, result.continueUpserts.single().progress.episode)
    }

    @Test
    fun `the snapshot records every visible status for the next refresh`() {
        val result = hydrate(listOf(entry(1, "CURRENT", 1), entry(2, "PLANNING"), entry(3, "PAUSED", hidden = true)))
        assertEquals(mapOf(1L to "CURRENT", 2L to "PLANNING"), result.snapshot)
    }
}
