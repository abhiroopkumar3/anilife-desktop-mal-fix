package me.enivzoy.anilife.app.data

import java.io.File
import java.util.Properties

/** The two list services an account can come from. Only one is active at a time, as on Android. */
enum class ListService { ANILIST, MAL }

/** Which service signed in most recently, and when (epoch milliseconds). */
data class ActiveAccount(val service: ListService, val adoptedAt: Long)

/**
 * Which service stays signed in when [aniListConnected] and [malConnected] are both true, which
 * happens for people who connected both before one-account parity. The most recent sign-in wins;
 * with no record of when, AniList stays because it is the service the rest of the app syncs to.
 * Returns null when neither is connected.
 */
fun serviceToKeep(aniListConnected: Boolean, malConnected: Boolean, last: ActiveAccount?): ListService? = when {
    aniListConnected && malConnected -> last?.service ?: ListService.ANILIST
    aniListConnected -> ListService.ANILIST
    malConnected -> ListService.MAL
    else -> null
}

class ActiveAccountStore(private val file: File = File(defaultDirectory(), "active-account.properties")) {
    fun read(): ActiveAccount? = runCatching {
        if (!file.isFile) return null
        val p = Properties().apply { file.inputStream().use(::load) }
        val service = p.getProperty("service")?.let { name -> ListService.entries.firstOrNull { it.name == name } }
            ?: return null
        ActiveAccount(service, p.getProperty("adoptedAt")?.toLongOrNull() ?: 0L)
    }.getOrNull()

    fun write(account: ActiveAccount) {
        file.parentFile?.mkdirs()
        val p = Properties()
        p.setProperty("service", account.service.name)
        p.setProperty("adoptedAt", account.adoptedAt.toString())
        file.outputStream().use { p.store(it, "AniLife active list account") }
    }

    fun clear() {
        file.delete()
    }

    private companion object {
        fun defaultDirectory(): File {
            val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(::File)
                ?: File(System.getProperty("user.home"), ".local/share")
            return File(base, "AniLife")
        }
    }
}

/**
 * Android's `AccountAdoption`: the new account is adopted first, and the other service is logged out
 * only once that sign-in has succeeded, so a failed sign-in never costs the account already in use.
 */
object AccountAdoption {
    private val store = ActiveAccountStore()

    suspend fun connectAniList(pastedAccessToken: String) {
        AniListSync.connect(pastedAccessToken)
        store.write(ActiveAccount(ListService.ANILIST, System.currentTimeMillis()))
        if (MyAnimeListSync.user.value != null) MyAnimeListSync.disconnect()
    }

    suspend fun connectMyAnimeList() {
        MyAnimeListSync.connect()
        store.write(ActiveAccount(ListService.MAL, System.currentTimeMillis()))
        if (AniListSync.user.value != null) AniListSync.disconnect()
    }

    /** Run after both services have restored, to settle anyone who has both connected. */
    suspend fun settleAfterRestore() {
        val keep = serviceToKeep(
            aniListConnected = AniListSync.user.value != null,
            malConnected = MyAnimeListSync.user.value != null,
            last = store.read(),
        )
        when (keep) {
            ListService.ANILIST -> if (MyAnimeListSync.user.value != null) MyAnimeListSync.disconnect()
            ListService.MAL -> if (AniListSync.user.value != null) AniListSync.disconnect()
            null -> store.clear()
        }
    }
}
