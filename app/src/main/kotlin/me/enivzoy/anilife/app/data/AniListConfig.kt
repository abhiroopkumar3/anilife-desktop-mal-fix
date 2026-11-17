package me.enivzoy.anilife.app.data

/** AniList OAuth client ID, from the environment or the `.env` file the account screen keeps. Empty until set. */
object AniListConfig {
    fun clientId(): String = ApiCredentials.shared.read(ApiKey.ANILIST_CLIENT_ID)
}
