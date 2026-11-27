package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.AnimeCard
import me.enivzoy.anilife.core.model.NextAiring
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/*
 * Ported from the Android app's LibraryViewSelectionTest, NewEpisodeCountTest, CombinedWatchlistTest
 * and LibraryWaveOrderTest (app/src/test/java/com/anilili/ui/profile). The cases and their reasons are
 * Android's; only the types differ. The sort and filter cases at the end are Windows' own.
 */

private val ALL = LibraryView.entries.toList()
private val SIGNED_OUT = listOf(LibraryView.WATCHLIST)

class LibraryViewSelectionTest {
    @Test
    fun `the default shows what is being watched and what is saved`() {
        assertEquals(
            listOf(LibraryView.WATCHLIST, LibraryView.WATCHING),
            libraryViewsFrom(defaultLibraryViewNames(), ALL),
        )
    }

    @Test
    fun `sections keep enum order however the chips were tapped`() {
        val names = toggledLibraryViews(toggledLibraryViews("", LibraryView.DROPPED), LibraryView.WATCHING)
        assertEquals(listOf(LibraryView.WATCHING, LibraryView.DROPPED), libraryViewsFrom(names, ALL))
    }

    @Test
    fun `tapping a chosen list again removes it`() {
        val names = toggledLibraryViews(defaultLibraryViewNames(), LibraryView.WATCHING)
        assertEquals(listOf(LibraryView.WATCHLIST), libraryViewsFrom(names, ALL))
    }

    @Test
    fun `turning every list off shows none of them`() {
        var names = defaultLibraryViewNames()
        names = toggledLibraryViews(names, LibraryView.WATCHING)
        names = toggledLibraryViews(names, LibraryView.WATCHLIST)
        assertEquals(emptyList(), libraryViewsFrom(names, ALL))
    }

    @Test
    fun `a signed-out viewer falls back to the watchlist rather than an empty page`() {
        assertEquals(SIGNED_OUT, libraryViewsFrom("WATCHING,COMPLETED", SIGNED_OUT))
    }

    @Test
    fun `an available list survives the account's lists being unavailable`() {
        assertEquals(SIGNED_OUT, libraryViewsFrom(defaultLibraryViewNames(), SIGNED_OUT))
    }

    @Test
    fun `a name from an older build is ignored rather than crashing the page`() {
        assertEquals(listOf(LibraryView.PAUSED), libraryViewsFrom("ON_HOLD,PAUSED", ALL))
    }
}

private const val NOW = 1_700_000_000L
private const val HOUR = 3_600L

private fun card(
    status: String? = "CURRENT",
    progress: Int? = 5,
    nextEpisodeNumber: Int? = 9,
    nextEpisodeAiringAt: Long? = NOW + 2 * HOUR,
) = LibraryCardData(
    id = 1,
    title = "Airing series",
    cover = null,
    format = "TV",
    airingStatus = "RELEASING",
    status = status,
    userScore = null,
    averageScore = null,
    progress = progress,
    totalEpisodes = 12,
    nextEpisodeNumber = nextEpisodeNumber,
    nextEpisodeAiringAt = nextEpisodeAiringAt,
)

class NewEpisodeCountTest {
    @Test
    fun `episode 9 is next and five are watched, so three are waiting`() {
        assertEquals(3, newEpisodeCount(card(), NOW))
    }

    @Test
    fun `a viewer level with the broadcast gets no badge`() {
        assertNull(newEpisodeCount(card(progress = 8), NOW))
    }

    @Test
    fun `a viewer ahead of the broadcast gets no badge`() {
        assertNull(newEpisodeCount(card(progress = 9), NOW))
    }

    @Test
    fun `a series with nothing scheduled has nothing new`() {
        assertNull(newEpisodeCount(card(nextEpisodeNumber = null, nextEpisodeAiringAt = null), NOW))
    }

    @Test
    fun `a series whose first episode has not aired yet has nothing new`() {
        assertNull(newEpisodeCount(card(status = "CURRENT", progress = 0, nextEpisodeNumber = 1), NOW))
    }

    @Test
    fun `re-watching and paused count, completed and dropped do not`() {
        assertEquals(3, newEpisodeCount(card(status = "REPEATING"), NOW))
        assertEquals(3, newEpisodeCount(card(status = "PAUSED"), NOW))
        assertNull(newEpisodeCount(card(status = "COMPLETED"), NOW))
        assertNull(newEpisodeCount(card(status = "DROPPED"), NOW))
    }

    @Test
    fun `a planned title never started is not behind on anything`() {
        assertNull(newEpisodeCount(card(status = "PLANNING"), NOW))
    }

    @Test
    fun `a save known only to this PC counts on its local progress`() {
        assertEquals(3, newEpisodeCount(card(status = null), NOW))
        assertNull(newEpisodeCount(card(status = null, progress = 0), NOW))
        assertNull(newEpisodeCount(card(status = null, progress = null), NOW))
    }

    @Test
    fun `a scheduled time already past counts that episode as out`() {
        assertEquals(4, newEpisodeCount(card(nextEpisodeAiringAt = NOW - 2 * HOUR), NOW))
    }

    @Test
    fun `an episode that has just gone out is not counted until sites can carry it`() {
        assertEquals(3, newEpisodeCount(card(nextEpisodeAiringAt = NOW - 10 * 60L), NOW))
    }

