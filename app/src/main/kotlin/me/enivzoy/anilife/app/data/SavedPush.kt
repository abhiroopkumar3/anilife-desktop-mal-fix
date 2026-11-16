package me.enivzoy.anilife.app.data

/** What to do on the account when a title is saved or unsaved here (Android `syncSavedAnime`). */
enum class SavedPush { ADD_PLANNING, SHOW_ON_PLANNING, HIDE_FROM_STATUS_LISTS, DELETE, NONE }

/**
 * AniList: saving adds the title as Planning; unsaving only touches an entry that is still
 * Planning, and never one that has been moved on. An entry that also sits in custom lists is tucked
 * away rather than deleted, so it stays in those lists, and saving it again brings it back.
 * [status] is null when the account has no entry.
 */
fun aniListSavedPush(saved: Boolean, status: String?, hidden: Boolean = false, inCustomLists: Boolean = false): SavedPush = when {
    saved && status == null -> SavedPush.ADD_PLANNING
    saved && status == "PLANNING" && hidden -> SavedPush.SHOW_ON_PLANNING
    !saved && status == "PLANNING" && !hidden && inCustomLists -> SavedPush.HIDE_FROM_STATUS_LISTS
    !saved && status == "PLANNING" && !inCustomLists -> SavedPush.DELETE
    else -> SavedPush.NONE
}

/** MyAnimeList: add as plan to watch when absent; remove only an entry that is still plan to watch. */
fun malSavedPush(saved: Boolean, malStatus: String?): SavedPush = when {
    saved && malStatus == null -> SavedPush.ADD_PLANNING
    !saved && malStatus == "plan_to_watch" -> SavedPush.DELETE
    else -> SavedPush.NONE
}
