package me.enivzoy.anilife.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.Properties

/**
 * Applies the signed-in account to this PC and pushes this PC's saves back, the way Android's
 * `LibraryStore` does (`hydrateRemoteLibrary`, `syncSavedToRemote`). The decisions live in
 * [hydrateFromAccount]; this file only reads, writes and calls the services.
 */
object AccountLibrary {
    private const val MAX_RETRIES_PER_RUN = 40
    private val mutex = Mutex()
    private val snapshots = StatusSnapshotStore()
    private val idMap = MalIdMap()
    private val pending = PendingSavedStore()

    private val mutableMalEntries = MutableStateFlow<List<ListEntry>>(emptyList())

    /** The MyAnimeList account's list, as last read. Empty unless MyAnimeList is the active account. */
    val malEntries = mutableMalEntries.asStateFlow()

    /** AniList's own custom list names from the last read, or null when they could not be read. */
    @Volatile private var lastAniListListNames: List<String>? = null

    @Volatile var repository: AniListRepository? = null
    @Volatile var syncSavedEnabled: () -> Boolean = { true }

    /** The signed-in account's list in the shared model, or null when nothing is connected or it cannot be read. */
    suspend fun remoteEntries(): List<ListEntry>? {
        val repo = repository ?: return null
        return when (serviceToKeep(AniListSync.user.value != null, MyAnimeListSync.user.value != null, null)) {
            ListService.ANILIST -> {
                mutableMalEntries.value = emptyList()
                AniListSync.refreshList()
                lastAniListListNames = runCatching { AniListSync.customListNames() }.getOrNull()
                aniListEntriesToEntries(AniListSync.entries.value)
            }
            ListService.MAL -> {
                val rows = MyAnimeListSync.rows()
                val malIds = rows.map { it.malId }.distinct()
                val gate = Semaphore(10)
                val resolved = kotlinx.coroutines.coroutineScope {
                    malIds.map { id ->
                        async(kotlinx.coroutines.Dispatchers.IO) { gate.withPermit { id to idMap.resolve(id) } }
                    }.awaitAll().toMap()
                }
                val mapped = resolved.values.filterNotNull().distinct()
                val mappedCards = if (mapped.isEmpty()) emptyList() else repo.cardsByIds(mapped)
                val unmapped = resolved.filterValues { it == null }.keys.toList()
                val fallback = if (unmapped.isEmpty()) emptyList() else repo.cardsByMalIds(unmapped)
                malRowsToEntries(rows, joinMalCards(malIds, resolved, mappedCards, fallback))
                    .also { mutableMalEntries.value = it }
            }
            null -> {
                mutableMalEntries.value = emptyList()
                null
            }
        }
    }

    /** The service whose list the menus write to, or null when nobody is signed in. */
    fun activeService(): ListService? =
        serviceToKeep(AniListSync.user.value != null, MyAnimeListSync.user.value != null, null)

    private fun accountKey(): String? = when {
        AniListSync.user.value != null -> "anilist:${AniListSync.user.value!!.id}"
        MyAnimeListSync.user.value != null -> "mal:${MyAnimeListSync.user.value!!.id}"
        else -> null
    }

    /** Pull the account into this PC. A failed read changes nothing. */
    suspend fun refresh() = mutex.withLock {
        val repo = repository ?: return@withLock
        val key = accountKey() ?: return@withLock
        val remote = runCatching { remoteEntries() }.getOrNull() ?: return@withLock
        val syncSaved = syncSavedEnabled()
        when (activeService()) {
            ListService.ANILIST -> lastAniListListNames?.let {
                CustomListStoreInstance.hydrateFromAccount(ListService.ANILIST, remote, it)
            }
            ListService.MAL -> CustomListStoreInstance.hydrateFromAccount(ListService.MAL, remote, null)
            null -> Unit
        }
        val result = hydrateFromAccount(
            remote = remote,
            saves = repo.library.saves.value,
            continueRows = ContinueStoreInstance.entries.value,
            previousSnapshot = snapshots.read(key),
        )
        if (syncSaved && result.saves != repo.library.saves.value) repo.library.replaceAll(result.saves)
        result.continueRemovals.forEach(ContinueStoreInstance::remove)
        result.continueUpserts.forEach(ContinueStoreInstance::replace)
        snapshots.write(key, result.snapshot)
    }

    private suspend fun pushOne(anime: me.enivzoy.anilife.core.model.AnimeCard, saved: Boolean) {
        when {
            AniListSync.user.value != null -> AniListSync.syncSaved(anime, saved)
            MyAnimeListSync.user.value != null -> anime.malId?.let { MyAnimeListSync.syncSaved(it, saved) }
        }
    }

