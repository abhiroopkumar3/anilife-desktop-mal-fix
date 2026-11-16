package me.enivzoy.anilife.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.enivzoy.anilife.core.model.AnimeCard
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class AniListConnection {
    RESTORING,
    SIGNED_OUT,
    CONNECTING,
    CONNECTED,
    OFFLINE,
    EXPIRED,
    ERROR,
}

data class AniListSyncStatus(
    val connection: AniListConnection = AniListConnection.RESTORING,
    val message: String? = null,
    val syncing: Boolean = false,
    val lastSyncedAt: Long? = null,
    val pendingProgress: Int = 0,
)

/** AniList's public native-client implicit flow never requires a client secret. */
object AniListSync {
    private const val AUTHORIZE = "https://anilist.co/api/v2/oauth/authorize"
    private val mutex = Mutex()
    private val api = AniListAccountApi()
    private val credentialStore = AniListCredentialStore()
    private val listStore = AniListListStore()
    private val mutableUser = MutableStateFlow<AniListUser?>(null)
    private val mutableEntries = MutableStateFlow<List<AniListListEntry>>(emptyList())
    private val mutableStatus = MutableStateFlow(AniListSyncStatus())
    private var credentials: AniListCredentials? = null

    val user = mutableUser.asStateFlow()
    val entries = mutableEntries.asStateFlow()
    val status = mutableStatus.asStateFlow()

    fun authorizationUrl(): String {
        val clientId = AniListConfig.clientId()
        require(clientId.isNotBlank()) { "AniList client ID is missing." }
        return "$AUTHORIZE?client_id=${URLEncoder.encode(clientId, StandardCharsets.UTF_8)}&response_type=token"
    }

