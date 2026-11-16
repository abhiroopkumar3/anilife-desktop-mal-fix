package me.enivzoy.anilife.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.enivzoy.anilife.core.model.AnimeCard
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

enum class AniListScoreFormat(val maximum: Double, val step: Double) {
    POINT_100(100.0, 1.0),
    POINT_10(10.0, 1.0),
    POINT_10_DECIMAL(10.0, 0.1),
    POINT_5(5.0, 1.0),
    POINT_3(3.0, 1.0),
    ;

    companion object {
        fun parse(value: String?): AniListScoreFormat =
            entries.firstOrNull { it.name == value } ?: POINT_100
    }
}

enum class AniListListStatus(val apiValue: String, val label: String) {
    CURRENT("CURRENT", "Watching"),
    PLANNING("PLANNING", "Planning"),
    COMPLETED("COMPLETED", "Completed"),
    PAUSED("PAUSED", "Paused"),
    DROPPED("DROPPED", "Dropped"),
    REPEATING("REPEATING", "Rewatching"),
    ;

    companion object {
        fun parse(value: String?): AniListListStatus? =
            entries.firstOrNull { it.apiValue == value }
    }
}

data class AniListUser(
    val id: Long,
    val name: String,
    val avatar: String?,
    val scoreFormat: AniListScoreFormat,
)

data class AniListListEntry(
    val entryId: Long,
    val anime: AnimeCard,
    val status: AniListListStatus,
    val progress: Int,
    val score: Double,
    val updatedAt: Long,
)

