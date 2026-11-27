package me.enivzoy.anilife.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RefreshHostTest {
    @Test
    fun aPressStartsOneRefreshAndMarksTheHostBusy() {
        val host = RefreshHost()

        assertTrue(host.request())

        assertEquals(1, host.requests)
        assertTrue(host.busy)
    }

    @Test
    fun pressesWhileARefreshRunsAreDropped() {
        val host = RefreshHost()
        host.request()

        assertFalse(host.request())
        assertFalse(host.request())

        assertEquals(1, host.requests)
        assertTrue(host.busy)
    }

    @Test
    fun finishingAllowsTheNextPress() {
        val host = RefreshHost()
        host.request()
        host.finish()

        assertFalse(host.busy)
        assertTrue(host.request())
        assertEquals(2, host.requests)
    }

    @Test
    fun aNewHostStartsIdle() {
        val host = RefreshHost()

        assertEquals(0, host.requests)
        assertFalse(host.busy)
    }
}
