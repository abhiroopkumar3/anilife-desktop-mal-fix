package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.AnimeCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

data class ContinueProgress(
    val episode: Double,
    val position: Double,
    val duration: Double,
)

data class ContinueEntry(
    val anime: AnimeCard,
    val progress: ContinueProgress,
    val updatedAt: Long,
    /** Episodes actually finished, as on Android's `HistoryEntry.watchedEpisodes`. */
    val watched: Set<Double> = emptySet(),
    /** Seeded from the account rather than from playback here (`HistoryEntry.fromRemote`). */
    val fromRemote: Boolean = false,
)

class ContinueStore(private val file: File = continueFile()) {
    private val lock = Any()
    private val mutableEntries = MutableStateFlow(readEntries())
    val entries: StateFlow<List<ContinueEntry>> = mutableEntries.asStateFlow()

    fun upsert(anime: AnimeCard, episode: Double, position: Double, duration: Double) {
        if (duration <= 0.0) return
        synchronized(lock) {
            val existing = mutableEntries.value.firstOrNull { it.anime.id == anime.id }
            // An episode already counted as watched has nothing left to resume, and this is what
            // keeps a late player callback from reopening a row that has moved past it.
            if (existing != null && episode in existing.watched) return
            val entry = ContinueEntry(
                anime = anime,
                progress = ContinueProgress(episode, position.coerceIn(0.0, duration), duration),
                updatedAt = System.currentTimeMillis(),
                watched = existing?.watched.orEmpty(),
            )
            val updated = (mutableEntries.value.filterNot { it.anime.id == anime.id } + entry)
                .sortedByDescending { it.updatedAt }
                .take(24)
            writeEntries(updated)
            mutableEntries.value = updated
        }
    }

    /**
     * An episode crossed the watched threshold: record it and move the row on (Android's
     * `markEpisodeWatched`). Returns the row as it was when something changed, so the caller can
     * log the day's activity from it, and null when nothing did.
     */
    fun markEpisodeWatched(animeId: Long, episode: Double, nextEpisode: Double?, seriesCompleted: Boolean): ContinueEntry? =
        synchronized(lock) {
            val existing = mutableEntries.value.firstOrNull { it.anime.id == animeId } ?: return null
            val updated = continueAfterEpisodeWatched(existing, episode, nextEpisode, seriesCompleted)
            if (updated == existing) return null
            val rows = mutableEntries.value.filterNot { it.anime.id == animeId } + listOfNotNull(updated)
            val sorted = rows.sortedByDescending { it.updatedAt }.take(24)
            writeEntries(sorted)
            mutableEntries.value = sorted
            existing
        }

    /** Put [entry] in as it is, keeping its own timestamp. Used when the account moves a row. */
    fun replace(entry: ContinueEntry) = synchronized(lock) {
        val updated = (mutableEntries.value.filterNot { it.anime.id == entry.anime.id } + entry)
            .sortedByDescending { it.updatedAt }
            .take(24)
        writeEntries(updated)
        mutableEntries.value = updated
    }

    fun remove(id: Long) = synchronized(lock) {
        val updated = mutableEntries.value.filterNot { it.anime.id == id }
        if (updated.size != mutableEntries.value.size) {
            writeEntries(updated)
            mutableEntries.value = updated
        }
    }

    /**
     * Where an episode was left off, or null when it was never started or is already finished.
     * The saved position is a few seconds back, so the line before the one missed is heard again.
     */
    fun positionFor(animeId: Long, episode: Double): Double? = synchronized(lock) {
        val entry = mutableEntries.value.firstOrNull { it.anime.id == animeId } ?: return null
        val progress = entry.progress
        if (progress.episode != episode) return null
        if (progress.duration <= 0.0) return null
        if (progress.position >= progress.duration * 0.9) return null
        if (progress.position < 10.0) return null
        (progress.position - 3.0).coerceAtLeast(0.0)
    }

    fun clear() = synchronized(lock) {
        writeEntries(emptyList())
        mutableEntries.value = emptyList()
    }

