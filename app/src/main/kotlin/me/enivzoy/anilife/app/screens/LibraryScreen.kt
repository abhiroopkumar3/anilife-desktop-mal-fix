package me.enivzoy.anilife.app.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.enivzoy.anilife.app.data.AccountLibrary
import me.enivzoy.anilife.app.data.AniListConnection
import me.enivzoy.anilife.app.data.AniListListEntry
import me.enivzoy.anilife.app.data.AniListListStatus
import me.enivzoy.anilife.app.data.AniListRepository
import me.enivzoy.anilife.app.data.AniListScoreFormat
import me.enivzoy.anilife.app.data.AniListSync
import me.enivzoy.anilife.app.data.ContinueStoreInstance
import me.enivzoy.anilife.app.data.LibraryCardData
import me.enivzoy.anilife.app.data.ListEdits
import me.enivzoy.anilife.app.data.LibrarySort
import me.enivzoy.anilife.app.data.LibraryView
import me.enivzoy.anilife.app.data.ListEntry
import me.enivzoy.anilife.app.data.MyAnimeListSync
import me.enivzoy.anilife.app.data.PosterElement
import me.enivzoy.anilife.app.data.aniListEntriesToEntries
import me.enivzoy.anilife.app.data.buildCombinedWatchlist
import me.enivzoy.anilife.app.data.CustomList
import me.enivzoy.anilife.app.data.CustomListStoreInstance
import me.enivzoy.anilife.app.data.ListService
import me.enivzoy.anilife.app.data.customListCards
import me.enivzoy.anilife.app.data.listKey
import me.enivzoy.anilife.core.model.AnimeCard
import me.enivzoy.anilife.app.data.cardsForStatus
import me.enivzoy.anilife.app.data.defaultLibraryViewNames
import me.enivzoy.anilife.app.data.libraryCards
import me.enivzoy.anilife.app.data.libraryViewsFrom
import me.enivzoy.anilife.app.data.newEpisodeCount
import me.enivzoy.anilife.app.data.posterElementsOff
import me.enivzoy.anilife.app.data.toggledLibraryViews
import me.enivzoy.anilife.app.ui.AniLifeColors
import me.enivzoy.anilife.app.ui.Chip
import me.enivzoy.anilife.app.ui.ContinueCard
import me.enivzoy.anilife.app.ui.EmptyPanel
import me.enivzoy.anilife.app.ui.PageScrollbar
import me.enivzoy.anilife.app.ui.RemoteImage
import me.enivzoy.anilife.app.ui.ScoreTag
import me.enivzoy.anilife.app.ui.onRightClick
import me.enivzoy.anilife.app.ui.parseAnimeColor

/**
 * The Library page, laid out like the Android app's "My List": a search box, one chip per list
 * with a count, format and airing filters, a sort, then a section per chosen list. The rules it
 * follows are in LibraryRules.kt; PARITY.md maps each part to the Android code.
 */

/** A custom list's chip on the header row. */
internal data class CustomChip(val name: String, val count: Int, val selected: Boolean)

/** What the viewer has chosen on this page. It lasts as long as the app runs, as Android's does while the app is open. */
internal object LibraryUiState {
    var search by mutableStateOf("")
    var format by mutableStateOf<String?>(null)
    var airing by mutableStateOf<String?>(null)
    var sort by mutableStateOf(LibrarySort.LAST_MODIFIED)
    var views by mutableStateOf(defaultLibraryViewNames())
    var expanded by mutableStateOf("")
    var continueExpanded by mutableStateOf(false)
    /** Custom lists the viewer switched off, and the ones shown as a grid, as comma-joined [listKey]s. */
    var hiddenCustom by mutableStateOf("")
    var expandedCustom by mutableStateOf("")
}

/** [key] added to the comma-joined [keys], or taken out when it is already there. */
internal fun toggledKeys(keys: String, key: String): String {
    val set = keys.split(',').filterTo(linkedSetOf()) { it.isNotBlank() }
    if (!set.add(key)) set.remove(key)
    return set.joinToString(",")
}

private val FORMAT_OPTIONS = listOf(
    null to "Any format", "TV" to "TV", "MOVIE" to "Movie", "ONA" to "ONA", "OVA" to "OVA", "SPECIAL" to "Special",
)

