package me.enivzoy.anilife.app.data

/**
 * Android's rules for folding an account's list into this device, ported without behaviour
 * changes. Source of truth: `LibraryModels.kt` in the Android app (`mergeWatchlistEntries`,
 * `historyAfterRemoteProgress`, `remoteRowEpisode`, `remoteMovesOffWatching`,
 * `isFinishedSeriesLeftover`). Keep both in step; see PARITY.md.
 *
 * Windows has no per-episode watched set, so the two rules that read one are narrowed to what the
 * Continue Watching row can say. Those two spots are listed under "Differences" in PARITY.md.
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
 * Fold the account's episode count into a row this device wrote itself. The local row wins
 * whenever it is level or ahead; advancing clears the resume position because it belonged to an
 * episode finished elsewhere, and carries the other device's timestamp so Continue Watching does
 * not claim the viewer watched it just now. Null means nothing changed.
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
    if (target <= local.progress.episode) return null
    return local.copy(
        progress = local.progress.copy(episode = target, position = 0.0),
        updatedAt = maxOf(local.updatedAt, remoteUpdatedAt),
    )
}

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
 * Whether a Continue Watching row is what a finished series left behind: the account says
 * COMPLETED, and the row sits on the series' last episode and has been played through.
 *
 * Android reads "watched" from its per-episode set. Windows has none, so a row counts as played
 * through once it is past 90% of the episode (the same line [ContinueStore.positionFor] uses).
 */
fun isFinishedSeriesLeftover(row: ContinueEntry, status: String?, totalEpisodes: Int?): Boolean {
    if (status != "COMPLETED") return false
    val total = totalEpisodes?.takeIf { it > 0 } ?: return false
    val p = row.progress
    return p.episode >= total && p.duration > 0.0 && p.position >= p.duration * 0.9
}
