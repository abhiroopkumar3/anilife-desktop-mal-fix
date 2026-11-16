package me.enivzoy.anilife.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForkVersionTest {
    @Test
    fun aForkPatchSuffixIsOneMoreNumber() {
        assertEquals(listOf(1, 0, 6), Updater.parseVersion("1.0.6"))
        assertEquals(listOf(1, 0, 6, 2), Updater.parseVersion("1.0.6-AK2"))
        assertEquals(listOf(1, 0, 6, 1), Updater.parseVersion(" 1.0.6-ak1 "))
    }

    @Test
    fun forkReleasesSitBetweenTheOriginalsReleases() {
        assertTrue(Updater.isNewer("1.0.6-AK2", "1.0.6-AK1"))
        assertTrue(Updater.isNewer("1.0.6-AK1", "1.0.6"))
        assertTrue(Updater.isNewer("1.0.7", "1.0.6-AK2"))
        assertFalse(Updater.isNewer("1.0.6-AK2", "1.0.7"))
        assertFalse(Updater.isNewer("1.0.6-AK2", "1.0.6-AK2"))
    }
}