private val AIRING_OPTIONS = listOf(
    null to "Any", "RELEASING" to "Ongoing", "FINISHED" to "Completed", "NOT_YET_RELEASED" to "Upcoming",
    "HIATUS" to "On hiatus", "CANCELLED" to "Cancelled",
)

private fun LibraryView.accountStatus(): String? = when (this) {
    LibraryView.WATCHLIST -> null
    LibraryView.WATCHING -> "CURRENT"
    LibraryView.REWATCHING -> "REPEATING"
    LibraryView.PAUSED -> "PAUSED"
    LibraryView.COMPLETED -> "COMPLETED"
    LibraryView.DROPPED -> "DROPPED"
}

@Composable
fun LibraryScreen(
    repository: AniListRepository,
    hiddenPosterElements: String,
    onOpen: (Long) -> Unit,
    onBrowse: () -> Unit,
) {
    val aniListUser by AniListSync.user.collectAsState()
    val malUser by MyAnimeListSync.user.collectAsState()
    val aniListEntries by AniListSync.entries.collectAsState()
    val malEntries by AccountLibrary.malEntries.collectAsState()
    val syncStatus by AniListSync.status.collectAsState()
    val saves by repository.library.saves.collectAsState()
    val continueRows by ContinueStoreInstance.entries.collectAsState()
    val customLists by CustomListStoreInstance.lists.collectAsState()

    if (syncStatus.connection == AniListConnection.RESTORING) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AniLifeColors.Primary)
        }
        return
    }

    val signedIn = aniListUser != null || malUser != null
    val serviceName = if (aniListUser != null) "AniList" else "MyAnimeList"
    val account: List<ListEntry> = when {
        aniListUser != null -> remember(aniListEntries) { aniListEntriesToEntries(aniListEntries) }
        malUser != null -> malEntries
        else -> emptyList()
    }
    val available = if (signedIn) LibraryView.entries.toList() else listOf(LibraryView.WATCHLIST)
    val chosen = libraryViewsFrom(LibraryUiState.views, available)
    val expandedViews = libraryViewsFrom(LibraryUiState.expanded, LibraryView.entries.toList()).toSet()
    val off = posterElementsOff(hiddenPosterElements)
    val nowSec = System.currentTimeMillis() / 1000L

    val sources: Map<LibraryView, List<LibraryCardData>> = remember(account, saves, continueRows) {
        val history = continueRows.associate { it.anime.id to it.progress.episode.toInt() }
        available.associateWith { view ->
            val status = view.accountStatus()
            if (status == null) buildCombinedWatchlist(account, saves, history) else cardsForStatus(account, status)
        }
    }
    val customSources: Map<String, List<LibraryCardData>> = remember(customLists, account, continueRows) {
        val history = continueRows.associate { it.anime.id to it.progress.episode.toInt() }
        customLists.associate { list -> listKey(list.name) to customListCards(list, account, history) }
    }
    val hiddenCustom = LibraryUiState.hiddenCustom.split(',').toSet()
    val expandedCustom = LibraryUiState.expandedCustom.split(',').toSet()
    val shownCustomLists = customLists.filterNot { listKey(it.name) in hiddenCustom }
    fun shownCustom(list: CustomList) = libraryCards(
        source = customSources[listKey(list.name)].orEmpty(),
        format = LibraryUiState.format,
        airing = LibraryUiState.airing,
        titleFilter = LibraryUiState.search,
        sort = LibraryUiState.sort,
    )
    fun shown(view: LibraryView) = libraryCards(
        source = sources[view].orEmpty(),
        format = LibraryUiState.format,
        airing = LibraryUiState.airing,
        titleFilter = LibraryUiState.search,
        sort = LibraryUiState.sort,
    )
    val resultCount = chosen.sumOf { shown(it).size } + shownCustomLists.sumOf { shownCustom(it).size }
    val filtersActive = LibraryUiState.format != null || LibraryUiState.airing != null || LibraryUiState.search.isNotBlank()

    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<LibraryCardData?>(null) }
    var namingList by remember { mutableStateOf(false) }
    var managingListName by remember { mutableStateOf<String?>(null) }
    val listService = AccountLibrary.activeService()
    var menuCard by remember { mutableStateOf<LibraryCardData?>(null) }
    var continueMenu by remember { mutableStateOf<me.enivzoy.anilife.app.data.ContinueEntry?>(null) }
    val listState = rememberLazyListState()

    fun deleteFromAccount(card: LibraryCardData) {
        scope.launch {
            error = null
            try {
                // The same call and clean-up as the card menu's Delete, so the two can never differ.
                ListEdits.delete(card.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error = failure.message ?: "Couldn't remove this from your list."
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = (((maxWidth - 64.dp) + 20.dp) / (168.dp + 20.dp)).toInt().coerceAtLeast(2)
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 26.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                item(key = "header") {
                    LibraryHeader(
                        available = available,
                        chosen = chosen,
                        counts = available.associateWith { shown(it).size },
                        customChips = customLists.map { list ->
                            CustomChip(list.name, shownCustom(list).size, listKey(list.name) !in hiddenCustom)
                        },
                        onToggleCustom = { name -> LibraryUiState.hiddenCustom = toggledKeys(LibraryUiState.hiddenCustom, listKey(name)) },
                        onNewList = { namingList = true },
                        resultCount = resultCount,
                        syncing = syncStatus.syncing,
                        message = error ?: syncStatus.message,
                    )
                }
                chosen.forEach { view ->
                    item(key = "section-${view.name}") {
                        val cards = shown(view)
                        val expanded = view in expandedViews
                        LibrarySection(
                            title = view.label,
                            subtitle = sectionSubtitle(view, cards.size, signedIn, serviceName),
                            expanded = expanded,
                            onToggleExpanded = {
                                LibraryUiState.expanded = toggledLibraryViews(LibraryUiState.expanded, view)
                            },
                        ) {
                            val emptyText = when {
                                cards.isNotEmpty() -> null
                                filtersActive && sources[view].orEmpty().isNotEmpty() -> "No anime match these filters"
                                view == LibraryView.WATCHLIST -> "Tap the heart on any anime to save it"
                                filtersActive -> "No anime match these filters"
                                else -> "Nothing here yet"
                            }
                            if (emptyText != null) {
                                EmptyLine(emptyText)
                            } else if (expanded) {
                                PosterGrid(cards, columns, off, nowSec, signedIn, onOpen, { menuCard = it }) { pendingDelete = it }
                            } else {
                                PosterCarousel(view.name + cards.size, cards, off, nowSec, signedIn, onOpen, { menuCard = it }) { pendingDelete = it }
                            }
                        }
                    }
                }
                shownCustomLists.forEach { list ->
                    val key = listKey(list.name)
                    item(key = "custom-$key") {
                        val cards = shownCustom(list)
                        val expanded = key in expandedCustom
                        LibrarySection(
                            title = list.name,
                            subtitle = "${cards.size} anime · " + when (listService) {
                                ListService.ANILIST -> "Your AniList custom list"
                                ListService.MAL -> "A MyAnimeList tag"
                                null -> "On this PC"
                            },
                            expanded = expanded,
                            onToggleExpanded = { LibraryUiState.expandedCustom = toggledKeys(LibraryUiState.expandedCustom, key) },
                            onManage = { managingListName = list.name },
                        ) {
                            when {
                                cards.isEmpty() -> EmptyLine(
                                    if (filtersActive) "No anime match these filters" else "Right-click any anime to add it to this list",
                                )
                                expanded -> PosterGrid(cards, columns, off, nowSec, signedIn, onOpen, { menuCard = it }) { pendingDelete = it }
                                else -> PosterCarousel("custom$key${cards.size}", cards, off, nowSec, signedIn, onOpen, { menuCard = it }) { pendingDelete = it }
                            }
                        }
                    }
                }
                if (chosen.isEmpty() && shownCustomLists.isEmpty()) {
                    item(key = "none-chosen") { EmptyLine("Choose a list above to show it here") }
                }
                item(key = "continue") {
                    ContinueSection(
                        rows = continueRows,
                        expanded = LibraryUiState.continueExpanded,
                        onToggle = { LibraryUiState.continueExpanded = !LibraryUiState.continueExpanded },
                        onOpen = onOpen,
                        columns = columns,
                        onMenu = { continueMenu = it },
                    )
                }
                if (!signedIn && saves.isEmpty()) {
                    item(key = "browse") {
                        OutlinedButton(onClick = onBrowse) { Text("Explore anime", fontSize = 14.sp) }
                    }
                }
            }
            PageScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd))
        }
    }

    menuCard?.let { card ->
        AnimeListActionsDialog(
            anilistId = card.id,
            title = card.title,
            cover = card.cover,
            metadata = listOfNotNull(card.status?.let(::statusLabel), card.format?.let(::formatText)).joinToString(" · ").ifBlank { null },
            saved = saves.any { it.anime.id == card.id },
            onRemoveSaved = { repository.removeFromLibrary(card.id) },
            onDismiss = { menuCard = null },
            listCard = AnimeCard(
                id = card.id, title = card.title, cover = card.cover, format = card.format,
                score = card.averageScore, episodes = card.totalEpisodes,
            ),
        )
    }
    if (namingList) {
        CustomListNameDialog(
            title = "New list",
            confirmText = "Create",
            service = listService,
            onSave = { name ->
                CustomListStoreInstance.create(name)?.also {
                    LibraryUiState.hiddenCustom = LibraryUiState.hiddenCustom.split(',').filterNot { key -> key == listKey(it) }.joinToString(",")
                    namingList = false
                }
            },
            onDismiss = { namingList = false },
        )
    }
    managingListName?.let { name ->
        val list = customLists.firstOrNull { listKey(it.name) == listKey(name) }
        if (list == null) managingListName = null
        else CustomListManageDialog(list = list, service = listService, onDismiss = { managingListName = null })
    }
    continueMenu?.let { row ->
        ContinueActionsDialog(entry = row, onOpenDetails = { onOpen(row.anime.id) }, onDismiss = { continueMenu = null })
    }

    pendingDelete?.let { card ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = RectangleShape,
            containerColor = AniLifeColors.Card,
            title = { Text("Delete this from your list?", color = AniLifeColors.Foreground) },
            text = { Text(card.title, color = AniLifeColors.MutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; deleteFromAccount(card) }) {
                    Text("Delete", color = AniLifeColors.Destructive)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun sectionSubtitle(view: LibraryView, count: Int, signedIn: Boolean, service: String): String {
    val where = when {
        view == LibraryView.WATCHLIST && !signedIn -> "Saved on this device"
        view == LibraryView.WATCHLIST -> "Saved here and in $service Planning"
        else -> "Synced from $service"
    }
    return "$count anime · $where"
}

@Composable
private fun LibraryHeader(
    available: List<LibraryView>,
    chosen: List<LibraryView>,
    counts: Map<LibraryView, Int>,
    customChips: List<CustomChip>,
    onToggleCustom: (String) -> Unit,
    onNewList: () -> Unit,
    resultCount: Int,
    syncing: Boolean,
    message: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Library", color = AniLifeColors.Foreground, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        message?.let { Text(it, color = AniLifeColors.Destructive, fontSize = 12.sp) }
        SearchBox(resultCount)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            available.forEach { view ->
                val selected = view in chosen
                PillButton(
                    text = "${view.chipLabel}  ${counts[view] ?: 0}",
                    selected = selected,
                    onClick = { LibraryUiState.views = toggledLibraryViews(LibraryUiState.views, view) },
                )
            }
            // The viewer's own lists, after the account's statuses and toggled the same way.
            customChips.forEach { chip ->
                PillButton(text = "${chip.name}  ${chip.count}", selected = chip.selected, onClick = { onToggleCustom(chip.name) })
            }
            PillButton(text = "+ New list", selected = false, onClick = onNewList)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OptionMenu("Format", FORMAT_OPTIONS, LibraryUiState.format) { LibraryUiState.format = it }
            OptionMenu("Airing status", AIRING_OPTIONS, LibraryUiState.airing) { LibraryUiState.airing = it }
            SortMenu()
        }
        Text(
            "$resultCount anime · Right-click a title to move it on your anime list",
            color = AniLifeColors.MutedForeground,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun SearchBox(resultCount: Int) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AniLifeColors.Card)
            .border(BorderStroke(1.dp, AniLifeColors.Border), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = AniLifeColors.MutedForeground, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
            if (LibraryUiState.search.isEmpty()) {
                Text("Search library", color = AniLifeColors.MutedForeground, fontSize = 14.sp)
            }
            BasicTextField(
                value = LibraryUiState.search,
                onValueChange = { LibraryUiState.search = it },
                singleLine = true,
                textStyle = TextStyle(color = AniLifeColors.Foreground, fontSize = 14.sp),
                cursorBrush = SolidColor(AniLifeColors.Primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text("$resultCount anime", color = AniLifeColors.MutedForeground, fontSize = 12.sp)
        if (LibraryUiState.search.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Clear search",
                tint = AniLifeColors.MutedForeground,
                modifier = Modifier.size(18.dp).clickable { LibraryUiState.search = "" }.pointerHoverIcon(PointerIcon.Hand),
            )
        }
    }
}

@Composable
private fun PillButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape)
            .background(if (selected) AniLifeColors.Primary.copy(alpha = 0.18f) else Color.Transparent)
            .border(BorderStroke(1.dp, if (selected) AniLifeColors.Primary else AniLifeColors.Border), CircleShape)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = AniLifeColors.Primary, modifier = Modifier.size(14.dp))
        Text(text, color = AniLifeColors.Foreground, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun OptionMenu(label: String, options: List<Pair<String?, String>>, selected: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PillButton("$label: ${options.firstOrNull { it.first == selected }?.second ?: options.first().second}", selected = selected != null) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); open = false })
            }
        }
    }
}

