package me.enivzoy.anilife.app.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppBusyTest {
    @Test
    fun isBusyOnlyWhileWorkRuns() = runBlocking {
        assertFalse(AppBusy.active.value)
        val result = AppBusy.hold {
            assertTrue(AppBusy.active.value)
            7
        }
        assertEquals(7, result)
        assertFalse(AppBusy.active.value)
    }

    @Test
    fun staysBusyUntilEveryOverlappingHoldHasEnded() = runBlocking {
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) { AppBusy.hold { releaseFirst.await() } }
        val second = async(Dispatchers.Default) { AppBusy.hold { releaseSecond.await() } }
        while (!AppBusy.active.value) Thread.yield()

        releaseFirst.complete(Unit)
        first.await()
        assertTrue(AppBusy.active.value)

        releaseSecond.complete(Unit)
        second.await()
        assertFalse(AppBusy.active.value)
    }

    @Test
    fun letsGoWhenTheWorkFails() = runBlocking {
        val failure = runCatching { AppBusy.hold { error("sync failed") } }.exceptionOrNull()
        assertEquals("sync failed", failure?.message)
        assertFalse(AppBusy.active.value)
    }
}
