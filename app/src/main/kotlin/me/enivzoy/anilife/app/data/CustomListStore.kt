package me.enivzoy.anilife.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Properties

/** Where a push of pending custom-list changes goes. Returns what could not be delivered and must stay pending. */
interface CustomListRemote {
    suspend fun push(service: ListService, sending: CustomListOps, lists: List<CustomList>): CustomListOps
}

/** A title no list service knows (catalogue-only); it lives in a list here only. */
fun isCatalogueNativeId(id: Long): Boolean = id <= 0

/**
 * The viewer's custom lists, kept on this PC and in step with the signed-in account (Android's
 * `CustomListStore`). Every change lands here first and is recorded as a pending [CustomListOps]; a
 * push carries the pending changes to the account and a refresh brings back what the account holds,
 * with anything still pending laid on top. Signed out, the changes wait until an account is connected.
 */
class CustomListStore(
    private val file: File = defaultFile(),
    private val activeService: () -> ListService? = { null },
    private val hiddenMalTags: () -> Set<String> = { emptySet() },
    private val revealMalTag: (String) -> Unit = {},
    private val remote: CustomListRemote? = null,
    /** Runs after a push that changed the account, so the next refresh shows what it now holds. */
    private val afterDelivery: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val pushMutex = Mutex()
    private val mutableLists = MutableStateFlow<List<CustomList>>(emptyList())
    val lists = mutableLists.asStateFlow()

    /** Every tag the signed-in MyAnimeList account uses, hidden ones included. Empty for AniList and signed out. */
    private val mutableMalTags = MutableStateFlow<List<String>>(emptyList())
    val malTags = mutableMalTags.asStateFlow()

    @Volatile private var ops = CustomListOps()

    /** What was left pending when the last push succeeded: pending by design, not by failure. */
    private var settledOps: CustomListOps? = null

    /** The pending changes, for tests and the diagnostics line. */
    val pending: CustomListOps get() = ops

    init {
        readFile()
    }

    fun list(name: String): CustomList? = mutableLists.value.firstOrNull { listKey(it.name) == listKey(name) }

    fun listsContaining(anilistId: Long): List<CustomList> =
        mutableLists.value.filter { list -> list.entries.any { it.anime.id == anilistId } }

    /** Make a new, empty list. Returns the name it was stored under, or null when the name is unusable or taken. */
    @Synchronized
    fun create(rawName: String): String? {
        val name = normalizedListName(rawName) ?: return null
        if (list(name) != null) return null
        // A list named after a MAL tag the viewer hid would vanish on the next refresh; making it is
        // asking to see it, so the tag comes out of hiding.
        hiddenMalTags().firstOrNull { listKey(it) == listKey(name) }?.let(revealMalTag)
        update(
            lists = listOf(CustomList(name, createdAt = clock())) + mutableLists.value,
            ops = ops.created(name),
        )
        return name
    }

    /** Rename [from] to [rawTo], keeping its titles. Returns the new name, or null when refused. */
    @Synchronized
    fun rename(from: String, rawTo: String): String? {
        val current = list(from) ?: return null
        val to = normalizedListName(rawTo) ?: return null
        if (to == current.name) return to
        if (listKey(to) != listKey(current.name) && list(to) != null) return null
        var next = ops.deleted(current.name).created(to)
        current.entries.forEach { entry ->
            if (!isCatalogueNativeId(entry.anime.id)) next = next.added(to, entry.anime.id)
        }
        update(
            lists = mutableLists.value.map { if (it === current) it.copy(name = to) else it },
            ops = next,
        )
        return to
    }

    @Synchronized
    fun delete(name: String) {
        val current = list(name) ?: return
        update(lists = mutableLists.value - current, ops = ops.deleted(current.name))
    }

    /** Put [anime] into, or take it out of, the list called [name]. */
    @Synchronized
    fun setMember(name: String, anime: me.enivzoy.anilife.core.model.AnimeCard, member: Boolean) {
        val current = list(name) ?: return
        val present = current.entries.any { it.anime.id == anime.id }
        if (present == member) return
        val entries = if (member) {
            listOf(LocalSave(anime, clock())) + current.entries
        } else {
            current.entries.filterNot { it.anime.id == anime.id }
        }
        var next = when {
            // A catalogue-only title has no id either service knows, so it lives in the list here only.
            isCatalogueNativeId(anime.id) -> ops
            member -> ops.added(current.name, anime.id)
            else -> ops.removed(current.name, anime.id)
        }
        // MyAnimeList has no empty lists: a list is a tag, and a tag nobody carries is gone. Emptying
        // one here would read as a deletion on the next refresh; held as pending it stays until the
        // next title is added to it, exactly as a list made here and not yet filled does.
        if (!member && entries.isEmpty() && activeService() == ListService.MAL) {
            next = next.created(current.name)
        }
        update(
            lists = mutableLists.value.map { if (it === current) it.copy(entries = entries) else it },
            ops = next,
        )
    }

    /** A new list made from inside a title's menu, which also puts that title in it. Null when the name is refused. */
    fun createWith(rawName: String, anime: me.enivzoy.anilife.core.model.AnimeCard): String? {
        val created = create(rawName) ?: return null
        setMember(created, anime, true)
        return created
    }

    /**
     * Take the account's lists after a refresh. [entries] is the account's whole collection (hidden
     * entries included); [aniListNames] is AniList's own list of names, null on MyAnimeList, where the
     * tags are the lists.
     */
    @Synchronized
    fun hydrateFromAccount(service: ListService, entries: List<ListEntry>, aniListNames: List<String>?) {
        val hidden = if (service == ListService.MAL) hiddenMalTags() else emptySet()
        mutableMalTags.value = if (service == ListService.MAL) {
            entries.flatMap { it.customLists }.distinctBy(::listKey).sortedBy { it.lowercase() }
        } else {
            emptyList()
        }
        fun shown(name: String) = shownMalTag(hidden, name)
        val members = LinkedHashMap<String, MutableList<LocalSave>>()
        val tagOrder = mutableListOf<String>()
        entries.forEach { entry ->
            entry.customLists.filter(::shown).forEach { name ->
                if (!tagOrder.containsListName(name)) tagOrder += name
                members.getOrPut(listKey(name)) { mutableListOf() } += LocalSave(entry.anime, entry.updatedAt * 1_000L)
            }
        }
        val names = (aniListNames ?: tagOrder.sortedBy { it.lowercase() }).filter(::shown)
        val merged = mergedCustomLists(
            local = mutableLists.value,
            remoteNames = names,
            remoteMembers = members,
            ops = ops,
            keepLocal = ::isCatalogueNativeId,
            now = clock(),
        )
        if (merged != mutableLists.value) {
            mutableLists.value = merged
            writeFile()
        }
        // Only what the last push has not already tried. What it kept on purpose (an empty MAL list,
        // a title MAL has no id for) would otherwise be re-sent by every refresh, and every push ends
        // in a refresh: a loop that never settles.
        if (!ops.isEmpty && ops != settledOps) pushPending()
    }

    /**
     * The account is being left, by a sign-out or a switch to the other service. Everything on this PC
     * becomes pending again, so whichever account comes next receives these lists rather than the PC
     * quietly keeping a copy of the old account's.
     */
    @Synchronized
    fun onAccountLeft() {
        mutableMalTags.value = emptyList()
        var next = CustomListOps()
        mutableLists.value.forEach { list ->
            next = next.created(list.name)
            list.entries.forEach { entry ->
                if (!isCatalogueNativeId(entry.anime.id)) next = next.added(list.name, entry.anime.id)
            }
        }
        ops = next
        settledOps = null
        writeFile()
    }

    /** Merge lists from a backup: same-named lists pool their titles, new ones are added. Returns how many lists are new. */
    @Synchronized
    fun importLists(imported: List<CustomList>): Int {
        var lists = mutableLists.value
        var next = ops
        var added = 0
        imported.forEach { incoming ->
            val name = normalizedListName(incoming.name) ?: return@forEach
            val existing = lists.firstOrNull { listKey(it.name) == listKey(name) }
            val fresh = incoming.entries.filter { entry ->
                existing?.entries?.none { it.anime.id == entry.anime.id } ?: true
            }
            if (existing == null) {
                lists = lists + CustomList(name, fresh, incoming.createdAt.takeIf { it > 0 } ?: clock())
                next = next.created(name)
                added++
            } else {
                lists = lists.map { if (it === existing) it.copy(entries = fresh + it.entries) else it }
            }
            fresh.forEach { if (!isCatalogueNativeId(it.anime.id)) next = next.added(name, it.anime.id) }
        }
        update(lists, next)
        return added
    }

    /** Carry pending changes to the signed-in account. Safe to call at any time; it does nothing idle. */
    fun pushPending() {
        val service = activeService() ?: return
        val target = remote ?: return
        if (ops.isEmpty) return
        scope.launch { pushNow(service, target) }
    }

    /** The push itself, awaitable. Returns true when it delivered (or kept on purpose) everything it sent. */
    suspend fun pushNow(service: ListService, target: CustomListRemote): Boolean = pushMutex.withLock {
        val sending = ops
        if (sending.isEmpty) return@withLock true
        runCatching { target.push(service, sending, mutableLists.value) }
            .onSuccess { kept ->
                synchronized(this) {
                    ops = ops.minus(sending, kept)
                    settledOps = ops
                    writeFile()
                }
                if (sending != kept) afterDelivery()
            }
            .isSuccess
    }

    private fun update(lists: List<CustomList>, ops: CustomListOps) {
        mutableLists.value = lists
        this.ops = ops
        writeFile()
        pushPending()
    }

    // --- persistence: one properties file holding the lists, their cards, and the pending ops ---

    private fun readFile() {
        if (!file.isFile) return
        val p = Properties().apply { file.inputStream().use(::load) }
        fun int(key: String) = p.getProperty(key)?.toIntOrNull() ?: 0
        mutableLists.value = (0 until int("list.count")).mapNotNull { i ->
            val name = p.getProperty("list.$i.name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val entries = (0 until int("list.$i.count")).mapNotNull { j ->
                val prefix = "list.$i.e.$j."
                val id = p.getProperty("${prefix}id")?.toLongOrNull() ?: return@mapNotNull null
                val card = p.readCard(prefix, id) ?: return@mapNotNull null
                LocalSave(card, p.getProperty("${prefix}addedAt")?.toLongOrNull() ?: 0L)
            }
            CustomList(name, entries, p.getProperty("list.$i.createdAt")?.toLongOrNull() ?: 0L)
        }
        fun names(prefix: String) = (0 until int("$prefix.count")).mapNotNull { p.getProperty("$prefix.$it")?.takeIf(String::isNotBlank) }
        fun ids(prefix: String) = (0 until int("$prefix.count")).mapNotNull { i ->
            val key = p.getProperty("$prefix.$i.key") ?: return@mapNotNull null
            key to p.getProperty("$prefix.$i.ids").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        }.toMap().filterValues { it.isNotEmpty() }
        ops = CustomListOps(names("ops.creates"), names("ops.deletes"), ids("ops.adds"), ids("ops.removes"))
    }

    @Synchronized
    private fun writeFile() {
        val p = Properties()
        p.setProperty("list.count", mutableLists.value.size.toString())
        mutableLists.value.forEachIndexed { i, list ->
            p.setProperty("list.$i.name", list.name)
            p.setProperty("list.$i.createdAt", list.createdAt.toString())
            p.setProperty("list.$i.count", list.entries.size.toString())
            list.entries.forEachIndexed { j, save ->
                val prefix = "list.$i.e.$j."
                p.setProperty("${prefix}id", save.anime.id.toString())
                p.setProperty("${prefix}addedAt", save.addedAt.toString())
                p.putCard(prefix, save.anime)
            }
        }
        fun putNames(prefix: String, names: List<String>) {
            p.setProperty("$prefix.count", names.size.toString())
            names.forEachIndexed { i, n -> p.setProperty("$prefix.$i", n) }
        }
        fun putIds(prefix: String, map: Map<String, List<Long>>) {
            val entries = map.entries.filter { it.value.isNotEmpty() }
            p.setProperty("$prefix.count", entries.size.toString())
            entries.forEachIndexed { i, (key, ids) ->
                p.setProperty("$prefix.$i.key", key)
                p.setProperty("$prefix.$i.ids", ids.joinToString(","))
            }
        }
        putNames("ops.creates", ops.creates)
        putNames("ops.deletes", ops.deletes)
        putIds("ops.adds", ops.adds)
        putIds("ops.removes", ops.removes)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().use { p.store(it, "AniLife custom lists") }
        java.nio.file.Files.move(
            temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    }

    companion object {
        fun defaultFile(): File {
            val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(::File)
                ?: File(System.getProperty("user.home"), ".local/share")
            return File(base, "AniLife/custom-lists.properties")
        }
    }
}
