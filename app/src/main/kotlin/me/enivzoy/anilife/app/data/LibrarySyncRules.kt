package me.enivzoy.anilife.app.data

/**
 * Android's rules for folding an account's list into this device, ported without behaviour
 * changes. Source of truth: `LibraryModels.kt` in the Android app (`mergeWatchlistEntries`,
 * `historyAfterRemoteProgress`, `remoteRowEpisode`, `remoteMovesOffWatching`,
 * `isFinishedSeriesLeftover`). Keep both in step; see PARITY.md.
 *
 * The watched set lives on [ContinueEntry.watched], as on Android's `HistoryEntry`.
 */

/**
 * Merge the account's Plan-to-watch list into this device's saves, in both directions.
 *
 * A save is dropped only when the account used to know the title and now knows it under no status
 * at all ([previousRemoteIds] minus [knownRemoteIds]). An empty [knownRemoteIds] is no information,
 * never an emptied account. Saves never seen in a remote snapshot (made while signed out, or
 * catalogue-native negative ids) can therefore never be dropped.
 */
fun mergeWatchlistEntries(
    local: List<LocalSave>,
    fromAccount: List<LocalSave>,
    knownRemoteIds: Set<Long> = emptySet(),
    previousRemoteIds: Set<Long> = emptySet(),
    addedAt: Long = System.currentTimeMillis(),
): List<LocalSave> {
    val removable = if (knownRemoteIds.isEmpty()) emptySet() else previousRemoteIds - knownRemoteIds
    if (fromAccount.isEmpty() && removable.isEmpty()) return local
    val remoteById = fromAccount.associateBy { it.anime.id }
    val localIds = local.mapTo(mutableSetOf()) { it.anime.id }
    return buildList {
        local.forEach { saved ->
            if (saved.anime.id in removable) return@forEach
            val remote = remoteById[saved.anime.id]
            add(remote?.copy(addedAt = saved.addedAt) ?: saved)
        }
        fromAccount.forEach { remote ->
            if (remote.anime.id !in localIds) add(remote.copy(addedAt = addedAt))
        }
    }.distinctBy { it.anime.id }
}

/**
 * The episode a Continue Watching row should point at when the account has finished [progress]
 * episodes, or null when there is nothing left to continue. Normally the one after the count; past
 * the planned run the series is finished; past what has aired the row rests on the latest aired.
 */
fun remoteRowEpisode(progress: Int, totalEpisodes: Int?, airedEpisodes: Int?): Int? {
    val next = progress + 1
    if (totalEpisodes != null && totalEpisodes > 0 && next > totalEpisodes) return null
    if (airedEpisodes != null && airedEpisodes > 0 && next > airedEpisodes) {
        return minOf(progress, airedEpisodes).takeIf { it >= 1 }
    }
    return next
}

/**
 * Fold the account's episode count into a row this device wrote itself (Android's
 * `historyAfterRemoteProgress`). Everything up to the count counts as watched, since that is all
 * AniList and MAL store, so the set grows even when the row is already far enough along. The local
 * row wins whenever it is level or ahead; advancing clears the resume position and carries the other
 * device's timestamp. Null means nothing changed.
 *
 * [remoteUpdatedAt] is epoch milliseconds, the unit [ContinueEntry.updatedAt] uses.
 */
fun continueAfterRemoteProgress(
    local: ContinueEntry,
    remoteProgress: Int,
    remoteUpdatedAt: Long,
    totalEpisodes: Int?,
    airedEpisodes: Int? = null,
): ContinueEntry? {
    if (remoteProgress < 1) return null
    val target = remoteRowEpisode(remoteProgress, totalEpisodes, airedEpisodes)?.toDouble() ?: return null
    val watched = local.watched + generateSequence(1.0) { it + 1.0 }.takeWhile { it <= remoteProgress }.toSet()
    if (target <= local.progress.episode) {
        if (watched == local.watched) return null
        return local.copy(watched = watched)
    }
    return local.copy(
        progress = ContinueProgress(target, 0.0, 0.0),
        updatedAt = maxOf(local.updatedAt, remoteUpdatedAt),
        watched = watched,
    )
}

/**
 * Move Continue Watching past an episode that crossed the watched threshold (Android's
 * `historyAfterEpisodeWatched`). The row only changes when it still points at the episode being
 * completed, which makes late player callbacks harmless. Null means the known final episode was
 * completed and the row should be removed.
 */
fun continueAfterEpisodeWatched(
    existing: ContinueEntry,
    watchedEpisode: Double,
    nextEpisode: Double?,
    seriesCompleted: Boolean,
): ContinueEntry? {
    if (existing.progress.episode != watchedEpisode) return existing
    val watched = existing.watched + watchedEpisode
    if (seriesCompleted) return null
    val next = nextEpisode?.takeIf { it > watchedEpisode } ?: return existing.copy(watched = watched)
    return existing.copy(progress = ContinueProgress(next, 0.0, 0.0), fromRemote = false, watched = watched)
}

/** Android's `shouldRecordWatchActivity`: only a genuinely new completion of the row's own episode counts. */
fun shouldRecordWatchActivity(existing: ContinueEntry, watchedEpisode: Double): Boolean =
    existing.progress.episode == watchedEpisode && watchedEpisode !in existing.watched

private val WATCHING_STATUSES = setOf("CURRENT", "REPEATING")
private val OFF_WATCHING_STATUSES = setOf("COMPLETED", "DROPPED", "PAUSED")

/**
 * The Continue Watching rows to take off because the account moved them off Watching elsewhere.
 * Only a move between two refreshes counts; a title the account no longer lists is a deletion and
 * says nothing about whether the viewer is still watching. The first refresh removes nothing.
 */
fun remoteMovesOffWatching(previous: Map<Long, String>, current: Map<Long, String>): Set<Long> =
    current.filter { (id, status) ->
        status in OFF_WATCHING_STATUSES && previous[id] in WATCHING_STATUSES
    }.keys

/**
 * Whether a Continue Watching row is what a finished series left behind: the account says COMPLETED,
 * and the row sits on the series' last episode with that episode already watched here (Android's
 * `isFinishedSeriesLeftover`). A rewatch is not mistaken for one: it sits on an earlier episode, or
 * on a final episode not yet finished, and either keeps its row.
 */
fun isFinishedSeriesLeftover(row: ContinueEntry, status: String?, totalEpisodes: Int?): Boolean {
    if (status != "COMPLETED") return false
    val total = totalEpisodes?.takeIf { it > 0 } ?: return false
    return row.progress.episode >= total && row.progress.episode in row.watched
}
