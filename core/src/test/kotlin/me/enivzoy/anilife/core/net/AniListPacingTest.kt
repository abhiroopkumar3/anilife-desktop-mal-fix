package me.enivzoy.anilife.core.net

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/** The slot scheduler is the Android app's, so its tests are Android's, ported as they are. */
class AniListPacingTest {
    @Test
    fun `a cold start with no observed budget does not throttle`() {
        val (start, next) = nextRateSlot(now = 1_000, remaining = UNKNOWN_RATE_REMAINING, reset = 0, nextSlot = 0)
        assertEquals(1_000L, start)
        assertEquals(1_000L + MIN_SPACING_MS, next)
    }

    @Test
    fun `a healthy budget lets a burst through`() {
        val (start, next) = nextRateSlot(now = 1_000, remaining = 50, reset = 61_000, nextSlot = 0)
        assertEquals(1_000L, start)
        assertEquals(1_000L + MIN_SPACING_MS, next)
        val (start2, _) = nextRateSlot(now = 1_000, remaining = 50, reset = 61_000, nextSlot = next)
        assertEquals(next, start2)
    }

    @Test
    fun `ten queued calls on a cold start cost under two seconds`() {
        var slot = 0L
        repeat(10) { slot = nextRateSlot(1_000, UNKNOWN_RATE_REMAINING, 0, slot).second }
        assertTrue(slot - 1_000 < 2_000, "ten queued calls cost ${slot - 1_000}ms")
    }

    @Test
    fun `spent budget holds until the window resets`() {
        assertEquals(31_000L, nextRateSlot(now = 1_000, remaining = 0, reset = 31_000, nextSlot = 0).first)
    }

    @Test
    fun `a clock stepping backwards does not strand the queue`() {
        val eightyMinutes = 80 * 60_000L
        val (start, _) = nextRateSlot(now = 1_000, remaining = 21, reset = 61_000, nextSlot = 1_000 + eightyMinutes)
        assertEquals(1_000L, start)
    }

    @Test
    fun `a slot reserved behind a spent budget is still honoured`() {
        val (_, next) = nextRateSlot(now = 1_000, remaining = 0, reset = 120_000, nextSlot = 0)
        assertEquals(next, nextRateSlot(now = 1_000, remaining = 0, reset = 120_000, nextSlot = next).first)
    }

    @Test
    fun `stale spent budget past the reset proceeds immediately`() {
        assertEquals(100_000L, nextRateSlot(now = 100_000, remaining = 0, reset = 31_000, nextSlot = 0).first)
    }

    @Test
    fun `low budget spreads the remaining calls across the window`() {
        val (start, next) = nextRateSlot(now = 1_000, remaining = 4, reset = 9_000, nextSlot = 0)
        assertEquals(1_000L, start)
        assertEquals(3_100L, next)
    }

    @Test
    fun `the watermark is where pacing begins`() {
        val (_, paced) = nextRateSlot(1_000, RATE_LOW_WATERMARK, 61_000, 0)
        assertTrue(paced - 1_000 > MIN_SPACING_MS)
        val (_, free) = nextRateSlot(1_000, RATE_LOW_WATERMARK + 1, 61_000, 0)
        assertEquals(1_000L + MIN_SPACING_MS, free)
    }

    @Test
    fun `spreading is capped and backoff to a far reset is bounded`() {
        assertEquals(1_000L + MAX_SPACING_MS, nextRateSlot(1_000, 1, 101_000, 0).second)
        assertEquals(1_000L + MAX_BACKOFF_MS, nextRateSlot(1_000, 0, 201_000, 0).first)
    }

    @Test
    fun `Retry-After in seconds is used, a missing or bad one falls back, and it is never under a second`() {
        assertEquals(7_000L, retryAfterMillis("7"))
        assertEquals(DEFAULT_RETRY_AFTER_SECONDS * 1_000, retryAfterMillis(null))
        assertEquals(DEFAULT_RETRY_AFTER_SECONDS * 1_000, retryAfterMillis("soon"))
        assertEquals(1_000L, retryAfterMillis("0"))
    }

    private fun serve(codes: List<Int>, hits: AtomicInteger): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.requestBody.readBytes()
                val code = codes[minOf(hits.getAndIncrement(), codes.lastIndex)]
                val body = if (code == 429) "{}" else """{"data":{"Page":{"media":[]}}}"""
                exchange.responseHeaders.add("Retry-After", "1")
                exchange.responseHeaders.add("X-RateLimit-Limit", "30")
                exchange.responseHeaders.add("X-RateLimit-Remaining", if (code == 429) "0" else "29")
                exchange.sendResponseHeaders(code, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            start()
        }

    @Test
    fun `a 429 is waited out and the request sent again`() = runBlocking {
        val hits = AtomicInteger()
        val server = serve(listOf(429, 200), hits)
        try {
            val client = AniListClient(endpoint = "http://127.0.0.1:${server.address.port}/", pacer = AniListPacer())
            client.cardsByIds(listOf(1L))
            assertEquals(2, hits.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a 429 that never lifts is shown after two retries`() {
        val hits = AtomicInteger()
        val server = serve(listOf(429), hits)
        try {
            val client = AniListClient(endpoint = "http://127.0.0.1:${server.address.port}/", pacer = AniListPacer())
            val failure = assertThrows(AniListException::class.java) { runBlocking { client.cardsByIds(listOf(1L)) } }
            assertTrue("rate limit" in failure.message)
            assertEquals(1 + MAX_RATE_LIMIT_RETRIES, hits.get())
        } finally {
            server.stop(0)
        }
    }
}
