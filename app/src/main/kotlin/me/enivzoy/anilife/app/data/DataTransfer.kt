package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.AnimeCard

/**
 * The four actions on the Data page: back up to a file, restore from a backup, export a
 * MyAnimeList XML and import one. It reads and writes the same stores the rest of the app uses,
 * and follows the Android app's rules for what a restore or an import is allowed to change.
 */
class DataTransfer(
    private val repository: AniListRepository,
    private val continueStore: ContinueStore = ContinueStoreInstance,
    private val appVersion: () -> String = { Updater.currentVersion() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Snapshot of everything a backup carries, right now. */
    fun createBackup(settings: AppSettings): BackupDocument {
        val stamp = now()
        return BackupDocument(
            createdAt = stamp,
            appVersion = appVersion(),
            settings = BackupSettings.toBackup(settings),
            // This app does not record when a title was saved, so the order on screen stands in for
            // it: the first title is the newest, as it is in the Android file.
            watchlist = repository.libraryEntries.value.mapIndexed { index, anime ->
                BackupWatchlistEntry(
                    anilistId = anime.id,
                    title = anime.title,
                    cover = anime.cover,
                    format = anime.format,
                    averageScore = anime.score?.toInt(),
                    addedAt = stamp - index,
                )
            },
            history = continueStore.entries.value.map { entry ->
                BackupHistoryEntry(
                    anilistId = entry.anime.id,
                    title = entry.anime.title,
                    cover = entry.anime.cover,
                    episodeNumber = entry.progress.episode,
                    episodeTitle = null,
                    provider = "",
                    category = settings.audio,
                    positionMs = (entry.progress.position * 1000).toLong(),
                    durationMs = (entry.progress.duration * 1000).toLong(),
                    updatedAt = entry.updatedAt,
                    fromRemote = false,
                )
            },
        )
    }

    /**
     * Apply [document] to this PC.
     *
     * Settings both apps share are replaced by the file's, and the two lists are merged: a title
     * saved here or in the file is still a title the viewer saved, and for Continue Watching the more
     * recently watched row wins. [applySettings] is handed the transform to run on the live settings.
     */
    suspend fun restore(
        document: BackupDocument,
        applySettings: ((AppSettings) -> AppSettings) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): RestoreSummary {
        val settingsApplied = BackupSettings.applyTo(AppSettings(), document.settings).second
        val history = document.history.filterNot(BackupHistoryEntry::fromRemote)

        // The file only has a title, a cover and a score for each show. Fetch the full details so
        // the library can show episode counts and so a MyAnimeList export can find the MAL ids; if
        // AniList cannot be reached the restore still works, with the sparse versions.
        val lookup = repository.lookupCards(
            ids = document.watchlist.map { it.anilistId } + history.map { it.anilistId },
            failFast = false,
            onProgress = onProgress,
        )
        val details = lookup.cards.associateBy(AnimeCard::id)
        fun card(id: Long, title: String, cover: String?, format: String?, score: Int?) =
            details[id] ?: AnimeCard(id = id, title = title, cover = cover, format = format, score = score?.toDouble())

        if (settingsApplied > 0) {
            applySettings { current -> BackupSettings.applyTo(current, document.settings).first }
        }
        val added = repository.library.addNew(
            document.watchlist.map { card(it.anilistId, it.title, it.cover, it.format, it.averageScore) },
        )
        val stamp = now()
        val restoredHistory = continueStore.restore(
            history.map { entry ->
                // A row written before the other app knew a length has none. This app only keeps rows
                // with one, so it gets a typical episode length; the position stays where it was.
                val duration = if (entry.durationMs > 0) entry.durationMs / 1000.0 else NOMINAL_EPISODE_SECONDS
                ContinueEntry(
                    anime = card(entry.anilistId, entry.title, entry.cover, null, null),
                    progress = ContinueProgress(
                        episode = entry.episodeNumber,
                        position = (entry.positionMs / 1000.0).coerceIn(0.0, duration),
                        duration = duration,
                    ),
                    updatedAt = entry.updatedAt.takeIf { it > 0 } ?: stamp,
                )
            },
        )

        val notRestored = buildList {
            val ignoredSettings = document.settings.size - settingsApplied
            if (ignoredSettings > 0) add("$ignoredSettings Android-only setting${if (ignoredSettings == 1) "" else "s"}")
            if (document.customListCount > 0) add("${document.customListCount} custom list${if (document.customListCount == 1) "" else "s"}")
            if (document.watchedCount > 0) add("${document.watchedCount} watched mark${if (document.watchedCount == 1) "" else "s"}")
            if (document.searchHistory.isNotEmpty()) add("${document.searchHistory.size} recent search${if (document.searchHistory.size == 1) "" else "es"}")
            if (document.loginServices.isNotEmpty()) add("logins (sign in under Accounts & sync instead)")
        }
        val warnings = buildList {
            if (lookup.failedBatches > 0) {
                add("Some titles were saved without their full details because AniList could not be reached. They fill in the next time you use Export MyAnimeList XML while online.")
            }
        }
        return RestoreSummary(settingsApplied, added, restoredHistory, notRestored, warnings)
    }

    /**
     * Build a MyAnimeList XML from the saved library and Continue Watching.
     *
     * Titles without a MyAnimeList id cannot be in the file and are counted as skipped. This app has
     * no per-title scores or dates of its own, so they are written as MyAnimeList's "none".
     */
    suspend fun buildMalExport(
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): MalExportFile {
        val continueEntries = continueStore.entries.value
        val episodeById = continueEntries.associate { it.anime.id to it.progress.episode.toInt() }
        val cards = LinkedHashMap<Long, AnimeCard>()
        repository.libraryEntries.value.forEach { cards[it.id] = it }
        continueEntries.forEach { cards.putIfAbsent(it.anime.id, it.anime) }

        val missingMalId = cards.values.filter { it.malId == null }.map(AnimeCard::id)
        val resolved = if (missingMalId.isEmpty()) {
            emptyMap()
        } else {
            repository.lookupCards(missingMalId, failFast = false, onProgress = onProgress).cards.associateBy(AnimeCard::id)
        }
        // Keep what was just learned, so the next export does not have to ask again.
        val saved = repository.libraryEntries.value.map(AnimeCard::id).toSet()
        resolved.values.filter { it.id in saved }.takeIf { it.isNotEmpty() }?.let { repository.addToLibrary(it) }

        var skipped = 0
        val entries = cards.values.mapNotNull { card ->
            val full = resolved[card.id] ?: card
            val malId = full.malId ?: run {
                skipped++
                return@mapNotNull null
            }
            val progress = episodeById[card.id] ?: 0
            val episodes = full.episodes ?: card.episodes
            MalExportEntry(
                malId = malId,
                title = card.title,
                format = full.format ?: card.format,
                episodes = episodes,
                watchedEpisodes = progress,
                score = 0,
                status = MalExport.statusFromLocal(progress, episodes),
            )
        }
        return MalExport.fromEntries(username = null, entries = entries, skippedCount = skipped)
    }

    /**
     * Add every title in a MyAnimeList export to the library. Nothing is written until every
     * AniList lookup has succeeded, so a dropped connection leaves the library as it was.
     */
    suspend fun importMalXml(
        bytes: ByteArray,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): MalImportSummary {
        val parsed = MalImport.parse(bytes)
        require(parsed.isNotEmpty()) { "The file parsed as a MyAnimeList export but listed no anime." }
        val lookup = repository.lookupCards(
            ids = parsed.map { it.malId },
            byMalId = true,
            failFast = true,
            onProgress = onProgress,
        )
        val byMalId = lookup.cards.filter { it.malId != null }.associateBy { it.malId!! }
        val matched = parsed.mapNotNull { byMalId[it.malId] }
        val added = repository.library.addNew(matched)
        return MalImportSummary(
            totalEntries = parsed.size,
            added = added,
            alreadySaved = matched.distinctBy(AnimeCard::id).size - added,
            unmatched = parsed.size - matched.size,
        )
    }

    private companion object {
        /** A typical episode, in seconds, for restored rows whose length was never recorded. */
        const val NOMINAL_EPISODE_SECONDS = 24 * 60.0
    }
}
