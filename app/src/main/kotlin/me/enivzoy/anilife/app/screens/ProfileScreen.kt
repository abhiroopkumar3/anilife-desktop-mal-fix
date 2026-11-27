package me.enivzoy.anilife.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.enivzoy.anilife.app.data.AniListListStatus
import me.enivzoy.anilife.app.data.AniListSync
import me.enivzoy.anilife.app.data.MyAnimeListEntry
import me.enivzoy.anilife.app.data.MyAnimeListSync
import me.enivzoy.anilife.app.data.MyAnimeListUser
import me.enivzoy.anilife.app.ui.AniLifeColors
import me.enivzoy.anilife.app.ui.GlassButton
import me.enivzoy.anilife.app.ui.GlassTabBar
import me.enivzoy.anilife.app.ui.PageScrollbar
import me.enivzoy.anilife.app.ui.RemoteImage
import me.enivzoy.anilife.app.ui.hoverScale
import me.enivzoy.anilife.app.ui.liquidGlass
import java.awt.Desktop
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface ProfileState {
    data object Loading : ProfileState
    data object Unlinked : ProfileState
    data class Loaded(val data: UnifiedProfile) : ProfileState
}

data class UnifiedProfile(
    val platform: String,
    val name: String,
    val avatar: String?,
    val banner: String?,
    val joined: String,
    val profileUrl: String,
    val animeCount: Int,
    val watchHours: Int,
    val meanScore: Double,
    val statuses: Map<String, Int>,
    val watching: List<WatchingItem>,
)

data class WatchingItem(
    val id: Long,
    val title: String,
    val cover: String?,
    val progressText: String,
    val progress: Float,
)

