package me.enivzoy.anilife.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.enivzoy.anilife.app.data.AniListRepository
import me.enivzoy.anilife.app.data.AppSettings
import me.enivzoy.anilife.app.ui.AniLifeColors
import me.enivzoy.anilife.app.ui.AnimePosterCard
import me.enivzoy.anilife.app.ui.EmptyPanel
import me.enivzoy.anilife.app.ui.ErrorPanel
import me.enivzoy.anilife.app.ui.LoadState
import me.enivzoy.anilife.app.ui.PageScrollbar
import me.enivzoy.anilife.app.ui.PosterSkeleton
import me.enivzoy.anilife.app.ui.loadState
import me.enivzoy.anilife.core.model.AnimeCard
import me.enivzoy.anilife.app.ui.RefreshHost
import me.enivzoy.anilife.app.ui.RefreshOn
import me.enivzoy.anilife.core.model.GENRES
import me.enivzoy.anilife.core.model.SearchPage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

 



@Composable
fun BrowseScreen(
    repository: AniListRepository,
    onOpen: (Long) -> Unit,
    settings: AppSettings = AppSettings(),
    refresh: RefreshHost,
) {
    var query by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<LoadState<SearchPage>>(LoadState.Loading) }
    var loadedPage by remember { mutableIntStateOf(1) }
    var loadingMore by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query, genre, reload) {
        // 300 ms is the floor so a burst of keystrokes still collapses into one request.
        if (query.isNotBlank()) delay(if (settings.expDebouncedSearch) 350 else 300)
        loadedPage = 1
        loadingMore = false
        if (!settings.expAsyncFetching) state = LoadState.Loading
        val previous = (state as? LoadState.Ready)?.value
        val result = loadState { repository.search(query = query.trim(), genre = genre) }
        state = if (settings.expAsyncFetching && result is LoadState.Failed && previous != null) {
            LoadState.Ready(previous)
        } else {
            result
        }
    }

    fun loadMore() {
        if (!settings.expPagination || loadingMore) return
        val current = state as? LoadState.Ready ?: return
        if (!current.value.hasNext) return
        val targetQuery = query.trim()
        val targetGenre = genre
        val nextPage = loadedPage + 1
        loadingMore = true
        scope.launch {
            val result = loadState { repository.search(targetQuery, targetGenre, nextPage) }
            if (result is LoadState.Ready && targetQuery == query.trim() && targetGenre == genre) {
                val shown = (state as? LoadState.Ready)?.value
                if (shown != null) {
                    state = LoadState.Ready(
                        shown.copy(
                            results = (shown.results + result.value.results).distinctBy { it.id },
                            hasNext = result.value.hasNext,
                        ),
                    )
                    loadedPage = nextPage
                }
            }
            loadingMore = false
        }
    }

    // Same search again, typed text and filters kept, results left on screen while it loads.
    RefreshOn(refresh) {
        state = loadState { repository.search(query = query.trim(), genre = genre) }
    }

    val label = when {
        query.isNotBlank() && genre != null -> "\"${query.trim()}\" in $genre"
        query.isNotBlank() -> "Results for \"${query.trim()}\""
        genre != null -> "Popular in $genre"
        else -> "Trending now"
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 32.dp, end = 32.dp, top = 30.dp)) {
            Text("Browse", color = AniLifeColors.Foreground, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Search AniList's catalogue of anime",
                color = AniLifeColors.MutedForeground,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(20.dp))
            SearchField(value = query, onValueChange = { query = it })
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GenreChip(label = "All", selected = genre == null) { genre = null }
                GENRES.filter { it != "Hentai" }.forEach { item ->
                    GenreChip(label = item, selected = genre == item) {
                        genre = if (genre == item) null else item
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = AniLifeColors.MutedForeground, fontSize = 13.sp)
            val count = (state as? LoadState.Ready)?.value?.results?.size
            if (count != null) {
                Spacer(Modifier.width(8.dp))
                Text("· $count titles", color = AniLifeColors.MutedForeground.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val current = state) {
                LoadState.Loading -> BrowseSkeleton(useKeys = settings.expHardwareScrolling)
                is LoadState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ErrorPanel(current.message, onRetry = { reload++ })
                }
                is LoadState.Ready -> {
                    if (current.value.results.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyPanel(
                                icon = Icons.Rounded.SearchOff,
                                title = "No titles found",
                                message = "Try a different spelling, or clear the genre filter.",
                            )
                        }
                    } else {
                        ResultsGrid(
                            page = current.value,
                            onOpen = onOpen,
                            onLoadMore = if (settings.expPagination && current.value.hasNext) ::loadMore else null,
                            loadingMore = loadingMore,
                            useKeys = settings.expHardwareScrolling,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultsGrid(
    page: SearchPage,
    onOpen: (Long) -> Unit,
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    useKeys: Boolean,
) {
    val gridState = rememberLazyGridState()
    val canLoadMore by rememberUpdatedState(onLoadMore != null)
    val loadMore by rememberUpdatedState(onLoadMore)
    val itemCount by rememberUpdatedState(page.results.size)
    val hasNext by rememberUpdatedState(page.hasNext)

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { last ->
                if (canLoadMore && hasNext && last >= itemCount - 5) loadMore?.invoke()
            }
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 168.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 4.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            if (useKeys) {
                items(items = page.results, key = { it.id }, contentType = { "poster" }) { anime ->
                    PosterItem(anime, onOpen)
                }
            } else {
                items(items = page.results, contentType = { "poster" }) { anime ->
                    PosterItem(anime, onOpen)
                }
            }
            if (onLoadMore != null) {
                item(key = "page-loader", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        if (loadingMore) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(26.dp),
                                color = AniLifeColors.Primary,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
            }
        }
        PageScrollbar(rememberScrollbarAdapter(gridState), Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun PosterItem(anime: AnimeCard, onOpen: (Long) -> Unit) {
    AnimePosterCard(
        anime = anime,
        onClick = { onOpen(anime.id) },
        modifier = Modifier.fillMaxWidth(),
        width = null,
    )
}

@Composable
private fun BrowseSkeleton(useKeys: Boolean = true) {
    val gridState = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 168.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 4.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            if (useKeys) {
                items(15, key = { it }) {
                    PosterSkeleton(modifier = Modifier.fillMaxWidth(), width = null)
                }
            } else {
                items(15) {
                    PosterSkeleton(modifier = Modifier.fillMaxWidth(), width = null)
                }
            }
        }
        PageScrollbar(rememberScrollbarAdapter(gridState), Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = AniLifeColors.Foreground, fontSize = 15.sp),
        cursorBrush = SolidColor(AniLifeColors.Foreground),
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .background(AniLifeColors.Card)
            .border(1.dp, AniLifeColors.Border, shape),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = AniLifeColors.MutedForeground,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text("Search anime…", color = AniLifeColors.MutedForeground, fontSize = 15.sp)
                    }
                    innerTextField()
                }
                if (value.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { onValueChange("") }
                            .pointerHoverIcon(PointerIcon.Hand),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear search",
                            tint = AniLifeColors.MutedForeground,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun GenreChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) AniLifeColors.Primary else AniLifeColors.Card)
            .border(1.dp, if (selected) AniLifeColors.Primary else AniLifeColors.Border, CircleShape)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) AniLifeColors.PrimaryForeground else AniLifeColors.MutedForeground,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

