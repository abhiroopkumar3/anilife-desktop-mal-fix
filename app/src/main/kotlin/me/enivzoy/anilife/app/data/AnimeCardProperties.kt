package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.AnimeCard
import me.enivzoy.anilife.core.model.NextAiring
import java.util.Properties

/** The way a card is kept in a `.properties` file, shared by the saved library and the custom lists. */
internal fun Properties.putCard(prefix: String, anime: AnimeCard) {
    fun put(name: String, value: Any?) {
        if (value != null) setProperty(prefix + name, value.toString())
    }
    put("title", anime.title)
    put("titleRomaji", anime.titleRomaji)
    put("titleEnglish", anime.titleEnglish)
    put("titleNative", anime.titleNative)
    put("malId", anime.malId)
    put("cover", anime.cover)
    put("color", anime.color)
    put("banner", anime.banner)
    put("format", anime.format)
    put("status", anime.status)
    put("episodes", anime.episodes)
    put("score", anime.score)
    put("year", anime.year)
    put("season", anime.season)
    put("genres", anime.genres.joinToString("|"))
    put("airingEpisode", anime.nextAiring?.episode)
    put("airingAt", anime.nextAiring?.airingAt)
    put("isAdult", anime.isAdult)
}

/** The card kept under [prefix] (which ends in a dot) for [id], or null when it has no title. */
internal fun Properties.readCard(prefix: String, id: Long): AnimeCard? {
    fun value(name: String) = getProperty("$prefix$name")?.takeIf(String::isNotBlank)
    fun intValue(name: String) = value(name)?.toIntOrNull()
    val title = value("title") ?: return null
    val airingEpisode = intValue("airingEpisode")
    val airingAt = value("airingAt")?.toLongOrNull()
    return AnimeCard(
        id = id,
        title = title,
        titleRomaji = value("titleRomaji"),
        titleEnglish = value("titleEnglish"),
        titleNative = value("titleNative"),
        malId = value("malId")?.toLongOrNull(),
        cover = value("cover"),
        color = value("color"),
        banner = value("banner"),
        format = value("format"),
        status = value("status"),
        episodes = intValue("episodes"),
        score = value("score")?.toDoubleOrNull(),
        year = intValue("year"),
        season = value("season"),
        genres = value("genres")?.split('|')?.filter(String::isNotBlank).orEmpty(),
        nextAiring = if (airingEpisode != null && airingAt != null) NextAiring(airingEpisode, airingAt) else null,
        isAdult = value("isAdult")?.toBooleanStrictOrNull() ?: false,
    )
}
