package me.enivzoy.anilife.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.input.pointer.pointerHoverIcon
import me.enivzoy.anilife.app.ui.blockInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Desktop
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import me.enivzoy.anilife.app.data.AccountAdoption
import me.enivzoy.anilife.app.data.AccountLibrary
import me.enivzoy.anilife.app.data.CustomListHooks
import me.enivzoy.anilife.app.data.CustomListStoreInstance
import me.enivzoy.anilife.app.data.hiddenTagNames
import me.enivzoy.anilife.app.data.hiddenTagSet
import me.enivzoy.anilife.app.data.listKey
import me.enivzoy.anilife.app.data.MalProgressSync
import me.enivzoy.anilife.app.data.AniListRepository
import me.enivzoy.anilife.app.data.AnimeVideoProviders
import me.enivzoy.anilife.app.data.DiscordOAuth
import me.enivzoy.anilife.app.data.AppSettingsStore
import me.enivzoy.anilife.app.data.DEFAULT_DISCORD_APPLICATION_ID
import me.enivzoy.anilife.app.data.DiscordPresenceManager
import me.enivzoy.anilife.app.data.DownloadQueueManager
import me.enivzoy.anilife.app.data.DirectMediaDownloader
import me.enivzoy.anilife.app.data.DownloadStatus
import me.enivzoy.anilife.app.data.ProviderCatalog
import me.enivzoy.anilife.app.screens.BrowseScreen
import me.enivzoy.anilife.app.screens.AnimeWatchScreen
import me.enivzoy.anilife.app.screens.CommandPalette
import me.enivzoy.anilife.app.screens.DetailScreen
import me.enivzoy.anilife.app.screens.DownloadsScreen
import me.enivzoy.anilife.app.screens.HomeScreen
import me.enivzoy.anilife.app.screens.LibraryScreen
import me.enivzoy.anilife.app.screens.PlayerScreen
import me.enivzoy.anilife.app.screens.ProvidersScreen
import me.enivzoy.anilife.app.screens.ScheduleScreen
import me.enivzoy.anilife.app.screens.SettingsScreen
import me.enivzoy.anilife.app.screens.UpdateDialog
import me.enivzoy.anilife.app.screens.MyAnimeListScreen
import me.enivzoy.anilife.app.screens.NotificationsScreen
import me.enivzoy.anilife.app.data.MyAnimeListSync
import me.enivzoy.anilife.app.data.AniListSync
import me.enivzoy.anilife.app.ui.RefreshButton
import me.enivzoy.anilife.app.ui.RefreshHost
import me.enivzoy.anilife.app.data.Updater
import me.enivzoy.anilife.app.data.UpdateState
import me.enivzoy.anilife.app.data.UpdateException
import me.enivzoy.anilife.app.data.UpdateHttpException
import me.enivzoy.anilife.app.data.SizeMismatchException
import me.enivzoy.anilife.app.data.ChecksumException
import me.enivzoy.anilife.app.data.redactForLog
import me.enivzoy.anilife.app.data.MpvBootstrap
import me.enivzoy.anilife.app.data.NoticeStoreInstance
import me.enivzoy.anilife.app.data.ReleaseNotifier
import me.enivzoy.anilife.core.model.Update
import me.enivzoy.anilife.core.model.AnimeCard
import me.enivzoy.anilife.core.model.AnimeDetail
import me.enivzoy.anilife.core.diagnostics.ActionBreadcrumbs
import me.enivzoy.anilife.core.diagnostics.DiagnosticsPaths
import me.enivzoy.anilife.core.diagnostics.MemoryProfiler
import io.github.oshai.kotlinlogging.KotlinLogging
import me.enivzoy.anilife.app.ui.AniLifeColors
import me.enivzoy.anilife.app.ui.RemoteImages
import me.enivzoy.anilife.app.ui.AppSidebar
import me.enivzoy.anilife.app.ui.appEnter
import me.enivzoy.anilife.app.ui.liquidGlass
import me.enivzoy.anilife.app.ui.rememberAppScreenTransition
import me.enivzoy.anilife.mpv.Mpv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}
private val updaterLogger = KotlinLogging.logger("AniLife.updater")
private const val UPDATE_LOG_TAIL_BYTES = 200 * 1024

