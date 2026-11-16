package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.AnimeCard
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MalIdMapTest {
    private fun tmp() = Files.createTempDirectory("idmap").toFile().resolve("cache.properties")

    @Test
    fun `the bundled map answers without a request`() {
        var calls = 0
        val map = MalIdMap({ mapOf(5L to 5L) }, { calls++; null }, tmp())
        assertEquals(5L, map.resolve(5))
        assertEquals(0, calls)
    }

    @Test
    fun `the real bundled file loads and knows a title`() {
        val map = MalIdMap(fetchShard = { null }, cacheFile = tmp())
        assertEquals(1L, map.resolve(1))
    }

    @Test
    fun `a title the map lacks is resolved from the CDN shard and remembered`() {
        var calls = 0
        val file = tmp()
        val first = MalIdMap({ emptyMap() }, { calls++; """{"anilist_id": 777}""" }, file)
        assertEquals(777L, first.resolve(9_000_001))
        val second = MalIdMap({ emptyMap() }, { calls++; null }, file)
        assertEquals(777L, second.resolve(9_000_001))
        assertEquals(1, calls)
    }

    @Test
    fun `an id the CDN does not know is remembered as unknown, a failure is not`() {
        var calls = 0
        val map = MalIdMap({ emptyMap() }, { id -> calls++; if (id == 1L) "{}" else null }, tmp())
        assertNull(map.resolve(1)); assertNull(map.resolve(1))
        assertEquals(1, calls)
        assertNull(map.resolve(2)); assertNull(map.resolve(2))
        assertEquals(3, calls)
    }

    @Test
    fun `an expired answer is asked for again`() {
        var clock = 0L; var calls = 0
        val map = MalIdMap({ emptyMap() }, { calls++; """{"anilist_id": 3}""" }, tmp(), { clock })
        map.resolve(10); clock = MalIdMap.TTL_MS + 1; map.resolve(10)
        assertEquals(2, calls)
    }

    @Test
    fun `parsing tolerates junk`() {
        assertNull(MalIdMap.parseShard("not json"))
        assertEquals(mapOf(7L to 3L), MalIdMap.parseBundled("""{"3":{"mal":7},"x":{"mal":1},"4":{}}"""))
    }

    @Test
    fun `cards join by id map, then by MAL id for the rest`() {
        val mapped = AnimeCard(id = 10, title = "A", malId = null)
        val fallback = AnimeCard(id = 20, title = "B", malId = 2)
        val joined = joinMalCards(listOf(1, 2, 3), mapOf(1L to 10L, 2L to null, 3L to null), listOf(mapped), listOf(fallback))
        assertEquals(setOf(1L, 2L), joined.keys)
        assertEquals(10L, joined.getValue(1).id)
        assertEquals(20L, joined.getValue(2).id)
    }
}
