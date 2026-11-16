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
| `historyAfterRemoteProgress`, `remoteRowEpisode`, `remoteMovesOffWatching`, `isFinishedSeriesLeftover` | `data/LibrarySyncRules.kt` | Done (rules), reduced | `RemoteProgressReconcileTest` ported (19). Windows rows have no watched-episode set, so the set is not filled in and "finished" means past 90% of the last episode. Wiring and seeding: Planned |
| `AccountAdoption` (one active account) | `data/AccountAdoption.kt` | Done, partly | New sign-in succeeds first, then the other service is logged out and its cached list cleared. Both-connected users keep the most recent sign-in (AniList if unknown). Pushing saved titles to the new account: Planned |
| `LibraryStore.syncSavedToRemote` | `AccountLibrary.pushSaved`, setting `syncSavedToAccount` | Done, one difference | Pushes saves the account lacks as Planning when the setting is on (default on). Android also keeps a retry queue for failed pushes; Windows retries on the next sign-in or refresh only |
| `planMediaListProgressUpdate`, `saveMalProgress` | | Planned | MAL progress rules during playback |
| `WatchActivityStore`, `mergedActivity`, `activityStreak` | | Planned | Activity grid |
| `CustomListStore`, `CustomLists.kt` | | Planned | AniList lists, MAL tags, local when signed out |

## Screen

| Android | Windows | Status | Notes |
|---|---|---|---|
| Chips with counts, search, filters, sort, carousel or expand | `screens/LibraryScreen.kt` | Planned | |
| `SavedAnimeCard` long press (`AnimeListActionsDialog`) | `screens/LibraryScreen.kt` | Planned | Right click on Windows |
| `PosterElement` settings | | Planned | Which badges show |

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
