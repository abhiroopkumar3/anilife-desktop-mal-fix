package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.AnimeCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

 
class LocalLibraryStore(private val file: File = libraryFile()) {
    private val lock = Any()
    private val savedAt: MutableMap<Long, Long> = readSavedAt().toMutableMap()
    private val mutableEntries = MutableStateFlow(readEntries())
    val entries = mutableEntries.asStateFlow()
    private val mutableSaves = MutableStateFlow(currentSaves())
    /** The same titles with the time each was saved (epoch milliseconds, 0 for saves made before dates were kept). */
    val saves = mutableSaves.asStateFlow()

    private fun currentSaves() = mutableEntries.value.map { LocalSave(it, savedAt[it.id] ?: 0L) }
    private fun publish(updated: List<AnimeCard>) {
        savedAt.keys.retainAll(updated.mapTo(mutableSetOf()) { it.id })
        writeEntries(updated)
        mutableEntries.value = updated
        mutableSaves.value = currentSaves()
    }

    fun add(anime: AnimeCard) = synchronized(lock) {
        addAll(listOf(anime))
    }

    fun addAll(anime: List<AnimeCard>) = synchronized(lock) {
        if (anime.isEmpty()) return@synchronized
        val now = System.currentTimeMillis()
        val fresh = anime.filter { new -> mutableEntries.value.none { it.id == new.id } }
        fresh.forEach { savedAt[it.id] = now }
        val updated = (mutableEntries.value.filterNot { existing -> anime.any { it.id == existing.id } } + anime)
            .sortedBy { it.title.lowercase() }
        publish(updated)
    }

    /** Replace the saves with [merged], keeping each title's own date. Used by the account sync. */
    fun replaceAll(merged: List<LocalSave>) = synchronized(lock) {
        merged.forEach { savedAt[it.anime.id] = it.addedAt }
        val updated = merged.map { it.anime }.sortedBy { it.title.lowercase() }
        if (updated != mutableEntries.value || merged.any { savedAt[it.anime.id] != currentSaves().firstOrNull { s -> s.anime.id == it.anime.id }?.addedAt }) {
            publish(updated)
        }
    }

    fun setTitleLanguage(language: String) = synchronized(lock) {
        val updated = mutableEntries.value
            .map { anime -> anime.withTitlePreference(language) }
            .sortedBy { it.title.lowercase() }
        if (updated != mutableEntries.value) {
            publish(updated)
        }
    }

    fun setPreferEnglishTitles(preferEnglish: Boolean) = setTitleLanguage(if (preferEnglish) "english" else "romaji")

    fun remove(id: Long) = synchronized(lock) {
        val updated = mutableEntries.value.filterNot { it.id == id }
        if (updated.size != mutableEntries.value.size) {
            publish(updated)
        }
    }

    private fun readSavedAt(): Map<Long, Long> {
        if (!file.isFile) return emptyMap()
        val properties = Properties()
        file.inputStream().use(properties::load)
        return properties.stringPropertyNames()
            .filter { it.startsWith("anime.") && it.endsWith(".addedAt") }
            .mapNotNull { key ->
                val id = key.removePrefix("anime.").removeSuffix(".addedAt").toLongOrNull() ?: return@mapNotNull null
                val at = properties.getProperty(key)?.toLongOrNull() ?: return@mapNotNull null
                id to at
            }.toMap()
    }

    private fun readEntries(): List<AnimeCard> {
        if (!file.isFile) return emptyList()
        val properties = Properties()
        file.inputStream().use(properties::load)
        return properties.stringPropertyNames()
            .asSequence()
            .filter { it.startsWith("anime.") && it.endsWith(".title") }
            .mapNotNull { titleKey ->
                val prefix = titleKey.removeSuffix("title")
                val id = prefix.removePrefix("anime.").removeSuffix(".").toLongOrNull() ?: return@mapNotNull null
                properties.readCard(prefix, id)
            }
            .sortedBy { it.title.lowercase() }
            .toList()
    }

    private fun writeEntries(entries: List<AnimeCard>) {
        val properties = Properties()
        entries.forEach { anime ->
            val prefix = "anime.${anime.id}."
            properties.putCard(prefix, anime)
            fun put(name: String, value: Any?) {
                if (value != null) properties.setProperty(prefix + name, value.toString())
            }
            savedAt[anime.id]?.takeIf { it > 0 }?.let { put("addedAt", it) }
        }

        file.parentFile?.let { Files.createDirectories(it.toPath()) }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().use { properties.store(it, "AniLife local library") }
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (error: IOException) {
            temporary.delete()
            throw error
        }
    }

    private companion object {
        fun libraryFile(): File {
            val appData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
            val base = appData?.let(::File) ?: File(System.getProperty("user.home"), ".local/share")
            return File(base, "AniLife/library.properties")
        }
    }
}