    fun beginAuth() {
        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            "This system cannot open a browser for AniList sign-in."
        }
        Desktop.getDesktop().browse(URI.create(authorizationUrl()))
    }

    suspend fun restore() = mutex.withLock {
        if (mutableStatus.value.connection != AniListConnection.RESTORING) return@withLock
        try {
            val saved = credentialStore.read() ?: readLegacyCredentials()
            if (saved == null) {
                mutableStatus.value = AniListSyncStatus(connection = AniListConnection.SIGNED_OUT)
                return@withLock
            }
            if (saved.expiresAt <= nowSeconds()) {
                credentialStore.delete()
                legacyTokenFile().delete()
                mutableStatus.value = AniListSyncStatus(
                    connection = AniListConnection.EXPIRED,
                    message = "Your AniList session expired. Connect again to continue syncing.",
                )
                return@withLock
            }
            val account = try {
                api.viewer(saved.accessToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val cachedAccount = saved.user
                if (cachedAccount == null) throw failure
                credentials = saved
                mutableUser.value = cachedAccount
                mutableEntries.value = listStore.read(cachedAccount.id)
                mutableStatus.value = AniListSyncStatus(
                    connection = AniListConnection.OFFLINE,
                    message = safeError(failure),
                    pendingProgress = listStore.pendingProgress(cachedAccount.id).size,
                )
                return@withLock
            }
            connectSession(saved.copy(user = account))
            refreshInternal(throwOnFailure = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mutableStatus.value = mutableStatus.value.copy(
                connection = if (credentials != null) AniListConnection.OFFLINE else AniListConnection.ERROR,
                message = safeError(failure),
                syncing = false,
            )
        }
    }

    suspend fun connect(pastedAccessToken: String) = mutex.withLock {
        mutableStatus.value = AniListSyncStatus(connection = AniListConnection.CONNECTING, syncing = true)
        try {
            val token = extractAniListAccessToken(pastedAccessToken)
            val account = api.viewer(token.accessToken)
            val saved = token.copy(user = account)
            credentialStore.write(saved)
            legacyTokenFile().delete()
            connectSession(saved)
            refreshInternal(throwOnFailure = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            credentials = null
            mutableUser.value = null
            mutableStatus.value = AniListSyncStatus(
                connection = AniListConnection.ERROR,
                message = safeError(failure),
                syncing = false,
            )
            throw failure
        }
    }

    suspend fun disconnect() = mutex.withLock {
        mutableUser.value?.id?.let(listStore::clear)
        credentialStore.delete()
        legacyTokenFile().delete()
        credentials = null
        mutableUser.value = null
        mutableEntries.value = emptyList()
        mutableStatus.value = AniListSyncStatus(connection = AniListConnection.SIGNED_OUT)
    }

    suspend fun refreshList() = mutex.withLock {
        requireConnected()
        refreshInternal()
    }

    suspend fun addToList(anime: AnimeCard, status: AniListListStatus = AniListListStatus.PLANNING) =
        mutex.withLock {
            val (account, auth) = requireConnected()
            val remote = api.entry(auth.accessToken, account.id, anime.id)
            if (remote != null) {
                replaceEntry(remote)
                return@withLock remote
            }
            saveRemoteEntry(
                account = account,
                auth = auth,
                anime = anime,
                entryId = null,
                status = status,
                progress = 0,
                score = 0.0,
            )
        }

    /** Mirror the Save button: see [aniListSavedPush]. Never changes an entry that has been moved on. */
    suspend fun syncSaved(anime: AnimeCard, saved: Boolean) = mutex.withLock {
        val (account, auth) = requireConnected()
        val current = api.entry(auth.accessToken, account.id, anime.id)
        when (aniListSavedPush(saved, current?.status?.apiValue)) {
            SavedPush.ADD_PLANNING -> saveRemoteEntry(account, auth, anime, null, AniListListStatus.PLANNING, 0, 0.0)
            SavedPush.DELETE -> {
                if (current != null && api.delete(auth.accessToken, current.entryId)) replaceEntry(null, anime.id)
                else error("AniList did not confirm the list entry deletion.")
            }
            else -> Unit
        }
    }

    suspend fun updateEntry(
        entry: AniListListEntry,
        status: AniListListStatus,
        progress: Int,
        score: Double,
    ) = mutex.withLock {
        val (account, auth) = requireConnected()
        require(progress >= 0) { "Episode progress cannot be negative." }
        require(score.isFinite() && score in 0.0..account.scoreFormat.maximum) {
            "Score must be between 0 and ${account.scoreFormat.maximum} for your AniList score format."
        }
        val latest = api.entry(auth.accessToken, account.id, entry.anime.id)
        if (latest?.updatedAt != entry.updatedAt) {
            latest?.let(::replaceEntry)
            error("This AniList entry changed remotely. The latest copy was refreshed; review it and try again.")
        }
        saveRemoteEntry(
            account = account,
            auth = auth,
            anime = entry.anime,
            entryId = latest.entryId,
            status = status,
            progress = progress,
            score = score,
        )
    }

    suspend fun deleteEntry(entry: AniListListEntry) = mutex.withLock {
        val (account, auth) = requireConnected()
        val latest = api.entry(auth.accessToken, account.id, entry.anime.id)
        if (latest == null) {
            replaceEntry(null, entry.anime.id)
            return@withLock
        }
        if (latest.updatedAt != entry.updatedAt) {
            replaceEntry(latest)
            error("This AniList entry changed remotely. The latest copy was refreshed; review it before removing.")
        }
        if (!api.delete(auth.accessToken, latest.entryId)) {
            error("AniList did not confirm the list entry deletion.")
        }
        replaceEntry(null, entry.anime.id)
    }

    suspend fun syncCompletedEpisode(anime: AnimeCard, episode: Double) = mutex.withLock {
        val account = mutableUser.value ?: return@withLock
        val auth = credentials ?: return@withLock
        val watched = episode.takeIf { it.isFinite() && it > 0 && it % 1.0 == 0.0 }?.toInt()
            ?: return@withLock
        val boundedProgress = anime.episodes?.takeIf { it > 0 }?.let(watched::coerceAtMost) ?: watched
        listStore.enqueueProgress(account.id, anime, boundedProgress)
        refreshPendingCount(account.id)
        try {
            syncPendingProgress(account, auth)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mutableStatus.value = mutableStatus.value.copy(message = safeError(failure))
        }
    }

    private suspend fun refreshInternal(throwOnFailure: Boolean = true) {
        val (account, auth) = requireConnected()
        mutableStatus.value = mutableStatus.value.copy(syncing = true, message = null)
        try {
            val fetched = api.list(auth.accessToken, account.id)
            mutableEntries.value = fetched
            listStore.write(account.id, fetched)
            mutableStatus.value = mutableStatus.value.copy(
                connection = AniListConnection.CONNECTED,
                syncing = false,
                lastSyncedAt = System.currentTimeMillis(),
                message = null,
            )
            syncPendingProgress(account, auth)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mutableStatus.value = mutableStatus.value.copy(
                connection = AniListConnection.CONNECTED,
                syncing = false,
                message = safeError(failure),
            )
            if (throwOnFailure) throw failure
        }
    }

    private suspend fun syncPendingProgress(account: AniListUser, auth: AniListCredentials) {
        val pending = listStore.pendingProgress(account.id)
        for ((mediaId, queued) in pending) {
            val current = api.entry(auth.accessToken, account.id, mediaId)
            val requested = queued.anime.episodes?.takeIf { it > 0 }
                ?.let(queued.episode::coerceAtMost) ?: queued.episode
            if (current != null && current.progress >= requested) {
                listStore.removePendingProgress(account.id, mediaId)
                continue
            }
            val nextStatus = when (current?.status) {
                null, AniListListStatus.PLANNING -> AniListListStatus.CURRENT
                else -> current.status
            }
            val saved = api.save(
                token = auth.accessToken,
                entryId = current?.entryId,
                mediaId = mediaId,
                status = nextStatus,
                progress = maxOf(current?.progress ?: 0, requested),
                score = current?.score ?: 0.0,
            )
            replaceEntry(saved)
            listStore.removePendingProgress(account.id, mediaId)
        }
        refreshPendingCount(account.id)
        mutableStatus.value = mutableStatus.value.copy(message = null)
    }

    private suspend fun saveRemoteEntry(
        account: AniListUser,
        auth: AniListCredentials,
        anime: AnimeCard,
        entryId: Long?,
        status: AniListListStatus,
        progress: Int,
        score: Double,
    ): AniListListEntry {
        val saved = api.save(auth.accessToken, entryId, anime.id, status, progress, score)
        replaceEntry(saved)
        return saved
    }

    private fun connectSession(auth: AniListCredentials) {
        val account = auth.user ?: error("AniList returned no user profile.")
        credentials = auth
        mutableUser.value = account
        mutableEntries.value = listStore.read(account.id)
        mutableStatus.value = AniListSyncStatus(
            connection = AniListConnection.CONNECTED,
            lastSyncedAt = mutableStatus.value.lastSyncedAt,
            pendingProgress = listStore.pendingProgress(account.id).size,
        )
    }

    private fun replaceEntry(entry: AniListListEntry?, mediaId: Long? = entry?.anime?.id) {
        val current = mutableEntries.value.filterNot { it.anime.id == mediaId }
        mutableEntries.value = (if (entry == null) current else current + entry)
            .sortedBy { it.anime.title.lowercase() }
        mutableUser.value?.id?.let { listStore.write(it, mutableEntries.value) }
    }

    private fun refreshPendingCount(userId: Long) {
        mutableStatus.value = mutableStatus.value.copy(
            pendingProgress = listStore.pendingProgress(userId).size,
        )
    }

    private fun requireConnected(): Pair<AniListUser, AniListCredentials> {
        val account = mutableUser.value ?: error("Connect an AniList account first.")
        val auth = credentials ?: error("Your AniList session has expired. Connect again to continue.")
        if (auth.expiresAt <= nowSeconds()) {
            mutableStatus.value = mutableStatus.value.copy(
                connection = AniListConnection.EXPIRED,
                message = "Your AniList session expired. Connect again to continue syncing.",
            )
            error("Your AniList session expired. Connect again to continue.")
        }
        return account to auth
    }

    private suspend fun readLegacyCredentials(): AniListCredentials? {
        val legacy = legacyTokenFile()
        if (!legacy.isFile) return null
        val parsed = extractAniListAccessToken(legacy.readText())
        val account = api.viewer(parsed.accessToken)
        val migrated = parsed.copy(user = account)
        credentialStore.write(migrated)
        if (!legacy.delete()) error("Couldn't remove the old unprotected AniList token file.")
        return migrated
    }

    private fun safeError(failure: Throwable): String =
        failure.message?.takeIf(String::isNotBlank)?.take(300) ?: "AniList request failed. Try again."

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    private fun legacyTokenFile(): File {
        val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(::File)
            ?: File(System.getProperty("user.home"), ".local/share")
        return File(base, "AniLife/anilist-token.txt")
    }
}
