package me.enivzoy.anilife.app.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * What the card menu writes to an account, ported from Android's AnimeListActions.kt,
 * MiruroRepository.kt and the list functions of AniListClient.kt and MalClient.kt. The decisions
 * are plain functions here; the calls that send them are in AniListAccountApi and MyAnimeListSync.
 */

/** One place a title can be moved to, with what the menu says about it. */
data class ListDestination(val status: String, val label: String, val hint: String)

/** Every long-press menu offers these, in this order (Android's `listDestinations`). */
val LIST_DESTINATIONS = listOf(
    ListDestination("CURRENT", "Watching", "Working through it now"),
    ListDestination("REPEATING", "Rewatching", "Seen it; going again"),
    ListDestination("PLANNING", "Plan to watch", "Saved for later"),
    ListDestination("PAUSED", "Paused", "On hold for now"),
    ListDestination("COMPLETED", "Completed", "Finished every episode"),
    ListDestination("DROPPED", "Dropped", "Given up on"),
)

/**
 * Whether the saved row on this PC survives a move to [status]. The saved list is the app's own
 * Planning list, so a title moved anywhere else stops being a save.
 */
fun savedRowSurvivesMoveTo(status: String): Boolean = status == "PLANNING"

/** Moving off Watching also takes the title out of Continue Watching. */
fun moveKeepsContinueRow(status: String): Boolean = status == "CURRENT" || status == "REPEATING"

/** What a status move has to write besides the status, so a rewatch is recorded rather than lost. */
data class RewatchMove(
    /** Start the episode count again from 0: a rewatch counts its own episodes. */
    val resetProgress: Boolean = false,
    /** The rewatch count to write; null leaves it. */
    val repeat: Int? = null,
) {
    val touchesRewatch: Boolean get() = resetProgress || repeat != null
}

/** Android's `planRewatchMove`, in AniList vocabulary (a MAL entry flagged rewatching is REPEATING). */
fun planRewatchMove(currentStatus: String?, currentRepeat: Int, target: String): RewatchMove = when {
    target == "REPEATING" && currentStatus == "COMPLETED" -> RewatchMove(resetProgress = true)
    target == "COMPLETED" && currentStatus == "REPEATING" -> RewatchMove(repeat = currentRepeat + 1)
    else -> RewatchMove()
}

/** An entry's start or finish date, as precise as the viewer set it. */
data class ListDate(val year: Int, val month: Int? = null, val day: Int? = null)

/** One AniList entry as the menu needs it. */
data class ListSnapshot(
    val entryId: Long,
    val status: String,
    val progress: Int,
    val repeat: Int = 0,
    val startedAt: ListDate? = null,
    val completedAt: ListDate? = null,
)

/** The dates to send back unchanged with a rewatch write; a date the entry lacks is left out, not cleared. */
fun preservedListDates(entry: ListSnapshot?): List<Pair<String, ListDate>> = listOfNotNull(
    entry?.startedAt?.let { "startedAt" to it },
    entry?.completedAt?.let { "completedAt" to it },
)

private fun ListDate.toInput(): JsonObject = buildJsonObject {
    put("year", year)
    month?.let { put("month", it) }
    day?.let { put("day", it) }
}

/** The `SaveMediaListEntry` pieces for a rewatch write: declarations, arguments, and their values. */
fun rewatchArguments(entry: ListSnapshot?, repeat: Int?): Triple<String, String, Map<String, JsonElement>> {
    val values = buildMap<String, Pair<String, JsonElement>> {
        repeat?.let { put("repeat", "Int" to JsonPrimitive(it)) }
        preservedListDates(entry).forEach { (name, date) -> put(name, "FuzzyDateInput" to date.toInput()) }
    }
    val declarations = values.entries.joinToString("") { (name, typed) -> ", \$$name: ${typed.first}" }
    val arguments = values.keys.joinToString("") { name -> ", $name: \$$name" }
    return Triple(declarations, arguments, values.mapValues { it.value.second })
}

/** A score out of ten as AniList's format-independent 0 to 100 `scoreRaw`; 0 clears it. */
fun aniListScoreRaw(score: Int): Int {
    require(score in 0..10) { "A list score is 0 to 10, not $score" }
    return score * 10
}

