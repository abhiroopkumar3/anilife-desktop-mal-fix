package me.enivzoy.anilife.app.data

import androidx.compose.ui.input.key.Key
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.Instant
import xyz.orbitary.perigee.activity.ActivityType

class AppSettingsTest {
    @Test
    fun startupIntroIsOffByDefault() {
        assertEquals(false, AppSettings().playIntro)
    }

    @Test
    fun discordPresenceFormatsIdleMovieAndEpisodeActivities() {
        val startedAt = Instant.parse("2026-11-03T22:00:00Z")
        val cover = "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/1.jpg"
        val idle = buildDiscordPresenceActivity(null, startedAt)
        val movie = buildDiscordPresenceActivity(
            DiscordPresenceTarget("movie", "My Movie", true, "FALL", 2026, 8.0),
            startedAt,
        )
        val series = buildDiscordPresenceActivity(
            DiscordPresenceTarget("series", "My Series", false, "FALL", 2026, 8.0, null, cover),
            startedAt,
        )
        val missingSeason = buildDiscordPresenceActivity(
            DiscordPresenceTarget("series-2", "My Series", false, null, null, 8.0),
            startedAt,
        )

        assertEquals(ActivityType.WATCHING, idle.type())
        assertEquals("Idling", idle.details())
        assertEquals("AniLife", idle.name())
        assertEquals("App developed by witherbl_de", idle.state())
        assertEquals("My Movie", movie.details())
        assertEquals("My Series", series.details())
        assertEquals("My Series", missingSeason.details())
        assertEquals("On S01E08", series.state())
        assertEquals("On S01E08", missingSeason.state())
        assertEquals(startedAt, series.timestamps()?.start())
        assertEquals(2, idle.buttons().size)
        assertEquals("Visit Website", idle.buttons()[0].label())
        assertEquals(ANILIFE_WEBSITE_URL, idle.buttons()[0].url())
        assertEquals("Star the Repo", idle.buttons()[1].label())
        assertEquals(ANILIFE_REPO_URL, idle.buttons()[1].url())
    }

    @Test
    fun discordPresenceDefaultsToTheBundledAniLifeApplication() {
        val settings = AppSettings()

        assertEquals(true, settings.discordPresenceEnabled)
        assertEquals("1567301811604553728", settings.discordApplicationId)
    }

