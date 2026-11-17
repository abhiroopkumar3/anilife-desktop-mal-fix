package me.enivzoy.anilife.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/*
 * Client-side pacing for AniList, ported from the Android app's AniListClient so the two behave the
 * same under AniList's rate limit (degraded to about 30 requests a minute for the whole IP).
 *
 * Every response says how much of the window is left. Spend that budget as a burst while it is
 * healthy, spread what is left once it runs low, and wait for the window to reset when it is spent,
 * rather than finding the limit by hitting it. A 429 is a one-minute lockout.
 */

internal const val DEFAULT_RATE_LIMIT = 30
internal const val RATE_WINDOW_MS = 60_000L
internal const val RATE_SAFETY_MS = 100L
internal const val MIN_SPACING_MS = 100L

/** Cap on the per-request gap while spreading a low budget across the rest of the window. */
internal const val MAX_SPACING_MS = 3_000L

/** Cap on how long to hold when the budget is fully spent and a reset is awaited. */
internal const val MAX_BACKOFF_MS = 60_000L

/** Start spreading requests once this few remain in the window. Above it a burst runs at full speed. */
internal const val RATE_LOW_WATERMARK = 8

/** "No X-RateLimit-Remaining seen yet". */
internal const val UNKNOWN_RATE_REMAINING = Int.MAX_VALUE

/** A 429 is waited out and the request sent again this many times before the failure is shown. */
internal const val MAX_RATE_LIMIT_RETRIES = 2
internal const val DEFAULT_RETRY_AFTER_SECONDS = 10L

/**
 * Pure slot scheduler. Given [now], the last-seen budget ([remaining] and its window [reset], both
 * milliseconds since the epoch) and the previously reserved [nextSlot], returns
 * (this request's start, the next free slot).
 *
 * The budget is a bucket to spend, not a rate to hold: bursts run at [MIN_SPACING_MS] while the
 * budget is healthy or unknown, and only glide toward the window reset once it is genuinely low.
 */
internal fun nextRateSlot(
    now: Long,
    remaining: Int,
    reset: Long,
    nextSlot: Long,
    limit: Int = DEFAULT_RATE_LIMIT,
): Pair<Long, Long> {
    val earliest = if (remaining <= 0 && now < reset) minOf(reset, now + MAX_BACKOFF_MS) else now
    val quotaSpacing = (RATE_WINDOW_MS / limit.coerceAtLeast(1) + RATE_SAFETY_MS).coerceAtLeast(MIN_SPACING_MS)
    val windowOpen = now < reset
    val interval = when {
        // Nothing observed yet, or the window has already rolled over: the budget is whole.
        remaining == UNKNOWN_RATE_REMAINING || !windowOpen -> MIN_SPACING_MS
        // Comfortable budget: let the burst through and let the response headers tighten pacing.
        remaining > RATE_LOW_WATERMARK -> MIN_SPACING_MS
        remaining > 0 -> maxOf(quotaSpacing, ((reset - now) / remaining).coerceAtMost(MAX_SPACING_MS))
        else -> quotaSpacing
    }
    // A reserved slot is a wall-clock time, so a clock stepped backwards leaves it far in the
    // future. No slot this function hands out is further off than a full backoff plus one spacing,
    // so one that is can only be the clock's doing, and is dropped rather than waited out.
    val reserved = if (nextSlot > now + MAX_BACKOFF_MS + MAX_SPACING_MS) now else nextSlot
    val start = maxOf(earliest, reserved)
    return start to (start + interval)
}

/** How long to wait after a 429: AniList's `Retry-After` in seconds, else a default, never under a second. */
internal fun retryAfterMillis(header: String?): Long =
    (header?.trim()?.toLongOrNull() ?: DEFAULT_RETRY_AFTER_SECONDS)
        .coerceAtLeast(1)
        .coerceAtMost(Long.MAX_VALUE / 1_000) * 1_000

/**
 * Holds the budget AniList last reported and hands out request slots against it. One per client, so
 * every screen and background job shares one budget, as they share one limit.
 */
class AniListPacer internal constructor(private val clock: () -> Long = System::currentTimeMillis) {
    private val gate = Mutex()

    // Only a couple of callers may reserve against the same stale snapshot; the permit is held until
    // the response headers have updated it, so background work cannot starve an interactive request.
    private val permits = Semaphore(2)
    private var nextSlotMs = 0L // guarded by gate

    @Volatile private var remaining = UNKNOWN_RATE_REMAINING
    @Volatile private var resetMs = 0L
    @Volatile private var limit = DEFAULT_RATE_LIMIT

    /** Waits for a slot, runs [send], then records the budget from the headers [headers] reads off its result. */
    internal suspend fun <T> paced(headers: (T) -> (String) -> String?, send: suspend () -> T): T =
        permits.withPermit {
            awaitSlot()
            send().also { response ->
                val header = headers(response)
                record(header("X-RateLimit-Limit"), header("X-RateLimit-Remaining"), header("X-RateLimit-Reset"))
            }
        }

    /** A 429 spent the budget: hold everything behind it until the wait AniList asked for is over. */
    internal fun limited(retryAfterMs: Long) {
        remaining = 0
        resetMs = maxOf(resetMs, clock() + retryAfterMs)
    }

    internal fun record(limitHeader: String?, remainingHeader: String?, resetHeader: String?) {
        limitHeader?.toIntOrNull()?.takeIf { it > 0 }?.let { limit = it }
        remainingHeader?.toIntOrNull()?.let { remaining = it }
        resetHeader?.toLongOrNull()?.let { resetMs = it * 1_000 }
    }

    private suspend fun awaitSlot() {
        val slot = gate.withLock {
            val (start, next) = nextRateSlot(clock(), remaining, resetMs, nextSlotMs, limit)
            nextSlotMs = next
            start
        }
        val wait = slot - clock()
        if (wait > 0) {
            if (wait >= SLOW_WAIT_LOG_MS) System.err.println("AniList throttle waiting ${wait}ms (remaining=$remaining)")
            delay(wait)
        }
    }

    /**
     * Sends [request] inside the budget. A 429 is waited out for as long as AniList asks and the
     * request sent again, up to [MAX_RATE_LIMIT_RETRIES] times; one that survives that is returned
     * as it is, for the caller to report.
     */
    suspend fun send(http: HttpClient, request: HttpRequest): HttpResponse<String> {
        var attempt = 0
        while (true) {
            val response = paced(headers = { r: HttpResponse<String> -> { name: String -> r.headers().firstValue(name).orElse(null) } }) {
                withContext(Dispatchers.IO) { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            }
            if (response.statusCode() != 429 || attempt >= MAX_RATE_LIMIT_RETRIES) return response
            val waitMs = retryAfterMillis(response.headers().firstValue("Retry-After").orElse(null))
            limited(waitMs)
            attempt++
            delay(waitMs)
        }
    }

    companion object {
        private const val SLOW_WAIT_LOG_MS = 750L

        /** The one budget for the whole app: AniList counts every request from this machine together. */
        val shared = AniListPacer()
    }
}
