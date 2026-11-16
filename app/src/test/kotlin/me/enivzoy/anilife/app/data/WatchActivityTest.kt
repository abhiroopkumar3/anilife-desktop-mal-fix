package me.enivzoy.anilife.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.enivzoy.anilife.core.model.AnimeCard
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchActivityTest {
    // Ported from Android's AccountInsightsTest.
    @Test
    fun `a streak runs back from today, or from yesterday until tonight's episode`() {
        val days = mapOf(10L to 2, 9L to 1, 8L to 3, 6L to 1)
        assertEquals(3, activityStreak(days, today = 10))
        assertEquals(3, activityStreak(days, today = 11))
        assertEquals(0, activityStreak(days, today = 12))
    }

    @Test
    fun `a day counted on the device and on the account keeps the larger number`() {
        assertEquals(
            mapOf(1L to 3, 2L to 4, 3L to 1),
            mergedActivity(local = mapOf(1L to 3, 2L to 1), account = mapOf(2L to 4, 3L to 1, 4L to 0)),
        )
    }

    @Test
    fun `heat level is relative to the busiest day`() {
        assertEquals(0, heatLevel(0, 5))
        assertEquals(1, heatLevel(1, 8))
        assertEquals(4, heatLevel(5, 5))
        assertEquals(3, heatLevel(3, 5))
    }

    @Test
    fun `the log forgets days past the kept window`() {
        val updated = withEpisodeOn(mapOf(1L to 2, 500L to 1), 500L)
        assertEquals(mapOf(500L to 2), updated)
    }

    @Test
    fun `account history is read by epoch day and zero days are dropped`() {
        val data = Json.parseToJsonElement(
            """{"User":{"stats":{"activityHistory":[{"date":86400,"amount":3},{"date":172800,"amount":0},{"amount":1}]}}}""",
        ).jsonObject
        assertEquals(mapOf(1L to 3), parseActivityHistory(data))
        assertEquals(emptyMap(), parseActivityHistory(Json.parseToJsonElement("{}").jsonObject))
    }

    private fun row(episode: Double, watched: Set<Double> = emptySet()) = ContinueEntry(
        anime = AnimeCard(id = 1, title = "A"),
        progress = ContinueProgress(episode, 0.0, 0.0),
        updatedAt = 0L,
        watched = watched,
    )

    @Test
    fun `only a new completion of the row's own episode is counted, and it persists`() {
        val dir = Files.createTempDirectory("activity").toFile()
        val file = File(dir, "a.properties")
        val store = WatchActivityStore(file)
        val day = LocalDate.of(2026, 3, 2)
        store.recordEpisode(row(3.0), 3.0, day)
        store.recordEpisode(row(3.0, setOf(3.0)), 3.0, day) // already watched
        store.recordEpisode(row(4.0), 3.0, day) // not the row's episode
        assertEquals(mapOf(day.toEpochDay() to 1), store.days.value)
        assertEquals(store.days.value, WatchActivityStore(file).days.value)
        dir.deleteRecursively()
    }
}
