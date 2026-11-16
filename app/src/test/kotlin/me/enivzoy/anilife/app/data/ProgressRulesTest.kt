package me.enivzoy.anilife.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Ported from Android's AniListPolicyTest and MalClientTest.
class ProgressRulesTest {
    private fun snap(status: String, progress: Int, repeat: Int = 0) = ListSnapshot(7, status, progress, repeat)

    @Test fun `new watched entry starts current`() =
        assertEquals(ProgressUpdate(3, "CURRENT"), planProgressUpdate(null, 3, 12))

    @Test fun `new releasing entry with unknown total starts current`() =
        assertEquals(ProgressUpdate(1, "CURRENT"), planProgressUpdate(null, 1, null))

    @Test fun `last episode completes current entry`() =
        assertEquals(ProgressUpdate(12, "COMPLETED"), planProgressUpdate(snap("CURRENT", 11), 12, 12))

    @Test fun `planning starts current`() =
        assertEquals(ProgressUpdate(1, "CURRENT"), planProgressUpdate(snap("PLANNING", 0), 1, 12))

    @Test
    fun `paused and an unfinished rewatch keep their status`() {
        assertEquals(ProgressUpdate(5, null), planProgressUpdate(snap("PAUSED", 4), 5, 12))
        assertEquals(ProgressUpdate(4, null), planProgressUpdate(snap("REPEATING", 3, 1), 4, 12))
    }

    @Test fun `the last episode of a rewatch completes it and counts it`() =
        assertEquals(ProgressUpdate(12, "COMPLETED", repeat = 2), planProgressUpdate(snap("REPEATING", 11, 1), 12, 12))

    @Test
    fun `a rewatch left at the full count still finishes on its last episode`() {
        val rewatch = snap("REPEATING", 12)
        assertNull(planProgressUpdate(rewatch, 5, 12))
        assertEquals(ProgressUpdate(12, "COMPLETED", repeat = 1), planProgressUpdate(rewatch, 12, 12))
    }

    @Test fun `a rewatch of a series with no known length is never finished by playback`() =
        assertEquals(ProgressUpdate(13, null), planProgressUpdate(snap("REPEATING", 12), 13, null))

    @Test
    fun `replaying a completed title without choosing Rewatching changes nothing`() {
        val finished = snap("COMPLETED", 12, 1)
        assertNull(planProgressUpdate(finished, 1, 12))
        assertNull(planProgressUpdate(finished, 12, 12))
    }

    @Test
    fun `completed and regressive progress are not changed`() {
        assertNull(planProgressUpdate(snap("COMPLETED", 12), 13, 13))
        assertNull(planProgressUpdate(snap("CURRENT", 8), 7, 12))
        assertNull(planProgressUpdate(null, 0, 12))
    }

    @Test
    fun `MAL's own total holds the target and decides the finish`() {
        assertEquals(12, malProgressTarget(24, 12))
        assertEquals(7, malProgressTarget(7, null))
        assertEquals(12, malProgressTotal(12, 24, 24))
        assertEquals(24, malProgressTotal(null, 24, 13))
    }

    @Test
    fun `no-op reasons distinguish completed and already synced entries`() {
        assertEquals(ProgressSkipReason.REMOTE_COMPLETED, progressSkipReason("COMPLETED", 12, 13))
        assertEquals(ProgressSkipReason.ALREADY_AT_OR_AHEAD, progressSkipReason("CURRENT", 1, 1))
        assertEquals(ProgressSkipReason.NO_CHANGE, progressSkipReason("CURRENT", 0, 1))
        assertEquals(ProgressSkipReason.INVALID_PROGRESS, progressSkipReason(null, null, 0))
    }
}
