package me.enivzoy.anilife.app.data

import kotlinx.coroutines.runBlocking
import me.enivzoy.anilife.core.model.AnimeCard
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomListStoreTest {
    private fun card(id: Long) = AnimeCard(id = id, title = "Title $id", cover = "c$id", genres = listOf("A", "B"))
    private fun file() = File.createTempFile("custom-lists", ".properties").also { it.delete(); it.deleteOnExit() }

    private class RecordingRemote(val keep: CustomListOps = CustomListOps()) : CustomListRemote {
        val sent = mutableListOf<CustomListOps>()
        override suspend fun push(service: ListService, sending: CustomListOps, lists: List<CustomList>): CustomListOps {
            sent += sending
            return keep
        }
    }

    private fun store(
        file: File = file(),
        service: ListService? = null,
        hidden: MutableSet<String> = mutableSetOf(),
        remote: CustomListRemote? = null,
    ) = CustomListStore(
        file = file,
        activeService = { service },
        hiddenMalTags = { hidden },
        revealMalTag = { tag -> hidden.removeAll { listKey(it) == listKey(tag) } },
        remote = remote,
        clock = { 1_000L },
    )

    @Test
    fun `a new list is stored tidied, and a name already used is refused`() {
        val s = store()
        assertEquals("Sci-fi space", s.create("  Sci-fi, space "))
        assertNull(s.create("sci-fi space"))
        assertNull(s.create(" , "))
        assertEquals(listOf("Sci-fi space"), s.lists.value.map { it.name })
        assertEquals(listOf("Sci-fi space"), s.pending.creates)
    }

    @Test
    fun `making a list named after a hidden MAL tag brings the tag out of hiding`() {
        val hidden = mutableSetOf("Private")
        store(hidden = hidden).create("private")
        assertTrue(hidden.isEmpty())
    }

    @Test
    fun `adding and removing a title records pending changes, and a catalogue-only title records none`() {
        val s = store()
        s.create("Faves")
        s.setMember("faves", card(5), true)
        s.setMember("Faves", card(-9), true)
        assertEquals(listOf(-9L, 5L), s.list("Faves")!!.entries.map { it.anime.id }.sorted())
        assertEquals(mapOf("faves" to listOf(5L)), s.pending.adds)
        s.setMember("Faves", card(5), false)
        assertEquals(mapOf("faves" to listOf(5L)), s.pending.removes)
        assertTrue(s.pending.adds.isEmpty())
    }

    @Test
    fun `renaming moves every title onto the new name`() {
        val s = store()
        s.create("Faves")
        s.setMember("Faves", card(1), true)
        assertEquals("Favourites", s.rename("Faves", "Favourites"))
        assertEquals(listOf("Favourites"), s.lists.value.map { it.name })
        assertEquals(listOf("Faves"), s.pending.deletes)
        assertEquals(mapOf("favourites" to listOf(1L)), s.pending.adds)
        s.create("Other")
        assertNull(s.rename("Favourites", "other"))
    }

    @Test
    fun `emptying a list on MyAnimeList keeps it pending so the next refresh does not read it as deleted`() {
        val s = store(service = ListService.MAL)
        s.create("Faves")
        s.setMember("Faves", card(1), true)
        s.setMember("Faves", card(1), false)
        assertTrue(s.pending.creates.containsListName("Faves"))
    }

    @Test
    fun `the lists and what is pending survive a restart`() {
        val f = file()
        val first = store(file = f)
        first.create("Faves")
        first.setMember("Faves", card(7), true)
        val second = store(file = f)
        assertEquals(listOf("Faves"), second.lists.value.map { it.name })
        val saved = second.list("Faves")!!.entries.single()
        assertEquals(card(7), saved.anime)
        assertEquals(1_000L, saved.addedAt)
        assertEquals(first.pending, second.pending)
    }

    @Test
    fun `a push settles what it carried and keeps what the remote could not deliver`() = runBlocking {
        val kept = CustomListOps(adds = mapOf("faves" to listOf(2L)))
        val remote = RecordingRemote(keep = kept)
        val s = store(service = ListService.ANILIST, remote = remote)
        s.create("Faves")
        s.setMember("Faves", card(1), true)
        s.setMember("Faves", card(2), true)
        assertTrue(s.pushNow(ListService.ANILIST, remote))
        assertEquals(mapOf("faves" to listOf(2L)), s.pending.adds)
        assertTrue(s.pending.creates.isEmpty())
    }

    @Test
    fun `a refresh takes the account's lists and lays pending changes on top`() {
        val s = store(service = ListService.ANILIST)
        s.create("Made here")
        val entry = ListEntry(card(3), "PLANNING", updatedAt = 50, customLists = listOf("From phone"))
        s.hydrateFromAccount(ListService.ANILIST, listOf(entry), aniListNames = listOf("From phone"))
        assertEquals(listOf("Made here", "From phone"), s.lists.value.map { it.name }.let { it })
        assertEquals(listOf(3L), s.list("From phone")!!.entries.map { it.anime.id })
        assertEquals(50_000L, s.list("From phone")!!.entries.single().addedAt)
    }

    @Test
    fun `MyAnimeList tags become lists, and hidden tags stay off them but are still known`() {
        val hidden = mutableSetOf("Private")
        val s = store(service = ListService.MAL, hidden = hidden)
        val entries = listOf(
            ListEntry(card(1), "CURRENT", customLists = listOf("Faves", "Private")),
            ListEntry(card(2), "COMPLETED", customLists = listOf("faves")),
        )
        s.hydrateFromAccount(ListService.MAL, entries, aniListNames = null)
        assertEquals(listOf("Faves"), s.lists.value.map { it.name })
        assertEquals(listOf(1L, 2L), s.list("Faves")!!.entries.map { it.anime.id }.sorted())
        assertEquals(listOf("Faves", "Private"), s.malTags.value)
    }

    @Test
    fun `leaving an account turns every list into pending changes for the next one`() {
        val s = store(service = ListService.ANILIST)
        s.hydrateFromAccount(
            ListService.ANILIST,
            listOf(ListEntry(card(4), "CURRENT", customLists = listOf("Faves"))),
            aniListNames = listOf("Faves", "Empty"),
        )
        assertTrue(s.pending.isEmpty)
        s.onAccountLeft()
        assertEquals(listOf("Faves", "Empty"), s.pending.creates)
        assertEquals(mapOf("faves" to listOf(4L)), s.pending.adds)
    }

    @Test
    fun `an import pools titles into same-named lists and adds new ones`() {
        val s = store()
        s.create("Faves")
        s.setMember("Faves", card(1), true)
        val added = s.importLists(
            listOf(
                CustomList("faves", listOf(LocalSave(card(1)), LocalSave(card(2)))),
                CustomList("New", listOf(LocalSave(card(3)))),
            ),
        )
        assertEquals(1, added)
        assertEquals(listOf(1L, 2L), s.list("Faves")!!.entries.map { it.anime.id }.sorted())
        assertEquals(listOf(3L), s.list("New")!!.entries.map { it.anime.id })
    }
}
