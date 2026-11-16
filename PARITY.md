# Library parity with the Android app

The Library page is ported from the Android app (`anilifecommunity/android-and-tv`). Android is the
specification: where the two differ, Android is right unless a row below says otherwise. Android paths
are under `app/src/main/java/com/anilili/`, Windows paths under `app/src/main/kotlin/me/enivzoy/anilife/app/`.

Status: **Done** (ported and tested), **Planned**, **Not ported** (no Windows equivalent; reason given).

## Rules

| Android | Windows | Status | Notes |
|---|---|---|---|
| `ui/profile/ProfileScreen.kt` `LibraryView`, `LibrarySort` | `data/LibraryRules.kt` | Done | Same names, order and labels |
| `libraryViewsFrom`, `toggledLibraryViews`, `defaultLibraryViewNames` | `data/LibraryRules.kt` | Done | `LibraryViewSelectionTest` ported (7) |
| `libraryCards` (filter and sort) | `data/LibraryRules.kt` | Done | Windows adds its own sort and filter tests (3) |
| `buildCombinedWatchlist` | `data/LibraryRules.kt` | Done | `CombinedWatchlistTest`, `LibraryWaveOrderTest` ported (4). Takes `ListEntry`/`LocalSave` instead of `AniListProfile` |
| `completedCards` | `data/LibraryRules.kt` | Done, reduced | Account Completed only; Android also adds titles marked watched on the device |
| `newEpisodeCount` | `data/LibraryRules.kt` | Done | `NewEpisodeCountTest` ported (11) |
| `WatchlistEntry.addedAt` | `LocalLibraryStore.saves`, `LocalSave` | Done | Date kept per save in `library.properties` (`anime.<id>.addedAt`); older files load as 0 |
| `MediaListEntry` | `data/LibraryRules.kt` `ListEntry` | Done (type) | Producers for MAL and AniList: Planned |

## Data and sync

