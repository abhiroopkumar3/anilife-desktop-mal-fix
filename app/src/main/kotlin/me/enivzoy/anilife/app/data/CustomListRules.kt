package me.enivzoy.anilife.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Custom lists, ported from Android's `data/library/CustomLists.kt`.
 *
 * A list is a name the viewer chose. On AniList it is one of the account's custom anime lists; on
 * MyAnimeList it is a tag, so every title carrying the tag is in the list; signed out it lives on
 * this PC only. Entries carry the card data a save does, so a list draws with no network.
 */
data class CustomList(
    val name: String,
    val entries: List<LocalSave> = emptyList(),
    val createdAt: Long = 0L,
)

/** The longest name offered. AniList has no documented cap; this keeps a chip on one line. */
const val MAX_CUSTOM_LIST_NAME = 40

/**
 * [raw] as a list name, or null when nothing usable is left. Commas become spaces because MyAnimeList
 * stores tags as one comma-separated field: "Sci-fi, space" would come back as two lists.
 */
fun normalizedListName(raw: String): String? =
    raw.replace(',', ' ')
        .trim()
        .replace(WHITESPACE_RUN, " ")
        .take(MAX_CUSTOM_LIST_NAME)
        .trim()
        .takeIf { it.isNotEmpty() }

private val WHITESPACE_RUN = Regex("\\s+")

/** List names are compared the way a viewer reads them: "Faves" and "faves" are one list. */
fun listKey(name: String): String = name.trim().lowercase()

fun Collection<String>.containsListName(name: String): Boolean = any { listKey(it) == listKey(name) }

/**
 * What this PC has changed and the account has not heard yet, recorded as individual changes so a
 * title added to the same list on another device meanwhile survives this PC's push. Each map is keyed
 * by [listKey]. [creates] and [deletes] can hold the same name at once (deleted, then made again).
 */
data class CustomListOps(
    val creates: List<String> = emptyList(),
    val deletes: List<String> = emptyList(),
    val adds: Map<String, List<Long>> = emptyMap(),
    val removes: Map<String, List<Long>> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = creates.isEmpty() && deletes.isEmpty() &&
            adds.values.all { it.isEmpty() } && removes.values.all { it.isEmpty() }

    fun created(name: String): CustomListOps =
        copy(creates = creates.filterNot { listKey(it) == listKey(name) } + name)

    /** Forgets everything pending for the list, and asks for it to go from the account. */
    fun deleted(name: String): CustomListOps {
        val key = listKey(name)
        return copy(
            creates = creates.filterNot { listKey(it) == key },
            deletes = deletes.filterNot { listKey(it) == key } + name,
            adds = adds - key,
            removes = removes - key,
        )
    }

    fun added(name: String, id: Long): CustomListOps {
        val key = listKey(name)
        return copy(
            adds = adds + (key to ((adds[key].orEmpty() - id) + id)),
            removes = (removes + (key to (removes[key].orEmpty() - id))).filterValues { it.isNotEmpty() },
        )
    }

    fun removed(name: String, id: Long): CustomListOps {
        val key = listKey(name)
        return copy(
            removes = removes + (key to ((removes[key].orEmpty() - id) + id)),
            adds = (adds + (key to (adds[key].orEmpty() - id))).filterValues { it.isNotEmpty() },
        )
    }

    /**
     * What is left once [pushed] has landed, keeping anything recorded while it was in flight and
     * anything in [kept]: the changes the push could not deliver, which stay for the next one.
     */
    fun minus(pushed: CustomListOps, kept: CustomListOps = CustomListOps()): CustomListOps {
        fun names(current: List<String>, done: List<String>, stay: List<String>) =
            current.filter { name -> !done.containsListName(name) || stay.containsListName(name) }
        fun ids(current: Map<String, List<Long>>, done: Map<String, List<Long>>, stay: Map<String, List<Long>>) =
            current.mapValues { (key, list) ->
                list.filter { id -> id !in done[key].orEmpty() || id in stay[key].orEmpty() }
            }.filterValues { it.isNotEmpty() }
        return CustomListOps(
            creates = names(creates, pushed.creates, kept.creates),
            deletes = names(deletes, pushed.deletes, kept.deletes),
            adds = ids(adds, pushed.adds, kept.adds),
            removes = ids(removes, pushed.removes, kept.removes),
        )
    }
}

/** One title's custom lists as the account holds them right now. */
data class RemoteMembership(
    /** AniList's list-entry id, or the MyAnimeList anime id: whatever the service deletes or patches by. */
    val entryId: Long,
    /** Every list or tag the title carries, including MAL tags this app has been asked to hide. */
    val lists: List<String>,
    /** AniList's "custom lists only" entry: the kind that should go once it is in no list. */
    val hidden: Boolean = false,
)

/** One title's change, worked out against the account's current state. */
sealed interface CustomListEntryChange {
    val anilistId: Long

    /** Replace the title's lists with [lists]; [existing] is null for a title the account lacks. */
    data class Write(
        override val anilistId: Long,
        val lists: List<String>,
        val existing: RemoteMembership?,
    ) : CustomListEntryChange

    /** A list-only entry left in no list at all: nothing is keeping it on the account. */
    data class Delete(override val anilistId: Long, val entryId: Long) : CustomListEntryChange
}

/**
 * The account's list names once [ops] are applied: its own order first, new names after. Only
 * AniList has names apart from entries; on MyAnimeList a list exists while some title carries it.
 */
fun plannedListNames(remoteNames: List<String>, ops: CustomListOps): List<String> {
    val kept = remoteNames.filterNot { ops.deletes.containsListName(it) }
    return kept + ops.creates.filterNot { kept.containsListName(it) }
}

