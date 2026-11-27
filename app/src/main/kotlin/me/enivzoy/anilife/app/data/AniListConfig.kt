package me.enivzoy.anilife.app.data

/** Public AniList OAuth client ID. A value in the environment or the `.env` file overrides it. */
object AniListConfig {
    const val DEFAULT_CLIENT_ID = "56997"

    fun clientId(): String = ApiCredentials.shared.read(ApiKey.ANILIST_CLIENT_ID).ifBlank { DEFAULT_CLIENT_ID }
}
