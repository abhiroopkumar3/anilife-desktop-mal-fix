package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.AiringTime

/**
 * AniList ids to their episodes airing in the window Home reads, with each episode's airing time in
 * epoch seconds (Android's `UpcomingAiring`).
 *
 * The window runs from yesterday to a fortnight out ([UPCOMING_AIRING_DAYS]): far enough for a
 * weekly show's next episode and the odd skipped week, and back far enough to see an episode that
 * aired late last night and is not watchable yet. The times are kept rather than the day alone
 * because the reader has to be able to ask whether one has passed.
 */
typealias UpcomingAiring = Map<Long, Map<Int, Long>>

/**
 * The last schedule read, kept for the life of the app. Home is built again every time the viewer
 * comes back to it; starting from the last read instead of an empty one is what stops a caught-up
 * row showing for the moment the new read takes. Android's view model holds it the same way.
 */
object UpcomingAiringMemory {
    @Volatile var last: UpcomingAiring = emptyMap()
}

/** How many days the [UpcomingAiring] window covers, starting yesterday. */
const val UPCOMING_AIRING_DAYS = 16

/** An episode is watchable this long after it airs: the time the servers need to carry it. */
const val EPISODE_WATCHABLE_AFTER_SEC = 2L * 60 * 60

/** Folds a schedule read into the shape the rule below takes. */
fun upcomingAiringFrom(times: List<AiringTime>): UpcomingAiring =
    times.groupBy({ it.mediaId }, { it.episode to it.airingAt })
        .mapValues { (_, airings) -> airings.toMap() }

/**
 * Whether Continue Watching should step aside for [entry] because nothing on it is left to play
 * (Android's `hideFromContinueUntilAired`).
 *
 * A title followed week by week, with the latest episode watched, has nothing to play until the
 * next one is out. Hidden, never removed: nothing is written, and the account never hears about it.
 * The row comes back on its own once the episode is out, at [returnsToContinueAt].
 */
fun hideFromContinueUntilAired(
    entry: ContinueEntry,
    upcoming: UpcomingAiring,
    nowSec: Long = System.currentTimeMillis() / 1000,
): Boolean = returnsToContinueAt(entry, upcoming, nowSec) != null

/**
 * When a row that is hidden now comes back to Continue Watching, in epoch seconds; null when it is
 * not hidden. That is the next episode's broadcast plus [EPISODE_WATCHABLE_AFTER_SEC].
 */
fun returnsToContinueAt(
    entry: ContinueEntry,
    upcoming: UpcomingAiring,
    nowSec: Long = System.currentTimeMillis() / 1000,
): Long? {
    val (episode, returnsAt) = nextUnreleasedEpisode(entry, upcoming, nowSec) ?: return null
    // What is playing, or was left part-way, is what Continue Watching is for, and what happens to be
    // airing next has nothing to do with the episode under the finger. Windows keeps one resume point
    // per title, so this is also Android's `HistoryEntry.resumeFor` check.
    if (entry.progress.position > 0) return null
    // Anything behind the last episode out is a rewatch, an earlier season or an episode not yet
    // watched: there is something to play, and the schedule has nothing to do with it.
    val lastAired = episode - 1
    if (entry.progress.episode < lastAired) return null
    // On the last episode out but not through it: that episode is the thing to play. Only a set that
    // records something can say so; an empty one is a row that predates the set.
    if (entry.progress.episode < episode &&
        entry.watched.isNotEmpty() &&
        entry.progress.episode !in entry.watched
    ) {
        return null
    }
    return returnsAt
}

/**
 * The first episode of [entry]'s title in [upcoming], at or after the row's own, that is not
 * watchable yet, with the moment it will be; null when the window holds none.
 *
 * At-or-after rather than after, because finishing the latest episode advances the row onto the next
 * one before it has aired. And the first unreleased one rather than the first, because the window
 * reaches back to yesterday: an episode in it that is already out is something to play.
 */
private fun nextUnreleasedEpisode(
    entry: ContinueEntry,
    upcoming: UpcomingAiring,
    nowSec: Long,
): Pair<Int, Long>? =
    upcoming[entry.anime.id]
        ?.mapValues { (_, airingAt) -> airingAt + EPISODE_WATCHABLE_AFTER_SEC }
        ?.filter { (episode, watchableAt) -> episode >= entry.progress.episode && nowSec < watchableAt }
        ?.minByOrNull { (episode, _) -> episode }
        ?.toPair()
