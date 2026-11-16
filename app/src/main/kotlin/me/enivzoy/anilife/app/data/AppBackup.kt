package me.enivzoy.anilife.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The backup file shared with the Android and TV apps.
 *
 * It is the same JSON document, field for field: a file written here restores there and the other
 * way round. Two things differ because the apps differ. Settings travel only for the options both
 * apps have (see [BackupSettings]), and logins are never written or applied here, because the
 * Windows app signs in to MyAnimeList on its own.
 */
data class BackupWatchlistEntry(
    val anilistId: Long,
    val title: String,
    val cover: String?,
    val format: String?,
    val averageScore: Int?,
    val addedAt: Long,
)

data class BackupHistoryEntry(
    val anilistId: Long,
    val title: String,
    val cover: String?,
    val episodeNumber: Double,
    val episodeTitle: String?,
    val provider: String,
    val category: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val fromRemote: Boolean,
)

data class BackupDocument(
    val createdAt: Long = 0L,
    val appVersion: String = "",
    val settings: JsonObject = JsonObject(emptyMap()),
    val watchlist: List<BackupWatchlistEntry> = emptyList(),
    val history: List<BackupHistoryEntry> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    /** Android-only sections. They are counted so a restore can say what it left out. */
    val customListCount: Int = 0,
    val watchedCount: Int = 0,
    /** Service names of any sealed logins in the file. They are never opened here. */
    val loginServices: List<String> = emptyList(),
) {
    /** "6 settings · 1,163 saved · 7 continue watching", skipping whatever the file has none of. */
    fun describe(): String = describeCounts(
        settings = settings.size,
        watchlist = watchlist.size,
        history = history.size,
        searchHistory = searchHistory.size,
        customLists = customListCount,
        watched = watchedCount,
        logins = loginServices.size,
    )
}

/** What a restore actually changed, which is rarely the same as what the file held. */
data class RestoreSummary(
    val settings: Int,
    val watchlist: Int,
    val history: Int,
    /** Things in the file this app has no place for, in plain words, for the result line. */
    val notRestored: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** What happened to the signed-in account's Plan to Watch, when this restore touched it. */
    val accountNote: String? = null,
) {
    fun describe(): String = buildList {
        if (settings > 0) add("$settings setting${plural(settings)}")
        if (watchlist > 0) add("$watchlist saved")
        if (history > 0) add("$history continue watching")
    }.joinToString(" · ").ifEmpty { "nothing new" }
}

class BackupFormatException(message: String) : IllegalArgumentException(message)

private fun describeCounts(
    settings: Int,
    watchlist: Int,
    history: Int,
    searchHistory: Int,
    customLists: Int,
    watched: Int,
    logins: Int,
): String = buildList {
    if (settings > 0) add("$settings setting${plural(settings)}")
    if (watchlist > 0) add("%,d saved".format(watchlist))
    if (customLists > 0) add("$customLists list${plural(customLists)}")
    if (watched > 0) add("$watched watched")
    if (history > 0) add("$history continue watching")
    if (searchHistory > 0) add("$searchHistory search${if (searchHistory == 1) "" else "es"}")
    if (logins > 0) add("$logins login${plural(logins)}")
}.joinToString(" · ").ifEmpty { "nothing" }

private fun plural(count: Int): String = if (count == 1) "" else "s"

