package me.enivzoy.anilife.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Settings the store reads without owning them; `App` fills these in from the settings file. */
object CustomListHooks {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile var hiddenMalTags: () -> Set<String> = { emptySet() }
    @Volatile var revealMalTag: (String) -> Unit = {}
}

/**
 * Carries pending custom-list changes to the account (Android's `pushToAniList` and `pushToMal`).
 * AniList keeps the list names as one field and each title's lists on its entry; MyAnimeList keeps a
 * list as a tag on each title.
 */
object CustomListSync : CustomListRemote {
    override suspend fun push(service: ListService, sending: CustomListOps, lists: List<CustomList>): CustomListOps =
        when (service) {
            ListService.ANILIST -> pushToAniList(sending)
            ListService.MAL -> pushToMal(sending, lists)
        }

    private suspend fun pushToAniList(sending: CustomListOps): CustomListOps {
        val remoteNames = AniListSync.customListNames()
        val names = plannedListNames(remoteNames, sending)
        if (names != remoteNames) AniListSync.setCustomListNames(names)
        val remote = AniListSync.freshEntries().associate {
            it.anime.id to RemoteMembership(it.entryId, it.customLists, it.hidden)
        }
        val changes = plannedEntryChanges(remote, sending, names, ::isCatalogueNativeId)
        val writes = changes.filterIsInstance<CustomListEntryChange.Write>()
        val deletes = changes.filterIsInstance<CustomListEntryChange.Delete>()
        AniListSync.saveCustomLists(writes.map { CustomListWrite(it.anilistId, it.lists, create = it.existing == null) })
        AniListSync.deleteEntries(deletes.map { it.entryId })
        return catalogueOnlyAdds(sending)
    }

    private suspend fun pushToMal(sending: CustomListOps, lists: List<CustomList>): CustomListOps {
        val entries = AccountLibrary.remoteEntries().orEmpty()
        val remote = entries.mapNotNull { entry ->
            entry.anime.malId?.let { entry.anime.id to RemoteMembership(it, entry.customLists) }
        }.toMap()
        fun listFor(name: String) = lists.firstOrNull { listKey(it.name) == listKey(name) }
        val canonical = lists.map { it.name } + entries.flatMap { it.customLists }
        val changes = plannedEntryChanges(remote, sending, canonical, ::isCatalogueNativeId)
        val unmapped = mutableSetOf<Long>()
        changes.filterIsInstance<CustomListEntryChange.Write>().forEach { write ->
            val malId = write.existing?.entryId ?: malIdFor(write.anilistId, lists)
            if (malId == null) {
                unmapped += write.anilistId
                return@forEach
            }
            MyAnimeListSync.setTags(malId, write.lists, create = write.existing == null)
        }
        // Lists with nothing in them cannot exist on MAL; they stay pending, and so on this PC.
        val emptyHere = sending.creates.filter { name -> listFor(name)?.entries.isNullOrEmpty() }
        val unmappedAdds = sending.adds.mapValues { (_, ids) -> ids.filter { it in unmapped } }
            .filterValues { it.isNotEmpty() }
        val catalogue = catalogueOnlyAdds(sending)
        return CustomListOps(
            creates = emptyHere,
            adds = (unmappedAdds.keys + catalogue.adds.keys).associateWith { key ->
                (unmappedAdds[key].orEmpty() + catalogue.adds[key].orEmpty()).distinct()
            },
        )
    }

    /** The MAL id for an AniList title: the card's own, else AniList's `idMal`. */
    private suspend fun malIdFor(anilistId: Long, lists: List<CustomList>): Long? {
        lists.firstNotNullOfOrNull { list -> list.entries.firstOrNull { it.anime.id == anilistId }?.anime?.malId }
            ?.let { return it }
        val repo = AccountLibrary.repository ?: return null
        return runCatching { repo.detail(anilistId).malId }.getOrNull()?.takeIf { it > 0 }
    }

    /** Catalogue-only titles have no id either service knows; their additions are kept, never sent. */
    private fun catalogueOnlyAdds(sending: CustomListOps) = CustomListOps(
        adds = sending.adds.mapValues { (_, ids) -> ids.filter(::isCatalogueNativeId) }
            .filterValues { it.isNotEmpty() },
    )
}

/** The one store the app uses. */
val CustomListStoreInstance: CustomListStore = CustomListStore(
    activeService = { AccountLibrary.activeService() },
    hiddenMalTags = { CustomListHooks.hiddenMalTags() },
    revealMalTag = { CustomListHooks.revealMalTag(it) },
    remote = CustomListSync,
    afterDelivery = { CustomListHooks.scope.launch { AccountLibrary.refresh() } },
)
