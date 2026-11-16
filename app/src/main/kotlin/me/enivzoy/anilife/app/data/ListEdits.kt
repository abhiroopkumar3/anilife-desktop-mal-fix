package me.enivzoy.anilife.app.data

/**
 * The card menu's writes to the signed-in account, each followed by the same clean-up on this PC as
 * Android's AnimeListActionsDialog. The service-specific calls follow MiruroRepository.kt.
 */
object ListEdits {
    val serviceLabel: (ListService) -> String = { if (it == ListService.ANILIST) "AniList" else "MyAnimeList" }

    private fun service(): ListService = AccountLibrary.activeService() ?: error("Sign in to AniList or MyAnimeList first.")

    private suspend fun malIdOf(anilistId: Long): Long {
        AccountLibrary.malEntries.value.firstOrNull { it.anime.id == anilistId }?.anime?.malId?.let { return it }
        val card = AccountLibrary.repository?.cardsByIds(listOf(anilistId))?.firstOrNull()
        return card?.malId?.takeIf { it > 0 } ?: error("This anime could not be matched to MyAnimeList")
    }

    /** Move a title to [status]. [completedMarksWatched] makes Completed fill the episode count too. */
    suspend fun moveTo(anilistId: Long, status: String, completedMarksWatched: Boolean = false, totalEpisodes: Int? = null) {
        require(LIST_DESTINATIONS.any { it.status == status }) { "Unsupported anime list status: $status" }
        when (service()) {
            ListService.ANILIST ->
                if (status == "COMPLETED" && completedMarksWatched) AniListSync.markWatched(anilistId, totalEpisodes)
                else AniListSync.moveStatus(anilistId, status)
            ListService.MAL -> {
                val malId = malIdOf(anilistId)
                val current = if (status == "REPEATING" || status == "COMPLETED") MyAnimeListSync.entryOf(malId) else null
                val rewatch = current?.let { planRewatchMove(malEntryToAniListStatus(it), it.timesRewatched, status) } ?: RewatchMove()
                if (status == "COMPLETED" && completedMarksWatched) {
                    val total = MyAnimeListSync.animeStatus(malId).totalEpisodes ?: totalEpisodes
                    MyAnimeListSync.writeEntry(
                        malId,
                        MalWrite(progress = total, status = "completed", isRewatching = false, numTimesRewatched = rewatch.repeat),
                    )
                } else {
                    MyAnimeListSync.writeEntry(
                        malId,
                        MalWrite(
                            progress = 0.takeIf { rewatch.resetProgress },
                            status = aniListToMalStatus(status),
                            isRewatching = status == "REPEATING",
                            numTimesRewatched = rewatch.repeat,
                            strictProgress = !rewatch.resetProgress,
                        ),
                    )
                }
            }
        }
        cleanUpAfterMove(anilistId, status)
    }

    private suspend fun cleanUpAfterMove(anilistId: Long, status: String) {
        if (!moveKeepsContinueRow(status)) ContinueStoreInstance.remove(anilistId)
        if (!savedRowSurvivesMoveTo(status)) AccountLibrary.repository?.removeFromLibrary(anilistId)
        AccountLibrary.refresh()
    }

    /** The viewer's own score out of ten; 0 clears it. Only for a title already on the account. */
    suspend fun rate(anilistId: Long, score: Int) {
        require(score in 0..10) { "Unsupported anime list score: $score" }
        when (service()) {
            ListService.ANILIST -> AniListSync.setScore(anilistId, score)
            ListService.MAL -> MyAnimeListSync.writeEntry(malIdOf(anilistId), MalWrite(score = score))
        }
        AccountLibrary.refresh()
    }

    /** The count read live from the account, with the most the service will take. Null when not listed. */
    suspend fun progressOf(anilistId: Long): ListProgress? = when (service()) {
        ListService.ANILIST -> AniListSync.progressOf(anilistId)?.let { progress ->
            ListProgress(progress, AccountLibrary.repository?.cardsByIds(listOf(anilistId))?.firstOrNull()?.episodes?.takeIf { it > 0 })
        }
        ListService.MAL -> MyAnimeListSync.animeStatus(malIdOf(anilistId)).let { remote ->
            remote.entry?.let { ListProgress(it.watched, remote.totalEpisodes) }
        }
    }

    suspend fun setProgress(anilistId: Long, progress: Int) {
        require(progress >= 0) { "Unsupported episode count: $progress" }
        when (service()) {
            ListService.ANILIST -> AniListSync.setProgress(anilistId, progress)
            ListService.MAL -> MyAnimeListSync.writeEntry(malIdOf(anilistId), MalWrite(progress = progress))
        }
        AccountLibrary.refresh()
    }

    /**
     * Take the title off the account entirely, then off this PC's saves and Continue Watching so the
     * next sync does not read them as a save made elsewhere. False when the account had no entry.
     */
    suspend fun delete(anilistId: Long): Boolean {
        val deleted = when (service()) {
            ListService.ANILIST -> AniListSync.deleteByMedia(anilistId)
            ListService.MAL -> {
                val malId = malIdOf(anilistId)
                if (MyAnimeListSync.entryOf(malId) == null) false else { MyAnimeListSync.removeEntry(malId); true }
            }
        }
        ContinueStoreInstance.remove(anilistId)
        AccountLibrary.repository?.removeFromLibrary(anilistId)
        AccountLibrary.refresh()
        return deleted
    }
}
