package me.enivzoy.anilife.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Properties

/*
 * The activity grid's data, ported from Android's WatchActivityStore.kt. A day is counted here as an
 * episode is finished, because nothing else records which days someone watched on. An AniList
 * account also has its own history, merged in; MyAnimeList keeps none, so this log is all there is.
 */

/** Days of history kept, as on Android. */
const val ACTIVITY_KEEP_DAYS = 400L

/** Weeks the grid draws. */
const val HEATMAP_WEEKS = 20

/**
 * This PC's episodes and the account's own activity, one count per day. A day counted in both keeps
 * the larger number: the same evening is often in both, and adding them would count it twice.
 */
fun mergedActivity(local: Map<Long, Int>, account: Map<Long, Int>): Map<Long, Int> =
    (local.keys + account.keys).associateWith { day -> maxOf(local[day] ?: 0, account[day] ?: 0) }
        .filterValues { it > 0 }

/** Consecutive active days ending today, or yesterday, so a streak survives until tonight's episode. */
fun activityStreak(days: Map<Long, Int>, today: Long): Int {
    var day = if ((days[today] ?: 0) > 0) today else today - 1
    var streak = 0
    while ((days[day] ?: 0) > 0) {
        streak++
        day--
    }
    return streak
}

/** How bright a day is drawn, 0 to 4, relative to the busiest day on screen. */
fun heatLevel(count: Int, max: Int): Int =
    if (count <= 0) 0 else kotlin.math.ceil(4.0 * count / max.coerceAtLeast(1)).toInt().coerceIn(1, 4)

/** [days] with one more episode on [day], and anything older than the kept window dropped. */
fun withEpisodeOn(days: Map<Long, Int>, day: Long): Map<Long, Int> =
    (days + (day to (days[day] ?: 0) + 1)).filterKeys { it > day - ACTIVITY_KEEP_DAYS }

/** AniList's `User.stats.activityHistory`, keyed by epoch day. Anything unreadable gives an empty map. */
fun parseActivityHistory(data: JsonObject): Map<Long, Int> {
    val history = (((data["User"] as? JsonObject)?.get("stats") as? JsonObject)?.get("activityHistory")) as? JsonArray
        ?: return emptyMap()
    return history.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val date = (obj["date"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
        val amount = (obj["amount"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        Instant.ofEpochSecond(date).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() to amount
    }.filter { it.second > 0 }.toMap()
}

/** Episodes finished on this PC per local day, kept in a small properties file. */
class WatchActivityStore(private val file: File = defaultFile()) {
    private val lock = Any()
    private val mutableDays = MutableStateFlow(read())
    val days: StateFlow<Map<Long, Int>> = mutableDays.asStateFlow()

    /** Count a finished episode, unless it is only a later report of one already counted. */
    fun recordEpisode(existing: ContinueEntry, watchedEpisode: Double, today: LocalDate = LocalDate.now()) {
        if (!shouldRecordWatchActivity(existing, watchedEpisode)) return
        synchronized(lock) {
            val updated = withEpisodeOn(mutableDays.value, today.toEpochDay())
            write(updated)
            mutableDays.value = updated
        }
    }

    /** Replace the whole log, as when a backup is restored. */
    fun replace(days: Map<Long, Int>) = synchronized(lock) {
        val cleaned = days.filterValues { it > 0 }
        write(cleaned)
        mutableDays.value = cleaned
    }

    private fun read(): Map<Long, Int> {
        if (!file.isFile) return emptyMap()
        val p = Properties()
        runCatching { file.inputStream().use(p::load) }.getOrElse { return emptyMap() }
        return p.stringPropertyNames().mapNotNull { name ->
            val day = name.toLongOrNull() ?: return@mapNotNull null
            val count = p.getProperty(name)?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            day to count
        }.toMap()
    }

    private fun write(days: Map<Long, Int>) {
        file.parentFile?.mkdirs()
        val p = Properties().apply { days.forEach { (day, count) -> setProperty(day.toString(), count.toString()) } }
        file.outputStream().use { p.store(it, "AniLife watch activity, episodes per local epoch day") }
    }

    private companion object {
        fun defaultFile(): File {
            val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank) ?: System.getProperty("user.home")
            return File(base, "AniLife/watch-activity.properties")
        }
    }
}

val WatchActivityInstance: WatchActivityStore = WatchActivityStore()
