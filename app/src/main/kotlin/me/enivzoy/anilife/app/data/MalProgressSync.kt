package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.AnimeCard
import java.io.File
import java.util.Properties

/**
 * Pushes a watched episode to MyAnimeList the way Android's `saveMalProgress` does, and keeps one
 * that failed so it is tried again, as Android's WorkManager chain does. The rules are in
 * [planProgressUpdate].
 */
object MalProgressSync {
    private const val MAX_ATTEMPTS = 6
    private val pending = PendingMalProgressStore()

    /** One push, no retry: the plan, then the write, then the result. */
    suspend fun push(malId: Long?, watched: Int, requestedTotal: Int?, cardTotal: Int?): ProgressResult {
        if (watched < 1) return ProgressResult.Skipped(ProgressSkipReason.INVALID_PROGRESS)
        if (malId == null || malId <= 0) return ProgressResult.Skipped(ProgressSkipReason.MISSING_MAL_ID)
        val remote = MyAnimeListSync.animeStatus(malId)
        val current = remote.entry?.let {
            ListSnapshot(malId, malEntryToAniListStatus(it), it.watched, it.timesRewatched)
        }
        val target = malProgressTarget(watched, remote.totalEpisodes)
        val total = malProgressTotal(remote.totalEpisodes, requestedTotal, cardTotal)
        val update = planProgressUpdate(current, target, total)
            ?: return ProgressResult.Skipped(progressSkipReason(current?.status, current?.progress, target))
        val rewatching = current?.status == "REPEATING"
        MyAnimeListSync.writeEntry(
            malId,
            MalWrite(
                progress = update.progress,
                status = update.status?.let(::aniListToMalStatus),
                // Finishing a rewatch takes the flag off and counts it; mid-rewatch the flag stays.
                isRewatching = false.takeIf { update.repeat != null },
                numTimesRewatched = update.repeat,
                strictProgress = !rewatching || update.repeat != null,
            ),
        )
        return ProgressResult.Updated(update)
    }

    /** Queue the episode, then try everything owed. A failure leaves it queued for the next try. */
    suspend fun submit(anime: AnimeCard, episode: Int) {
        if (MyAnimeListSync.user.value == null || anime.id <= 0 || episode < 1) return
        pending.add(anime.id, anime.malId, episode, anime.episodes)
        drain()
    }

    suspend fun drain() {
        if (MyAnimeListSync.user.value == null) return
        pending.all().forEach { item ->
            val outcome = runCatching { push(item.malId, item.episode, item.total, item.total) }
            val result = outcome.getOrNull()
            if (result is ProgressResult.Updated && result.update.status == "COMPLETED") {
                ContinueStoreInstance.finishedOnAccount(item.id, result.update.progress)
            }
            if (outcome.isSuccess) pending.remove(item.id) else pending.failed(item.id, MAX_ATTEMPTS)
        }
    }
}

/** Progress MyAnimeList has not confirmed yet, kept across restarts. */
class PendingMalProgressStore(private val file: File = defaultFile()) {
    data class Item(val id: Long, val malId: Long?, val episode: Int, val total: Int?, val attempts: Int)

    @Synchronized fun all(): List<Item> {
        val p = load()
        return p.stringPropertyNames().mapNotNull { key ->
            if (!key.endsWith(".episode")) return@mapNotNull null
            val id = key.removeSuffix(".episode").toLongOrNull() ?: return@mapNotNull null
            Item(
                id = id,
                malId = p.getProperty("$id.malId")?.toLongOrNull(),
                episode = p.getProperty(key)?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null,
                total = p.getProperty("$id.total")?.toIntOrNull(),
                attempts = p.getProperty("$id.attempts")?.toIntOrNull() ?: 0,
            )
        }.sortedBy { it.id }
    }

    /** Keeps the highest episode asked for; both services refuse to go backwards, so lower ones are moot. */
    @Synchronized fun add(id: Long, malId: Long?, episode: Int, total: Int?) {
        val p = load()
        val kept = maxOf(p.getProperty("$id.episode")?.toIntOrNull() ?: 0, episode)
        p.setProperty("$id.episode", kept.toString())
        malId?.let { p.setProperty("$id.malId", it.toString()) }
        total?.let { p.setProperty("$id.total", it.toString()) }
        save(p)
    }

    @Synchronized fun remove(id: Long) {
        val p = load()
        listOf("episode", "malId", "total", "attempts").forEach { p.remove("$id.$it") }
        save(p)
    }

    /** Count a failed try; an item that has used them all is dropped, as Android's worker drops its own. */
    @Synchronized fun failed(id: Long, limit: Int) {
        val p = load()
        val attempts = (p.getProperty("$id.attempts")?.toIntOrNull() ?: 0) + 1
        if (attempts >= limit) {
            listOf("episode", "malId", "total", "attempts").forEach { p.remove("$id.$it") }
        } else {
            p.setProperty("$id.attempts", attempts.toString())
        }
        save(p)
    }

    private fun load() = Properties().apply { if (file.isFile) file.inputStream().use(::load) }
    private fun save(p: Properties) {
        file.parentFile?.mkdirs()
        file.outputStream().use { p.store(it, "AniLife pending MyAnimeList progress") }
    }

    private companion object {
        fun defaultFile(): File {
            val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank) ?: System.getProperty("user.home")
            return File(base, "AniLife/pending-mal-progress.properties")
        }
    }
}