    /** A save or unsave happened here. Catalogue-native (negative) ids never go to a list service. */
    suspend fun saveToggled(anime: me.enivzoy.anilife.core.model.AnimeCard, saved: Boolean) {
        if (anime.id <= 0 || !syncSavedEnabled() || accountKey() == null) return
        mutex.withLock {
            runCatching { pushOne(anime, saved) }
                .onSuccess { pending.settle(setOf(anime.id)) }
                .onFailure { pending.add(setOf(anime.id)) }
        }
    }

    /** Re-send saves whose last push failed, each as what the PC says now. Cheap when nothing is owed. */
    suspend fun retryPending() = mutex.withLock {
        val repo = repository ?: return@withLock
        if (!syncSavedEnabled() || accountKey() == null) return@withLock
        val batch = pending.ids().take(MAX_RETRIES_PER_RUN)
        if (batch.isEmpty()) return@withLock
        val saves = repo.library.saves.value.associateBy { it.anime.id }
        val settled = mutableSetOf<Long>()
        batch.forEach { id ->
            val card = saves[id]?.anime ?: pending.card(id)
            if (card == null) { settled += id; return@forEach }
            runCatching { pushOne(card, saves.containsKey(id)) }.onSuccess { settled += id }
        }
        pending.settle(settled)
    }

    /** Push saved titles the account lacks, as Planning. Does nothing while the setting is off. */
    suspend fun pushSaved() = mutex.withLock {
        val repo = repository ?: return@withLock
        if (!syncSavedEnabled()) return@withLock
        val key = accountKey() ?: return@withLock
        val known = snapshots.read(key).keys
        repo.library.saves.value
            .map { it.anime }
            .filter { it.id > 0 && it.id !in known }
            .forEach { anime ->
                runCatching { pushOne(anime, true) }.onFailure { pending.add(setOf(anime.id), anime) }
            }
    }

    /** A new account was adopted: forget the old snapshot, read the new list, then push saves up. */
    suspend fun adopted() {
        accountKey()?.let(snapshots::clear)
        pending.clear()
        refresh()
        pushSaved()
        refresh()
    }
}

/** The statuses last seen on each account, so the next refresh can tell a move from an absence. */
class StatusSnapshotStore(private val file: File = File(directory(), "account-snapshot.properties")) {
    fun read(key: String): Map<Long, String> = synchronized(this) {
        val p = load()
        p.stringPropertyNames().filter { it.startsWith("$key|") }.mapNotNull { name ->
            val id = name.substringAfter('|').toLongOrNull() ?: return@mapNotNull null
            id to p.getProperty(name)
        }.toMap()
    }

    fun write(key: String, statuses: Map<Long, String>) = synchronized(this) {
        val p = load()
        p.stringPropertyNames().filter { it.startsWith("$key|") }.forEach(p::remove)
        statuses.forEach { (id, status) -> p.setProperty("$key|$id", status) }
        save(p)
    }

    fun clear(key: String) = write(key, emptyMap())

    private fun load() = Properties().apply { if (file.isFile) file.inputStream().use(::load) }
    private fun save(p: Properties) {
        file.parentFile?.mkdirs()
        file.outputStream().use { p.store(it, "AniLife account snapshot") }
    }

    private companion object {
        fun directory(): File {
            val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(::File)
                ?: File(System.getProperty("user.home"), ".local/share")
            return File(base, "AniLife")
        }
    }
}

/** Saves the account has not confirmed yet, kept across restarts so they are retried on launch. */
class PendingSavedStore(private val file: File = File(dir(), "pending-saved-sync.properties")) {
    private val cards = mutableMapOf<Long, me.enivzoy.anilife.core.model.AnimeCard>()

    @Synchronized fun ids(): List<Long> = load().stringPropertyNames().mapNotNull { it.toLongOrNull() }.sorted()
    @Synchronized fun card(id: Long) = cards[id]
    @Synchronized fun add(ids: Set<Long>, card: me.enivzoy.anilife.core.model.AnimeCard? = null) {
        if (card != null) cards[card.id] = card
        val p = load(); ids.forEach { p.setProperty(it.toString(), "1") }; save(p)
    }
    @Synchronized fun settle(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val p = load(); ids.forEach { p.remove(it.toString()); cards.remove(it) }; save(p)
    }
    @Synchronized fun clear() { cards.clear(); file.delete() }

    private fun load() = Properties().apply { if (file.isFile) file.inputStream().use(::load) }
    private fun save(p: Properties) {
        file.parentFile?.mkdirs()
        file.outputStream().use { p.store(it, "AniLife pending saved sync") }
    }

    private companion object {
        fun dir(): File {
            val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(::File)
                ?: File(System.getProperty("user.home"), ".local/share")
            return File(base, "AniLife")
        }
    }
}
