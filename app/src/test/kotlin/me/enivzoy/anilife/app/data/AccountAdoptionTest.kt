package me.enivzoy.anilife.app.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountAdoptionTest {
    @Test
    fun `one connected service stays`() {
        assertEquals(ListService.ANILIST, serviceToKeep(true, false, null))
        assertEquals(ListService.MAL, serviceToKeep(false, true, null))
        assertNull(serviceToKeep(false, false, ActiveAccount(ListService.MAL, 1)))
    }

    @Test
    fun `with both connected the most recent sign-in stays`() {
        assertEquals(ListService.MAL, serviceToKeep(true, true, ActiveAccount(ListService.MAL, 5)))
        assertEquals(ListService.ANILIST, serviceToKeep(true, true, ActiveAccount(ListService.ANILIST, 5)))
    }

    @Test
    fun `with both connected and no record AniList stays`() {
        assertEquals(ListService.ANILIST, serviceToKeep(true, true, null))
    }

    @Test
    fun `the record survives a restart and a bad file reads as none`() {
        val dir = Files.createTempDirectory("adopt").toFile()
        val file = dir.resolve("active-account.properties")
        val store = ActiveAccountStore(file)
        assertNull(store.read())
        store.write(ActiveAccount(ListService.MAL, 42))
        assertEquals(ActiveAccount(ListService.MAL, 42), store.read())
        file.writeText("service=NOPE")
        assertNull(store.read())
        store.clear()
        dir.deleteRecursively()
    }
}

class LocalLibraryStoreDatesTest {
    private fun store(dir: java.io.File) = LocalLibraryStore(dir.resolve("library.properties"))

    @Test
    fun `a save remembers when it was added across restarts`() {
        val dir = Files.createTempDirectory("lib").toFile()
        val before = System.currentTimeMillis()
        store(dir).add(me.enivzoy.anilife.core.model.AnimeCard(id = 1, title = "A"))
        val saves = store(dir).saves.value
        assertEquals(1, saves.size)
        assert(saves.single().addedAt >= before)
        dir.deleteRecursively()
    }

    @Test
    fun `a library file from before dates were kept still loads with date zero`() {
        val dir = Files.createTempDirectory("lib").toFile()
        dir.resolve("library.properties").writeText("anime.7.title=Old\n")
        val saves = store(dir).saves.value
        assertEquals(7L, saves.single().anime.id)
        assertEquals(0L, saves.single().addedAt)
        dir.deleteRecursively()
    }

    @Test
    fun `re-adding a title keeps its first date`() {
        val dir = Files.createTempDirectory("lib").toFile()
        val s = store(dir)
        val card = me.enivzoy.anilife.core.model.AnimeCard(id = 1, title = "A")
        s.add(card)
        val first = s.saves.value.single().addedAt
        Thread.sleep(5)
        s.add(card)
        assertEquals(first, s.saves.value.single().addedAt)
        dir.deleteRecursively()
    }
}

class SeededContinueRowTest {
    @Test
    fun `a seeded row with no length survives a restart and offers no resume position`() {
        val dir = Files.createTempDirectory("cont").toFile()
        val file = dir.resolve("continue.properties")
        val card = me.enivzoy.anilife.core.model.AnimeCard(id = 1, title = "A")
        ContinueStore(file).replace(ContinueEntry(card, ContinueProgress(5.0, 0.0, 0.0), 1_000))
        val reread = ContinueStore(file)
        assertEquals(5.0, reread.entries.value.single().progress.episode)
        assertNull(reread.positionFor(1, 5.0))
        dir.deleteRecursively()
    }
}

class ContinueWatchedPersistenceTest {
    private val card = me.enivzoy.anilife.core.model.AnimeCard(id = 1, title = "A")

    @Test
    fun `the watched set and the seeded flag survive a restart`() {
        val dir = Files.createTempDirectory("cont").toFile()
        val file = dir.resolve("continue.properties")
        ContinueStore(file).replace(ContinueEntry(card, ContinueProgress(5.0, 0.0, 0.0), 1_000, setOf(1.0, 2.0, 2.5), true))
        val row = ContinueStore(file).entries.value.single()
        assertEquals(setOf(1.0, 2.0, 2.5), row.watched)
        assertEquals(true, row.fromRemote)
        dir.deleteRecursively()
    }

    @Test
    fun `a row written before the watched set existed is seeded once`() {
        val dir = Files.createTempDirectory("cont").toFile()
        val file = dir.resolve("continue.properties")
        file.writeText("entry.1.id=1\nentry.1.title=A\nentry.1.episode=4.0\nentry.1.position=0\nentry.1.duration=1400\nentry.1.updatedAt=5\n")
        assertEquals(setOf(1.0, 2.0, 3.0), ContinueStore(file).entries.value.single().watched)
        dir.deleteRecursively()
    }

    @Test
    fun `an empty set that was written stays empty`() {
        val dir = Files.createTempDirectory("cont").toFile()
        val file = dir.resolve("continue.properties")
        ContinueStore(file).replace(ContinueEntry(card, ContinueProgress(7.0, 0.0, 1400.0), 1_000))
        assertEquals(emptySet<Double>(), ContinueStore(file).entries.value.single().watched)
        dir.deleteRecursively()
    }

    @Test
    fun `playing an episode keeps the set, and a watched episode no longer moves the row`() {
        val dir = Files.createTempDirectory("cont").toFile()
        val store = ContinueStore(dir.resolve("continue.properties"))
        store.replace(ContinueEntry(card, ContinueProgress(4.0, 0.0, 0.0), 1_000, setOf(1.0, 2.0, 3.0)))
        store.upsert(card, 4.0, 300.0, 1400.0)
        assertEquals(setOf(1.0, 2.0, 3.0), store.entries.value.single().watched)
        store.upsert(card, 2.0, 100.0, 1400.0)
        assertEquals(4.0, store.entries.value.single().progress.episode)
        dir.deleteRecursively()
    }

    @Test
    fun `finishing an episode moves the row, and the last one removes it`() {
        val dir = Files.createTempDirectory("cont").toFile()
        val store = ContinueStore(dir.resolve("continue.properties"))
        store.upsert(card, 3.0, 1300.0, 1400.0)
        val before = store.markEpisodeWatched(1, 3.0, 4.0, seriesCompleted = false)
        assertEquals(3.0, before?.progress?.episode)
        assertEquals(4.0, store.entries.value.single().progress.episode)
        assertEquals(null, store.markEpisodeWatched(1, 3.0, 4.0, seriesCompleted = false))
        store.markEpisodeWatched(1, 4.0, null, seriesCompleted = true)
        assertEquals(0, store.entries.value.size)
        dir.deleteRecursively()
    }
}