@Composable
fun ProfileScreen(onOpenAnime: (Long) -> Unit) {
    val anilistUser by AniListSync.user.collectAsState()
    val anilistEntries by AniListSync.entries.collectAsState()
    val malUser by MyAnimeListSync.user.collectAsState()
    val malConfigured by MyAnimeListSync.configured.collectAsState()
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ProfileState>(ProfileState.Loading) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }

    suspend fun loadProfile() {
        error = null
        state = ProfileState.Loading
        val account = anilistUser
        if (account != null) {
            try {
                val profile = AniListSync.viewerProfile()
                state = ProfileState.Loaded(
                    UnifiedProfile(
                        platform = "AniList",
                        name = profile.name.ifBlank { account.name },
                        avatar = profile.avatar ?: account.avatar,
                        banner = profile.banner,
                        joined = joinedLabel(profile.createdAt),
                        profileUrl = "https://anilist.co/user/${urlPart(account.name)}",
                        animeCount = profile.animeCount,
                        watchHours = (profile.minutesWatched / 60L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        // anilist reports 0-100 and mal 0-10, so both land on a 10-point scale.
                        meanScore = profile.meanScore / 10.0,
                        statuses = profile.statuses,
                        watching = anilistEntries
                            .filter { it.status == AniListListStatus.CURRENT || it.status == AniListListStatus.REPEATING }
                            .sortedByDescending { it.updatedAt }
                            .map { entry ->
                                val total = entry.anime.episodes?.takeIf { it > 0 } ?: (entry.progress + 1)
                                WatchingItem(
                                    id = entry.anime.id,
                                    title = entry.anime.title,
                                    cover = entry.anime.cover,
                                    progressText = "Episode ${entry.progress} of $total",
                                    progress = (entry.progress.toFloat() / total.toFloat()).coerceIn(0f, 1f),
                                )
                            },
                    ),
                )
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error = failure.message?.takeIf(String::isNotBlank) ?: "Couldn't load your AniList profile."
                state = ProfileState.Unlinked
                return
            }
        }
        val malAccount = malUser
        if (malAccount != null) {
            try {
                state = ProfileState.Loaded(malProfile(malAccount, MyAnimeListSync.list()))
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error = failure.message?.takeIf(String::isNotBlank) ?: "Couldn't load your MyAnimeList profile."
                state = ProfileState.Unlinked
                return
            }
        }
        state = ProfileState.Unlinked
    }

    LaunchedEffect(anilistUser?.id, malUser?.id, reloadKey) {
        loadProfile()
    }

    Box(Modifier.fillMaxSize()) {
        when (val current = state) {
            ProfileState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AniLifeColors.Primary)
            }

            ProfileState.Unlinked -> UnlinkedProfile(
                malConfigured = malConfigured,
                error = error,
                onAniList = {
                    try {
                        AniListSync.beginAuth()
                    } catch (failure: Throwable) {
                        error = failure.message ?: "Couldn't open AniList sign-in."
                    }
                },
                onMal = {
                    scope.launch {
                        error = null
                        try {
                            MyAnimeListSync.configure()
                            MyAnimeListSync.connect()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            error = failure.message ?: "MyAnimeList sign-in failed."
                        }
                    }
                },
            )

            is ProfileState.Loaded -> LoadedProfile(
                data = current.data,
                tab = tab,
                onTab = { tab = it },
                error = error,
                onOpenAnime = onOpenAnime,
                onRefresh = { reloadKey++ },
                onOpenProfile = { url ->
                    runCatching {
                        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                            Desktop.getDesktop().browse(URI.create(url))
                        } else {
                            throw IllegalStateException("This system cannot open a browser.")
                        }
                    }.onFailure { failure ->
                        error = failure.message ?: "Couldn't open the profile."
                    }
                },
                onLogout = {
                    scope.launch {
                        error = null
                        try {
                            if (current.data.platform == "AniList") AniListSync.disconnect() else MyAnimeListSync.disconnect()
                            state = ProfileState.Unlinked
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            error = failure.message ?: "Couldn't sign out."
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun UnlinkedProfile(
    malConfigured: Boolean,
    error: String?,
    onAniList: () -> Unit,
    onMal: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(32.dp).widthIn(max = 460.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(AniLifeColors.Card)
                .border(1.dp, AniLifeColors.Border, RoundedCornerShape(18.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Your profile", color = AniLifeColors.Foreground, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "Link an AniList or MyAnimeList account to see your stats and continue watching.",
                color = AniLifeColors.MutedForeground,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            ProfileButton("Link AniList", onClick = onAniList, modifier = Modifier.fillMaxWidth())
            Text(
                "After approving in your browser, paste the token in the AniList account settings.",
                color = AniLifeColors.MutedForeground,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            ProfileButton("Link MAL", onClick = onMal, enabled = malConfigured, modifier = Modifier.fillMaxWidth())
            if (!malConfigured) {
                Text("MyAnimeList client configuration is unavailable.", color = AniLifeColors.Destructive, fontSize = 11.sp)
            }
            error?.let { Text(it, color = AniLifeColors.Destructive, fontSize = 12.sp, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun LoadedProfile(
    data: UnifiedProfile,
    tab: Int,
    onTab: (Int) -> Unit,
    error: String?,
    onOpenAnime: (Long) -> Unit,
    onRefresh: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onLogout: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
        Box(Modifier.fillMaxWidth().height(220.dp).background(AniLifeColors.SurfaceVariant)) {
            RemoteImage(
                url = data.banner,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                placeholderColor = AniLifeColors.SurfaceVariant,
            )
            RemoteImage(
                url = data.avatar,
                contentDescription = data.name,
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(start = 24.dp)
                    .offset(y = 60.dp)
                    .size(120.dp)
                    .clip(CircleShape)
                    .border(4.dp, AniLifeColors.Primary, CircleShape),
                placeholderColor = AniLifeColors.SurfaceVariant,
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(68.dp))
            Text(data.name, color = AniLifeColors.Foreground, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            val platformLine = listOfNotNull(
                data.platform,
                data.joined.takeIf(String::isNotBlank)?.let { "Joined $it" },
            ).joinToString(" · ")
            Text(platformLine, color = AniLifeColors.MutedForeground, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileButton("View profile", onClick = { onOpenProfile(data.profileUrl) })
                ProfileButton("Refresh", onClick = onRefresh)
                ProfileButton("Logout", onClick = onLogout, danger = true)
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = AniLifeColors.Destructive, fontSize = 12.sp)
            }
            Spacer(Modifier.height(20.dp))
            GlassTabBar(
                labels = listOf("Overview", "Stats", "Activity"),
                selectedIndex = tab,
                onSelect = onTab,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            when (tab) {
                0 -> OverviewTab(data, onOpenAnime)
                1 -> StatsTab(data)
                2 -> ActivityTab()
            }
            Spacer(Modifier.height(32.dp))
        }
    }
        PageScrollbar(scrollState, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun OverviewTab(data: UnifiedProfile, onOpenAnime: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        StatCards(data)
        Text("List status", color = AniLifeColors.Foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        StatusSegments(data.statuses)
        Text("Continue watching", color = AniLifeColors.Foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (data.watching.isEmpty()) {
            Text("Nothing in progress right now.", color = AniLifeColors.MutedForeground, fontSize = 13.sp)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(data.watching, key = { it.id }) { item ->
                    WatchingCard(item) { onOpenAnime(item.id) }
                }
            }
        }
    }
}

@Composable
private fun StatCards(data: UnifiedProfile) {
    val labels = listOf(
        "${data.animeCount} Anime",
        "${data.watchHours} h Watch time",
        "${String.format(Locale.ENGLISH, "%.1f", data.meanScore)} Mean score",
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        labels.forEach { label ->
            Card(
                modifier = Modifier.weight(1f).liquidGlass(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = AniLifeColors.Card, contentColor = AniLifeColors.Foreground),
            ) {
                Text(
                    label,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 18.dp),
                    color = AniLifeColors.Foreground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun StatusSegments(statuses: Map<String, Int>) {
    val counts = statusCounts(statuses)
    val total = counts.values.sum()
    if (total == 0) {
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp)).background(AniLifeColors.Border))
    } else {
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp))) {
            statusOrder.forEach { bucket ->
                val count = counts[bucket.label] ?: 0
                if (count > 0) {
                    Box(Modifier.weight(count.toFloat()).fillMaxHeight().background(bucket.color))
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    StatusLegend(counts)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusLegend(counts: Map<String, Int>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        statusOrder.forEach { bucket ->
            val count = counts[bucket.label] ?: 0
            if (count > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(bucket.color))
                    Text("${bucket.label} $count", color = AniLifeColors.MutedForeground, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun StatsTab(data: UnifiedProfile) {
    val counts = statusCounts(data.statuses)
    Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Status breakdown", color = AniLifeColors.Foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        statusOrder.forEach { bucket ->
            val count = counts[bucket.label] ?: 0
            if (count > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth().liquidGlass(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AniLifeColors.Card, contentColor = AniLifeColors.Foreground),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(bucket.color))
                        Spacer(Modifier.width(12.dp))
                        Text(bucket.label, Modifier.weight(1f), color = AniLifeColors.Foreground, fontSize = 14.sp)
                        Text("$count", color = AniLifeColors.MutedForeground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (counts.values.sum() == 0) {
            Text("No list statistics yet.", color = AniLifeColors.MutedForeground, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ActivityTab() {
    Box(
        Modifier.fillMaxWidth().padding(top = 24.dp).height(160.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AniLifeColors.SurfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "No recent activity yet.",
            color = AniLifeColors.MutedForeground,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun WatchingCard(item: WatchingItem, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier.width(180.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverScale()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AniLifeColors.Card, contentColor = AniLifeColors.Foreground),
    ) {
        Box(Modifier.fillMaxWidth().height(110.dp).background(AniLifeColors.SurfaceVariant)) {
            RemoteImage(
                url = item.cover,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                placeholderColor = AniLifeColors.SurfaceVariant,
            )
        }
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                item.title,
                color = AniLifeColors.Foreground,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(item.progressText, color = AniLifeColors.MutedForeground, fontSize = 11.sp, maxLines = 1)
            LinearProgressIndicator(
                progress = { item.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = AniLifeColors.Primary,
                trackColor = AniLifeColors.Border,
            )
        }
    }
}

@Composable
private fun ProfileButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    GlassButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        accent = !danger,
        danger = danger,
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

private data class StatusBucket(val label: String, val color: Color)

private val statusOrder = listOf(
    StatusBucket("Watching", Color(0xFF60A5FA)),
    StatusBucket("Completed", Color(0xFF4ADE80)),
    StatusBucket("Planned", Color(0xFF9CA3AF)),
    StatusBucket("Paused", Color(0xFFFBBF24)),
    StatusBucket("Dropped", Color(0xFFF87171)),
)

private fun statusCounts(statuses: Map<String, Int>): Map<String, Int> {
    val counts = statusOrder.associate { it.label to 0 }.toMutableMap()
    statuses.forEach { (key, count) ->
        val label = when (key.trim().uppercase(Locale.ROOT)) {
            "CURRENT", "REPEATING", "WATCHING" -> "Watching"
            "COMPLETED" -> "Completed"
            "PLANNING", "PLANNED", "PLAN_TO_WATCH" -> "Planned"
            "PAUSED", "ON_HOLD" -> "Paused"
            "DROPPED" -> "Dropped"
            else -> return@forEach
        }
        counts[label] = (counts[label] ?: 0) + count.coerceAtLeast(0)
    }
    return counts
}

private fun malProfile(user: MyAnimeListUser, entries: List<MyAnimeListEntry>): UnifiedProfile {
    val statuses = linkedMapOf(
        "CURRENT" to 0,
        "COMPLETED" to 0,
        "PLANNING" to 0,
        "PAUSED" to 0,
        "DROPPED" to 0,
    )
    entries.forEach { entry ->
        val key = when {
            entry.rewatching || entry.status.equals("watching", ignoreCase = true) -> "CURRENT"
            entry.status.equals("completed", ignoreCase = true) -> "COMPLETED"
            entry.status.equals("plan_to_watch", ignoreCase = true) -> "PLANNING"
            entry.status.equals("on_hold", ignoreCase = true) -> "PAUSED"
            entry.status.equals("dropped", ignoreCase = true) -> "DROPPED"
            else -> null
        }
        if (key != null) statuses[key] = (statuses[key] ?: 0) + 1
    }
    val scores = entries.mapNotNull { it.score.takeIf { score -> score > 0 } }
    val watched = entries.sumOf { it.watched.coerceAtLeast(0) }
    // mal has no watch-time field, so each episode is counted as 24 minutes.
    val watching = entries
        .filter { it.rewatching || it.status.equals("watching", ignoreCase = true) }
        .sortedByDescending(MyAnimeListEntry::updatedAt)
        .map { entry ->
            val total = entry.episodes?.takeIf { it > 0 } ?: (entry.watched + 1)
            WatchingItem(
                id = entry.id,
                title = entry.title,
                cover = null,
                progressText = "Episode ${entry.watched} of $total",
                progress = (entry.watched.toFloat() / total.toFloat()).coerceIn(0f, 1f),
            )
        }
    return UnifiedProfile(
        platform = "MyAnimeList",
        name = user.name.ifBlank { "MyAnimeList user" },
        avatar = user.picture,
        banner = null,
        joined = "",
        profileUrl = "https://myanimelist.net/profile/${urlPart(user.name)}",
        animeCount = entries.size,
        watchHours = (watched.toLong() * 24L / 60L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        meanScore = if (scores.isEmpty()) 0.0 else scores.sum().toDouble() / scores.size.toDouble(),
        statuses = statuses,
        watching = watching,
    )
}

private fun joinedLabel(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return ""
    return runCatching {
        Instant.ofEpochSecond(epochSeconds)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH))
    }.getOrDefault("")
}

private fun urlPart(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
