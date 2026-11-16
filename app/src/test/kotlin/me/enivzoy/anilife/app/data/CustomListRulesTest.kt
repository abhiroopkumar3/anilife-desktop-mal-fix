package me.enivzoy.anilife.app.data

import kotlinx.serialization.json.Json
import me.enivzoy.anilife.core.model.AnimeCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ported from Android's `CustomListsTest`, with `WatchlistEntry` as [LocalSave]. */
class CustomListRulesTest {
    private fun entry(id: Long, addedAt: Long = 0L) = LocalSave(AnimeCard(id = id, title = "Title $id"), addedAt)

    @Test
    fun `names lose commas and extra spaces, and a blank name is refused`() {
        assertEquals("Sci-fi space", normalizedListName("  Sci-fi,   space "))
        assertNull(normalizedListName(" , "))
        assertEquals(MAX_CUSTOM_LIST_NAME, normalizedListName("x".repeat(80))!!.length)
    }

    @Test
    fun `an add undone before it is pushed leaves a removal, and a delete forgets the list's pending titles`() {
        val ops = CustomListOps().added("Faves", 1).removed("faves", 1)
        assertEquals(emptyList<Long>(), ops.adds["faves"].orEmpty())
        assertEquals(listOf(1L), ops.removes["faves"])

        val deleted = CustomListOps().created("Faves").added("Faves", 2).deleted("Faves")
        assertTrue(deleted.creates.isEmpty())
        assertTrue(deleted.adds.isEmpty())
        assertEquals(listOf("Faves"), deleted.deletes)
    }

    @Test
    fun `a push only settles what it carried, and keeps what it could not deliver`() {
        val sent = CustomListOps().added("Faves", 1).added("Faves", 2)
        val meanwhile = sent.added("Faves", 3)
        val kept = CustomListOps(adds = mapOf("faves" to listOf(2L)))
        assertEquals(mapOf("faves" to listOf(2L, 3L)), meanwhile.minus(sent, kept).adds)
    }

    @Test
    fun `list names keep the account's order, drop deletions and add new names once`() {
        val ops = CustomListOps().deleted("Old").created("faves").created("New")
        assertEquals(listOf("Faves", "New"), plannedListNames(listOf("Old", "Faves"), ops))
    }

    @Test
    fun `adding an untracked title creates it in the list, spelled the account's way`() {
        val changes = plannedEntryChanges(
            remote = emptyMap(),
            ops = CustomListOps().added("faves", 7),
            canonicalNames = listOf("Faves"),
        )
        assertEquals(listOf(CustomListEntryChange.Write(7, listOf("Faves"), null)), changes)
    }

    @Test
    fun `adding keeps every other list and tag the title already has`() {
        val existing = RemoteMembership(entryId = 70, lists = listOf("Private tag", "Rewatch"))
        val changes = plannedEntryChanges(
            remote = mapOf(7L to existing),
            ops = CustomListOps().added("Faves", 7),
            canonicalNames = listOf("Faves", "Rewatch"),
        )
        assertEquals(
            listOf(CustomListEntryChange.Write(7, listOf("Private tag", "Rewatch", "Faves"), existing)),
            changes,
        )
    }

    @Test
    fun `a list-only entry left in no list is deleted, a tracked one only loses the list`() {
        val hidden = RemoteMembership(entryId = 70, lists = listOf("Faves"), hidden = true)
        val tracked = RemoteMembership(entryId = 80, lists = listOf("Faves"))
        val changes = plannedEntryChanges(
            remote = mapOf(7L to hidden, 8L to tracked),
            ops = CustomListOps().removed("Faves", 7).removed("Faves", 8),
            canonicalNames = listOf("Faves"),
        )
        assertEquals(
            listOf(
                CustomListEntryChange.Delete(7, 70),
                CustomListEntryChange.Write(8, emptyList(), tracked),
            ),
            changes,
        )
    }

