package me.enivzoy.anilife.app.data

import java.awt.Desktop
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MyAnimeListUser(val id: Long, val name: String, val picture: String?)
data class MyAnimeListEntry(val id: Long, val title: String, val status: String, val watched: Int, val score: Int)

/** MyAnimeList OAuth and list sync. OAuth tokens stay in the per-user AniLife data directory. */
object MyAnimeListSync {
    private const val AUTHORIZE = "https://myanimelist.net/v1/oauth2/authorize"
    private const val TOKEN = "https://myanimelist.net/v1/oauth2/token"
    private const val API = "https://api.myanimelist.net/v2"
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val mutableUser = MutableStateFlow<MyAnimeListUser?>(null)
    val user = mutableUser.asStateFlow()
    private val mutableConfigured = MutableStateFlow(false)
    val configured = mutableConfigured.asStateFlow()
    @Volatile private var clientId: String = DEFAULT_CLIENT_ID
    private const val DEFAULT_CLIENT_ID = "0e98545d5b71fdaad927ef2e8e388396"
    @Volatile private var tokens: Tokens? = readTokens()

    fun configure() {
        val fromEnvironment = System.getenv("MAL_CLIENT_ID")?.trim().orEmpty()
        val fromFile = runCatching {
            val envFile = File(System.getProperty("user.dir"), ".env")
            envFile.readLines().firstOrNull { it.trimStart().startsWith("MAL_CLIENT_ID=") }
                ?.substringAfter('=')?.trim()?.trim('"', '\'')
        }.getOrNull().orEmpty()
        val resolved = fromEnvironment.ifBlank { fromFile }.ifBlank { DEFAULT_CLIENT_ID }
        clientId = resolved.take(128)
        mutableConfigured.value = clientId.isNotBlank()
    }

    fun isConfigured(): Boolean = clientId.isNotBlank()

    suspend fun restore() {
        mutex.withLock {
            val current = tokens ?: return
            runCatching { userRequest(accessToken(current)) }
                .onSuccess { mutableUser.value = it }
                .onFailure { mutableUser.value = null }
        }
    }

    suspend fun connect() {
        require(clientId.isNotBlank()) { "Enter your MyAnimeList client ID in Settings first." }
        val state = randomUrlToken()
        val verifier = randomUrlToken()
        // MyAnimeList only supports the "plain" PKCE method, so the challenge is the verifier itself.
        // Sending an S256 hash makes the token exchange fail with "invalid_grant".
        val challenge = verifier
        val authorizeUrl = URI.create(AUTHORIZE).let { uri ->
            val redirectUri = "http://localhost:$MAL_CALLBACK_PORT"
            val query = listOf(
                "response_type" to "code",
                "client_id" to clientId,
                "state" to state,
                "code_challenge" to challenge,
                "code_challenge_method" to "plain",
                "redirect_uri" to redirectUri,
            ).joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
            "$uri?$query"
        }
        val response = showLogin(authorizeUrl)
        require(response.second == state) { "MyAnimeList sign-in state did not match. Try connecting again." }
        mutex.withLock {
            val fresh = exchangeCode(response.first, verifier)
            val account = userRequest(fresh.accessToken)
            saveTokens(fresh)
            tokens = fresh
            mutableUser.value = account
        }
    }

    suspend fun disconnect() = mutex.withLock {
        tokens = null
        mutableUser.value = null
        tokenFile().delete()
    }

