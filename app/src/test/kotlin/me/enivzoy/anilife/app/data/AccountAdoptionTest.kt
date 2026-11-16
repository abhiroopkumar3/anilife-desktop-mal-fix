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