    @Test
    fun `a long-runner picked up early reports its whole backlog`() {
        assertEquals(1100, newEpisodeCount(card(progress = 40, nextEpisodeNumber = 1141), NOW))
    }
}

private fun save(id: Long, title: String, cover: String? = null, addedAt: Long) =
    LocalSave(AnimeCard(id = id, title = title, cover = cover), addedAt)

class CombinedWatchlistTest {
    @Test
    fun `a saved list holding one anime twice still yields one card`() {
        val local = listOf(
            save(185874, "Dandadan", addedAt = 2),
            save(185874, "Dandadan", addedAt = 1),
            save(21, "One Piece", addedAt = 3),
        )

        val cards = buildCombinedWatchlist(planning = emptyList(), local = local)

        assertEquals(listOf(185874L, 21L), cards.map { it.id })
    }

    @Test
    fun `the first copy of a duplicate is the one kept`() {
        val local = listOf(save(7, "Kept", cover = "a", addedAt = 1), save(7, "Dropped", cover = "b", addedAt = 2))

        val cards = buildCombinedWatchlist(planning = emptyList(), local = local)

        assertEquals(1, cards.size)
        assertEquals("Kept", cards.single().title)
    }
}

private fun planning(id: Long, title: String, updatedAt: Long) = ListEntry(
    anime = AnimeCard(id = id, title = title, format = "TV"),
    status = "PLANNING",
    updatedAt = updatedAt,
)

private fun ordered(cards: List<LibraryCardData>) =
    libraryCards(cards, format = null, airing = null, titleFilter = "", sort = LibrarySort.LAST_MODIFIED)

private const val SAVED_AT_MS = 1_760_000_000_000L

class LibraryWaveOrderTest {
    @Test
    fun `a saved title moves down the order once the account's own timestamps land`() {
        val local = listOf(save(7, "Kagurabachi", addedAt = SAVED_AT_MS))

        val firstWave = ordered(buildCombinedWatchlist(planning = emptyList(), local = local))
        assertEquals(listOf(7L), firstWave.map { it.id })

        val account = listOf(
            planning(7, "Kagurabachi", updatedAt = 1_600_000_000L),
            planning(1, "False Memory", updatedAt = 1_759_000_000L),
            planning(2, "The Strongest Magician", updatedAt = 1_758_000_000L),
        )
        val secondWave = ordered(buildCombinedWatchlist(account, local))

        assertEquals(listOf(1L, 2L, 7L), secondWave.map { it.id })
        assertNotEquals(firstWave.first().id, secondWave.first().id)
    }

    @Test
    fun `a head that does not move is not something the carousel has to correct`() {
        val local = listOf(save(7, "Kagurabachi", addedAt = SAVED_AT_MS))
        val account = listOf(
            planning(7, "Kagurabachi", updatedAt = 1_760_000_000L),
            planning(1, "False Memory", updatedAt = 1_700_000_000L),
        )

        val firstWave = ordered(buildCombinedWatchlist(planning = emptyList(), local = local))
        val secondWave = ordered(buildCombinedWatchlist(account, local))

        assertEquals(7L, firstWave.first().id)
        assertEquals(7L, secondWave.first().id)
    }
}

class LibraryCardsWindowsTest {
    private fun c(id: Long, title: String, format: String? = "TV", airing: String? = "FINISHED", score: Double? = null, progress: Int? = null, updated: Long = 0) =
        LibraryCardData(id, title, null, format, airing, "CURRENT", score, null, progress, 12, updated)

    private val cards = listOf(
        c(1, "Bleach", updated = 30, score = 7.0, progress = 3),
        c(2, "akira", format = "MOVIE", updated = 10, score = 9.0, progress = 1),
        c(3, "Naruto", airing = "RELEASING", updated = 20, progress = 12),
    )

    @Test
    fun `each sort orders the way its label says`() {
        fun ids(sort: LibrarySort) = libraryCards(cards, null, null, "", sort).map { it.id }
        assertEquals(listOf(1L, 3L, 2L), ids(LibrarySort.LAST_MODIFIED))
        assertEquals(listOf(2L, 1L, 3L), ids(LibrarySort.TITLE))
        assertEquals(listOf(2L, 1L, 3L), ids(LibrarySort.SCORE))
        assertEquals(listOf(3L, 1L, 2L), ids(LibrarySort.PROGRESS))
        assertEquals(listOf(1L, 2L, 3L), ids(LibrarySort.DEFAULT))
    }

    @Test
    fun `format, airing status and the title search all narrow the list`() {
        assertEquals(listOf(2L), libraryCards(cards, "MOVIE", null, "", LibrarySort.DEFAULT).map { it.id })
        assertEquals(listOf(3L), libraryCards(cards, null, "RELEASING", "", LibrarySort.DEFAULT).map { it.id })
        assertEquals(listOf(1L), libraryCards(cards, null, null, "BLEA", LibrarySort.DEFAULT).map { it.id })
    }

    @Test
    fun `a title filed in custom lists only is on no status list`() {
        val entries = listOf(
            ListEntry(AnimeCard(1, "Shown"), "COMPLETED"),
            ListEntry(AnimeCard(2, "Hidden"), "COMPLETED", hiddenFromStatusLists = true),
        )
        assertEquals(listOf(1L), completedCards(entries).map { it.id })
    }
}