/**
 * The per-title writes that bring the account into line with [ops], read against [remote], the
 * account as fetched at the start of the push. [canonicalNames] is the spelling each list is written
 * with. Titles for which [unsyncable] is true (catalogue-only ones) are skipped.
 */
fun plannedEntryChanges(
    remote: Map<Long, RemoteMembership>,
    ops: CustomListOps,
    canonicalNames: List<String>,
    unsyncable: (Long) -> Boolean = { false },
): List<CustomListEntryChange> {
    fun canonical(key: String): String =
        canonicalNames.firstOrNull { listKey(it) == key }
            ?: ops.creates.firstOrNull { listKey(it) == key }
            ?: key
    val deleted = ops.deletes.map(::listKey).toSet()
    val touched = linkedSetOf<Long>().apply {
        ops.adds.values.forEach(::addAll)
        ops.removes.values.forEach(::addAll)
        if (deleted.isNotEmpty()) {
            remote.forEach { (id, membership) ->
                if (membership.lists.any { listKey(it) in deleted }) add(id)
            }
        }
    }
    return touched.filterNot(unsyncable).mapNotNull { id ->
        val existing = remote[id]
        val current = existing?.lists.orEmpty()
        val removedKeys = ops.removes.filterValues { id in it }.keys
        val addedKeys = ops.adds.filterValues { id in it }.keys
        val next = current
            .filterNot { listKey(it) in deleted || listKey(it) in removedKeys }
            .let { kept -> kept + addedKeys.filterNot { key -> kept.any { listKey(it) == key } }.map(::canonical) }
        when {
            next.map(::listKey).toSet() == current.map(::listKey).toSet() -> null
            existing == null -> if (next.isEmpty()) null else CustomListEntryChange.Write(id, next, null)
            existing.hidden && next.isEmpty() -> CustomListEntryChange.Delete(id, existing.entryId)
            else -> CustomListEntryChange.Write(id, next, existing)
        }
    }
}

/**
 * This PC's lists after a refresh: the account's, with this PC's unpushed changes on top. The account
 * is the truth for everything [ops] does not mention. [remoteMembers] is keyed by [listKey]; local
 * order is kept and lists new to this PC follow it. [keepLocal] marks titles that can never reach the
 * account (catalogue-only ones), which stay in whatever list they were put in here.
 */
fun mergedCustomLists(
    local: List<CustomList>,
    remoteNames: List<String>,
    remoteMembers: Map<String, List<LocalSave>>,
    ops: CustomListOps,
    keepLocal: (Long) -> Boolean = { false },
    now: Long = System.currentTimeMillis(),
): List<CustomList> {
    val names = plannedListNames(remoteNames, ops)
    val ordered = local.map { it.name }.filter { names.containsListName(it) } +
        names.filterNot { name -> local.any { listKey(it.name) == listKey(name) } }
    return ordered.map { name ->
        val key = listKey(name)
        val mine = local.firstOrNull { listKey(it.name) == key }
        val localById = mine?.entries.orEmpty().associateBy { it.anime.id }
        val fromAccount = if (ops.deletes.containsListName(name)) emptyList() else remoteMembers[key].orEmpty()
        val removed = ops.removes[key].orEmpty().toSet()
        val added = ops.adds[key].orEmpty()
        val entries = buildList {
            fromAccount.forEach { remote ->
                if (remote.anime.id in removed) return@forEach
                // The account's title and cover are fresher; when it joined the list is ours, and a
                // title new to this PC dates from the account's last touch of the entry.
                val addedAt = localById[remote.anime.id]?.addedAt
                    ?: remote.addedAt.takeIf { it > 0 }
                    ?: now
                add(remote.copy(addedAt = addedAt))
            }
            added.forEach { id -> localById[id]?.let(::add) }
            mine?.entries.orEmpty().filter { keepLocal(it.anime.id) && it.anime.id !in removed }.forEach(::add)
        }.distinctBy { it.anime.id }.sortedByDescending { it.addedAt }
        CustomList(
            // The account's spelling wins once it has one, so every device shows the same name.
            name = remoteNames.firstOrNull { listKey(it) == key } ?: mine?.name ?: name,
            entries = entries,
            createdAt = mine?.createdAt ?: now,
        )
    }
}

/**
 * The names AniList says a title is in. `customLists(asArray: true)` gives `[{name, enabled}]`; the
 * older object form `{name: enabled}` is read too, as is null.
 */
fun parseAniListCustomLists(element: JsonElement?): List<String> = when (element) {
    is JsonArray -> element.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        name.takeIf { (obj["enabled"] as? JsonPrimitive)?.booleanOrNull == true }
    }
    is JsonObject -> element.entries.mapNotNull { (name, value) ->
        name.takeIf { (value as? JsonPrimitive)?.booleanOrNull == true }
    }
    JsonNull, null -> emptyList()
    else -> emptyList()
}

/**
 * Whether a MAL tag is shown: tags the viewer hid in Settings are dropped from the lists, as on
 * Android (`SettingsStore.hiddenMalTags`).
 */
fun shownMalTag(hidden: Set<String>, name: String): Boolean = hidden.none { listKey(it) == listKey(name) }

/** The hidden MAL tags as stored in settings (comma-separated; a tag never holds a comma). */
fun hiddenTagSet(stored: String): Set<String> =
    stored.split(',').map(String::trim).filter(String::isNotEmpty).toCollection(linkedSetOf())

fun hiddenTagNames(tags: Collection<String>): String = tags.joinToString(",")
