package me.enivzoy.anilife.app.data

import java.util.Locale
import kotlin.math.roundToInt

/*
 * What the account sheet shows, worked out once from the lists already loaded. Ported from Android's
 * AccountSheet.kt (`accountInsights`, `statWatchTime`, the label helpers). Top studios is the one
 * chart left out: the PC's cards do not carry studios (see PARITY.md).
 */

/** Where an account's titles sit, in a fixed order with a fixed colour each, as on Android. */
enum class ListStatusSlice(val label: String, val argb: Long) {
    WATCHING("Watching", 0xFF3987E5),
    COMPLETED("Completed", 0xFFD95926),
    PLANNING("Planned", 0xFF199E70),
    PAUSED("Paused", 0xFFC98500),
    DROPPED("Dropped", 0xFFD55181),
}

/** What the account itself says about the viewer: Android's `Viewer.bannerImage` and `statistics.anime`. */
data class AccountStats(
    val banner: String? = null,
    val titles: Int = 0,
    val episodes: Int = 0,
    val minutesWatched: Long = 0L,
    val meanScore: Double = 0.0,
)

data class AccountInsights(
    val titles: Int,
    val episodes: Int,
    val minutesWatched: Long,
    /** Null when nothing is scored, so the stat can say so rather than print a mean of 0.0. */
    val meanScore: Double?,
    val statusCounts: Map<ListStatusSlice, Int>,
    /** Most-watched genres, most first, at most [MAX_GENRES]. */
    val topGenres: List<Pair<String, Int>>,
    /** Every entry, most recently changed first: the sheet's activity feed. */
    val recent: List<ListEntry>,
    val formats: List<Pair<String, Int>> = emptyList(),
    /** Titles per release year, or per decade once the list spans more than a dozen years. */
    val releaseYears: List<Pair<String, Int>> = emptyList(),
    /** How many titles the viewer gave each whole score, 1 to 10; empty when nothing is scored. */
    val scoreSpread: List<Pair<String, Int>> = emptyList(),
    /** Completed as a share of everything finished one way or the other; null before either. */
    val completionRate: Int? = null,
    /** Episodes left in what is being watched, where the series' length is known. */
    val episodesToGo: Int = 0,
)

const val MAX_GENRES = 5

fun accountInsights(entries: List<ListEntry>, stats: AccountStats?): AccountInsights {
    fun with(status: String) = entries.filter { it.status == status }
    val watching = with("CURRENT")
    val rewatching = with("REPEATING")
    val planning = with("PLANNING")
    val paused = with("PAUSED")
    val completed = with("COMPLETED")
    val dropped = with("DROPPED")
    val statusCounts = linkedMapOf(
        ListStatusSlice.WATCHING to watching.size + rewatching.size,
        ListStatusSlice.COMPLETED to completed.size,
        ListStatusSlice.PLANNING to planning.size,
        ListStatusSlice.PAUSED to paused.size,
        ListStatusSlice.DROPPED to dropped.size,
    )
    // Genres of what the viewer has actually started: a Planning list is a wish list, and letting it
    // vote would describe what they mean to watch rather than what they watch.
    val started = watching + rewatching + completed + paused + dropped
    val genreSource = started.ifEmpty { planning }
    fun ranked(values: List<String>, limit: Int) = values.groupingBy { it }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(limit)
        .map { it.key to it.value }
    val topGenres = ranked(genreSource.map { it.anime }.distinctBy { it.id }.flatMap { it.genres }, MAX_GENRES)
    val allEntries = started + planning
    val allMedia = allEntries.map { it.anime }.distinctBy { it.id }
    val years = allMedia.mapNotNull { it.year }.filter { it > 0 }
    val releaseYears = when {
        years.isEmpty() -> emptyList()
        years.max() - years.min() <= 11 ->
            (years.min()..years.max()).map { year -> yearLabel(year) to years.count { it == year } }
        else -> {
            val decades = years.map { it / 10 * 10 }
            (decades.min()..decades.max() step 10).map { decade -> decadeLabel(decade) to decades.count { it == decade } }
        }
    }
    val scored = allEntries.filter { it.score > 0.0 }.distinctBy { it.anime.id }
    val finished = completed.size + dropped.size
    return AccountInsights(
        titles = stats?.titles?.takeIf { it > 0 } ?: statusCounts.values.sum(),
        episodes = stats?.episodes?.takeIf { it > 0 } ?: allEntries.sumOf { it.progress.coerceAtLeast(0) },
        minutesWatched = stats?.minutesWatched ?: 0L,
        meanScore = stats?.meanScore?.takeIf { it > 0.0 },
        statusCounts = statusCounts,
        topGenres = topGenres,
        recent = allEntries
            .filter { it.updatedAt > 0 }
            .distinctBy { it.anime.id }
            .sortedByDescending { it.updatedAt },
        formats = ranked(allMedia.mapNotNull { formatLabel(it.format) }, Int.MAX_VALUE),
        releaseYears = releaseYears,
        scoreSpread = if (scored.isEmpty()) {
            emptyList()
        } else {
            (1..10).map { value -> value.toString() to scored.count { it.score.roundToInt().coerceIn(1, 10) == value } }
        },
        completionRate = if (finished == 0) null else (completed.size * 100f / finished).roundToInt(),
        episodesToGo = (watching + rewatching).sumOf { entry ->
            val total = entry.anime.episodes ?: return@sumOf 0
            (total - entry.progress).coerceAtLeast(0)
        },
    )
}

