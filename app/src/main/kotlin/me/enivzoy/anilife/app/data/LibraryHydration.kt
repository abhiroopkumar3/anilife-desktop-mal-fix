package me.enivzoy.anilife.app.data

/**
 * What one refresh of the signed-in account changes on this PC. Android does this inside
 * `LibraryStore.hydrateRemoteLibrary`; here it is a pure function so the same cases can be tested
 * without a network or a disk.
 *
 * Steps, in Android's order:
 *  1. Titles the account hides from status lists are ignored.
 *  2. Saved titles are merged with the account's Planning list. A save goes only when the account
 *     had the title in the previous snapshot and now has it under no status at all.
 *  3. Continue Watching rows move forward to the account's progress, never back.
 *  4. Rows for titles the account moved off Watching since the last refresh are removed, as are
 *     rows a finished series left behind.
 *  5. Titles on Watching or Rewatching with no row get one pointing at the next episode.
 *     Completed and Dropped titles are left alone.
 */
data class HydrationResult(
    val saves: List<LocalSave>,
    val continueUpserts: List<ContinueEntry>,
    val continueRemovals: Set<Long>,
    /** The statuses now on the account, kept for the next refresh's "moved off Watching" check. */
    val snapshot: Map<Long, String>,
)

/** A row seeded from the account has no real playback length yet; this is only a placeholder. */
const val SEEDED_EPISODE_SECONDS = 1440.0

fun hydrateFromAccount(
    remote: List<ListEntry>,
    saves: List<LocalSave>,
    continueRows: List<ContinueEntry>,
    previousSnapshot: Map<Long, String>,
    nowMillis: Long = System.currentTimeMillis(),
): HydrationResult {
    val visible = remote.filterNot { it.hiddenFromStatusLists }
    val statuses = visible.associate { it.anime.id to it.status }

    val planning = visible.filter { it.status == "PLANNING" }.map { LocalSave(it.anime, nowMillis) }
    val mergedSaves = mergeWatchlistEntries(
        local = saves,
        fromAccount = planning,
        knownRemoteIds = statuses.keys,
        previousRemoteIds = previousSnapshot.keys,
        addedAt = nowMillis,
    )

    val removals = remoteMovesOffWatching(previousSnapshot, statuses).toMutableSet()
    val rowsById = continueRows.associateBy { it.anime.id }
    val upserts = mutableListOf<ContinueEntry>()

    visible.forEach { entry ->
        val id = entry.anime.id
        val total = entry.anime.episodes?.takeIf { it > 0 }
        val row = rowsById[id]
        if (row != null && isFinishedSeriesLeftover(row, entry.status, total)) removals += id
        if (id in removals) return@forEach
        val aired = entry.anime.nextAiring?.episode?.minus(1)?.takeIf { it > 0 }
        when {
            row != null -> continueAfterRemoteProgress(
                row, entry.progress, entry.updatedAt * 1000L, total, aired,
            )?.let(upserts::add)
            entry.status == "CURRENT" || entry.status == "REPEATING" -> {
                val episode = remoteRowEpisode(entry.progress.coerceAtLeast(0), total, aired)
                    ?: if (entry.progress < 1) 1 else null
                if (episode != null) {
                    upserts += ContinueEntry(
                        anime = entry.anime,
                        progress = ContinueProgress(episode.toDouble(), 0.0, SEEDED_EPISODE_SECONDS),
                        updatedAt = entry.updatedAt * 1000L,
                    )
                }
            }
        }
    }
    return HydrationResult(mergedSaves, upserts, removals.filter { it in rowsById }.toSet(), statuses)
}
