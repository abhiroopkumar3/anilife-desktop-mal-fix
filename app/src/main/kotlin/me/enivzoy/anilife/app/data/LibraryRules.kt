package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.AnimeCard

/*
 * The Library page's rules, ported from the Android app's ProfileScreen.kt so both apps arrange a
 * list the same way. Everything here is a plain function over plain data: no screen, no network.
 * See PARITY.md for the Android function each one answers to.
 */

/**
 * A list the page can show. Several are shown at once, so the labels come in two lengths: the
 * heading over a section, and the shorter one a chip has room for.
 */
enum class LibraryView(val label: String, val chipLabel: String = label) {
    WATCHLIST("My watchlist", "Watchlist"),
    WATCHING("Watching"),
    REWATCHING("Re-watching"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    DROPPED("Dropped"),
}

enum class LibrarySort(val label: String) {
    LAST_MODIFIED("Last modified"),
    TITLE("Title (A–Z)"),
    SCORE("Highest score"),
    PROGRESS("Most progress"),
    DEFAULT("Default"),
}

/** What a poster can show besides the cover. Android keeps the ones switched off, so a new one is on by default. */
enum class PosterElement(val label: String) {
    SCORE("Score"),
    NEW_EPISODES("New episodes"),
    PROGRESS("Progress"),
    TITLE("Title"),
    DETAILS("Status and format"),
}

fun posterElementsOff(names: String): Set<PosterElement> =
    names.split(',').mapNotNull { name -> PosterElement.entries.firstOrNull { it.name == name.trim() } }.toSet()

fun posterElementNames(off: Set<PosterElement>): String =
    PosterElement.entries.filter { it in off }.joinToString(",") { it.name }

/**
 * One title on the account's list, in one shape for MyAnimeList and AniList alike. Statuses use
 * AniList's words (CURRENT, REPEATING, PLANNING, PAUSED, COMPLETED, DROPPED), as Android's does.
 */
data class ListEntry(
    val anime: AnimeCard,
    val status: String,
    val progress: Int = 0,
    val score: Double = 0.0,
    /** Epoch seconds. */
    val updatedAt: Long = 0L,
    /** AniList's "only in my custom lists" flag; such a title is on no status list. */
    val hiddenFromStatusLists: Boolean = false,
    /** Custom list names (AniList) or tags (MyAnimeList) this title is in. */
    val customLists: List<String> = emptyList(),
)

/** A title saved on this PC, and when (epoch milliseconds). */
data class LocalSave(val anime: AnimeCard, val addedAt: Long = 0L)

/** One poster's worth of data, however the title got on the page. */
data class LibraryCardData(
    val id: Long,
    val title: String,
    val cover: String?,
    val format: String?,
    val airingStatus: String?,
    val status: String?,
    val userScore: Double?,
    val averageScore: Double?,
    val progress: Int?,
    val totalEpisodes: Int?,
    val updatedAt: Long = 0L,
    /** AniList's next scheduled broadcast, which is what says how much of an airing series is out. */
    val nextEpisodeNumber: Int? = null,
    /** Epoch seconds; null for anything without a scheduled episode. */
    val nextEpisodeAiringAt: Long? = null,
)

fun ListEntry.toCard(): LibraryCardData = LibraryCardData(
    id = anime.id,
    title = anime.title,
    cover = anime.cover,
    format = anime.format,
    airingStatus = anime.status,
    status = status,
    userScore = score.takeIf { it > 0 },
    averageScore = anime.score,
    progress = progress,
    totalEpisodes = anime.episodes,
    updatedAt = updatedAt,
    nextEpisodeNumber = anime.nextAiring?.episode,
    nextEpisodeAiringAt = anime.nextAiring?.airingAt,
)

/** The status sections ask for exactly these entries: the ones filed away in custom lists only are on no list. */
fun cardsForStatus(entries: List<ListEntry>, status: String): List<LibraryCardData> =
    entries.filterNot { it.hiddenFromStatusLists }
        .filter { it.status == status }
        .map(ListEntry::toCard)
        .distinctBy(LibraryCardData::id)

/**
 * The saved titles with the account's Planning list: this PC's saves first, wearing the account's
 * status, score and progress where it has them, then Planning titles not saved here. One card per
 * title, because a repeated id is fatal as a lazy-list key.
 */
fun buildCombinedWatchlist(
    planning: List<ListEntry>,
    local: List<LocalSave>,
    /** The episode this PC last played, per title, for a save the account knows nothing about. */
    historyEpisodes: Map<Long, Int> = emptyMap(),
): List<LibraryCardData> {
    // A title filed in custom lists only is on no status list, Planning included.
    val account = planning.filter { it.status == "PLANNING" && !it.hiddenFromStatusLists }.distinctBy { it.anime.id }
    val byId = account.associateBy { it.anime.id }
    val localIds = local.mapTo(mutableSetOf()) { it.anime.id }
    return buildList {
        local.distinctBy { it.anime.id }.forEach { saved ->
            val entry = byId[saved.anime.id]
            val anime = entry?.anime ?: saved.anime
            add(
                LibraryCardData(
                    id = saved.anime.id,
                    title = anime.title,
                    cover = anime.cover ?: saved.anime.cover,
                    format = anime.format ?: saved.anime.format,
                    airingStatus = entry?.anime?.status,
                    status = entry?.status,
                    userScore = entry?.score?.takeIf { it > 0 },
                    averageScore = anime.score ?: saved.anime.score,
                    progress = entry?.progress ?: historyEpisodes[saved.anime.id],
                    totalEpisodes = entry?.anime?.episodes,
                    updatedAt = entry?.updatedAt?.takeIf { it > 0 } ?: (saved.addedAt / 1000L),
                    nextEpisodeNumber = entry?.anime?.nextAiring?.episode,
                    nextEpisodeAiringAt = entry?.anime?.nextAiring?.airingAt,
                ),
            )
        }
        account.forEach { entry ->
            if (entry.anime.id in localIds) return@forEach
            add(entry.toCard())
        }
    }.distinctBy(LibraryCardData::id)
}

/**
 * One custom list's cards. The list holds what it needs to draw a title; where the account also
 * tracks it on a status list, that status, score and progress are shown the way every other section
 * shows them. A title filed in custom lists only is on no status list, so it shows none (Android
 * reads the status lists, which leave those out).
 */
fun customListCards(
    list: CustomList,
    account: List<ListEntry>,
    historyEpisodes: Map<Long, Int> = emptyMap(),
): List<LibraryCardData> {
    val tracked = account.filterNot { it.hiddenFromStatusLists }.associateBy { it.anime.id }
    return list.entries.distinctBy { it.anime.id }.map { saved ->
        val entry = tracked[saved.anime.id]
        val anime = entry?.anime ?: saved.anime
        LibraryCardData(
            id = saved.anime.id,
            title = anime.title,
            cover = anime.cover ?: saved.anime.cover,
            format = anime.format ?: saved.anime.format,
            airingStatus = entry?.anime?.status,
            status = entry?.status,
            userScore = entry?.score?.takeIf { it > 0 },
            averageScore = anime.score ?: saved.anime.score,
            progress = entry?.progress ?: historyEpisodes[saved.anime.id],
            totalEpisodes = entry?.anime?.episodes,
            updatedAt = saved.addedAt / 1000L,
            nextEpisodeNumber = entry?.anime?.nextAiring?.episode,
            nextEpisodeAiringAt = entry?.anime?.nextAiring?.airingAt,
        )
    }
}

/** The account's Completed list. Android also adds titles marked watched on the device; Windows has no such mark. */
fun completedCards(entries: List<ListEntry>): List<LibraryCardData> = cardsForStatus(entries, "COMPLETED")

/** One list's cards, filtered and ordered the way its section shows them. */
fun libraryCards(
    source: List<LibraryCardData>,
    format: String?,
    airing: String?,
    titleFilter: String,
    sort: LibrarySort,
): List<LibraryCardData> {
    val filtered = source.filter { entry ->
        (format == null || entry.format == format) &&
            (airing == null || entry.airingStatus == airing) &&
            (titleFilter.isBlank() || entry.title.contains(titleFilter, ignoreCase = true))
    }
    return when (sort) {
        LibrarySort.LAST_MODIFIED -> filtered.sortedByDescending { it.updatedAt }
        LibrarySort.TITLE -> filtered.sortedBy { it.title.lowercase() }
        LibrarySort.SCORE -> filtered.sortedByDescending { it.userScore ?: 0.0 }
        LibrarySort.PROGRESS -> filtered.sortedByDescending { it.progress ?: 0 }
        LibrarySort.DEFAULT -> filtered
    }
}

/** What a viewer sees before choosing any list: what they are part-way through, and what they saved. */
fun defaultLibraryViewNames(): String =
    listOf(LibraryView.WATCHING, LibraryView.WATCHLIST).joinToString(",") { it.name }

/**
 * The chosen lists, read back out of the one string that holds them. Sections come out in enum
 * order however they were tapped, and a list this account cannot offer is dropped rather than left
 * as a heading over nothing.
 */
fun libraryViewsFrom(names: String, available: List<LibraryView>): List<LibraryView> {
    val chosen = names.split(',').filterTo(mutableSetOf()) { it.isNotBlank() }
    val views = available.filter { it.name in chosen }
    // Everything chosen is unavailable: falling back keeps a list on the page, which is a different
    // state from having deliberately turned them all off.
    return if (views.isEmpty() && chosen.isNotEmpty()) available.take(1) else views
}

/** Adds [view] to [names], or removes it when it is already there. Also used for expanded sections. */
fun toggledLibraryViews(names: String, view: LibraryView): String {
    val chosen = names.split(',').filterTo(mutableSetOf()) { it.isNotBlank() }
    if (!chosen.add(view.name)) chosen.remove(view.name)
    return LibraryView.entries.filter { it.name in chosen }.joinToString(",") { it.name }
}

/** Lists a title can be behind on: the ones the viewer is partway through, not finished or dropped. */
private val NEW_EPISODE_LIST_STATUSES = setOf("CURRENT", "REPEATING", "PAUSED")

/** Broadcast to watchable: an episode is only counted once the streaming sites have plausibly picked it up. */
private const val AIRED_BUFFER_SEC = 90L * 60L

/**
 * How many broadcast episodes this title is ahead of the viewer, or null for no badge. A save known
 * only to this PC counts on its local progress, once the viewer has started it.
 */
fun newEpisodeCount(entry: LibraryCardData, nowSec: Long): Int? {
    val watched = entry.progress ?: 0
    val status = entry.status?.uppercase()
    if (status == null) {
        if (watched <= 0) return null
    } else if (status !in NEW_EPISODE_LIST_STATUSES) {
        return null
    }
    val aired = airedEpisodeCount(entry, nowSec) ?: return null
    return (aired - watched).takeIf { it > 0 }
}

/** Episodes broadcast so far, counted back from the next scheduled one. */
private fun airedEpisodeCount(entry: LibraryCardData, nowSec: Long): Int? {
    val next = entry.nextEpisodeNumber?.takeIf { it > 0 } ?: return null
    val airingAt = entry.nextEpisodeAiringAt?.takeIf { it > 0 }
    val aired = if (airingAt != null && nowSec >= airingAt + AIRED_BUFFER_SEC) next else next - 1
    return aired.takeIf { it > 0 }
}