    @Test
    fun appearanceSettingsPersistAndInvalidValuesAreNormalized() {
        val root = Files.createTempDirectory("AniLife-settings-appearance")
        try {
            val file = root.resolve("settings.properties").toFile()
            val store = AppSettingsStore(file)
            store.update {
                it.copy(
                    themeMode = "LIQUID_GLASS",
                    smoothScrolling = false,
                    scrollbarVisibility = "ALWAYS",
                    scrollAnimationDuration = "LONG",
                    reducedMotion = true,
                    glassBlurIntensity = 1.4f,
                    glassTransparency = 0.65f,
                    glassHighlightIntensity = 0.8f,
                )
            }

            val persisted = AppSettingsStore(file).settings.value
            assertEquals("LIQUID_GLASS", persisted.themeMode)
            assertEquals(false, persisted.smoothScrolling)
            assertEquals("ALWAYS", persisted.scrollbarVisibility)
            assertEquals("LONG", persisted.scrollAnimationDuration)
            assertEquals(true, persisted.reducedMotion)
            assertEquals(1.4f, persisted.glassBlurIntensity)
            assertEquals(0.65f, persisted.glassTransparency)
            assertEquals(0.8f, persisted.glassHighlightIntensity)

            store.update {
                it.copy(
                    themeMode = "UNKNOWN",
                    scrollbarVisibility = "SOMETIMES",
                    scrollAnimationDuration = "INSTANT",
                    glassBlurIntensity = 9f,
                    glassTransparency = -1f,
                    glassHighlightIntensity = Float.NaN,
                )
            }
            val normalized = AppSettingsStore(file).settings.value
            assertEquals("DEFAULT", normalized.themeMode)
            assertEquals("AUTOMATIC", normalized.scrollbarVisibility)
            assertEquals("NORMAL", normalized.scrollAnimationDuration)
            assertEquals(2f, normalized.glassBlurIntensity)
            assertEquals(0.25f, normalized.glassTransparency)
            assertEquals(1f, normalized.glassHighlightIntensity)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun settingsPersistAndInvalidValuesAreNormalized() {
        val root = Files.createTempDirectory("AniLife-settings")
        try {
            val file = root.resolve("settings.properties").toFile()
            val store = AppSettingsStore(file)
            store.update {
                it.copy(
                    audio = "dub",
                    subtitleLanguage = "fr",
                    resumePlayback = false,
                    playIntro = true,
                    titleLanguage = "romaji",
                    discordPresenceEnabled = true,
                    discordApplicationId = "123456789012345678x",
                    volume = 140,
                    contentScale = "invalid",
                    subtitleScale = 4f,
                    aniListSubmitPercent = 250,
                    captionFont = "  Custom Anime Font  ",
                    captionFontFile = root.resolve("custom.otf").toString(),
                    captionTextColor = "#AABBCC",
                    captionTextOpacity = 0.7f,
                    captionBackgroundColor = "#112233",
                    captionBackgroundOpacity = 0.45f,
                    captionEdgeStyle = "shadow",
                    captionVerticalPosition = "top",
                    captionVerticalOffset = 12f,
                    captionAlignment = "right",
                    captionPreset = "cinema",
                    downloadDirectory = root.toString(),
                    themeCss = ":root {\n  --background: #101010;\n}",
                )
            }

            val persisted = AppSettingsStore(file).settings.value

            assertEquals("dub", persisted.audio)
            assertEquals("fr", persisted.subtitleLanguage)
            assertEquals(false, persisted.resumePlayback)
            assertEquals(true, persisted.playIntro)
            assertEquals("romaji", persisted.titleLanguage)
            assertEquals(true, persisted.discordPresenceEnabled)
            assertEquals("123456789012345678", persisted.discordApplicationId)
            assertEquals(100, persisted.volume)
            assertEquals("fit", persisted.contentScale)
            assertEquals(2f, persisted.subtitleScale)
            assertEquals(100, persisted.aniListSubmitPercent)
            assertEquals("Custom Anime Font", persisted.captionFont)
            assertEquals(root.resolve("custom.otf").toString(), persisted.captionFontFile)
            assertEquals("#AABBCC", persisted.captionTextColor)
            assertEquals(0.7f, persisted.captionTextOpacity)
            assertEquals("#112233", persisted.captionBackgroundColor)
            assertEquals(0.45f, persisted.captionBackgroundOpacity)
            assertEquals("shadow", persisted.captionEdgeStyle)
            assertEquals("top", persisted.captionVerticalPosition)
            assertEquals(12f, persisted.captionVerticalOffset)
            assertEquals("right", persisted.captionAlignment)
            assertEquals("cinema", persisted.captionPreset)
            assertEquals(root.toAbsolutePath().normalize().toString(), persisted.downloadDirectory)
            assertEquals(":root {\n  --background: #101010;\n}", persisted.themeCss)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun unsupportedSubtitleLanguageFallsBackToEnglish() {
        val root = Files.createTempDirectory("AniLife-settings-language")
        try {
            val file = root.resolve("settings.properties").toFile()
            val store = AppSettingsStore(file)
            store.update { it.copy(subtitleLanguage = "unsupported") }
            assertEquals("en", AppSettingsStore(file).settings.value.subtitleLanguage)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun refreshButtonPlacementDefaultsToTheLeftBarAndPersistsEachChoice() {
        val root = Files.createTempDirectory("AniLife-settings-refresh")
        try {
            val file = root.resolve("settings.properties").toFile()
            assertEquals("sidebar", AppSettingsStore(file).settings.value.refreshButtonPlacement)
            for (placement in listOf("top-right", "bottom-right", "sidebar")) {
                AppSettingsStore(file).update { it.copy(refreshButtonPlacement = placement) }
                assertEquals(placement, AppSettingsStore(file).settings.value.refreshButtonPlacement)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun anUnknownRefreshButtonPlacementFallsBackToTheLeftBar() {
        val root = Files.createTempDirectory("AniLife-settings-refresh-bad")
        try {
            val file = root.resolve("settings.properties").toFile()
            AppSettingsStore(file).update { it.copy(refreshButtonPlacement = "middle") }
            assertEquals("sidebar", AppSettingsStore(file).settings.value.refreshButtonPlacement)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun onlyEpisodesYouCanWatchIsOffByDefaultAsOnAndroidAndPersistsWhenSwitchedOn() {
        val root = Files.createTempDirectory("AniLife-settings-continue")
        try {
            val file = root.resolve("settings.properties").toFile()
            assertEquals(false, AppSettingsStore(file).settings.value.hideUnairedFromContinue)
            AppSettingsStore(file).update { it.copy(hideUnairedFromContinue = true) }
            assertEquals(true, AppSettingsStore(file).settings.value.hideUnairedFromContinue)
            AppSettingsStore(file).update { it.copy(hideUnairedFromContinue = false) }
            assertEquals(false, AppSettingsStore(file).settings.value.hideUnairedFromContinue)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun homeSectionOrderAndVisibilityPersistAndMissingSectionsAreRestored() {
        val root = Files.createTempDirectory("AniLife-settings-home")
        try {
            val file = root.resolve("settings.properties").toFile()
            val store = AppSettingsStore(file)
            store.update {
                it.copy(
                    homeSectionOrder = listOf(HomeSection.MOVIES, HomeSection.NEW_EPISODES),
                    hiddenHomeSections = setOf(HomeSection.CONTINUE_WATCHING),
                )
            }

            val persisted = AppSettingsStore(file).settings.value

            assertEquals(
                listOf(HomeSection.MOVIES, HomeSection.NEW_EPISODES) +
                    HomeSection.entries.filterNot { it == HomeSection.MOVIES || it == HomeSection.NEW_EPISODES },
                persisted.homeSectionOrder,
            )
            assertEquals(setOf(HomeSection.CONTINUE_WATCHING), persisted.hiddenHomeSections)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun playerKeyBindingsPersistAndCanBeReset() {
        val root = Files.createTempDirectory("AniLife-settings-keys")
        try {
            val file = root.resolve("settings.properties").toFile()
            val store = AppSettingsStore(file)
            val rebound = rebindPlayerKey(DefaultPlayerKeyBindings, PlayerAction.MUTE, Key.Q).bindings
            store.update { it.copy(keyBindings = rebound) }

            val persisted = AppSettingsStore(file).settings.value
            assertEquals(Key.Q, persisted.keyBindings[PlayerAction.MUTE])

            store.update { it.copy(keyBindings = DefaultPlayerKeyBindings) }
            assertEquals(DefaultPlayerKeyBindings, AppSettingsStore(file).settings.value.keyBindings)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun gpuAccelerationDefaultsToAuto() {
        assertEquals("auto", AppSettings().gpuAcceleration)
    }

    @Test
    fun gpuAccelerationPersistsAcrossStoreInstances() {
        val root = Files.createTempDirectory("AniLife-settings-gpu")
        try {
            val file = root.resolve("settings.properties").toFile()
            val store = AppSettingsStore(file)
            store.update { it.copy(gpuAcceleration = "off") }
            assertEquals("off", AppSettingsStore(file).settings.value.gpuAcceleration)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalidGpuAccelerationValueIsNormalizedToAuto() {
        val root = Files.createTempDirectory("AniLife-settings-gpu-invalid")
        try {
            val file = root.resolve("settings.properties").toFile()
            File(file.absolutePath).writeText("gpuAcceleration=banana\n")
            assertEquals("auto", AppSettingsStore(file).settings.value.gpuAcceleration)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun legacyHardwareDecodingFalseMigratesToOff() {
        val root = Files.createTempDirectory("AniLife-settings-gpu-legacy")
        try {
            val file = root.resolve("settings.properties").toFile()
            File(file.absolutePath).writeText("hardwareDecoding=false\n")
            assertEquals("off", AppSettingsStore(file).settings.value.gpuAcceleration)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun legacyHardwareDecodingTrueMigratesToAuto() {
        val root = Files.createTempDirectory("AniLife-settings-gpu-legacy-true")
        try {
            val file = root.resolve("settings.properties").toFile()
            File(file.absolutePath).writeText("hardwareDecoding=true\n")
            assertEquals("auto", AppSettingsStore(file).settings.value.gpuAcceleration)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun newGpuAccelerationKeyWinsOverLegacyHardwareDecoding() {
        val root = Files.createTempDirectory("AniLife-settings-gpu-both")
        try {
            val file = root.resolve("settings.properties").toFile()
            File(file.absolutePath).writeText("gpuAcceleration=off\nhardwareDecoding=true\n")
            assertEquals("off", AppSettingsStore(file).settings.value.gpuAcceleration)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun updateDoesNotWriteLegacyHardwareDecodingKey() {
        val root = Files.createTempDirectory("AniLife-settings-gpu-write")
        try {
            val file = root.resolve("settings.properties").toFile()
            val store = AppSettingsStore(file)
            store.update { it.copy(gpuAcceleration = "off") }
            val content = File(file.absolutePath).readText()
            assertFalse(content.contains("hardwareDecoding"))
            assertTrue(content.contains("gpuAcceleration=off"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun experimentalTogglesPersistAndTransitionsAreClamped() {
        val root = Files.createTempDirectory("AniLife-settings-experimental")
        try {
            val file = root.resolve("settings.properties").toFile()
            val store = AppSettingsStore(file)
            store.update {
                it.copy(
                    expDebouncedSearch = false,
                    expImageCaching = false,
                    expAsyncFetching = true,
                    expPagination = true,
                    expFadeTransitions = false,
                    expHardwareScrolling = false,
                    expMpvBuffering = false,
                    expSmartDownloads = true,
                    expSmartDownloadsStartAfter = 9,
                )
            }

            val persisted = AppSettingsStore(file).settings.value
            assertEquals(false, persisted.expDebouncedSearch)
            assertEquals(false, persisted.expImageCaching)
            assertEquals(true, persisted.expAsyncFetching)
            assertEquals(true, persisted.expPagination)
            assertEquals(false, persisted.expFadeTransitions)
            assertEquals(false, persisted.expHardwareScrolling)
            assertEquals(false, persisted.expMpvBuffering)
            assertEquals(true, persisted.expSmartDownloads)
            assertEquals(3, persisted.expSmartDownloadsStartAfter)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun experimentalDefaultsKeepTheExistingBehaviourEnabled() {
        val settings = AppSettings()
        assertTrue(settings.expDebouncedSearch)
        assertTrue(settings.expImageCaching)
        assertTrue(settings.expFadeTransitions)
        assertTrue(settings.expHardwareScrolling)
        assertTrue(settings.expMpvBuffering)
        assertFalse(settings.expPagination)
        assertFalse(settings.expSmartDownloads)
    }
}