@Composable
private fun SortMenu() {
    var open by remember { mutableStateOf(false) }
    Box {
        PillButton("Sort by: ${LibraryUiState.sort.label}", selected = LibraryUiState.sort != LibrarySort.LAST_MODIFIED) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LibrarySort.entries.forEach { sort ->
                DropdownMenuItem(text = { Text(sort.label) }, onClick = { LibraryUiState.sort = sort; open = false })
            }
        }
    }
}

@Composable
private fun LibrarySection(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onManage: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = AniLifeColors.Foreground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = AniLifeColors.MutedForeground, fontSize = 12.sp)
            }
            onManage?.let { TextButton(onClick = it) { Text("Edit", color = AniLifeColors.Primary) } }
            TextButton(onClick = onToggleExpanded) { Text(if (expanded) "Carousel" else "Expand", color = AniLifeColors.Primary) }
        }
        content()
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, color = AniLifeColors.MutedForeground, fontSize = 14.sp, modifier = Modifier.padding(vertical = 18.dp))
}

@Composable
private fun PosterGrid(
    cards: List<LibraryCardData>,
    columns: Int,
    off: Set<PosterElement>,
    nowSec: Long,
    signedIn: Boolean,
    onOpen: (Long) -> Unit,
    onMenu: (LibraryCardData) -> Unit,
    onDelete: (LibraryCardData) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        cards.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { card ->
                    Box(Modifier.weight(1f)) { LibraryPosterCard(card, off, nowSec, signedIn, onOpen, onMenu, onDelete) }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PosterCarousel(
    key: String,
    cards: List<LibraryCardData>,
    off: Set<PosterElement>,
    nowSec: Long,
    signedIn: Boolean,
    onOpen: (Long) -> Unit,
    onMenu: (LibraryCardData) -> Unit,
    onDelete: (LibraryCardData) -> Unit,
) {
    val state = rememberLazyListState()
    var dragged by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress) { if (state.isScrollInProgress) dragged = true }
    // Homes to the first poster whenever the list changes, unless the viewer has scrolled it themselves.
    LaunchedEffect(key) { if (!dragged) state.scrollToItem(0) }
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(cards, key = { it.id }) { card ->
            Box(Modifier.width(168.dp)) { LibraryPosterCard(card, off, nowSec, signedIn, onOpen, onMenu, onDelete) }
        }
    }
}