    suspend fun list(): List<MyAnimeListEntry> = mutex.withLock {
        val token = accessToken(tokens ?: error("Connect a MyAnimeList account first."))
        val request = HttpRequest.newBuilder(URI.create("$API/users/@me/animelist?fields=list_status&limit=1000&nsfw=true"))
            .header("Authorization", "Bearer $token")
            .GET().build()
        val root = requestJson(request)
        root["data"]?.jsonArray?.mapNotNull { item ->
            val obj = item.jsonObject
            val node = obj["node"]?.jsonObject ?: return@mapNotNull null
            val status = obj["list_status"]?.jsonObject ?: return@mapNotNull null
            MyAnimeListEntry(
                id = node["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null,
                title = node["title"]?.jsonPrimitive?.content.orEmpty(),
                status = status["status"]?.jsonPrimitive?.content.orEmpty(),
                watched = status["num_episodes_watched"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                score = status["score"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            )
        }.orEmpty()
    }

    /**
     * The whole MAL list the way Android reads it: every page, with episode totals, rewatch flag,
     * update time and tags. Capped at 50 pages so a bad `next` link cannot loop forever.
     */
    suspend fun rows(): List<MalListRow> = mutex.withLock {
        val token = accessToken(tokens ?: error("Connect a MyAnimeList account first."))
        val rows = mutableListOf<MalListRow>()
        var url: String? = "$API/users/@me/animelist?fields=${encode(MAL_LIST_FIELDS)}&limit=1000&nsfw=true"
        var pages = 0
        while (url != null && pages < 50) {
            val request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer $token")
                .GET().build()
            val response = withContext(Dispatchers.IO) { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            check(response.statusCode() in 200..299) { "MyAnimeList list request failed (${response.statusCode()})." }
            val (page, next) = parseMalListPage(response.body())
            rows += page
            url = next
            pages++
        }
        rows
    }

    /** Add a title to the signed-in MAL list as plan to watch, leaving it alone if it is already listed. */
    suspend fun addPlanned(malId: Long) = mutex.withLock {
        val token = accessToken(tokens ?: error("Connect a MyAnimeList account first."))
        val body = "status=${encode("plan_to_watch")}"
        val request = HttpRequest.newBuilder(URI.create("$API/anime/$malId/my_list_status"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            .build()
        requestJson(request)
    }

    /** Mirror the Save button on MAL: see [malSavedPush]. */
    suspend fun syncSaved(malId: Long, saved: Boolean) = mutex.withLock {
        val token = accessToken(tokens ?: error("Connect a MyAnimeList account first."))
        val get = HttpRequest.newBuilder(URI.create("$API/anime/$malId?fields=my_list_status"))
            .header("Authorization", "Bearer $token").GET().build()
        val status = requestJson(get)["my_list_status"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
        when (malSavedPush(saved, status)) {
            SavedPush.ADD_PLANNING -> {
                val patch = HttpRequest.newBuilder(URI.create("$API/anime/$malId/my_list_status"))
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString("status=plan_to_watch")).build()
                requestJson(patch)
            }
            SavedPush.DELETE -> {
                val delete = HttpRequest.newBuilder(URI.create("$API/anime/$malId/my_list_status"))
                    .header("Authorization", "Bearer $token").DELETE().build()
                val response = withContext(Dispatchers.IO) { http.send(delete, HttpResponse.BodyHandlers.ofString()) }
                check(response.statusCode() in 200..299) { "MyAnimeList: delete failed (HTTP ${response.statusCode()})." }
            }
            else -> Unit
        }
    }

    /** Take a title off the signed-in MAL list, whatever its status. */
    suspend fun removeEntry(malId: Long) = mutex.withLock {
        val token = accessToken(tokens ?: error("Connect a MyAnimeList account first."))
        val delete = HttpRequest.newBuilder(URI.create("$API/anime/$malId/my_list_status"))
            .header("Authorization", "Bearer $token").DELETE().build()
        val response = withContext(Dispatchers.IO) { http.send(delete, HttpResponse.BodyHandlers.ofString()) }
        check(response.statusCode() in 200..299 || response.statusCode() == 404) {
            "MyAnimeList: delete failed (HTTP ${response.statusCode()})."
        }
    }

    /** The entry plus MyAnimeList's own episode count, the figure a progress write is measured against. */
    suspend fun animeStatus(malId: Long): MalAnimeStatus = mutex.withLock {
        val token = accessToken(tokens ?: error("Connect a MyAnimeList account first."))
        animeStatusLocked(token, malId)
    }

    private suspend fun animeStatusLocked(token: String, malId: Long): MalAnimeStatus {
        val fields = "my_list_status{status,score,num_episodes_watched,is_rewatching,num_times_rewatched,updated_at,tags},num_episodes"
        val get = HttpRequest.newBuilder(URI.create("$API/anime/$malId?fields=${encode(fields)}"))
            .header("Authorization", "Bearer $token").GET().build()
        return parseMalAnimeStatus(requestJson(get))
    }

    /** Upsert status, count, score or rewatch fields, then check MyAnimeList did what was asked. */
    suspend fun writeEntry(malId: Long, write: MalWrite): MalEntryStatus = mutex.withLock {
        val token = accessToken(tokens ?: error("Connect a MyAnimeList account first."))
        val body = listOfNotNull(
            write.progress?.let { "num_watched_episodes" to it.toString() },
            write.status?.let { "status" to it },
            write.isRewatching?.let { "is_rewatching" to it.toString() },
            write.score?.let { "score" to it.toString() },
            write.numTimesRewatched?.let { "num_times_rewatched" to it.toString() },
        ).toTypedArray()
        write.score?.let { require(it in 0..10) { "A MyAnimeList score is 0 to 10, not $it" } }
        val patch = HttpRequest.newBuilder(URI.create("$API/anime/$malId/my_list_status"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(form(*body))).build()
        val confirmed = parseMalEntryStatus(requestJson(patch))
        malWriteMismatch(write, confirmed)?.let { error(it) }
        confirmed
    }

    /** The entry's current status, for a move that depends on it (a rewatch). Null when not listed. */
    suspend fun entryOf(malId: Long): MalEntryStatus? = animeStatus(malId).entry

    private suspend fun userRequest(token: String): MyAnimeListUser {
        val request = HttpRequest.newBuilder(URI.create("$API/users/@me?fields=picture"))
            .header("Authorization", "Bearer $token").GET().build()
        val root = requestJson(request)
        return MyAnimeListUser(
            id = root["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: error("MyAnimeList returned no user ID."),
            name = root["name"]?.jsonPrimitive?.content.orEmpty(),
            picture = root["picture"]?.jsonPrimitive?.content,
        )
    }

    private suspend fun accessToken(current: Tokens): String {
        if (System.currentTimeMillis() / 1000 < current.expiresAt - 300) return current.accessToken
        val request = HttpRequest.newBuilder(URI.create(TOKEN))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(
                "client_id" to clientId,
                "grant_type" to "refresh_token",
                "refresh_token" to current.refreshToken,
            )))
            .build()
        val fresh = parseTokens(requestJson(request))
        tokens = fresh
        saveTokens(fresh)
        return fresh.accessToken
    }

    private suspend fun exchangeCode(code: String, verifier: String): Tokens {
        val request = HttpRequest.newBuilder(URI.create(TOKEN))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(
                "client_id" to clientId,
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to "http://localhost:$MAL_CALLBACK_PORT",
                "code_verifier" to verifier,
            )))
            .build()
        return parseTokens(requestJson(request))
    }

    private suspend fun requestJson(request: HttpRequest) = withContext(Dispatchers.IO) {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val body = runCatching { json.parseToJsonElement(response.body()).jsonObject }.getOrNull()
        if (response.statusCode() !in 200..299) {
            val detail = body?.get("message")?.jsonPrimitive?.contentOrNull
                ?: body?.get("error")?.jsonPrimitive?.contentOrNull
                ?: "Request failed (HTTP ${response.statusCode()})."
            error("MyAnimeList: $detail")
        }
        body ?: error("MyAnimeList returned an unreadable response.")
    }

    private fun parseTokens(root: kotlinx.serialization.json.JsonObject): Tokens {
        val access = root["access_token"]?.jsonPrimitive?.content.orEmpty()
        val refresh = root["refresh_token"]?.jsonPrimitive?.content.orEmpty()
        require(access.isNotBlank() && refresh.isNotBlank()) { "MyAnimeList returned no access token." }
        val expiry = root["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
        return Tokens(access, refresh, System.currentTimeMillis() / 1000 + expiry)
    }

    private const val MAL_CALLBACK_PORT = 8765

    private suspend fun showLogin(url: String): Pair<String, String> = suspendCancellableCoroutine { continuation ->
        val server = try {
            HttpServer.create(InetSocketAddress("127.0.0.1", MAL_CALLBACK_PORT), 0)
        } catch (e: Throwable) {
            continuation.resumeWith(Result.failure(IllegalStateException("Could not open the MyAnimeList sign-in callback port $MAL_CALLBACK_PORT: ${e.message}")))
            return@suspendCancellableCoroutine
        }
        server.createContext("/") { exchange: HttpExchange ->
            val params = exchange.requestURI.rawQuery.orEmpty().split('&').mapNotNull { part ->
                val pair = part.split('=', limit = 2)
                if (pair.size == 2) decode(pair[0]) to decode(pair[1]) else null
            }.toMap()
            val code = params["code"]
            val state = params["state"]
            val body = if (code != null) "Signed in. You can close this tab." else "Waiting for sign-in..."
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            if (code != null && continuation.isActive) {
                continuation.resumeWith(Result.success(code to state.orEmpty()))
                server.stop(0)
            }
        }
        server.start()
        continuation.invokeOnCancellation { server.stop(0) }
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI.create(url))
            } else {
                throw IllegalStateException("Desktop browsing is not supported on this system.")
            }
        }.onFailure { error ->
            server.stop(0)
            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
        }
    }

    private fun randomUrlToken(): String = ByteArray(32).also(SecureRandom()::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun form(vararg pairs: Pair<String, String>) = pairs.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
    private fun decode(value: String) = java.net.URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun tokenFile(): File {
        val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(::File)
            ?: File(System.getProperty("user.home"), ".local/share")
        return File(base, "AniLife/mal-session.properties")
    }

    private fun readTokens(): Tokens? = runCatching {
        val file = tokenFile()
        if (!file.isFile) return null
        val properties = Properties().apply { file.inputStream().use(::load) }
        Tokens(
            properties.getProperty("access") ?: return null,
            properties.getProperty("refresh") ?: return null,
            properties.getProperty("expiresAt")?.toLongOrNull() ?: return null,
        )
    }.getOrNull()

    private fun saveTokens(value: Tokens) {
        val file = tokenFile()
        file.parentFile?.mkdirs()
        Properties().apply {
            setProperty("access", value.accessToken)
            setProperty("refresh", value.refreshToken)
            setProperty("expiresAt", value.expiresAt.toString())
        }.store(file.outputStream(), "AniLife MyAnimeList session")
    }

    private data class Tokens(val accessToken: String, val refreshToken: String, val expiresAt: Long)
}
