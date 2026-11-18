package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.Update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

private const val DEFAULT_MANIFEST_URL =
    "https://api.github.com/repos/abhiroopkumar3/anilife-desktop-mal-fix/releases/latest"

/**
 * Checks the latest GitHub release without following its installer asset redirect. The parser
 * also accepts the version manifest format used by earlier releases.
 */
class Updater(
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val manifestUrl: String = DEFAULT_MANIFEST_URL,
) {
    private val mutableUpdate = MutableStateFlow<Update?>(null)
    val update: StateFlow<Update?> = mutableUpdate.asStateFlow()

    /** Returns the newer [Update] for [currentVersion], or null when the app is up to date. */
    suspend fun check(currentVersion: String): Update? = withContext(Dispatchers.IO) {
        val update = fetchUpdate(currentVersion)
        mutableUpdate.value = update
        update
    }

    fun clear() {
        mutableUpdate.value = null
    }

    /** Downloads the release asset and verifies its SHA-256 against the manifest. */
    suspend fun download(
        update: Update,
        destination: Path,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        val uri = httpUri(update.url) ?: run {
            System.err.println("[Updater] invalid download URL for version ${update.version}")
            return@withContext false
        }
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(600))
            .header("Accept", "*/*")
            .GET()
            .uri(uri)
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() >= 400) {
            println("[Updater] asset download failed (HTTP ${response.statusCode()})")
            return@withContext false
        }
        val bytes = response.body()
        if (update.sizeBytes > 0L && bytes.size.toLong() != update.sizeBytes) {
            System.err.println("[Updater] asset size mismatch for ${update.version}")
            return@withContext false
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (update.sha256.isNotBlank() && digest.lowercase() != update.sha256.lowercase()) {
            System.err.println("[Updater] sha256 mismatch for ${update.version}")
            return@withContext false
        }
        val parent = destination.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, destination.fileName.toString(), ".part")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            onProgress(bytes.size.toLong(), bytes.size.toLong())
            true
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Starts the downloaded Windows installer; the caller should exit after this succeeds. */
    fun installWindows(installer: Path) {
        val name = installer.fileName.toString()
        if (name.lowercase().endsWith(".msi")) {
            ProcessBuilder("msiexec", "/i", installer.toString(), "/qn", "/norestart").start()
        } else {
            ProcessBuilder(installer.toString()).start()
        }
    }

    private suspend fun fetchUpdate(currentVersion: String): Update? {
        val uri = URI.create(manifestUrl)
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", "AniLife-Updater")
            .header("Accept", "application/vnd.github+json")
            .GET()
            .uri(uri)
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() >= 400) {
            throw IOException("Update manifest fetch failed (HTTP ${response.statusCode()}).")
        }
        return parseUpdateManifest(response.body(), currentVersion, currentPlatform())
    }

    companion object {
        const val FALLBACK_VERSION = "1.0.0"
        const val UPDATES_DIRECTORY = "updates"

        fun currentVersion(): String {
            val stream = Updater::class.java.classLoader.getResourceAsStream("version/version.properties")
            val value = stream?.bufferedReader()?.readText()?.trim()?.substringAfter('=')?.takeIf(String::isNotBlank)
            return value ?: FALLBACK_VERSION
        }

        fun updatesDirectory(): Path {
            val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it) }
                ?: Path.of(System.getProperty("user.home"), ".local", "share")
            return base.resolve("AniLife").resolve("updates")
        }

        /**
         * "1.0.7" is [1, 0, 7]. A fork patch suffix counts as one more number, so "1.0.7-AK2" is
         * [1, 0, 7, 2]: newer than 1.0.7 and older than 1.0.8, which keeps this fork's releases
         * from ever running ahead of the original project's.
         */
        fun parseVersion(version: String): List<Int> {
            val trimmed = version.trim()
            val patch = Regex("-AK(\\d+)$", RegexOption.IGNORE_CASE).find(trimmed)
            val base = if (patch != null) trimmed.removeSuffix(patch.value) else trimmed
            val parts = base.split('.').mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(0) }
            return if (patch != null) parts + patch.groupValues[1].toInt() else parts
        }

        fun isNewer(candidate: String, current: String): Boolean {
            val left = parseVersion(candidate)
            val right = parseVersion(current)
            val size = maxOf(left.size, right.size)
            for (index in 0 until size) {
                val a = left.getOrElse(index) { 0 }
                val b = right.getOrElse(index) { 0 }
                if (a != b) return a > b
            }
            return false
        }
    }
}

internal fun parseUpdateManifest(body: String, currentVersion: String, platform: String): Update? {
    val json = Json { ignoreUnknownKeys = true }
    val root = json.parseToJsonElement(body) as? JsonObject ?: return null
    val version = root.string("version")
        ?: root.string("tag_name")?.removePrefix("v")
        ?: return null
    if (!Updater.isNewer(version, currentVersion)) return null
    val assets = (root["assets"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()
    val platformAsset = assets.firstOrNull {
        it.string("platform").equals(platform, ignoreCase = true)
    }
    val releaseAsset = if (root.string("tag_name") != null) {
        // Current releases ship anilife-<version>.x64.exe. Older ones used .base.exe, and a release
        // keeps a .base.exe copy so installed copies that only look for that name still update.
        val names = listOf(".x64.exe", ".base.exe", "#base.exe")
        names.firstNotNullOfOrNull { suffix ->
            assets.firstOrNull { it.string("name").orEmpty().endsWith(suffix, ignoreCase = true) }
        }
    } else {
        null
    }
    val asset = platformAsset ?: releaseAsset
    val url = sequenceOf(
        root.string("url"),
        root.string("assetUrl"),
        asset?.string("browser_download_url"),
        asset?.string("url"),
    )
        .filterNotNull()
        .firstOrNull(String::isNotBlank)
        ?: return null
    val downloadUrl = normalizeAssetUrl(url) ?: return null
    return Update(
        version = version,
        changelog = root.string("releaseNotes") ?: root.string("changelog") ?: root.string("body") ?: "",
        url = downloadUrl,
        sizeBytes = root.long("sizeBytes") ?: root.long("size") ?: asset?.long("size") ?: 0L,
        sha256 = root.string("sha256") ?: root.string("checksum")
            ?: asset?.string("sha256")
            ?: asset?.string("digest")?.removePrefix("sha256:")
            ?: "",
        publishedAt = root.long("releasedAt")
            ?: root.string("published_at")?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }
            ?: 0L,
    )
}

private fun currentPlatform(): String = when {
    System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true) -> "windows"
    System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true) -> "macos"
    else -> "linux"
}

private fun httpUri(url: String): URI? = runCatching { URI.create(url) }
    .getOrNull()
    ?.takeIf { uri ->
        uri.isAbsolute &&
            (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank()
    }

private fun normalizeAssetUrl(url: String): String? {
    val uri = httpUri(url) ?: return null
    if (!uri.host.equals("github.com", ignoreCase = true) ||
        !uri.path.contains("/releases/download/") ||
        !uri.path.contains("/dist/") ||
        uri.rawFragment.isNullOrBlank()
    ) {
        return uri.toASCIIString()
    }

    val path = "${uri.path.substringBeforeLast("/dist/")}/${uri.path.substringAfterLast('/')}.${uri.rawFragment}"
    return runCatching { URI(uri.scheme, uri.authority, path, uri.query, null).toASCIIString() }.getOrNull()
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