fun formatLabel(format: String?): String? = when (format) {
    null -> null
    "TV" -> "TV"
    "TV_SHORT" -> "TV short"
    "MOVIE" -> "Movie"
    "OVA" -> "OVA"
    "ONA" -> "ONA"
    "SPECIAL" -> "Special"
    "MUSIC" -> "Music"
    else -> format.lowercase().replaceFirstChar { it.uppercase() }
}

/** A year as a column label narrow enough for twelve across: 2024 becomes '24. */
fun yearLabel(year: Int): String = "’" + (year % 100).toString().padStart(2, '0')

/** A decade as a column label: 1990 becomes 90s, 2000 becomes 00s. */
fun decadeLabel(decade: Int): String = (decade % 100).toString().padStart(2, '0') + "s"

/** Watch time as "2h 5m", dropping the part that is zero. */
fun watchTimeLabel(minutesWatched: Long): String {
    val minutes = minutesWatched.coerceAtLeast(0)
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours == 0L -> "${remainder}m"
        remainder == 0L -> "${hours}h"
        else -> "${hours}h ${remainder}m"
    }
}

/**
 * Watch time as the stat row prints it: hours once there are enough of them to count ("1,062 h"),
 * and hours-and-minutes before that, where "0 h" would say nothing.
 */
fun statWatchTime(minutes: Long): String =
    if (minutes >= 600) String.format(Locale.US, "%,d h", minutes / 60) else watchTimeLabel(minutes)

/** The account's public page on its own site, for the sheet's "View profile". */
fun accountProfileUrl(service: ListService, name: String): String {
    val encoded = java.net.URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")
    return when (service) {
        ListService.ANILIST -> "https://anilist.co/user/$encoded/"
        ListService.MAL -> "https://myanimelist.net/profile/$encoded"
    }
}

/** What one change to the list says, in Android's words. */
enum class ActivityKind { COMPLETED, WATCHING, REWATCHING, PAUSED, DROPPED, PLANNED }

fun activityKind(status: String): ActivityKind = when (status) {
    "COMPLETED" -> ActivityKind.COMPLETED
    "CURRENT" -> ActivityKind.WATCHING
    "REPEATING" -> ActivityKind.REWATCHING
    "PAUSED" -> ActivityKind.PAUSED
    "DROPPED" -> ActivityKind.DROPPED
    else -> ActivityKind.PLANNED
}

fun activityHeadline(entry: ListEntry, title: String): String = when (activityKind(entry.status)) {
    ActivityKind.COMPLETED -> "Completed $title"
    ActivityKind.WATCHING ->
        if (entry.progress > 0) "Watched episode ${entry.progress} of $title" else "Started $title"
    ActivityKind.REWATCHING -> "Rewatching $title"
    ActivityKind.PAUSED -> "Paused $title"
    ActivityKind.DROPPED -> "Dropped $title"
    ActivityKind.PLANNED -> "Planned $title"
}

/** "5 minutes ago" style text, as Android's DateUtils prints it, with the score appended when there is one. */
fun activityDetail(entry: ListEntry, nowMillis: Long = System.currentTimeMillis()): String {
    val ago = relativeTime(entry.updatedAt * 1000L, nowMillis)
    val rated = entry.score.takeIf { it > 0.0 }?.let { score ->
        "Rated ${if (score % 1.0 == 0.0) score.toInt().toString() else String.format(Locale.US, "%.1f", score)}/10"
    }
    return listOfNotNull(ago, rated).joinToString(" · ")
}

fun relativeTime(thenMillis: Long, nowMillis: Long): String {
    val seconds = ((nowMillis - thenMillis) / 1000).coerceAtLeast(0)
    fun unit(value: Long, word: String) = "$value $word${if (value == 1L) "" else "s"} ago"
    return when {
        seconds < 60 -> "0 minutes ago"
        seconds < 3_600 -> unit(seconds / 60, "minute")
        seconds < 86_400 -> unit(seconds / 3_600, "hour")
        seconds < 7 * 86_400 -> unit(seconds / 86_400, "day")
        else -> unit(seconds / (7 * 86_400), "week")
    }
}
