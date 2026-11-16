package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.AnimeCard

/**
 * One row of a MyAnimeList list, as Android reads it (`MalClient.MalListEntry`). [status] is already
 * in AniList's words, so everything after this point treats both services the same way.
 */
data class MalListRow(
    val malId: Long,
    val title: String,
    val totalEpisodes: Int?,
    val status: String,
    val progress: Int,
    val score: Double,
    /** Epoch seconds, 0 when MAL's stamp could not be read. */
    val updatedAt: Long,
    val tags: List<String>,
)

/** `MalClient.anilistStatus`: a rewatch is `completed` on MAL with `is_rewatching` set. */
fun malToAniListStatus(status: String?, isRewatching: Boolean): String = when {
    isRewatching -> "REPEATING"
    status == "watching" -> "CURRENT"
    status == "completed" -> "COMPLETED"
    status == "on_hold" -> "PAUSED"
    status == "dropped" -> "DROPPED"
    else -> "PLANNING"
}

/** `MalClient.malStatus`: a rewatch is stored as `completed`, which is how MAL's own editor does it. */
fun aniListToMalStatus(status: String): String = when (status) {
    "CURRENT" -> "watching"
    "COMPLETED", "REPEATING" -> "completed"
    "PAUSED" -> "on_hold"
    "DROPPED" -> "dropped"
    else -> "plan_to_watch"
}

/**
 * Seconds since the epoch for a MAL timestamp, or 0. MAL writes a numeric offset
 * (`2018-04-25T22:23:11+00:00`), so `OffsetDateTime` is tried before `Instant`.
 */
fun malEpochSeconds(raw: String?): Long {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return 0L
    return runCatching { java.time.OffsetDateTime.parse(value).toEpochSecond() }
        .recoverCatching { java.time.Instant.parse(value).epochSecond }
        .getOrDefault(0L)
}

/** AniList's episode count when it has a real one, else MAL's. */
fun remoteEpisodeCeiling(anilistEpisodes: Int?, malEpisodes: Int?): Int? =
    anilistEpisodes?.takeIf { it > 0 } ?: malEpisodes?.takeIf { it > 0 }

/** A watched count from another service, held to the title's known length. */
fun clampRemoteProgress(progress: Int, totalEpisodes: Int?): Int = when {
    totalEpisodes == null || totalEpisodes <= 0 -> progress
    else -> progress.coerceAtMost(totalEpisodes)
}

/**
 * The MAL list as [ListEntry]s. [cardsByMalId] holds the AniList card for each MAL id that AniList
 * knows; a title it does not know is dropped, as on Android. The MAL episode total fills a missing
 * AniList count and the watched count is held to the season's length, so a mismatched season split
 * cannot report "episode 25 of 24".
 */
fun malRowsToEntries(rows: List<MalListRow>, cardsByMalId: Map<Long, AnimeCard>): List<ListEntry> =
    rows.mapNotNull { row ->
        val card = cardsByMalId[row.malId] ?: return@mapNotNull null
        val total = remoteEpisodeCeiling(card.episodes, row.totalEpisodes)
        // The MAL id the row came with is the one a tag write must go to, so the card carries it.
        val withMalId = if (card.malId == row.malId) card else card.copy(malId = row.malId)
        ListEntry(
            anime = if (total == withMalId.episodes) withMalId else withMalId.copy(episodes = total),
            status = row.status,
            progress = clampRemoteProgress(row.progress, total),
            score = row.score,
            updatedAt = row.updatedAt,
            customLists = row.tags,
        )
    }

/** AniList's own list rows in the shared model. */
fun aniListEntriesToEntries(entries: List<AniListListEntry>): List<ListEntry> =
    entries.map { entry ->
        ListEntry(
            anime = entry.anime,
            status = entry.status.apiValue,
            progress = entry.progress,
            score = entry.score,
            updatedAt = entry.updatedAt,
            hiddenFromStatusLists = entry.hidden,
            customLists = entry.customLists,
        )
    }

/** The fields Android asks MAL for on every list row. */
const val MAL_LIST_FIELDS =
    "list_status{status,score,num_episodes_watched,is_rewatching,num_times_rewatched,updated_at,tags},num_episodes"

/** One page of MAL's list response: its rows, and the URL of the next page when there is one. */
fun parseMalListPage(body: String): Pair<List<MalListRow>, String?> {
    val root = kotlinx.serialization.json.Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
        ?: return emptyList<MalListRow>() to null
    fun kotlinx.serialization.json.JsonElement?.text() = (this as? kotlinx.serialization.json.JsonPrimitive)?.content
    val rows = (root["data"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { item ->
        val obj = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        val node = obj["node"] as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        val list = obj["list_status"] as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        MalListRow(
            malId = node["id"].text()?.toLongOrNull() ?: return@mapNotNull null,
            title = node["title"].text().orEmpty(),
            totalEpisodes = node["num_episodes"].text()?.toIntOrNull()?.takeIf { it > 0 },
            status = malToAniListStatus(list["status"].text(), list["is_rewatching"].text() == "true"),
            progress = list["num_episodes_watched"].text()?.toIntOrNull() ?: 0,
            score = list["score"].text()?.toDoubleOrNull() ?: 0.0,
            updatedAt = malEpochSeconds(list["updated_at"].text()),
            tags = (list["tags"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { it.text()?.takeIf(String::isNotBlank) },
        )
    }
    val next = ((root["paging"] as? kotlinx.serialization.json.JsonObject)?.get("next")).text()
    return rows to next
}
