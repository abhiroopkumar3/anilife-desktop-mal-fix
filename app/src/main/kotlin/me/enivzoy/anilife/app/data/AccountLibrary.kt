package me.enivzoy.anilife.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Properties

/**
 * Applies the signed-in account to this PC and pushes this PC's saves back, the way Android's
 * `LibraryStore` does (`hydrateRemoteLibrary`, `syncSavedToRemote`). The decisions live in
 * [hydrateFromAccount]; this file only reads, writes and calls the services.
 */
object AccountLibrary {
    private val mutex = Mutex()
    private val snapshots = StatusSnapshotStore()

    @Volatile var repository: AniListRepository? = null
    @Volatile var syncSavedEnabled: () -> Boolean = { true }

    /** The signed-in account's list in the shared model, or null when nothing is connected or it cannot be read. */
    suspend fun remoteEntries(): List<ListEntry>? {
        val repo = repository ?: return null
        return when (serviceToKeep(AniListSync.user.value != null, MyAnimeListSync.user.value != null, null)) {
            ListService.ANILIST -> {
                AniListSync.refreshList()
                aniListEntriesToEntries(AniListSync.entries.value)
            }
            ListService.MAL -> {
                val rows = MyAnimeListSync.rows()
                val cards = repo.cardsByMalIds(rows.map { it.malId }).mapNotNull { c -> c.malId?.let { it to c } }.toMap()
                malRowsToEntries(rows, cards)
            }
            null -> null
        }
    }

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
                runCatching {
                    when {
                        AniListSync.user.value != null -> AniListSync.addToList(anime, AniListListStatus.PLANNING)
                        MyAnimeListSync.user.value != null -> anime.malId?.let { MyAnimeListSync.addPlanned(it) }
                    }
                }
            }
    }

    /** A new account was adopted: forget the old snapshot, read the new list, then push saves up. */
    suspend fun adopted() {
        accountKey()?.let(snapshots::clear)
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