@Composable
fun AniLifeApp(
    settingsStore: AppSettingsStore,
    onPlayerFullscreen: (Boolean) -> Unit = {},
    onPlayerPip: (Boolean) -> Unit = {},
    onExitApplication: () -> Unit = {},
) {
    val animeVideoProviders = remember { AnimeVideoProviders() }
    val repository = remember {
        AniListRepository(
            titleLanguage = settingsStore.settings.value.titleLanguage,
            videoProviders = animeVideoProviders,
        )
    }
    val mediaProviders = remember { ProviderCatalog() }
    val discordPresence = remember { DiscordPresenceManager() }
    val releaseNotifier = remember(repository) { ReleaseNotifier(repository) }
    val updater = remember { Updater() }
    val availableUpdate by updater.update.collectAsState()
    val downloadScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val downloadQueue = remember(repository) { DownloadQueueManager(repository, downloadScope, 4) }
    val settings by settingsStore.settings.collectAsState()
    val discordState by discordPresence.state.collectAsState()
    val smartDownloaded = remember(repository) { mutableSetOf<String>() }
    LaunchedEffect(settings.expImageCaching) {
        RemoteImages.diskCacheEnabled = settings.expImageCaching
    }
    val backStack = remember { mutableStateListOf<Screen>() }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var homeLoaded by remember { mutableStateOf(false) }
    val refresh = remember { RefreshHost() }
    val aniListUser by AniListSync.user.collectAsState()
    val malUser by MyAnimeListSync.user.collectAsState()
    // Where the floating refresh button shows: the screens that load something to reload. Library only
    // with an account connected, since a signed-out list has nothing to pull. Never over the player,
    // Watch or Settings, so it cannot cover their controls.
    val showRefresh = when (screen) {
        Screen.Home, Screen.Browse, Screen.Schedule, is Screen.Detail -> true
        Screen.Library -> aniListUser != null || malUser != null
        else -> false
    }
    var playerFullscreen by remember { mutableStateOf(false) }
    var isPipMode by remember { mutableStateOf(false) }
    val consumedWatchAutoPlays = remember { mutableStateListOf<Long>() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var updateCheckStatus by remember { mutableStateOf<String?>(null) }
    var checkingForUpdates by remember { mutableStateOf(false) }
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var shareNotice by remember { mutableStateOf(false) }

    suspend fun checkForUpdates(showUpToDate: Boolean) {
        if (checkingForUpdates) return
        checkingForUpdates = true
        updateCheckStatus = if (showUpToDate) "Checking for updates…" else null
        shareNotice = false
        updater.clear()
        try {
            val currentVersion = Updater.currentVersion()
            val update = updater.check(currentVersion)
            updateCheckStatus = if (update == null && showUpToDate) {
                "AniLife $currentVersion is up to date."
            } else {
                null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val detail = failure.message?.takeIf(String::isNotBlank) ?: "Unknown error"
            updateCheckStatus = "Update check failed: $detail"
            updaterLogger.error {
                "update check failed ${failure::class.java.simpleName}: ${redactForLog(detail)}"
            }
        } finally {
            checkingForUpdates = false
        }
    }

    fun shareUpdateLogs() {
        val text = runCatching {
            val bytes = Files.readAllBytes(Updater.logFile())
            val tail = if (bytes.size > UPDATE_LOG_TAIL_BYTES) {
                bytes.copyOfRange(bytes.size - UPDATE_LOG_TAIL_BYTES, bytes.size)
            } else {
                bytes
            }
            String(tail, StandardCharsets.UTF_8)
        }.getOrElse { failure ->
            updaterLogger.warn {
                "update logs not read ${failure::class.java.simpleName}: " +
                    redactForLog(failure.message.orEmpty())
            }
            "updater.log is missing."
        }
        clipboard.setText(AnnotatedString(text))
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            runCatching { Desktop.getDesktop().open(DiagnosticsPaths.logs.toFile()) }
                .onFailure { failure ->
                    updaterLogger.warn {
                        "update logs folder not opened ${failure::class.java.simpleName}: " +
                            redactForLog(failure.message.orEmpty())
                    }
                }
        }
        shareNotice = true
    }

    fun installUpdate(update: Update) {
        if (updateState.busy) return
        scope.launch {
            shareNotice = false
            updateState = UpdateState.Downloading(null)
            var lastPercent = -1
            try {
                val extension = if (update.url.contains(".msi", ignoreCase = true)) "msi" else "exe"
                val destination = Updater.updatesDirectory().resolve("anilife-${update.version}.$extension")
                updater.download(update, destination) { bytes, total ->
                    val percent = if (total != null && total > 0L) {
                        (bytes * 100 / total).toInt()
                    } else {
                        -1
                    }
                    if (percent != lastPercent) {
                        lastPercent = percent
                        updateState = UpdateState.Downloading(if (percent >= 0) percent / 100f else null)
                    }
                }
                updateState = UpdateState.Installing
                updater.installWindows(destination)
                delay(1500L)
                updater.clear()
                onExitApplication()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                updaterLogger.error {
                    "install failed ${failure::class.java.simpleName}: " +
                        redactForLog(failure.message.orEmpty())
                }
                updateState = UpdateState.Failed(shortUpdateFailureMessage(failure))
            }
        }
    }

    LaunchedEffect(Unit) {
        MemoryProfiler.start(this)
        Updater.reportPreviousInstall()
        // A blank .env with the three keys, made once so there is a file to fill in. Never overwrites one.
        me.enivzoy.anilife.app.data.ApiCredentials.shared.ensureFile()
        AniListSync.restore()
        MyAnimeListSync.configure()
        MyAnimeListSync.restore()
        AccountLibrary.repository = repository
        AccountLibrary.syncSavedEnabled = { settingsStore.settings.value.syncSavedToAccount }
        CustomListHooks.hiddenMalTags = { hiddenTagSet(settingsStore.settings.value.hiddenMalTags) }
        CustomListHooks.revealMalTag = { tag ->
            settingsStore.update { it.copy(hiddenMalTags = hiddenTagNames(hiddenTagSet(it.hiddenMalTags).filterNot { t -> listKey(t) == listKey(tag) })) }
        }
        val pushScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        repository.onSaveChanged = { anime, saved -> pushScope.launch { AccountLibrary.saveToggled(anime, saved) } }
        repository.onBulkSaved = { pushScope.launch { AccountLibrary.pushSaved() } }
        AccountAdoption.settleAfterRestore()
        AccountLibrary.retryPending()
        AccountLibrary.refreshIfDue()
        CustomListStoreInstance.pushPending()
        MalProgressSync.drain()
        DiscordOAuth.restore()
    }
    DisposableEffect(Unit) {
        onDispose { MemoryProfiler.stop() }
    }
    LaunchedEffect(settings.checkUpdatesOnLaunch) {
        if (settings.checkUpdatesOnLaunch) {
            checkForUpdates(showUpToDate = false)
        } else {
            updater.clear()
            updateCheckStatus = null
        }
    }
    LaunchedEffect(Unit) {
        if (System.getProperty("AniLife.mpv.library") == null) {
            val path = MpvBootstrap.ensure { status -> NoticeStoreInstance.add(kind = "mpv", title = "mpv", body = status) }
            if (path == null) {
                NoticeStoreInstance.add(kind = "mpv", title = "mpv", body = "libmpv not found. Install mpv manually.")
            }
        }
    }

    // Configuration only: the player reports position/pause/episode changes directly to the worker.
    LaunchedEffect(settings.discordPresenceEnabled, settings.discordApplicationId) {
        discordPresence.configure(
            enabled = settings.discordPresenceEnabled,
            configuredApplicationId = settings.discordApplicationId,
        )
    }
    DisposableEffect(discordPresence) {
        onDispose { discordPresence.close() }
    }

    fun updateSettings(transform: (me.enivzoy.anilife.app.data.AppSettings) -> me.enivzoy.anilife.app.data.AppSettings) {
        settingsStore.update(transform)
        repository.setTitleLanguage(settingsStore.settings.value.titleLanguage)
    }

    fun connectDiscord() {
        val id = settings.discordApplicationId.ifBlank { DEFAULT_DISCORD_APPLICATION_ID }
        if (!settings.discordPresenceEnabled || settings.discordApplicationId != id) {
            updateSettings { it.copy(discordPresenceEnabled = true, discordApplicationId = id) }
        }
        discordPresence.configure(
            enabled = true,
            configuredApplicationId = id,
            reconnect = true,
        )
    }

    var picking by remember { mutableStateOf(false) }
    val appBusyHolds by me.enivzoy.anilife.app.data.AppBusy.holds.collectAsState()
    val appBusy = appBusyHolds > 0
    var pickError by remember { mutableStateOf<String?>(null) }
    val mpvLibrary = remember { runCatching { Mpv.defaultLibraryPath() }.getOrNull() }
    var showIntro by remember { mutableStateOf(settings.playIntro) }

    LaunchedEffect(screen) {
        playerFullscreen = screen is Screen.Player
        if (screen !is Screen.Player) isPipMode = false
    }
    LaunchedEffect(playerFullscreen) { onPlayerFullscreen(playerFullscreen) }
    LaunchedEffect(isPipMode) { onPlayerPip(isPipMode) }

    fun navigateTo(next: Screen) {
        if (next == screen) return
        backStack.add(screen)
        screen = next
    }

    fun goBack() {
        screen = backStack.removeLastOrNull() ?: Screen.Home
    }

    fun pickRandom() {
        if (picking) return
        picking = true
        scope.launch {
            try {
                val pick = repository.random()
                if (pick != null) {
                    navigateTo(Screen.Detail(pick.id))
                } else {
                    pickError = "AniList returned no title. Try the dice again."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                pickError = t.message?.takeIf { it.isNotBlank() } ?: "Couldn't reach AniList."
            } finally {
                picking = false
            }
        }
    }

    suspend fun resolvePlayerEpisode(target: Screen.Player, episode: Double, audio: String) {
        val anime = target.detail ?: return
        val startedAt = System.nanoTime()
        val rows = animeVideoProviders.episodes(anime.id, anime.episodes, anime.malId)
        val resolved = animeVideoProviders.sources(
            anilistId = anime.id,
            malId = anime.malId,
            titles = listOfNotNull(anime.title, anime.titleRomaji, anime.titleNative) + anime.synonyms,
            episode = episode,
            dub = audio.equals("Dub", ignoreCase = true),
        )
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        val selected = resolved.firstOrNull()
        if (selected == null) {
            logger.warn { "stream resolution found no servers; episode=$episode audio=$audio elapsedMs=$elapsedMs" }
            return
        }
        val fallback = !target.url.isNullOrBlank() && resolved.none { it.url == target.url }
        logger.info {
            "stream resolved episode=$episode audio=$audio servers=${resolved.size} elapsedMs=$elapsedMs " +
                "fallback=$fallback selected=${selected.provider}/${selected.server}"
        }
        if (fallback) logger.warn { "fallback server used for episode=$episode selected=${selected.provider}/${selected.server}" }
        val current = screen as? Screen.Player ?: return
        if (current.detail?.id != anime.id) return
        val label = formatPlayerEpisode(episode)
        screen = current.copy(
            title = "${anime.title} · Episode $label · ${selected.provider} ${selected.quality ?: selected.format.uppercase()}",
            url = selected.url,
            headers = selected.headers,
            alternatives = resolved,
            audioTrack = audio,
            episode = episode,
            episodeTitle = rows.firstOrNull { it.number == episode }?.title,
            episodes = rows.map { it.number },
            episodeTitles = rows.mapNotNull { row -> row.title?.takeIf(String::isNotBlank)?.let { row.number to it } }.toMap(),
            episodeThumbnails = rows.mapNotNull { row -> row.thumbnail?.takeIf(String::isNotBlank)?.let { row.number to it } }.toMap(),
        )
    }

    fun smartDownloadNext(anime: AnimeDetail?, episode: Double, audio: String) {
        if (!settings.expSmartDownloads || anime == null) return
        val key = "${anime.id}:$episode"
        if (key in smartDownloaded) return
        val alreadyDownloaded = repository.localEpisodes.value.any { it.anime.id == anime.id && it.episode == episode }
        val alreadyQueued = downloadQueue.jobs.value.any { job ->
            job.anime?.id == anime.id && job.episode == episode &&
                job.status != DownloadStatus.Failed && job.status != DownloadStatus.Cancelled
        }
        if (alreadyDownloaded || alreadyQueued) return
        smartDownloaded += key
        scope.launch {
            try {
                val titles = listOfNotNull(anime.title, anime.titleRomaji, anime.titleNative) + anime.synonyms
                val sources = animeVideoProviders.sources(
                    anilistId = anime.id,
                    malId = anime.malId,
                    titles = titles,
                    episode = episode,
                    dub = audio.equals("Dub", ignoreCase = true),
                )
                val source = sources.firstOrNull()
                if (source == null) {
                    smartDownloaded -= key
                    return@launch
                }
                val root = settings.downloadDirectory?.takeIf(String::isNotBlank)?.let(java.nio.file.Path::of)
                    ?: DirectMediaDownloader.defaultDownloadDirectory()
                downloadQueue.enqueue(
                    title = anime.title,
                    episode = episode,
                    url = source.url,
                    audio = source.audio.lowercase(),
                    downloadRoot = root,
                    anime = anime.toCard(),
                    headers = source.headers,
                )
            } catch (cancelled: CancellationException) {
                smartDownloaded -= key
                throw cancelled
            } catch (_: Throwable) {
                smartDownloaded -= key
            }
        }
    }

    fun deleteSmartDownload(anime: AnimeDetail?, episode: Double) {
        if (!settings.expSmartDownloads || anime == null) return
        if ("${anime.id}:$episode" !in smartDownloaded) return
        val entry = repository.localEpisodes.value
            .firstOrNull { it.anime.id == anime.id && it.episode == episode } ?: return
        downloadQueue.jobs.value.firstOrNull { it.localEpisode?.path == entry.path }
            ?.let { downloadQueue.remove(it.id) }
        runCatching { Files.deleteIfExists(entry.path) }
        repository.removeLocalEpisode(entry)
        smartDownloaded -= "${anime.id}:$episode"
    }

    var paletteOpen by remember { mutableStateOf(false) }
    var accountOpen by remember { mutableStateOf(false) }
    // The account the lists follow: AniList or MyAnimeList, whichever is the active one.
    val accountService = me.enivzoy.anilife.app.data.serviceToKeep(
        aniListUser != null, malUser != null, null,
    )
    val accountName = if (accountService == me.enivzoy.anilife.app.data.ListService.ANILIST) aniListUser?.name else malUser?.name
    val accountAvatar = if (accountService == me.enivzoy.anilife.app.data.ListService.ANILIST) aniListUser?.avatar else malUser?.picture

    Box(
        Modifier.fillMaxSize()
            .onPreviewKeyEvent { event ->
                // While a sync, restore, import or random pick runs, the keyboard does nothing.
                if (appBusy || picking) {
                    true
                } else if (event.type == KeyEventType.KeyDown &&
                    (event.isCtrlPressed || event.isMetaPressed) &&
                    event.key == Key.K
                ) {
                    paletteOpen = !paletteOpen
                    true
                } else {
                    false
                }
            },
    ) {
    Row(Modifier.fillMaxSize()) {
        if (screen !is Screen.Player) {
            AppSidebar(
                current = screen,
                onNavigate = ::navigateTo,
                onRandom = ::pickRandom,
                onSearch = { paletteOpen = true },
                refresh = if (settings.refreshButtonPlacement == "sidebar") refresh else null,
                refreshShown = showRefresh,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape &&
                        (screen is Screen.Detail || screen is Screen.Watch || screen is Screen.Player)
                    ) {
                        goBack()
                        true
                    } else {
                        false
                    }
                },
        ) {
            key(settings.titleLanguage) {
            val screenTransition = rememberAppScreenTransition()
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    if (settings.expFadeTransitions) screenTransition
                    else EnterTransition.None togetherWith ExitTransition.None
                },
                label = "screen",
            ) { target ->
                Box(Modifier.fillMaxSize().appEnter()) {
                when (target) {
                    Screen.Home -> HomeScreen(
                        repository = repository,
                        settings = settings,
                        onSettingsChange = ::updateSettings,
                        onOpen = { navigateTo(Screen.Detail(it)) },
                        onContinue = { entry ->
                            scope.launch {
                                val detail = runCatching { repository.detail(entry.anime.id) }.getOrNull()
                                if (detail != null) {
                                    val nextEp = (entry.progress.episode).takeIf { it > 0.0 }
                                    navigateTo(Screen.Watch(detail, nextEp))
                                } else {
                                    navigateTo(Screen.Detail(entry.anime.id))
                                }
                            }
                        },
                        refresh = refresh,
                        onContentReady = { homeLoaded = true },
                    )
                    Screen.Browse -> BrowseScreen(
                        repository = repository,
                        settings = settings,
                        onOpen = { navigateTo(Screen.Detail(it)) },
                        refresh = refresh,
                    )
                    Screen.Schedule -> ScheduleScreen(repository = repository, onOpen = { navigateTo(Screen.Detail(it)) }, refresh = refresh)
                    Screen.Library -> LibraryScreen(
                        repository = repository,
                        hiddenPosterElements = settings.hiddenPosterElements,
                        onOpen = { navigateTo(Screen.Detail(it)) },
                        onBrowse = { navigateTo(Screen.Browse) },
                        refresh = refresh,
                    )
                    Screen.Settings -> SettingsScreen(
                        settings = settings,
                        repository = repository,
                        discordState = discordState,
                        onSettingsChange = ::updateSettings,
                        onDiscordConnect = ::connectDiscord,
                        currentVersion = Updater.currentVersion(),
                        updateCheckStatus = updateCheckStatus,
                        checkingForUpdates = checkingForUpdates,
                        onCheckForUpdates = { scope.launch { checkForUpdates(showUpToDate = true) } },
                    )
                    Screen.Providers -> ProvidersScreen(catalog = mediaProviders)
                    Screen.Notifications -> NotificationsScreen(
                        store = NoticeStoreInstance,
                        notifier = releaseNotifier,
                        onOpenAnime = { navigateTo(Screen.Detail(it)) },
                    )
                    Screen.Profile -> MyAnimeListScreen(repository, settings, ::updateSettings, onOpenAccount = { accountOpen = true })
                    Screen.Downloads -> DownloadsScreen(
                        repository = repository,
                        settings = settings,
                        onSettingsChange = ::updateSettings,
                        onPlay = { title, url -> navigateTo(Screen.Player(title, url)) },
                        onOpen = { id -> navigateTo(Screen.Detail(id)) },
                        onBrowse = { navigateTo(Screen.Browse) },
                        queueManager = downloadQueue,
                    )
                    is Screen.Detail -> DetailScreen(
                        repository = repository,
                        providers = animeVideoProviders,
                        id = target.id,
                        onBack = ::goBack,
                        onOpen = { navigateTo(Screen.Detail(it)) },
                        onWatch = { detail, episode -> navigateTo(Screen.Watch(detail, episode)) },
                        queueManager = downloadQueue,
                        settings = settings,
                        refresh = refresh,
                    )
                    is Screen.Watch -> AnimeWatchScreen(
                        detail = target.detail,
                        repository = repository,
                        providers = animeVideoProviders,
                        initialEpisode = target.initialEpisode,
                        initialAutoPlayConsumed = target.navId in consumedWatchAutoPlays,
                        onInitialAutoPlay = { if (target.navId !in consumedWatchAutoPlays) consumedWatchAutoPlays += target.navId },
                        onBack = ::goBack,
                        onPlay = { title, url, headers, alternatives, episode, episodeTitle, episodes, episodeTitles, episodeThumbnails ->
                            navigateTo(
                                Screen.Player(
                                    title = title,
                                    url = url,
                                    headers = headers,
                                    alternatives = alternatives,
                                    audioTrack = alternatives.firstOrNull { it.url == url }?.audio ?: "Sub",
                                    detail = target.detail,
                                    episode = episode,
                                    episodeTitle = episodeTitle,
                                    episodes = episodes,
                                    episodeTitles = episodeTitles,
                                    episodeThumbnails = episodeThumbnails,
                                ),
                            )
                        },
                    )
                    is Screen.Player -> PlayerScreen(
                        title = target.title,
                        showTitle = target.detail?.title?.takeIf(String::isNotBlank) ?: target.title.substringBefore(" · Episode "),
                        url = target.url,
                        headers = target.headers,
                        alternatives = target.alternatives,
                        audioTrack = target.audioTrack,
                        episode = target.episode,
                        episodeTitle = target.episodeTitle,
                        durationMinutes = target.detail?.duration,
                        episodes = target.episodes,
                        episodeTitles = target.episodeTitles,
                        episodeThumbnails = target.episodeThumbnails,
                        detail = target.detail,
                        settings = settings,
                        onSettingsChange = { updated -> updateSettings { updated } },
                        isFullscreen = playerFullscreen,
                        isPipMode = isPipMode,
                        onTogglePip = { isPipMode = !isPipMode },
                        onClosePip = { isPipMode = false },
                        onToggleFullscreen = { playerFullscreen = !playerFullscreen },
                        onSourceSelect = { source ->
                            val serverName = source.server.takeIf(String::isNotBlank) ?: source.provider
                            ActionBreadcrumbs.record("Clicked server: $serverName")
                            logger.info { "server selected provider=${source.provider} server=${source.server} quality=${source.quality} url=${source.url}" }
                            screen = target.copy(
                                title = "${target.detail?.title ?: target.title.substringBefore(" · Episode ")} · Episode ${formatPlayerEpisode(target.episode ?: 1.0)} · ${source.provider} ${source.quality ?: source.format.uppercase()}",
                                url = source.url,
                                headers = source.headers,
                                audioTrack = source.audio,
                            )
                        },
                        onAudioChange = { audio ->
                            val activeEpisode = target.episode
                            if (activeEpisode != null) scope.launch { resolvePlayerEpisode(target, activeEpisode, audio) }
                        },
                        onSmartDownload = { nextEpisode ->
                            smartDownloadNext(target.detail, nextEpisode, target.audioTrack)
                        },
                        onSmartDelete = { oldEpisode -> deleteSmartDownload(target.detail, oldEpisode) },
                        onEpisodeChange = { episode ->
                            if (target.detail != null) scope.launch { resolvePlayerEpisode(target, episode, target.audioTrack) }
                            else screen = target.copy(episode = episode, episodeTitle = target.episodeTitles[episode])
                        },
                        onPresence = { presence ->
                            if (presence != null) {
                                discordPresence.show(presence)
                            } else {
                                // mpv reports an end-of-file and Compose disposes the old engine when
                                // switching episodes. Don't flash idle unless we really left the player.
                                if (screen !is Screen.Player) {
                                    discordPresence.clearPlayer()
                                } else {
                                    scope.launch {
                                        delay(700)
                                        if (screen !is Screen.Player) discordPresence.clearPlayer()
                                    }
                                }
                            }
                        },
                        onBack = ::goBack,
                    )
                }
                }
                }
            }
            if (showRefresh && settings.refreshButtonPlacement != "sidebar") {
                val corner = if (settings.refreshButtonPlacement == "top-right") Alignment.TopEnd else Alignment.BottomEnd
                RefreshButton(refresh, Modifier.align(corner).padding(24.dp))
            }
        }
    }
        if (showIntro) IntroOverlay(homeReady = homeLoaded, onFinished = { showIntro = false })
    }

    if (picking) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AniLifeColors.Scrim.copy(alpha = 0.35f))
                .blockInput()
                .pointerHoverIcon(me.enivzoy.anilife.app.ui.NotAllowedPointer, overrideDescendants = true),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(AniLifeColors.Card)
                    .padding(horizontal = 22.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = AniLifeColors.Foreground,
                    strokeWidth = 2.dp,
                )
                Text("Rolling the dice…", color = AniLifeColors.Foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    // A sync, restore or import is running: nothing in the window can be clicked until it ends.
    if (appBusy) me.enivzoy.anilife.app.ui.BusyOverlay()

    if (accountOpen && accountService != null && accountName != null) {
        me.enivzoy.anilife.app.ui.AccountSheet(
            service = accountService,
            name = accountName,
            avatar = accountAvatar,
            onOpenAnime = { accountOpen = false; navigateTo(Screen.Detail(it)) },
            onDismiss = { accountOpen = false },
        )
    }
    if (paletteOpen) {
        CommandPalette(
            repository = repository,
            onOpenAnime = { navigateTo(Screen.Detail(it)) },
            onNavigate = ::navigateTo,
            onDismiss = { paletteOpen = false },
        )
    }

    pickError?.let { message ->
        AlertDialog(
            onDismissRequest = { pickError = null },
            modifier = Modifier.liquidGlass(shape = RoundedCornerShape(28.dp)).appEnter(),
            containerColor = AniLifeColors.Card,
            titleContentColor = AniLifeColors.Foreground,
            textContentColor = AniLifeColors.MutedForeground,
            title = { Text("Random pick failed", fontWeight = FontWeight.SemiBold) },
            text = { Text(message, fontSize = 14.sp, lineHeight = 21.sp) },
            confirmButton = {
                TextButton(onClick = { pickError = null }) {
                    Text("Close", color = AniLifeColors.Primary, fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }

    availableUpdate?.let { update ->
        UpdateDialog(
            update = update,
            state = updateState,
            onUpdate = {
                shareNotice = false
                installUpdate(update)
            },
            onLater = {
                updater.clear()
                updateState = UpdateState.Idle
                shareNotice = false
            },
            onShareLogs = { shareUpdateLogs() },
            shareNotice = shareNotice,
        )
    }
}

private fun shortUpdateFailureMessage(failure: Exception): String = when (failure) {
    is UpdateHttpException -> "Update request failed (HTTP ${failure.status})."
    is SizeMismatchException -> "Downloaded file size didn't match. Retry or share logs."
    is ChecksumException -> "Checksum didn't match. Retry or share logs."
    is UpdateException -> failure.message?.takeIf(String::isNotBlank) ?: "Update failed."
    else -> "Update failed. Check your connection and retry."
}

private fun formatPlayerEpisode(number: Double): String =
    if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()

private fun AnimeDetail.toCard() = AnimeCard(
    id = id,
    title = title,
    titleRomaji = titleRomaji,
    titleEnglish = titleEnglish,
    titleNative = titleNative,
    malId = malId,
    cover = cover,
    color = color,
    banner = banner,
    format = format,
    status = status,
    episodes = episodes,
    score = score,
    year = year,
    season = season,
    genres = genres,
    nextAiring = nextAiring,
    isAdult = isAdult,
)

    
