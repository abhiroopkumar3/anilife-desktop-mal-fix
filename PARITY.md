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
| `MediaListEntry` | `data/LibraryRules.kt` `ListEntry` | Done | Producers: `aniListEntriesToEntries` and `malRowsToEntries` (`data/MalListModel.kt`), both carrying the hidden flag and custom lists |

## Data and sync

| Android | Windows | Status | Notes |
|---|---|---|---|
| `MiruroRepository.malAnimeList` (MAL list as `MediaListEntry`) | `data/MalListModel.kt`, `data/MalIdMap.kt`, `MyAnimeListSync.rows()` | Done | Paged read with Android's fields, status maps, episode ceiling and clamp ported. MAL ids resolve through Android's bundled `id-map.json` (copied as is), then the Konoha CDN shard (30 day cache), then AniList `idMal_in`, as Android does |
| `LibraryStore.hydrateRemoteLibrary` | `data/LibraryHydration.kt`, `data/AccountLibrary.kt` | Done, wired at startup and on sign-in | Hidden entries ignored, saves merge, rows move forward, moved-off and finished rows removed, Watching titles seeded. 12 tests of Windows' own. |
| `mergeWatchlistEntries` | `data/LibrarySyncRules.kt` | Done (rule) | `WatchlistRemovalTest` ported (7) plus one date-kept case. Wired in `AccountLibrary.refresh` |
| `HistoryEntry.watchedEpisodes`, `historyAfterRemoteProgress`, `historyAfterEpisodeWatched`, `remoteRowEpisode`, `remoteMovesOffWatching`, `isFinishedSeriesLeftover`, `shouldRecordWatchActivity`, `seedLegacyWatchedEpisodes` | `data/LibrarySyncRules.kt`, `ContinueEntry.watched` and `fromRemote`, `ContinueStore` | Done | Watched set is stored per Continue row, grown by the account's count, recorded when the player crosses the watched threshold (the AniList submit share; at 100 only the end counts), and seeded once for rows saved before it existed. Tests ported with Windows rows in place of `HistoryEntry` |
| `AccountAdoption` (one active account) | `data/AccountAdoption.kt` | Done, partly | New sign-in succeeds first, then the other service is logged out and its cached list cleared. Both-connected users keep the most recent sign-in (AniList if unknown). Saved titles are pushed to the new account by `AccountLibrary.adopted` |
| `LibraryStore.syncSavedToRemote`, `toggleWatchlist`, `retryPendingSavedSync` | `AccountLibrary`, `SavedPush.kt`, `PendingSavedStore`, setting `syncSavedToAccount` | Done | Saving adds Planning; unsaving removes only an entry still on Planning; failures are queued and retried on launch; queue cleared when the account changes. Detail screen Save now always toggles the local save (before, it edited the AniList entry directly). A Planning entry that also sits in custom lists is hidden from the status lists instead of deleted on unsave, and saving it again shows it (`SHOW_ON_PLANNING`, `HIDE_FROM_STATUS_LISTS`) |
| `planMediaListProgressUpdate`, `saveMalProgress`, `malProgressSkipReason`, `finishedOnAccount` | `data/ProgressRules.kt`, `data/MalProgressSync.kt`, `AniListSync.syncPendingProgress` | Done | Both services follow one plan: never lower a count, leave Completed alone, finish on the last episode, count a rewatch. MAL is held to its own episode total. Android's AniList and MAL tests ported (`ProgressRulesTest`). Failed MAL pushes are kept and retried at launch (up to 6 tries, as Android's worker gives up). Windows pushes MAL at the same watched threshold as AniList; it used to wait for the end of the episode |
| `WatchActivityStore`, `mergedActivity`, `activityStreak`, `shouldRecordWatchActivity`, `ActivityHeatmap`, `activityHistory` | `data/WatchActivity.kt`, `ui/ActivityHeatmap.kt`, `AniListSync.refreshActivity` | Done (needs a visual check on a PC) | Android's two tests ported. Grid sits on the Accounts screen, because Windows has no account drawer. The Android drawer's other Overview and Stats panels are not part of My List and are not ported. Backups do not carry the log (Android's does not either) |
| `CustomLists.kt` (`normalizedListName`, `CustomListOps`, `plannedListNames`, `plannedEntryChanges`, `mergedCustomLists`) | `data/CustomListRules.kt` | Done (rule) | 14 ported cases in `CustomListRulesTest`, plus `CustomListCardsTest` and `HiddenEntriesTest` |
| `CustomListStore` | `data/CustomListStore.kt`, `data/CustomListSync.kt`, `data/AnimeCardProperties.kt`, file `custom-lists.properties` | Done, needs a visual check | AniList custom lists, MAL tags, and a local list when signed out. Changes are recorded as pending ops and replayed against the account; a refresh lays them on top of what the account holds. Leaving an account turns every list back into pending ops for the next one. 10 cases in `CustomListStoreTest` |
| `AppBackup.customLists`, `CustomListStore.importLists` | `data/AppBackup.kt` `BackupCustomList`, `data/DataTransfer.kt` | Done | A backup writes every custom list with its titles, and a restore merges them the way Android does: a list the PC already has (any letter case) pools titles, a new one is added, titles are filled in from AniList, and the changes reach the signed-in account through the same pending queue. Three tests in `DataTransferTest` |
| `aniListCollectionForCustomLists`, `setCustomListNames`, `saveCustomListMemberships`, `deleteMediaListEntries` | `AniListAccountApi`, `AniListSync` | Done | Same GraphQL. A title put in a list with no account entry is made as Planning hidden from the status lists; one left in no list is deleted |
| `MalClient.updateListStatus(tags=)` | `MyAnimeListSync.setTags` | Done | A title MAL lacks is added as Plan to Watch |
| `hiddenFromStatusLists` on entries | `AniListListEntry.hidden`, `ListEntry.hiddenFromStatusLists` | Done | Windows used to ignore the flag; custom-list-only entries no longer appear as Planning or in the watchlist |
| Custom-list chips, sections, `CustomListNameDialog`, `CustomListManageDialog` | `screens/LibraryScreen.kt`, `screens/CustomListDialogs.kt` | Done, needs a visual check | One chip per list after the status chips, then "+ New list". Each shown list is a section with Edit (rename or delete) and Expand |
| `MalTagsDialog`, `hiddenMalTags` | `screens/CustomListDialogs.kt`, `AppSettings.hiddenMalTags`, Accounts page button | Done | Hidden tags stay on MyAnimeList untouched and are written back unchanged |

## Screen

| Android | Windows | Status | Notes |
|---|---|---|---|
| Chips with counts, search, filters, sort, carousel or expand, Continue Watching section | `screens/LibraryScreen.kt` | Done (needs a visual check on a PC) | Result count sums the chosen lists. Right-click hint replaces Android's long-press wording. Choices last for the app session. Android's DUB badge and Downloads are not ported |
| `SavedAnimeCard` delete disc and confirm | `screens/LibraryScreen.kt` | Done | Both services (AniList delete, MAL `removeEntry`) |
| `AnimeListActionsDialog`, `ContinueWatchingActionsDialog`, `AnimeScoreDialog`, `AnimeProgressDialog` | `screens/ListActionsDialog.kt`, `data/ListEdits.kt` | Done, except the rows named below (needs a visual check on a PC) | Right click on Windows. Move on either service, score 1 to 10, episode count up or down, delete with confirm, remove from watchlist. Library posters and Continue cards, plus Home's Continue cards |
| `planRewatchMove`, `rewatchArguments`, `aniListScoreRaw`, `malWriteMismatch`, `savedRowSurvivesMoveTo` | `data/ListEditRules.kt` | Done (rule) | 7 tests in `ListEditRulesTest` |
| `updateAnimeListStatus`, `updateAnimeListScore`, `setAnimeListProgress`, `deleteAnimeListEntry`, `setAnimeWatched` | `data/ListEdits.kt`, `AniListAccountApi`, `MyAnimeListSync.writeEntry` | Done | Same GraphQL and MAL writes, with the same confirm-after-write checks and rewatch handling |
| `PosterElement` settings | `data/LibraryRules.kt`, Settings screen | Done | Android keeps the switched-off ones, so does Windows. No DUB (needs the dub index) or 18+ switch |
| Pull-to-refresh (`PullRefreshContainer`) on Home, Browse, Schedule, Library, anime detail | `ui/Refresh.kt` `RefreshHost`, `RefreshOn`, `RefreshButton`; floating button bottom right, wired in `App.kt` | Done (needs a visual check on a PC) | Windows has no pull gesture, so one button reloads whatever screen is showing, and nothing else. Home: live rows, page left up while loading. Browse: same search again, text and filters kept. Schedule: reloads and stays on the selected day. Library and Profile (Accounts): re-pull the account's lists (`AccountLibrary.refresh`), plus the MAL list and the AniList activity history on Profile; shown only with an AniList or MAL account connected. Detail: reloads that anime. Setting `refreshButtonPlacement` (Settings, Appearance) puts it in the left bar above Notifications (default; hidden on screens with nothing to refresh), top right or bottom right; Android has no such choice. Spinner held 550 ms, as Android's. A failed reload shows the error panel on Browse, Schedule and Detail, as on Android. On Home it keeps the rows already showing (Android serves its offline catalogue there; Windows has no bundled catalogue), and a Home request past 15 seconds counts as failed, as on Android. Home's refresh also recomputes the "Because you watched" row, as Android's forced load recomputes its personal rows; a failed re-read keeps the row. Home's refresh also re-reads that schedule. Not on Downloads, Providers, Random, Notifications, Watch, Player or Settings. 6 tests of Windows' own (`RefreshHostTest`, `AppSettingsTest`) |
| `ContinueWatchingVisibility.kt` (`hideFromContinueUntilAired`, `returnsToContinueAt`, `UpcomingAiring`) and setting `hideAiringTomorrowFromContinue` ("Only episodes you can watch") | `data/ContinueVisibility.kt`, `AniListClient.airingTimes`, `AniListRepository.upcomingAirings`, `HomeScreen`, setting `hideUnairedFromContinue` (Settings, Content) | Done (needs a visual check on a PC) | Off by default, as on Android. Hides a Continue Watching title from Home (not from Library) until its next episode is out, 2 hours after broadcast; nothing is written. 23 tests: Android's 22 ported (`ContinueVisibilityTest`; the stored-resume-point case becomes a part-watched row, since Windows keeps one resume point per title) plus one for the schedule fold. Differences: the schedule is read only while the setting is on, and only for the titles on the row (`mediaId_in`), because Windows has no schedule cache; the 16-day window (from yesterday) is re-read at launch, on refresh, when the titles change and at midnight; a failed read keeps what is held |
| Client-side AniList pacing (`nextRateSlot`, `recordRateHeaders`, `requestGate`) and the 429 `Retry-After` retry in `AniListClient.post` | `core/.../net/AniListPacing.kt` (`AniListPacer`, `nextRateSlot`, `retryAfterMillis`), used by `AniListClient` and `AniListAccountApi` | Done | One shared budget for every AniList call from the app, read from the `X-RateLimit-*` headers: a burst runs while the budget is healthy, is spread once 8 or fewer calls remain, and waits for the reset when spent (never past 60 s). A 429 waits `Retry-After` (10 s if absent, at least 1 s) and retries twice before the "rate limit reached" message is shown. AniList's degraded limit is 30 a minute. Android's 11 slot tests are ported (`AniListPacingTest`, 13 tests with two against a local server). Differences: Android also sends a `Referer` header and the sign-in token on every call (a higher limit); Windows sends neither on its public calls |

## Menu differences

- The Detail page's Save button still toggles the local save; on Android it opens this menu. Outside the My List page.
- The menu's "My lists" rows are on the Library page's card menu only. The Continue Watching menu on Windows does not list them.
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

