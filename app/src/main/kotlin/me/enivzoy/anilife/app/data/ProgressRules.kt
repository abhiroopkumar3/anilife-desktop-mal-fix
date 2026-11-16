package me.enivzoy.anilife.app.data

/*
 * What playback writes to the account when an episode is watched, ported from Android's
 * `planMediaListProgressUpdate`, `saveMalProgress` and `malProgressSkipReason`. One policy serves
 * both services: never lower a count, leave a Completed entry alone, finish a series on its last
 * episode, and count a finished rewatch.
 */

/** What to write. A null [status] keeps the account's. A non-null [repeat] finishes a rewatch. */
data class ProgressUpdate(val progress: Int, val status: String? = null, val repeat: Int? = null)

/**
 * Null means the account is left alone. A REPEATING entry is a rewatch and its last episode finishes
 * it, even when the count never moved during the rewatch.
 */
fun planProgressUpdate(current: ListSnapshot?, watched: Int, totalEpisodes: Int?): ProgressUpdate? {
    if (watched < 1) return null
    val completed = totalEpisodes?.takeIf { it > 0 }?.let { watched >= it } == true
    if (current?.status == "REPEATING" && completed) {
        return ProgressUpdate(progress = watched, status = "COMPLETED", repeat = current.repeat + 1)
    }
    if (current != null && watched <= current.progress) return null
    if (current?.status == "COMPLETED") return null
    val status = when {
        current == null -> if (completed) "COMPLETED" else "CURRENT"
        current.status == "PLANNING" -> if (completed) "COMPLETED" else "CURRENT"
        current.status == "CURRENT" && completed -> "COMPLETED"
        else -> null // Preserve REPEATING, PAUSED and DROPPED.
    }
    return ProgressUpdate(progress = watched, status = status)
}

/** MyAnimeList refuses a count above its own episode total, so the target is held to it. */
fun malProgressTarget(watched: Int, malTotal: Int?): Int =
    malTotal?.takeIf { it > 0 }?.let { minOf(watched, it) } ?: watched

/** The length that decides whether this episode finishes the series: MAL's own, else AniList's. */
fun malProgressTotal(malTotal: Int?, requestedTotal: Int?, cardTotal: Int?): Int? =
    malTotal ?: requestedTotal ?: cardTotal

/** Why a MyAnimeList progress push made no write. */
enum class ProgressSkipReason { INVALID_PROGRESS, MISSING_MAL_ID, ALREADY_AT_OR_AHEAD, REMOTE_COMPLETED, NO_CHANGE }

fun progressSkipReason(currentStatus: String?, currentProgress: Int?, target: Int): ProgressSkipReason = when {
    target < 1 -> ProgressSkipReason.INVALID_PROGRESS
    currentStatus == "COMPLETED" -> ProgressSkipReason.REMOTE_COMPLETED
    currentProgress != null && target <= currentProgress -> ProgressSkipReason.ALREADY_AT_OR_AHEAD
    else -> ProgressSkipReason.NO_CHANGE
}

/** How a push ended, for the log and for what this PC does next. */
sealed interface ProgressResult {
    data class Updated(val update: ProgressUpdate) : ProgressResult
    data class Skipped(val reason: ProgressSkipReason) : ProgressResult
}
