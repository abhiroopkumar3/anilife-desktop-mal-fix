package me.enivzoy.anilife.app.data

import java.io.File

/** The three values the account sign-ins need. Each is one line in the `.env` file. */
enum class ApiKey(val variable: String) {
    MAL_CLIENT_ID("MAL_CLIENT_ID"),
    ANILIST_CLIENT_ID("ANILIST_CLIENT_ID"),
    ANILIST_CLIENT_SECRET("ANILIST_CLIENT_SECRET"),
}

/**
 * Where the app finds and keeps its API client IDs and secret.
 *
 * Read order for each value: the process environment, then the app's own `.env` in the user's data
 * folder, then a `.env` beside the program (what people pasted by hand before this existed). Nothing
 * is built in: an empty value is how the app knows to ask.
 *
 * Writes go to the data folder, never the install folder. `C:\Program Files` is not writable by an
 * app run without administrator rights, so a file there could be read but not edited from the app.
 * A save changes only the keys it is given and leaves every other line as it was, so saving one
 * service's values never erases the other's.
 */
class ApiCredentials(
    private val dataFile: File = defaultDataFile(),
    private val installFile: File = File(System.getProperty("user.dir").orEmpty(), ".env"),
    private val environment: (String) -> String? = System::getenv,
) {
    fun read(key: ApiKey): String {
        val fromEnvironment = environment(key.variable)?.trim().orEmpty()
        if (fromEnvironment.isNotBlank()) return fromEnvironment.take(MAX_LENGTH)
        return valueIn(dataFile, key).ifBlank { valueIn(installFile, key) }.take(MAX_LENGTH)
    }

    fun isSet(key: ApiKey): Boolean = read(key).isNotBlank()

    /** Creates the file with every value empty if there is none yet. An existing file is never touched. */
    fun ensureFile() {
        if (dataFile.exists()) return
        runCatching {
            dataFile.parentFile?.mkdirs()
            dataFile.writeText(TEMPLATE)
        }
    }

    /** Sets the given keys and leaves everything else in the file exactly as it was. */
    fun save(values: Map<ApiKey, String>) {
        ensureFile()
        val lines = runCatching { dataFile.readLines() }.getOrDefault(TEMPLATE.lines()).toMutableList()
        for ((key, value) in values) {
            val clean = value.trim().replace("\r", "").replace("\n", "")
            val line = "${key.variable}=$clean"
            val at = lines.indexOfFirst { it.trimStart().startsWith("${key.variable}=") }
            if (at >= 0) lines[at] = line else lines += line
        }
        dataFile.parentFile?.mkdirs()
        dataFile.writeText(lines.joinToString("\n").trimEnd('\n') + "\n")
    }

    private fun valueIn(file: File, key: ApiKey): String = runCatching {
        file.readLines()
            .firstOrNull { it.trimStart().startsWith("${key.variable}=") }
            ?.substringAfter('=')?.trim()?.trim('"', '\'')
    }.getOrNull().orEmpty()

    companion object {
        private const val MAX_LENGTH = 128

        /** Same shape as `.env.example`, with every value empty. */
        val TEMPLATE = """
            # AniLife API settings. The app keeps this file up to date from its account screens.
            # MyAnimeList OAuth client id (create an app at https://myanimelist.net/apiconfig with
            # redirect URL http://localhost:8765).
            MAL_CLIENT_ID=
            # AniList OAuth (https://anilist.co/settings/developer, redirect URL
            # https://anilist.co/api/v2/oauth/pin).
            ANILIST_CLIENT_ID=
            # Only needed to exchange an AniList authorization code for a token.
            ANILIST_CLIENT_SECRET=
        """.trimIndent() + "\n"

        fun defaultDataFile(): File {
            val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(::File)
                ?: File(System.getProperty("user.home"), ".local/share")
            return File(base, "AniLife/.env")
        }

        /** The one the app uses. */
        val shared: ApiCredentials by lazy { ApiCredentials() }
    }
}