| Android | Windows | Status | Notes |
|---|---|---|---|
| `MiruroRepository.malAnimeList` (MAL list as `MediaListEntry`) | `data/MalListModel.kt`, `data/MalIdMap.kt`, `MyAnimeListSync.rows()` | Done | Paged read with Android's fields, status maps, episode ceiling and clamp ported. MAL ids resolve through Android's bundled `id-map.json` (copied as is), then the Konoha CDN shard (30 day cache), then AniList `idMal_in`, as Android does |
| `LibraryStore.hydrateRemoteLibrary` | `data/LibraryHydration.kt`, `data/AccountLibrary.kt` | Done, wired at startup and on sign-in | Hidden entries ignored, saves merge, rows move forward, moved-off and finished rows removed, Watching titles seeded. 12 tests of Windows' own. |
| `mergeWatchlistEntries` | `data/LibrarySyncRules.kt` | Done (rule) | `WatchlistRemovalTest` ported (7) plus one date-kept case. Wiring: Planned |
| `HistoryEntry.watchedEpisodes`, `historyAfterRemoteProgress`, `historyAfterEpisodeWatched`, `remoteRowEpisode`, `remoteMovesOffWatching`, `isFinishedSeriesLeftover`, `shouldRecordWatchActivity`, `seedLegacyWatchedEpisodes` | `data/LibrarySyncRules.kt`, `ContinueEntry.watched` and `fromRemote`, `ContinueStore` | Done | Watched set is stored per Continue row, grown by the account's count, recorded when the player crosses the watched threshold (the AniList submit share; at 100 only the end counts), and seeded once for rows saved before it existed. Tests ported with Windows rows in place of `HistoryEntry` |
| `AccountAdoption` (one active account) | `data/AccountAdoption.kt` | Done, partly | New sign-in succeeds first, then the other service is logged out and its cached list cleared. Both-connected users keep the most recent sign-in (AniList if unknown). Pushing saved titles to the new account: Planned |
| `LibraryStore.syncSavedToRemote`, `toggleWatchlist`, `retryPendingSavedSync` | `AccountLibrary`, `SavedPush.kt`, `PendingSavedStore`, setting `syncSavedToAccount` | Done | Saving adds Planning; unsaving removes only an entry still on Planning; failures are queued and retried on launch; queue cleared when the account changes. Detail screen Save now always toggles the local save (before, it edited the AniList entry directly). Custom-list hiding arrives with step 7 |
| `planMediaListProgressUpdate`, `saveMalProgress`, `malProgressSkipReason`, `finishedOnAccount` | `data/ProgressRules.kt`, `data/MalProgressSync.kt`, `AniListSync.syncPendingProgress` | Done | Both services follow one plan: never lower a count, leave Completed alone, finish on the last episode, count a rewatch. MAL is held to its own episode total. Android's AniList and MAL tests ported (`ProgressRulesTest`). Failed MAL pushes are kept and retried at launch (up to 6 tries, as Android's worker gives up). Windows pushes MAL at the same watched threshold as AniList; it used to wait for the end of the episode |
| `WatchActivityStore`, `mergedActivity`, `activityStreak`, `shouldRecordWatchActivity`, `ActivityHeatmap`, `activityHistory` | `data/WatchActivity.kt`, `ui/ActivityHeatmap.kt`, `AniListSync.refreshActivity` | Done (needs a visual check on a PC) | Android's two tests ported. Grid sits on the Accounts screen, because Windows has no account drawer. The Android drawer's other Overview and Stats panels are not part of My List and are not ported. Backups do not carry the log (Android's does not either) |
| `CustomListStore`, `CustomLists.kt` | | Planned | AniList lists, MAL tags, local when signed out |

## Screen

| Android | Windows | Status | Notes |
|---|---|---|---|
| Chips with counts, search, filters, sort, carousel or expand, Continue Watching section | `screens/LibraryScreen.kt` | Done (needs a visual check on a PC) | Result count sums the chosen lists. Right-click hint replaces Android's long-press wording. Choices last for the app session. Android's DUB badge and Downloads are not ported |
| `SavedAnimeCard` delete disc and confirm | `screens/LibraryScreen.kt` | Done | Both services (AniList delete, MAL `removeEntry`) |
| `AnimeListActionsDialog`, `ContinueWatchingActionsDialog`, `AnimeScoreDialog`, `AnimeProgressDialog` | `screens/ListActionsDialog.kt`, `data/ListEdits.kt` | Done, except the rows named below (needs a visual check on a PC) | Right click on Windows. Move on either service, score 1 to 10, episode count up or down, delete with confirm, remove from watchlist. Library posters and Continue cards, plus Home's Continue cards |
| `planRewatchMove`, `rewatchArguments`, `aniListScoreRaw`, `malWriteMismatch`, `savedRowSurvivesMoveTo` | `data/ListEditRules.kt` | Done (rule) | 7 tests in `ListEditRulesTest` |
| `updateAnimeListStatus`, `updateAnimeListScore`, `setAnimeListProgress`, `deleteAnimeListEntry`, `setAnimeWatched` | `data/ListEdits.kt`, `AniListAccountApi`, `MyAnimeListSync.writeEntry` | Done | Same GraphQL and MAL writes, with the same confirm-after-write checks and rewatch handling |
| `PosterElement` settings | `data/LibraryRules.kt`, Settings screen | Done | Android keeps the switched-off ones, so does Windows. No DUB (needs the dub index) or 18+ switch |

## Menu differences

- The Detail page's Save button still toggles the local save; on Android it opens this menu. Outside the My List page.
- "My lists" rows in the menu arrive with custom lists (step 7).
- Android's "Watched" row for a signed-out viewer belongs to LocalWatchedStore, not ported.
- Android also tells Simkl about moves and removals; Simkl is not part of Windows.

## Not ported

| Android | Why |
|---|---|
| `WatchNextManager` (TV launcher row) and TV remote focus | Android TV only |
| Downloads section on the Library page | Windows has its own Downloads screen |
| `LocalWatchedStore` (mark watched) | Windows has no such feature |
| Encrypted logins, recent searches in backups | No Windows equivalent |

## Keeping the two in step

1. When Android changes one of the functions above, change its Windows twin and its test in the same pull request.
2. The ported tests keep Android's case names, so a missing case is easy to spot by comparing the two files.
3. Re-read this table when pulling a new Android version, and update the Status column as work lands.

## Known differences outside the Library page

- Android keeps a resume point per part-watched episode (`resumePoints`) and lets the viewer mark episodes watched by hand. Windows keeps one resume point per title, and its Continue row follows the episode last played. Neither is on the Library page; both belong with the Detail and Player pages.