object AppBackup {
    /** Stamped into every file and checked on the way back in, so a stray JSON is refused early. */
    const val FORMAT = "anilili-backup"
    const val VERSION = 1
    const val MAX_SOURCE_BYTES = 8L * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun encode(document: BackupDocument): String {
        val root = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("createdAt", document.createdAt)
            put("appVersion", document.appVersion)
            put("settings", document.settings)
            put("watchlist", buildJsonArray {
                document.watchlist.forEach { entry ->
                    add(buildJsonObject {
                        put("anilistId", entry.anilistId)
                        put("title", entry.title)
                        put("cover", entry.cover?.let(::JsonPrimitive) ?: JsonNull)
                        entry.format?.let { put("format", it) }
                        entry.averageScore?.let { put("averageScore", it) }
                        put("addedAt", entry.addedAt)
                    })
                }
            })
            put("history", buildJsonArray {
                document.history.forEach { entry ->
                    add(buildJsonObject {
                        put("anilistId", entry.anilistId)
                        put("title", entry.title)
                        put("cover", entry.cover?.let(::JsonPrimitive) ?: JsonNull)
                        put("episodeNumber", entry.episodeNumber)
                        entry.episodeTitle?.let { put("episodeTitle", it) }
                        put("provider", entry.provider)
                        put("category", entry.category)
                        put("positionMs", entry.positionMs)
                        put("durationMs", entry.durationMs)
                        put("updatedAt", entry.updatedAt)
                        put("watchedEpisodes", JsonArray(emptyList()))
                        put("fromRemote", entry.fromRemote)
                        put("resumePoints", JsonObject(emptyMap()))
                    })
                }
            })
            put("searchHistory", JsonArray(document.searchHistory.map(::JsonPrimitive)))
            put("customLists", JsonArray(emptyList()))
            put("watched", JsonArray(emptyList()))
            put("credentials", JsonNull)
        }
        return json.encodeToString(JsonElement.serializer(), root)
    }

    /** Parse [text], or throw a [BackupFormatException] with something worth showing the viewer. */
    fun decode(text: String): BackupDocument {
        if (text.isBlank()) throw BackupFormatException("The chosen file is empty.")
        val root = runCatching { Json.parseToJsonElement(text.removePrefix("﻿")) }.getOrNull() as? JsonObject
            ?: throw BackupFormatException(
                if (text.trimStart().startsWith("<")) {
                    "That looks like a MyAnimeList export. Use Import MyAnimeList XML for it."
                } else {
                    "The chosen file is not the JSON an Anilife backup is written as."
                },
            )
        val format = root.string("format")
        if (format != FORMAT) {
            throw BackupFormatException(
                if (format == null) {
                    "The chosen file is not an Anilife backup."
                } else {
                    "The chosen file declares itself as \"$format\" rather than $FORMAT."
                },
            )
        }
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: 1
        if (version > VERSION) {
            throw BackupFormatException(
                "That backup was written by a newer version of Anilife (format v$version). Update the app, then restore it.",
            )
        }
        val credentials = root["credentials"] as? JsonObject
        return BackupDocument(
            createdAt = root.long("createdAt") ?: 0L,
            appVersion = root.string("appVersion").orEmpty(),
            settings = root["settings"] as? JsonObject ?: JsonObject(emptyMap()),
            watchlist = (root["watchlist"] as? JsonArray).orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                BackupWatchlistEntry(
                    anilistId = item.long("anilistId") ?: return@mapNotNull null,
                    title = item.string("title") ?: return@mapNotNull null,
                    cover = item.string("cover"),
                    format = item.string("format"),
                    averageScore = item.long("averageScore")?.toInt(),
                    addedAt = item.long("addedAt") ?: 0L,
                )
            },
            history = (root["history"] as? JsonArray).orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                BackupHistoryEntry(
                    anilistId = item.long("anilistId") ?: return@mapNotNull null,
                    title = item.string("title") ?: return@mapNotNull null,
                    cover = item.string("cover"),
                    episodeNumber = (item["episodeNumber"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null,
                    episodeTitle = item.string("episodeTitle"),
                    provider = item.string("provider").orEmpty(),
                    category = item.string("category").orEmpty(),
                    positionMs = item.long("positionMs") ?: 0L,
                    durationMs = item.long("durationMs") ?: 0L,
                    updatedAt = item.long("updatedAt") ?: 0L,
                    fromRemote = (item["fromRemote"] as? JsonPrimitive)?.booleanOrNull ?: false,
                )
            },
            searchHistory = (root["searchHistory"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content },
            customListCount = (root["customLists"] as? JsonArray)?.size ?: 0,
            watchedCount = (root["watched"] as? JsonArray)?.size ?: 0,
            loginServices = (credentials?.get("services") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
    }

    fun fileName(date: LocalDate = LocalDate.now()): String =
        "Anilife-backup-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}.json"
}

/**
 * The settings both apps share, translated between their two vocabularies.
 *
 * The Android app's backup carries about thirty preferences; the Windows app has its own, mostly
 * different, set. Only the ones with a clear counterpart travel: autoplay, dub preference,
 * captions on by default, skipping intros and outros, the skip button length, the caption
 * language and whether saved titles are added to the signed-in account. Everything else on this PC is left exactly as it is.
 */
object BackupSettings {
    private val languageCodes = mapOf(
        "en" to "eng", "ja" to "jpn", "es" to "spa", "pt" to "por", "fr" to "fra",
        "de" to "deu", "it" to "ita", "ru" to "rus", "ar" to "ara", "zh" to "zho",
    )
    private val shortCodes = languageCodes.entries.associate { (short, long) -> long to short } + mapOf(
        "fre" to "fr", "ger" to "de", "chi" to "zh",
    )

    fun toBackup(settings: AppSettings): JsonObject = buildJsonObject {
        put("autoplay", settings.autoplayNextEpisode)
        put("prefer_dub", settings.audio == "dub")
        put("subtitles_by_default", settings.subtitlesByDefault)
        put("auto_skip_intro_outro", settings.autoSkipIntro && settings.autoSkipOutro)
        put("skip_button_seconds", settings.skipButtonSeconds)
        put("sync_saved_to_anilist", settings.syncSavedToAccount)
        languageCodes[settings.subtitleLanguage]?.let { put("preferred_subtitle_language", it) }
    }

    /** The settings with the file's shared options applied, and how many of them landed. */
    fun applyTo(current: AppSettings, values: JsonObject): Pair<AppSettings, Int> {
        var applied = 0
        var result = current
        fun bool(key: String): Boolean? = (values[key] as? JsonPrimitive)?.booleanOrNull
        bool("autoplay")?.let { result = result.copy(autoplayNextEpisode = it); applied++ }
        bool("prefer_dub")?.let { result = result.copy(audio = if (it) "dub" else "sub"); applied++ }
        bool("subtitles_by_default")?.let { result = result.copy(subtitlesByDefault = it); applied++ }
        bool("auto_skip_intro_outro")?.let {
            result = result.copy(autoSkipIntro = it, autoSkipOutro = it)
            applied++
        }
        bool("sync_saved_to_anilist")?.let { result = result.copy(syncSavedToAccount = it); applied++ }
        (values["skip_button_seconds"] as? JsonPrimitive)?.intOrNull?.let {
            result = result.copy(skipButtonSeconds = it.coerceIn(10, 300))
            applied++
        }
        (values["preferred_subtitle_language"] as? JsonPrimitive)?.contentOrNull
            ?.let { shortCodes[it.lowercase()] }
            ?.let { result = result.copy(subtitleLanguage = it); applied++ }
        return result to applied
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