internal class AniListAccountApi(
    private val request: suspend (String, String, JsonObject) -> JsonObject = ::networkRequest,
) {
    suspend fun viewer(token: String): AniListUser {
        val data = request(token, VIEWER_QUERY, buildJsonObject {})
        val viewer = data["Viewer"] as? JsonObject ?: error("AniList returned no user profile.")
        return AniListUser(
            id = viewer.long("id") ?: error("AniList returned no user ID."),
            name = viewer.string("name")?.takeIf(String::isNotBlank) ?: "AniList user",
            avatar = (viewer["avatar"] as? JsonObject)?.string("large"),
            scoreFormat = AniListScoreFormat.parse(
                ((viewer["mediaListOptions"] as? JsonObject)?.string("scoreFormat")),
            ),
        )
    }

    suspend fun list(token: String, userId: Long): List<AniListListEntry> {
        require(userId in 1..Int.MAX_VALUE.toLong()) { "AniList returned an invalid user ID." }
        val result = mutableListOf<AniListListEntry>()
        var page = 1
        var hasNextPage: Boolean
        do {
            val data = request(
                token,
                LIST_QUERY,
                buildJsonObject {
                    put("userId", userId)
                    put("page", page)
                },
            )
            val pageData = data["Page"] as? JsonObject ?: error("AniList returned no list page.")
            val pageInfo = pageData["pageInfo"] as? JsonObject
            val entries = pageData["mediaList"] as? JsonArray ?: JsonArray(emptyList())
            result += entries.mapNotNull { item ->
                (item as? JsonObject)?.let(::parseEntry)
            }
            hasNextPage = pageInfo?.boolean("hasNextPage") ?: false
            page++
        } while (hasNextPage)
        return result.distinctBy(AniListListEntry::entryId)
    }

    /** The account's activity per day, as AniList's own profile heatmap draws it. Empty when unreadable. */
    suspend fun activityHistory(token: String, userId: Long): Map<Long, Int> =
        parseActivityHistory(request(token, ACTIVITY_QUERY, buildJsonObject { put("id", userId) }))

    suspend fun entry(token: String, userId: Long, mediaId: Long): AniListListEntry? {
        val data = request(
            token,
            ENTRY_QUERY,
            buildJsonObject {
                put("userId", userId)
                put("mediaId", mediaId)
            },
        )
        return (data["MediaList"] as? JsonObject)?.let(::parseEntry)
    }

    suspend fun save(
        token: String,
        entryId: Long?,
        mediaId: Long,
        status: AniListListStatus,
        progress: Int,
        score: Double,
    ): AniListListEntry {
        require(mediaId in 1..Int.MAX_VALUE.toLong()) { "Invalid AniList media ID." }
        require(progress >= 0) { "Episode progress cannot be negative." }
        require(score.isFinite() && score in 0.0..100.0) { "Score must be between 0 and 100." }
        val data = request(
            token,
            SAVE_ENTRY_MUTATION,
            buildJsonObject {
                // AniList rejects an explicit id: null ("The selected id is invalid."), so the
                // variable must be omitted entirely when creating a new list entry.
                entryId?.let { put("entryId", it) }
                put("mediaId", mediaId)
                put("status", status.apiValue)
                put("progress", progress)
                put("score", score)
            },
        )
        return (data["SaveMediaListEntry"] as? JsonObject)?.let(::parseEntry)
            ?: error("AniList did not return the saved list entry.")
    }

    suspend fun delete(token: String, entryId: Long): Boolean {
        val data = request(
            token,
            DELETE_ENTRY_MUTATION,
            buildJsonObject { put("entryId", entryId) },
        )
        val deleted = data["DeleteMediaListEntry"] as? JsonObject
            ?: error("AniList did not confirm the list entry deletion.")
        return deleted.boolean("deleted") ?: false
    }

    private fun parseEntry(json: JsonObject): AniListListEntry? {
        val media = json["media"] as? JsonObject ?: return null
        val id = json.long("id") ?: return null
        val anime = media.toAnimeCard() ?: return null
        return AniListListEntry(
            entryId = id,
            anime = anime,
            status = AniListListStatus.parse(json.string("status")) ?: AniListListStatus.PLANNING,
            progress = json.int("progress")?.coerceAtLeast(0) ?: 0,
            score = json.double("score")?.coerceAtLeast(0.0) ?: 0.0,
            updatedAt = json.long("updatedAt") ?: 0L,
        )
    }

    private fun JsonObject.toAnimeCard(): AnimeCard? {
        val id = long("id") ?: return null
        val title = this["title"] as? JsonObject
        val cover = this["coverImage"] as? JsonObject
        return AnimeCard(
            id = id,
            title = title?.string("userPreferred")
                ?: title?.string("english")
                ?: title?.string("romaji")
                ?: return null,
            titleRomaji = title?.string("romaji"),
            titleEnglish = title?.string("english"),
            titleNative = title?.string("native"),
            malId = long("idMal"),
            cover = cover?.string("extraLarge") ?: cover?.string("large"),
            color = cover?.string("color"),
            format = string("format"),
            status = string("status"),
            episodes = int("episodes"),
            score = int("averageScore")?.toDouble(),
            year = int("seasonYear"),
            season = string("season"),
            genres = (this["genres"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty(),
            isAdult = boolean("isAdult") ?: false,
        )
    }

    internal companion object {
        const val ENDPOINT = "https://graphql.anilist.co"
        val json = Json { ignoreUnknownKeys = true }
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

        const val MEDIA_FIELDS = """
            id idMal isAdult format status episodes averageScore season seasonYear genres
            title { romaji english native userPreferred }
            coverImage { extraLarge large color }
        """

        const val ENTRY_FIELDS = """
            id status progress score updatedAt
            media { $MEDIA_FIELDS }
        """

        const val ACTIVITY_QUERY = """
            query (${'$'}id: Int) { User(id: ${'$'}id) { stats { activityHistory { date amount } } } }
        """

        const val VIEWER_QUERY = """
            query {
              Viewer {
                id
                name
                avatar { large }
                mediaListOptions { scoreFormat }
              }
            }
        """

        const val LIST_QUERY = """
            query (${'$'}userId: Int!, ${'$'}page: Int!) {
              Page(page: ${'$'}page, perPage: 50) {
                pageInfo { hasNextPage }
                mediaList(userId: ${'$'}userId, type: ANIME, sort: UPDATED_TIME_DESC) {
                  $ENTRY_FIELDS
                }
              }
            }
        """

        const val ENTRY_QUERY = """
            query (${'$'}userId: Int!, ${'$'}mediaId: Int!) {
              MediaList(userId: ${'$'}userId, mediaId: ${'$'}mediaId) {
                $ENTRY_FIELDS
              }
            }
        """

        const val SAVE_ENTRY_MUTATION = """
            mutation (
              ${'$'}entryId: Int,
              ${'$'}mediaId: Int!,
              ${'$'}status: MediaListStatus!,
              ${'$'}progress: Int!,
              ${'$'}score: Float!
            ) {
              SaveMediaListEntry(
                id: ${'$'}entryId,
                mediaId: ${'$'}mediaId,
                status: ${'$'}status,
                progress: ${'$'}progress,
                score: ${'$'}score
              ) { $ENTRY_FIELDS }
            }
        """

        const val DELETE_ENTRY_MUTATION = """
            mutation (${'$'}entryId: Int!) {
              DeleteMediaListEntry(id: ${'$'}entryId) { deleted }
            }
        """

        suspend fun networkRequest(token: String, query: String, variables: JsonObject): JsonObject =
            withContext(Dispatchers.IO) {
                val payload = buildJsonObject {
                    put("query", query)
                    put("variables", variables)
                }
                val httpRequest = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer $token")
                    .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(JsonObject.serializer(), payload)))
                    .build()
                val response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString())
                parseGraphQlResponse(response.statusCode(), response.body())
            }

        /**
         * AniList answers a lookup for a list entry that does not exist with HTTP 404 and an
         * errors array of "Not Found." while still returning a data object whose field is null.
         * That is the valid "not on the list" answer, so pass it through instead of throwing;
         * every other error response stays fatal.
         */
        internal fun parseGraphQlResponse(statusCode: Int, body: String): JsonObject {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }
                .getOrElse { error("AniList returned an unreadable response.") }
            val errorObjects = (root["errors"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val messages = errorObjects.mapNotNull { it.string("message") }
            val data = root["data"] as? JsonObject
            val missingLookup = data != null &&
                statusCode == 404 &&
                messages.isNotEmpty() &&
                messages.all { it.startsWith("Not Found") } &&
                errorObjects.all { it.int("status") == 404 }
            if (messages.isNotEmpty() && !missingLookup) {
                error(messages.first().take(300))
            }
            if (statusCode !in 200..299 && !missingLookup) {
                error(messages.firstOrNull()?.take(300) ?: "AniList request failed (HTTP $statusCode).")
            }
            return data ?: error("AniList returned no data.")
        }
    }
}

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.long(name: String): Long? =
    this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

private fun JsonObject.int(name: String): Int? =
    this[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

private fun JsonObject.double(name: String): Double? =
    this[name]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()

private fun JsonObject.boolean(name: String): Boolean? =
    this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
