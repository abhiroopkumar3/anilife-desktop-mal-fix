package me.enivzoy.anilife.app.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SavedPushTest {
    @Test
    fun `AniList save adds Planning only when the account has no entry`() {
        assertEquals(SavedPush.ADD_PLANNING, aniListSavedPush(true, null))
        assertEquals(SavedPush.NONE, aniListSavedPush(true, "CURRENT"))
        assertEquals(SavedPush.NONE, aniListSavedPush(true, "PLANNING"))
    }

    @Test
    fun `AniList unsave removes only a Planning entry`() {
        assertEquals(SavedPush.DELETE, aniListSavedPush(false, "PLANNING"))
        assertEquals(SavedPush.NONE, aniListSavedPush(false, "CURRENT"))
        assertEquals(SavedPush.NONE, aniListSavedPush(false, "COMPLETED"))
        assertEquals(SavedPush.NONE, aniListSavedPush(false, null))
    }

    @Test
    fun `an entry in custom lists is hidden, not deleted, and saving brings it back`() {
        assertEquals(SavedPush.HIDE_FROM_STATUS_LISTS, aniListSavedPush(false, "PLANNING", hidden = false, inCustomLists = true))
        assertEquals(SavedPush.SHOW_ON_PLANNING, aniListSavedPush(true, "PLANNING", hidden = true, inCustomLists = true))
    }

    @Test
    fun `MAL adds plan to watch when absent and removes only plan to watch`() {
        assertEquals(SavedPush.ADD_PLANNING, malSavedPush(true, null))
        assertEquals(SavedPush.NONE, malSavedPush(true, "watching"))
        assertEquals(SavedPush.DELETE, malSavedPush(false, "plan_to_watch"))
        assertEquals(SavedPush.NONE, malSavedPush(false, "completed"))
        assertEquals(SavedPush.NONE, malSavedPush(false, null))
    }

    @Test
    fun `pending saves survive a restart and settle`() {
        val file = Files.createTempDirectory("pend").toFile().resolve("p.properties")
        PendingSavedStore(file).add(setOf(3, 1))
        val again = PendingSavedStore(file)
        assertEquals(listOf(1L, 3L), again.ids())
        again.settle(setOf(1))
        assertEquals(listOf(3L), PendingSavedStore(file).ids())
    }
}