@Composable
private fun ContinueSection(
    rows: List<me.enivzoy.anilife.app.data.ContinueEntry>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (Long) -> Unit,
    columns: Int,
    onMenu: (me.enivzoy.anilife.app.data.ContinueEntry) -> Unit,
) {
    LibrarySection(
        title = "Continue Watching",
        subtitle = "Right-click a title to remove it or move it on your anime list",
        expanded = expanded,
        onToggleExpanded = onToggle,
    ) {
        val sorted = rows.sortedByDescending { it.updatedAt }
        if (sorted.isEmpty()) {
            EmptyLine("Nothing watched yet")
        } else if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                sorted.chunked((columns / 2).coerceAtLeast(1)).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        row.forEach { entry ->
                            Box(Modifier.onRightClick { onMenu(entry) }) {
                                ContinueCard(entry, onResume = { onOpen(it.anime.id) }, onDetails = onOpen, onRemove = ContinueStoreInstance::remove)
                            }
                        }
                    }
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(sorted, key = { it.anime.id }) { entry ->
                    Box(Modifier.onRightClick { onMenu(entry) }) {
                        ContinueCard(entry, onResume = { onOpen(it.anime.id) }, onDetails = onOpen, onRemove = ContinueStoreInstance::remove)
                    }
                }
            }
        }
    }
}