    private fun readEntries(): List<ContinueEntry> {
        if (!file.isFile) return emptyList()
        val properties = Properties()
        file.inputStream().use { properties.load(it) }
        return properties.stringPropertyNames()
            .asSequence()
            .filter { it.startsWith("entry.") && it.endsWith(".id") }
            .mapNotNull { key ->
                val prefix = key.removeSuffix("id")
                val id = properties.getProperty(key)?.toLongOrNull() ?: return@mapNotNull null
                fun value(name: String) = properties.getProperty("${prefix}${name}")?.takeIf(String::isNotBlank)
                val title = value("title") ?: return@mapNotNull null
                val episode = value("episode")?.toDoubleOrNull() ?: return@mapNotNull null
                val position = value("position")?.toDoubleOrNull() ?: 0.0
                val duration = value("duration")?.toDoubleOrNull() ?: 0.0
                val updatedAt = value("updatedAt")?.toLongOrNull() ?: 0L
                // A row seeded from an account has no length until the episode is played.
                val anime = AnimeCard(
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
                    episodes = value("episodes")?.toIntOrNull(),
                    score = value("score")?.toDoubleOrNull(),
                    year = value("year")?.toIntOrNull(),
                    season = value("season"),
                    genres = value("genres")?.split('|')?.filter(String::isNotBlank).orEmpty(),
                    isAdult = value("isAdult")?.toBooleanStrictOrNull() ?: false,
                )
                val written = properties.getProperty("${prefix}watched")
                val watched = if (written != null) {
                    written.split(',').mapNotNull { it.trim().toDoubleOrNull() }.toSet()
                } else {
                    seededLegacyWatched(episode)
                }
                ContinueEntry(
                    anime, ContinueProgress(episode, position, duration), updatedAt, watched,
                    fromRemote = value("fromRemote")?.toBooleanStrictOrNull() ?: false,
                )
            }
            .sortedByDescending { it.updatedAt }
            .toList()
    }

    private fun writeEntries(entries: List<ContinueEntry>) {
        val properties = Properties()
        entries.forEach { e ->
            val a = e.anime
            val prefix = "entry.${a.id}."
            fun put(name: String, value: Any?) {
                if (value != null) properties.setProperty(prefix + name, value.toString())
            }
            put("id", a.id)
            put("title", a.title)
            put("titleRomaji", a.titleRomaji)
            put("titleEnglish", a.titleEnglish)
            put("titleNative", a.titleNative)
            put("malId", a.malId)
            put("cover", a.cover)
            put("color", a.color)
            put("banner", a.banner)
            put("format", a.format)
            put("status", a.status)
            put("episodes", a.episodes)
            put("score", a.score)
            put("year", a.year)
            put("season", a.season)
            put("genres", a.genres.joinToString("|"))
            put("isAdult", a.isAdult)
            put("episode", e.progress.episode)
            put("position", e.progress.position)
            put("duration", e.progress.duration)
            put("updatedAt", e.updatedAt)
            // Always written, even when empty: its absence is what marks a row from before this existed.
            properties.setProperty(prefix + "watched", e.watched.sorted().joinToString(",") { formatEpisode(it) })
            put("fromRemote", e.fromRemote)
        }
        file.parentFile?.let { java.nio.file.Files.createDirectories(it.toPath()) }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().use { properties.store(it, "AniLife continue watching") }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (error: IOException) {
            temporary.delete()
            throw error
        }
    }

    companion object {
        private fun continueFile(): File {
            val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
                ?: System.getProperty("user.home")
            return File(base, "AniLife/continue.properties")
        }
    }
}

val ContinueStoreInstance: ContinueStore = ContinueStore()
private fun formatEpisode(number: Double): String =
    if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()

/** Rows saved before the watched set existed: every whole episode below the one in progress. */
internal fun seededLegacyWatched(episode: Double): Set<Double> =
    if (episode <= 1.0) emptySet()
    else generateSequence(1.0) { it + 1.0 }.takeWhile { it < episode }.toSet()
