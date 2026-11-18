package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.AnimeCard

/**
 * AniList cards already fetched for the MyAnimeList list, so a pull does not download the whole
 * list again every time.
 *
 * Only a title AniList lists as finished may be reused, and only for [TTL_MS]. Anything airing,
 * upcoming or on hiatus is fetched fresh every time, because its episode count and next air time
 * are what Continue Watching and the "NEW" badges read. Kept in memory for the life of the app.
 */
class MalCardCache(private val now: () -> Long = System::currentTimeMillis) {
    private val held = mutableMapOf<Long, Pair<AnimeCard, Long>>()

    /** The held card for [id], or null when it must be fetched: never held, not finished, or expired. */
    @Synchronized
    fun reusable(id: Long): AnimeCard? {
        val (card, at) = held[id] ?: return null
        if (card.status != FINISHED || now() - at >= TTL_MS) return null
        return card
    }

    @Synchronized
    fun remember(cards: List<AnimeCard>) {
        val at = now()
        cards.forEach { held[it.id] = it to at }
    }

    companion object {
        const val FINISHED = "FINISHED"
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
