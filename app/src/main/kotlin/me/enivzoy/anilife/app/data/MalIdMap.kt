package me.enivzoy.anilife.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.enivzoy.anilife.core.model.AnimeCard
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * MAL id to AniList id, resolved the way Android's `KonohaClient.resolveAnilistIdFromMal` does:
 * the bundled `id-map.json` first (instant, works offline), then the Konoha CDN shard for
 * `mappings/mal/<id / 1000>/<id>.json`, with each answer, including "unknown", kept for 30 days.
 */
class MalIdMap(
    private val bundled: () -> Map<Long, Long> = ::loadBundled,
    private val fetchShard: (Long) -> String? = ::fetchFromCdn,
    private val cacheFile: File = File(directory(), "mal-id-cache.properties"),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val map by lazy(bundled)
    private val cache = java.util.concurrent.ConcurrentHashMap<Long, Pair<Long?, Long>>().also { loadCache(it) }

    fun resolve(malId: Long): Long? {
        map[malId]?.let { return it }
        cache[malId]?.let { (anilistId, at) -> if (now() - at < TTL_MS) return anilistId }
        val body = runCatching(fetchShard.let { f -> { f(malId) } }).getOrNull() ?: return null
        val anilistId = parseShard(body)
        cache[malId] = anilistId to now()
        saveCache()
        return anilistId
    }

    private fun loadCache(into: MutableMap<Long, Pair<Long?, Long>>) {
        if (!cacheFile.isFile) return
        val p = java.util.Properties().apply { cacheFile.inputStream().use(::load) }
        p.stringPropertyNames().forEach { key ->
            val id = key.toLongOrNull() ?: return@forEach
            val (value, at) = p.getProperty(key).split('|').let { it.getOrNull(0).orEmpty() to it.getOrNull(1)?.toLongOrNull() }
            into[id] = value.toLongOrNull() to (at ?: return@forEach)
        }
    }

    @Synchronized
    private fun saveCache() {
        cacheFile.parentFile?.mkdirs()
        val p = java.util.Properties()
        cache.forEach { (id, v) -> p.setProperty(id.toString(), "${v.first ?: ""}|${v.second}") }
        runCatching { cacheFile.outputStream().use { p.store(it, "AniLife MAL id cache") } }
    }

    companion object {
        const val TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val DATA_BASE = "https://cdn.jsdelivr.net/gh/anilifecommunity/media-index@main/data"
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

        /** The `anilist_id` in a shard body, or null. */
        fun parseShard(body: String): Long? = runCatching {
            (Json.parseToJsonElement(body).jsonObject["anilist_id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        }.getOrNull()

        /** `{ "<anilistId>": { "mal": <malId>, ... } }` to MAL id -> AniList id. */
        fun parseBundled(text: String): Map<Long, Long> {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: return emptyMap()
            val out = HashMap<Long, Long>(root.size)
            root.forEach { (anilistId, entry) ->
                val a = anilistId.toLongOrNull() ?: return@forEach
                val mal = ((entry as? JsonObject)?.get("mal") as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return@forEach
                out[mal] = a
            }
            return out
        }

        private fun loadBundled(): Map<Long, Long> =
            MalIdMap::class.java.getResourceAsStream("/id-map.json")
                ?.use { parseBundled(it.readBytes().toString(Charsets.UTF_8)) }.orEmpty()

        private fun fetchFromCdn(malId: Long): String? {
            val request = HttpRequest.newBuilder(URI.create("$DATA_BASE/mappings/mal/${malId / 1000}/$malId.json"))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            return when {
                response.statusCode() == 404 -> "{}"
                response.statusCode() in 200..299 -> response.body()
                else -> null
            }
        }

        private fun directory(): File {
            val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(::File)
                ?: File(System.getProperty("user.home"), ".local/share")
            return File(base, "AniLife")
        }
    }
}

/**
 * Android's join of MAL rows to AniList titles (`MiruroRepository.malAnimeList`, steps 1 to 3):
 * a title found through the id map is matched by its resolved id (or by its own `idMal`), and a MAL
 * id the map could not place falls back to AniList's `idMal` lookup.
 */
fun joinMalCards(
    malIds: List<Long>,
    resolved: Map<Long, Long?>,
    mappedCards: List<AnimeCard>,
    fallbackCards: List<AnimeCard>,
): Map<Long, AnimeCard> {
    val byMal = mutableMapOf<Long, AnimeCard>()
    mappedCards.forEach { card ->
        card.malId?.let { byMal[it] = card }
        resolved.entries.firstOrNull { it.value == card.id }?.key?.let { byMal[it] = card }
    }
    fallbackCards.forEach { card -> card.malId?.let { byMal[it] = card } }
    return byMal.filterKeys { it in malIds }
}
