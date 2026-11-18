package me.enivzoy.anilife.core.net

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class AdultFilterTest {
    @Test
    fun hidingAdultContentSendsIsAdultFalse() {
        val vars = buildJsonObject { putAdultFilter(showAdult = false) }
        assertEquals(JsonPrimitive(false), vars["isAdult"])
    }

    @Test
    fun showingAdultContentSendsNothingSoAniListReturnsBoth() {
        // isAdult: true would return only adult titles.
        assertFalse(buildJsonObject { putAdultFilter(showAdult = true) }.containsKey("isAdult"))
    }
}