    @Test
    fun `deleting a list reaches titles another device put in it`() {
        val other = RemoteMembership(entryId = 90, lists = listOf("Faves", "Keep"))
        val changes = plannedEntryChanges(
            remote = mapOf(9L to other),
            ops = CustomListOps().deleted("Faves"),
            canonicalNames = listOf("Keep"),
        )
        assertEquals(listOf(CustomListEntryChange.Write(9, listOf("Keep"), other)), changes)
    }

    @Test
    fun `a push does not touch titles another device added to the same list`() {
        val theirs = RemoteMembership(entryId = 90, lists = listOf("Faves"))
        val changes = plannedEntryChanges(
            remote = mapOf(9L to theirs),
            ops = CustomListOps().added("Faves", 1),
            canonicalNames = listOf("Faves"),
        )
        assertEquals(listOf(1L), changes.map { it.anilistId })
    }

    @Test
    fun `catalogue-only titles are never sent`() {
        val changes = plannedEntryChanges(
            remote = emptyMap(),
            ops = CustomListOps().added("Faves", -5).added("Faves", 5),
            canonicalNames = listOf("Faves"),
            unsyncable = { it < 0 },
        )
        assertEquals(listOf(5L), changes.map { it.anilistId })
    }

    @Test
    fun `a rename moves every title onto the new name in one write each`() {
        val member = RemoteMembership(entryId = 10, lists = listOf("Faves"))
        val ops = CustomListOps().deleted("Faves").created("Favourites").added("Favourites", 1)
        val names = plannedListNames(listOf("Faves"), ops)
        assertEquals(listOf("Favourites"), names)
        assertEquals(
            listOf(CustomListEntryChange.Write(1, listOf("Favourites"), member)),
            plannedEntryChanges(mapOf(1L to member), ops, names),
        )
    }

    @Test
    fun `a refresh takes the account's lists and lays pending changes on top`() {
        val local = listOf(
            CustomList("Faves", listOf(entry(1, addedAt = 5), entry(2, addedAt = 4)), createdAt = 1),
            CustomList("Gone", listOf(entry(3))),
            CustomList("Made here", emptyList()),
        )
        val ops = CustomListOps().created("Made here").added("Faves", 2).removed("Faves", 4)
        val merged = mergedCustomLists(
            local = local,
            remoteNames = listOf("FAVES", "From phone"),
            remoteMembers = mapOf(
                "faves" to listOf(entry(1), entry(4)),
                "from phone" to listOf(entry(6, addedAt = 9)),
            ),
            ops = ops,
            now = 100,
        )
        assertEquals(listOf("FAVES", "Made here", "From phone"), merged.map { it.name })
        assertEquals(listOf(1L, 2L), merged[0].entries.map { it.anime.id })
        assertEquals(5L, merged[0].entries[0].addedAt)
        assertEquals(1L, merged[0].createdAt)
        assertEquals(listOf(6L), merged[2].entries.map { it.anime.id })
    }

    @Test
    fun `catalogue-only titles stay in their list through a refresh`() {
        val merged = mergedCustomLists(
            local = listOf(CustomList("Faves", listOf(entry(-3)))),
            remoteNames = listOf("Faves"),
            remoteMembers = emptyMap(),
            ops = CustomListOps(),
            keepLocal = { it < 0 },
        )
        assertEquals(listOf(-3L), merged.single().entries.map { it.anime.id })
    }

    @Test
    fun `AniList's custom list flags read as the names that are on`() {
        val json = Json
        assertEquals(
            listOf("Faves"),
            parseAniListCustomLists(json.parseToJsonElement("""[{"name":"Faves","enabled":true},{"name":"Later","enabled":false}]""")),
        )
        assertEquals(
            listOf("Later"),
            parseAniListCustomLists(json.parseToJsonElement("""{"Faves":false,"Later":true}""")),
        )
        assertEquals(emptyList<String>(), parseAniListCustomLists(json.parseToJsonElement("null")))
        assertEquals(emptyList<String>(), parseAniListCustomLists(null))
    }
}