/** A poster as Android's SavedAnimeCard draws it, each extra gated by the poster settings. */
@Composable
private fun LibraryPosterCard(
    card: LibraryCardData,
    off: Set<PosterElement>,
    nowSec: Long,
    signedIn: Boolean,
    onOpen: (Long) -> Unit,
    onMenu: (LibraryCardData) -> Unit,
    onDelete: (LibraryCardData) -> Unit,
) {
    val tint = remember { parseAnimeColor(null) }
    val newEpisodes = newEpisodeCount(card, nowSec)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .onRightClick { onMenu(card) }
            .clickable { onOpen(card.id) }
            .pointerHoverIcon(PointerIcon.Hand),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(tint)) {
            RemoteImage(url = card.cover, contentDescription = card.title, modifier = Modifier.fillMaxSize(), placeholderColor = tint)
            if (PosterElement.SCORE !in off) {
                val user = card.userScore
                val average = card.averageScore
                when {
                    user != null -> Chip(
                        text = "★ ${if (user % 1.0 == 0.0) user.toInt().toString() else user.toString()}",
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        content = AniLifeColors.Score,
                    )
                    average != null -> ScoreTag(average, Modifier.align(Alignment.TopStart).padding(6.dp))
                }
            }
            if (PosterElement.PROGRESS !in off && card.progress != null && card.status != null) {
                Chip("${card.progress}/${card.totalEpisodes ?: "?"}", Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            if (PosterElement.NEW_EPISODES !in off && newEpisodes != null) {
                Chip(
                    "$newEpisodes NEW",
                    Modifier.align(Alignment.BottomStart).padding(6.dp),
                    container = AniLifeColors.Primary,
                    content = AniLifeColors.PrimaryForeground,
                )
            }
            if (signedIn && card.status != null) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(6.dp).size(28.dp).clip(CircleShape)
                        .background(AniLifeColors.Chip)
                        .clickable { onDelete(card) }
                        .pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Delete from list", tint = AniLifeColors.Foreground, modifier = Modifier.size(15.dp))
                }
            }
        }
        if (PosterElement.TITLE !in off || PosterElement.DETAILS !in off) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (PosterElement.TITLE !in off) {
                    Text(
                        card.title, color = AniLifeColors.Foreground, fontSize = 14.sp, lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (PosterElement.DETAILS !in off) {
                    Text(
                        listOfNotNull(card.status?.let(::statusLabel), card.format?.let(::formatText)).joinToString(" · "),
                        color = AniLifeColors.MutedForeground, fontSize = 12.sp, lineHeight = 16.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun statusLabel(status: String): String = when (status) {
    "CURRENT" -> "Watching"
    "REPEATING" -> "Re-watching"
    "PLANNING" -> "Planning"
    "PAUSED" -> "Paused"
    else -> status.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }
}

private fun formatText(format: String): String = when (format) {
    "TV_SHORT" -> "TV Short"
    "MOVIE" -> "Movie"
    else -> format.replace('_', ' ')
}

/**
 * The AniList entry editor, presented as a flat rectangular dialog in the Anime Info card color.
 */
@Composable
internal fun AniListEntryEditorDialog(
    entry: AniListListEntry,
    scoreFormat: AniListScoreFormat,
    busy: Boolean,
    onClose: () -> Unit,
    onUpdate: (AniListListStatus, Int, Double) -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(entry.anime.title, color = AniLifeColors.Foreground, fontWeight = FontWeight.SemiBold) },
        text = {
            AniListEntryControls(
                entry = entry,
                scoreFormat = scoreFormat,
                busy = busy,
                onUpdate = onUpdate,
                onDelete = onDelete,
            )
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        shape = RectangleShape,
        containerColor = AniLifeColors.Card,
        titleContentColor = AniLifeColors.Foreground,
        textContentColor = AniLifeColors.Foreground,
    )
}

@Composable
internal fun AniListEntryControls(
    entry: AniListListEntry,
    scoreFormat: AniListScoreFormat,
    busy: Boolean,
    onUpdate: (AniListListStatus, Int, Double) -> Unit,
    onDelete: () -> Unit,
) {
    var statusExpanded by remember(entry.entryId) { mutableStateOf(false) }
    var showScoreDialog by remember(entry.entryId) { mutableStateOf(false) }
    var showDeleteDialog by remember(entry.entryId) { mutableStateOf(false) }
    val total = entry.anime.episodes?.takeIf { it > 0 }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(onClick = { statusExpanded = true }, enabled = !busy) {
                    Text(entry.status.label, fontSize = 11.sp)
                }
                DropdownMenu(expanded = statusExpanded, onDismissRequest = { statusExpanded = false }) {
                    AniListListStatus.entries.forEach { status ->
                        DropdownMenuItem(
                            text = { Text(status.label) },
                            onClick = {
                                statusExpanded = false
                                onUpdate(status, entry.progress, entry.score)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showDeleteDialog = true }, enabled = !busy) {
                Text("Remove", fontSize = 11.sp, color = AniLifeColors.MutedForeground)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onUpdate(entry.status, (entry.progress - 1).coerceAtLeast(0), entry.score) },
                enabled = !busy && entry.progress > 0,
            ) {
                Icon(Icons.Rounded.Remove, contentDescription = "Decrease episode progress", tint = AniLifeColors.Foreground)
            }
            Text(
                if (total == null) "${entry.progress} watched" else "${entry.progress} / $total episodes",
                Modifier.weight(1f),
                color = AniLifeColors.Foreground,
                fontSize = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(
                onClick = { onUpdate(entry.status, (entry.progress + 1).coerceAtMost(total ?: Int.MAX_VALUE), entry.score) },
                enabled = !busy && (total == null || entry.progress < total),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Increase episode progress", tint = AniLifeColors.Foreground)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Score ${formatScore(entry.score, scoreFormat)} / ${formatScore(scoreFormat.maximum, scoreFormat)}",
                Modifier.weight(1f),
                color = AniLifeColors.MutedForeground,
                fontSize = 11.sp,
            )
            TextButton(onClick = { showScoreDialog = true }, enabled = !busy) { Text("Edit score", fontSize = 11.sp) }
        }
        if (busy) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).width(18.dp), strokeWidth = 2.dp)
        }
    }

    if (showScoreDialog) {
        var value by remember(entry.entryId) { mutableStateOf(formatScore(entry.score, scoreFormat)) }
        AlertDialog(
            onDismissRequest = { showScoreDialog = false },
            title = { Text("Edit AniList score") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Your AniList scale is ${scoreFormat.name.replace("POINT_", "").replace('_', ' ')}.",
                        color = AniLifeColors.MutedForeground,
                        fontSize = 12.sp,
                    )
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("Score (0-${formatScore(scoreFormat.maximum, scoreFormat)})") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val score = value.toDoubleOrNull()
                    if (score != null && score.isFinite() && score in 0.0..scoreFormat.maximum) {
                        onUpdate(entry.status, entry.progress, score)
                        showScoreDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showScoreDialog = false }) { Text("Cancel") } },
            containerColor = AniLifeColors.Card,
            titleContentColor = AniLifeColors.Foreground,
            textContentColor = AniLifeColors.Foreground,
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Remove from AniList?") },
            text = { Text("This removes ${entry.anime.title} from your AniList account list.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) { Text("Remove", color = AniLifeColors.Destructive) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } },
            containerColor = AniLifeColors.Card,
            titleContentColor = AniLifeColors.Foreground,
            textContentColor = AniLifeColors.Foreground,
        )
    }
}

private fun formatScore(score: Double, format: AniListScoreFormat): String =
    if (format.step < 1.0) String.format(java.util.Locale.ROOT, "%.1f", score)
    else score.toInt().toString()
