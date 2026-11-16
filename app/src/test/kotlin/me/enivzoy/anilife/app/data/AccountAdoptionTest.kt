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
