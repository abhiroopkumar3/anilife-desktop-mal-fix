package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.AnimeCard
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

internal data class PendingAniListProgress(val anime: AnimeCard, val episode: Int)

internal class AniListListStore(
    private val directory: File = appDataDirectory(),
) {
    fun read(userId: Long): List<AniListListEntry> {
        val file = listFile(userId)
        if (!file.isFile) return emptyList()
        val properties = Properties().apply { file.inputStream().use(::load) }
        return properties.stringPropertyNames()
            .asSequence()
            .filter { it.startsWith("entry.") && it.endsWith(".mediaId") }
            .mapNotNull { key ->
                val prefix = key.removeSuffix("mediaId")
                val mediaId = properties.getProperty(key)?.toLongOrNull() ?: return@mapNotNull null
                fun value(name: String) = properties.getProperty("$prefix$name")?.takeIf(String::isNotBlank)
                val title = value("title") ?: return@mapNotNull null
                val entryId = value("entryId")?.toLongOrNull() ?: return@mapNotNull null
                val status = AniListListStatus.parse(value("status")) ?: return@mapNotNull null
                AniListListEntry(
                    entryId = entryId,
                    anime = AnimeCard(
                        id = mediaId,
                        title = title,
                        titleRomaji = value("titleRomaji"),
                        titleEnglish = value("titleEnglish"),
                        titleNative = value("titleNative"),
                        malId = value("malId")?.toLongOrNull(),
                        cover = value("cover"),
                        color = value("color"),
                        format = value("format"),
                        status = value("mediaStatus"),
                        episodes = value("episodes")?.toIntOrNull(),
                        score = value("averageScore")?.toDoubleOrNull(),
                        year = value("year")?.toIntOrNull(),
                        season = value("season"),
                        genres = value("genres")?.split('|')?.filter(String::isNotBlank).orEmpty(),
                        isAdult = value("isAdult")?.toBooleanStrictOrNull() ?: false,
                    ),
                    status = status,
                    progress = value("progress")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                    score = value("score")?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
                    updatedAt = value("updatedAt")?.toLongOrNull() ?: 0L,
                )
            }
            .sortedBy { it.anime.title.lowercase() }
            .toList()
    }

    /** Forget the cached list of a signed-out account so the next one never sees it. */
    fun clear(userId: Long) {
        listFile(userId).delete()
    }

    fun write(userId: Long, entries: List<AniListListEntry>) {
        val properties = Properties()
        entries.forEach { entry ->
            val anime = entry.anime
            val prefix = "entry.${anime.id}."
            fun put(name: String, value: Any?) {
                if (value != null) properties.setProperty(prefix + name, value.toString())
            }
            put("entryId", entry.entryId)
            put("mediaId", anime.id)
            put("title", anime.title)
            put("titleRomaji", anime.titleRomaji)
            put("titleEnglish", anime.titleEnglish)
            put("titleNative", anime.titleNative)
            put("malId", anime.malId)
            put("cover", anime.cover)
            put("color", anime.color)
            put("format", anime.format)
            put("mediaStatus", anime.status)
            put("episodes", anime.episodes)
            put("averageScore", anime.score)
            put("year", anime.year)
            put("season", anime.season)
            put("genres", anime.genres.joinToString("|"))
            put("isAdult", anime.isAdult)
            put("status", entry.status.apiValue)
            put("progress", entry.progress)
            put("score", entry.score)
            put("updatedAt", entry.updatedAt)
        }
        writeProperties(listFile(userId), properties)
    }

    fun pendingProgress(userId: Long): Map<Long, PendingAniListProgress> {
        val file = progressFile(userId)
        if (!file.isFile) return emptyMap()
        val properties = Properties().apply { file.inputStream().use(::load) }
        return properties.stringPropertyNames().mapNotNull { key ->
            if (!key.startsWith("media.") || !key.endsWith(".progress")) return@mapNotNull null
            val prefix = key.removeSuffix("progress")
            val mediaId = key.removePrefix("media.").removeSuffix(".progress").toLongOrNull() ?: return@mapNotNull null
            val progress = properties.getProperty(key)?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val title = properties.getProperty("${prefix}title")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            fun value(name: String) = properties.getProperty("$prefix$name")?.takeIf(String::isNotBlank)
            mediaId to PendingAniListProgress(
                AnimeCard(
                    id = mediaId,
                    title = title,
                    titleRomaji = value("titleRomaji"),
                    titleEnglish = value("titleEnglish"),
                    titleNative = value("titleNative"),
                    malId = value("malId")?.toLongOrNull(),
                    cover = value("cover"),
                    color = value("color"),
                    format = value("format"),
                    status = value("mediaStatus"),
                    episodes = value("episodes")?.toIntOrNull(),
                    score = value("averageScore")?.toDoubleOrNull(),
                    year = value("year")?.toIntOrNull(),
                    season = value("season"),
                    genres = value("genres")?.split('|')?.filter(String::isNotBlank).orEmpty(),
                    isAdult = value("isAdult")?.toBooleanStrictOrNull() ?: false,
                ),
                progress,
            )
        }.toMap()
    }

    fun enqueueProgress(userId: Long, anime: AnimeCard, episode: Int) {
        require(anime.id > 0 && episode > 0)
        val pending = pendingProgress(userId).toMutableMap()
        val previous = pending[anime.id]
        val properties = Properties().apply {
            pending.forEach { (id, item) -> putProgress(id, item.anime, item.episode) }
            putProgress(anime.id, anime, maxOf(previous?.episode ?: 0, episode))
        }
        writeProperties(progressFile(userId), properties)
    }

    fun removePendingProgress(userId: Long, mediaId: Long) {
        val pending = pendingProgress(userId).toMutableMap().apply { remove(mediaId) }
        val properties = Properties().apply {
            pending.forEach { (id, item) -> putProgress(id, item.anime, item.episode) }
        }
        writeProperties(progressFile(userId), properties)
    }

    private fun writeProperties(file: File, properties: Properties) {
        if (!directory.exists() && !directory.mkdirs()) error("Couldn't create the AniLife data folder.")
        val temporary = File(directory, "${file.name}.tmp")
        temporary.outputStream().use { properties.store(it, "AniLife AniList data") }
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: IOException) {
            temporary.delete()
            throw failure
        }
    }

    private fun Properties.putProgress(mediaId: Long, anime: AnimeCard, episode: Int) {
        val prefix = "media.$mediaId."
        setProperty("${prefix}progress", episode.toString())
        setProperty("${prefix}title", anime.title)
        fun put(name: String, value: Any?) {
            if (value != null) setProperty(prefix + name, value.toString())
        }
        put("titleRomaji", anime.titleRomaji)
        put("titleEnglish", anime.titleEnglish)
        put("titleNative", anime.titleNative)
        put("malId", anime.malId)
        put("cover", anime.cover)
        put("color", anime.color)
        put("format", anime.format)
        put("mediaStatus", anime.status)
        put("episodes", anime.episodes)
        put("averageScore", anime.score)
        put("year", anime.year)
        put("season", anime.season)
        put("genres", anime.genres.joinToString("|"))
        put("isAdult", anime.isAdult)
    }

    private fun listFile(userId: Long) = File(directory, "anilist-list-$userId.properties")
    private fun progressFile(userId: Long) = File(directory, "anilist-progress-$userId.properties")

    private companion object {
        fun appDataDirectory(): File {
            val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(::File)
                ?: File(System.getProperty("user.home"), ".local/share")
            return File(base, "AniLife")
        }
    }
}