fun listScoreLabel(score: Int): String? = when (score) {
    10 -> "Masterpiece"
    9 -> "Great"
    8 -> "Very good"
    7 -> "Good"
    6 -> "Fine"
    5 -> "Average"
    4 -> "Bad"
    3 -> "Very bad"
    2 -> "Horrible"
    1 -> "Appalling"
    else -> null
}

/** A score as a viewer reads it: "8", or "7.5" for an AniList score set on the website. */
fun formatListScore(score: Double): String =
    if (score % 1.0 == 0.0) score.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, score)

/** The number of episodes watched, and the most the service will take. */
data class ListProgress(val progress: Int, val maxEpisodes: Int?)

/** One MyAnimeList entry's own fields, as `my_list_status` reports them. */
data class MalEntryStatus(
    val status: String?,
    val score: Int,
    val watched: Int,
    val isRewatching: Boolean,
    val timesRewatched: Int,
)

/** MyAnimeList's entry plus its own episode count, which is what a progress write is measured against. */
data class MalAnimeStatus(val entry: MalEntryStatus?, val totalEpisodes: Int?)

fun parseMalEntryStatus(obj: JsonObject): MalEntryStatus = MalEntryStatus(
    status = (obj["status"] as? JsonPrimitive)?.contentOrNull,
    score = (obj["score"] as? JsonPrimitive)?.intOrNull ?: 0,
    watched = (obj["num_episodes_watched"] as? JsonPrimitive)?.intOrNull ?: 0,
    isRewatching = (obj["is_rewatching"] as? JsonPrimitive)?.booleanOrNull ?: false,
    timesRewatched = (obj["num_times_rewatched"] as? JsonPrimitive)?.intOrNull ?: 0,
)

fun parseMalAnimeStatus(root: JsonObject): MalAnimeStatus = MalAnimeStatus(
    entry = (root["my_list_status"] as? JsonObject)?.let(::parseMalEntryStatus)?.takeIf { it.status != null },
    totalEpisodes = (root["num_episodes"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 },
)

/** An entry in AniList's words: a rewatching flag wins over the status it is stored under. */
fun malEntryToAniListStatus(entry: MalEntryStatus): String = when {
    entry.isRewatching -> "REPEATING"
    entry.status == "watching" -> "CURRENT"
    entry.status == "completed" -> "COMPLETED"
    entry.status == "on_hold" -> "PAUSED"
    entry.status == "dropped" -> "DROPPED"
    else -> "PLANNING"
}

/** What a MyAnimeList write asked for, so the answer can be checked against it. */
data class MalWrite(
    val progress: Int? = null,
    val status: String? = null,
    val isRewatching: Boolean? = null,
    val score: Int? = null,
    val numTimesRewatched: Int? = null,
    val strictProgress: Boolean = true,
)

/**
 * What MyAnimeList's answer got wrong, or null when it did what was asked. A rewatch's own episode
 * count is not held to the figure, because MAL may keep the finished count on a completed row.
 */
fun malWriteMismatch(asked: MalWrite, confirmed: MalEntryStatus): String? {
    asked.progress?.takeIf { asked.strictProgress || confirmed.watched == it }?.let {
        if (confirmed.watched != it) return "MyAnimeList confirmed ${confirmed.watched} watched episodes after requesting $it"
    }
    asked.status?.let {
        if (confirmed.status != it) return "MyAnimeList confirmed status ${confirmed.status ?: "missing"} after requesting $it"
    }
    asked.score?.let {
        if (confirmed.score != it) return "MyAnimeList confirmed score ${confirmed.score} after requesting $it"
    }
    asked.isRewatching?.let {
        if (confirmed.isRewatching != it) return "MyAnimeList confirmed is_rewatching=${confirmed.isRewatching} after requesting $it"
    }
    asked.numTimesRewatched?.let {
        if (confirmed.timesRewatched != it) return "MyAnimeList confirmed num_times_rewatched=${confirmed.timesRewatched} after requesting $it"
    }
    return null
}
