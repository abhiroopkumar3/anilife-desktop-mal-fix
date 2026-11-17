package me.enivzoy.anilife.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import me.enivzoy.anilife.app.data.AccountInsights
import me.enivzoy.anilife.app.data.AccountLibrary
import me.enivzoy.anilife.app.data.AccountStats
import me.enivzoy.anilife.app.data.ActivityKind
import me.enivzoy.anilife.app.data.AniListSync
import me.enivzoy.anilife.app.data.ContinueEntry
import me.enivzoy.anilife.app.data.ContinueStoreInstance
import me.enivzoy.anilife.app.data.ListEntry
import me.enivzoy.anilife.app.data.ListService
import me.enivzoy.anilife.app.data.ListStatusSlice
import me.enivzoy.anilife.app.data.MyAnimeListSync
import me.enivzoy.anilife.app.data.WatchActivityInstance
import me.enivzoy.anilife.app.data.accountInsights
import me.enivzoy.anilife.app.data.accountProfileUrl
import me.enivzoy.anilife.app.data.activityDetail
import me.enivzoy.anilife.app.data.activityHeadline
import me.enivzoy.anilife.app.data.activityKind
import me.enivzoy.anilife.app.data.aniListEntriesToEntries
import me.enivzoy.anilife.app.data.mergedActivity
import me.enivzoy.anilife.app.data.statWatchTime
import me.enivzoy.anilife.app.data.watchTimeLabel
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The account sheet, as Android's AccountSheet.kt draws it behind the My List account pill (here the
 * account's row on the Accounts page opens it): banner
 * and avatar, three headline totals, Overview / Stats / Activity, and the account's own actions at
 * the foot. The streak and activity grid and the MyAnimeList list that used to sit on the Accounts
 * page live here now. "Link a TV" is left out: the PC has no TV to pair (see PARITY.md).
 */

private enum class SheetTab(val label: String) { OVERVIEW("Overview"), STATS("Stats"), ACTIVITY("Activity") }

private val BarColor = Color(0xFF3987E5)

private fun ListService.label() = if (this == ListService.ANILIST) "AniList" else "MyAnimeList"

/** The account's picture, or a plain person when it has none (MyAnimeList hands back none for some). */
@Composable
fun AccountAvatar(url: String?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val shape = CircleShape
    if (url.isNullOrBlank()) {
        Box(modifier.size(size).clip(shape).background(AniLifeColors.CardHover), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Person, null, tint = AniLifeColors.MutedForeground, modifier = Modifier.size(size * 0.6f))
        }
    } else {
        RemoteImage(url, null, modifier.size(size).clip(shape))
    }
}

/** The signed-in account on its Accounts row: the picture, the name beside it, and a click opens the sheet. */
@Composable
fun AccountIdentity(name: String, avatar: String?, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (hovered) AniLifeColors.CardHover else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AccountAvatar(avatar, 40.dp)
        Column {
            Text(name, color = AniLifeColors.Foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = AniLifeColors.MutedForeground, fontSize = 12.sp)
        }
    }
}

@Composable
fun AccountSheet(
    service: ListService,
    name: String,
    avatar: String?,
    onOpenAnime: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val aniListEntries by AniListSync.entries.collectAsState()
    val malEntries by AccountLibrary.malEntries.collectAsState()
    val aniListActivity by AniListSync.activity.collectAsState()
    val localActivity by WatchActivityInstance.days.collectAsState()
    val continueRows by ContinueStoreInstance.entries.collectAsState()
    val entries = remember(service, aniListEntries, malEntries) {
        if (service == ListService.ANILIST) aniListEntriesToEntries(aniListEntries) else malEntries
    }
    var stats by remember(service) { mutableStateOf<AccountStats?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(SheetTab.OVERVIEW) }
    var confirmingLogout by remember { mutableStateOf(false) }

    suspend fun readAccount() {
        stats = if (service == ListService.ANILIST) AniListSync.profileStats() else MyAnimeListSync.profileStats()
        if (service == ListService.ANILIST) AniListSync.refreshActivity()
    }

    // Opening the sheet reads the totals and the activity; the lists are already on the PC. A
    // MyAnimeList list that has not been read yet is read now.
    LaunchedEffect(service) {
        syncing = true
        try {
            if (service == ListService.MAL && AccountLibrary.malEntries.value.isEmpty()) AccountLibrary.refresh()
            readAccount()
        } finally {
            syncing = false
        }
    }

    fun syncNow() {
        if (syncing) return
        scope.launch {
            syncing = true
            try {
                AccountLibrary.refresh()
                readAccount()
            } finally {
                syncing = false
            }
        }
    }

    if (confirmingLogout) {
        // Asked first, as on Android: signing out drops this PC's copy of the account's lists.
        AlertDialog(
            onDismissRequest = { confirmingLogout = false },
            title = { Text("Log out of ${service.label()}?") },
            text = {
                Text(
                    "This PC stops syncing with ${service.label()}, and the lists and progress it brought here " +
                        "leave with it. Your saved titles, custom lists and downloads made on this PC stay. " +
                        "Sign in again any time to bring the rest back.",
                    color = AniLifeColors.MutedForeground,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLogout = false
                    scope.launch {
                        if (service == ListService.ANILIST) AniListSync.disconnect() else MyAnimeListSync.disconnect()
                        onDismiss()
                    }
                }) { Text("Log out", color = AniLifeColors.Destructive) }
            },
            dismissButton = { TextButton(onClick = { confirmingLogout = false }) { Text("Cancel") } },
        )
        return
    }

    val insights = remember(entries, stats) { accountInsights(entries, stats) }
    val scroll = rememberScrollState()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = true)) {
        Box(Modifier.fillMaxSize().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .heightIn(max = 760.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(AniLifeColors.Card)
                    .border(1.dp, AniLifeColors.Border, RoundedCornerShape(16.dp))
                    .verticalScroll(scroll)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                SheetHeader(service, name, avatar, stats?.banner, continueRows.firstOrNull()?.anime?.cover, syncing, ::syncNow, onDismiss)
                if (syncing && entries.isEmpty()) {
                    Text("Syncing your profile…", color = AniLifeColors.MutedForeground, fontSize = 14.sp)
                } else {
                    HeadlineStats(insights)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SheetTab.entries.forEach { entry ->
                            TabPill(entry.label, entry == tab, { tab = entry }, Modifier.weight(1f))
                        }
                    }
                    when (tab) {
                        SheetTab.OVERVIEW -> Overview(insights, continueRows, entries, onOpenAnime, { tab = it })
                        SheetTab.STATS -> Stats(
                            insights,
                            remember(localActivity, aniListActivity) { mergedActivity(localActivity, aniListActivity) },
                            fromAccount = service == ListService.ANILIST,
                        )
                        SheetTab.ACTIVITY -> {
                            if (insights.recent.isEmpty()) {
                                Text("Nothing on your list has changed yet.", color = AniLifeColors.MutedForeground, fontSize = 13.sp)
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    insights.recent.take(20).forEach { ActivityRow(it) }
                                }
                            }
                        }
                    }
                }
                AccountActions(service, ::syncNow) { confirmingLogout = true }
            }
        }
    }
}

@Composable
private fun SheetHeader(
    service: ListService,
    name: String,
    avatar: String?,
    banner: String?,
    fallbackBanner: String?,
    syncing: Boolean,
    onSync: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(132.dp)) {
            Box(
                Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(14.dp))
                    .background(AniLifeColors.Foreground.copy(alpha = 0.06f)),
            ) {
                // An account with no banner, which is every MyAnimeList account, borrows the cover of
                // what was watched last, as on Android.
                (banner?.takeIf { it.isNotBlank() } ?: fallbackBanner)?.let {
                    RemoteImage(it, null, Modifier.fillMaxSize())
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.15f), 1f to Color.Black.copy(alpha = 0.65f)),
                    ),
                )
                Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    BannerButton(Icons.Rounded.Refresh, "Sync now", onSync, enabled = !syncing)
                    BannerButton(Icons.Rounded.Close, "Close", onDismiss)
                }
            }
            AccountAvatar(
                avatar,
                76.dp,
                Modifier.align(Alignment.BottomStart).padding(start = 12.dp).border(3.dp, AniLifeColors.Card, CircleShape),
            )
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    name.ifBlank { "Your account" },
                    color = AniLifeColors.Foreground,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${service.label()} connected",
                    color = AniLifeColors.MutedForeground,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (name.isNotBlank()) {
                val shape = RoundedCornerShape(50)
                Row(
                    Modifier
                        .clip(shape)
                        .border(1.dp, AniLifeColors.Foreground.copy(alpha = 0.5f), shape)
                        .clickable { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI.create(accountProfileUrl(service, name))) } }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("View profile", color = AniLifeColors.Foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Icon(
                        Icons.AutoMirrored.Rounded.OpenInNew, null, tint = AniLifeColors.Foreground,
                        modifier = Modifier.padding(start = 6.dp).size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BannerButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(20.dp))
    }
}

/** Anime, watch time and mean score, three across with hairlines between. */
@Composable
private fun HeadlineStats(insights: AccountInsights) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(52.dp)) {
            HeadlineStat(String.format(Locale.US, "%,d", insights.titles), "Anime", Modifier.weight(1f))
            StatDivider()
            HeadlineStat(statWatchTime(insights.minutesWatched), "Watch time", Modifier.weight(1f))
            StatDivider()
            HeadlineStat(
                insights.meanScore?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
                if (insights.meanScore == null) "No scores yet" else "Mean score",
                Modifier.weight(1f),
            )
        }
        Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(1.dp).background(AniLifeColors.Border))
    }
}

@Composable
private fun HeadlineStat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = AniLifeColors.Foreground, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text(label, color = AniLifeColors.MutedForeground, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun StatDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight(0.7f).background(AniLifeColors.Border))
}

@Composable
private fun TabPill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) AniLifeColors.Primary else Color.Transparent)
            .then(if (selected) Modifier else Modifier.border(BorderStroke(1.dp, AniLifeColors.Foreground.copy(alpha = 0.3f)), shape))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) AniLifeColors.PrimaryForeground else AniLifeColors.Foreground,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun SectionHeading(title: String, action: Pair<String, () -> Unit>? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = AniLifeColors.Foreground, fontSize = 20.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(
                action.first,
                color = AniLifeColors.Primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = action.second)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column {
        Text(title, color = AniLifeColors.Foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = AniLifeColors.MutedForeground, fontSize = 11.sp)
    }
}

@Composable
private fun Overview(
    insights: AccountInsights,
    continueRows: List<ContinueEntry>,
    entries: List<ListEntry>,
    onOpenAnime: (Long) -> Unit,
    onTab: (SheetTab) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeading("Your anime", "View all" to { onTab(SheetTab.STATS) })
            StackedStatusBar(insights.statusCounts)
            Row {
                listOf(ListStatusSlice.WATCHING, ListStatusSlice.COMPLETED, ListStatusSlice.PLANNING).forEachIndexed { index, slice ->
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = when (index) {
                            0 -> Arrangement.Start
                            1 -> Arrangement.Center
                            else -> Arrangement.End
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(slice.argb)))
                        Text(
                            (insights.statusCounts[slice] ?: 0).toString(),
                            color = AniLifeColors.Foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                        Text(" ${slice.label}", color = AniLifeColors.MutedForeground, fontSize = 14.sp, maxLines = 1)
                    }
                }
            }
        }
        val cards = continueRows.take(2)
        if (cards.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeading("Continue watching")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    cards.forEach { ContinueTile(it, onOpenAnime, Modifier.weight(1f)) }
                    if (cards.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (insights.recent.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeading("Recent activity", "View all" to { onTab(SheetTab.ACTIVITY) })
                insights.recent.take(3).forEach { ActivityRow(it) }
            }
        }
    }
}

@Composable
private fun StackedStatusBar(counts: Map<ListStatusSlice, Int>) {
    val total = counts.values.sum()
    Row(
        Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))
            .background(AniLifeColors.Foreground.copy(alpha = 0.08f)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (total > 0) {
            counts.filterValues { it > 0 }.forEach { (slice, count) ->
                Box(Modifier.weight(count.toFloat()).fillMaxHeight().background(Color(slice.argb)))
            }
        }
    }
}

/** A Continue Watching title as a poster card: which episode, and how far through. */
@Composable
private fun ContinueTile(entry: ContinueEntry, onOpenAnime: (Long) -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val anime = entry.anime
    val total = anime.episodes?.takeIf { it > 0 }
    val episode = entry.progress.episode.toInt()
    val inEpisode = if (entry.progress.duration > 0.0) (entry.progress.position / entry.progress.duration).coerceIn(0.0, 1.0).toFloat() else 0f
    val fraction = if (total != null) ((episode - 1 + inEpisode) / total).coerceIn(0f, 1f) else inEpisode
    Column(modifier.clickable { onOpenAnime(anime.id) }.pointerHoverIcon(PointerIcon.Hand)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f).clip(shape)) {
            RemoteImage(anime.cover, null, Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.BottomEnd).padding(8.dp).size(38.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)).border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, "Resume", tint = Color.White, modifier = Modifier.size(22.dp)) }
        }
        Text(
            anime.title, color = AniLifeColors.Foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            if (total != null) "Episode $episode of $total" else "Episode $episode",
            color = AniLifeColors.MutedForeground, fontSize = 12.sp, maxLines = 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(AniLifeColors.Foreground.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(AniLifeColors.Primary))
            }
            Text("${(fraction * 100).roundToInt()}%", color = AniLifeColors.MutedForeground, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** One change to the list: what happened, when, and the title's cover. */
@Composable
private fun ActivityRow(entry: ListEntry) {
    val icon = when (activityKind(entry.status)) {
        ActivityKind.COMPLETED -> Icons.Rounded.Check
        ActivityKind.WATCHING -> Icons.Rounded.PlayArrow
        ActivityKind.REWATCHING -> Icons.Rounded.Replay
        ActivityKind.PAUSED -> Icons.Rounded.Pause
        ActivityKind.DROPPED -> Icons.Rounded.RemoveCircleOutline
        ActivityKind.PLANNED -> Icons.Rounded.Bookmark
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(AniLifeColors.Foreground.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = AniLifeColors.Primary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                activityHeadline(entry, entry.anime.title),
                color = AniLifeColors.Foreground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(activityDetail(entry), color = AniLifeColors.MutedForeground, fontSize = 12.sp, maxLines = 1)
        }
        RemoteImage(entry.anime.cover, null, Modifier.size(width = 44.dp, height = 60.dp).clip(RoundedCornerShape(8.dp)))
    }
}

@Composable
private fun Stats(insights: AccountInsights, activity: Map<Long, Int>, fromAccount: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ActivityHeatmap(activity, fromAccount)
        HighlightTiles(insights)
        StatusBreakdown(insights.statusCounts)
        if (insights.formats.isNotEmpty()) HorizontalBars("Formats", "What your list is made of", insights.formats)
        if (insights.topGenres.isNotEmpty()) {
            val onlyPlanning = insights.statusCounts.filterKeys { it != ListStatusSlice.PLANNING }.values.sum() == 0
            HorizontalBars(
                "Top genres",
                if (onlyPlanning) "Across the titles you plan to watch" else "Across the titles you have started",
                insights.topGenres,
            )
        }
        if (insights.releaseYears.isNotEmpty()) ColumnChart("Release years", "When the titles on your list came out", insights.releaseYears)
        if (insights.scoreSpread.isNotEmpty()) {
            ColumnChart("Your scores", "How you rate what you have scored", insights.scoreSpread)
        } else {
            SectionTitle("Your scores", "Score a few titles and your spread shows up here")
        }
    }
}

/** Days watched, episodes, completion rate and what is left to watch, two by two. */
@Composable
private fun HighlightTiles(insights: AccountInsights) {
    val hasWholeDay = insights.minutesWatched >= 1440
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HighlightTile(
                if (hasWholeDay) String.format(Locale.US, "%.1f", insights.minutesWatched / 1440.0) else watchTimeLabel(insights.minutesWatched),
                if (hasWholeDay) "Days watched" else "Watch time",
                Modifier.weight(1f),
            )
            HighlightTile(String.format(Locale.US, "%,d", insights.episodes), "Episodes", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HighlightTile(insights.completionRate?.let { "$it%" } ?: "—", "Completion rate", Modifier.weight(1f))
            HighlightTile(String.format(Locale.US, "%,d", insights.episodesToGo), "Episodes to go", Modifier.weight(1f))
        }
    }
}

@Composable
private fun HighlightTile(value: String, label: String, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.clip(shape).border(1.dp, AniLifeColors.Border, shape).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(value, color = AniLifeColors.Foreground, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text(label, color = AniLifeColors.MutedForeground, fontSize = 12.sp)
    }
}

/** One stacked bar of every status, and a legend that doubles as the table: count and share, in text. */
@Composable
private fun StatusBreakdown(counts: Map<ListStatusSlice, Int>) {
    val total = counts.values.sum()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Your list", "$total titles by status")
        StackedStatusBar(counts)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            counts.forEach { (slice, count) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(slice.argb)))
                    Text(slice.label, color = AniLifeColors.Foreground, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
                    Text(count.toString(), color = AniLifeColors.Foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (total > 0) "${(count * 100f / total).roundToInt()}%" else "—",
                        color = AniLifeColors.MutedForeground, fontSize = 12.sp,
                        modifier = Modifier.width(44.dp).padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** Magnitude across a handful of categories: horizontal bars against a common track, longest first. */
@Composable
private fun HorizontalBars(title: String, subtitle: String, items: List<Pair<String, Int>>) {
    val max = items.maxOf { it.second }.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(title, subtitle)
        items.forEach { (label, count) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = AniLifeColors.Foreground, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(104.dp))
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(AniLifeColors.Foreground.copy(alpha = 0.08f))) {
                    Box(Modifier.fillMaxWidth(count.toFloat() / max).fillMaxHeight().background(BarColor))
                }
                Text(count.toString(), color = AniLifeColors.Foreground, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp).padding(start = 10.dp))
            }
        }
    }
}

/** Counts along an ordered axis, as columns on a shared baseline; only the tallest carries its number. */
@Composable
private fun ColumnChart(title: String, subtitle: String, items: List<Pair<String, Int>>) {
    val max = items.maxOf { it.second }.coerceAtLeast(1)
    val peak = items.indexOfFirst { it.second == max }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(title, subtitle)
        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            items.forEachIndexed { index, (_, count) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (index == peak) Text(count.toString(), color = AniLifeColors.Foreground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Box(
                        Modifier.fillMaxWidth()
                            .height(if (count == 0) 2.dp else (96f * count / max).coerceAtLeast(4f).dp)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(if (count == 0) AniLifeColors.Foreground.copy(alpha = 0.12f) else BarColor),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { (label, _) ->
                Text(label, color = AniLifeColors.MutedForeground, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** The account's own controls, as rows at the foot of every tab. */
@Composable
private fun AccountActions(service: ListService, onSync: () -> Unit, onLogout: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Account", "Signed in with ${service.label()}")
        ActionRow(Icons.Rounded.Refresh, "Sync now", "Pull your lists from ${service.label()} again", onSync)
        ActionRow(Icons.AutoMirrored.Rounded.Logout, "Log out", "Sign this PC out of ${service.label()}", onLogout, danger = true)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, danger: Boolean = false) {
    val shape = RoundedCornerShape(12.dp)
    val ink = if (danger) AniLifeColors.Destructive else AniLifeColors.Foreground
    Row(
        Modifier.fillMaxWidth().clip(shape).background(AniLifeColors.Foreground.copy(alpha = 0.05f))
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, color = ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = AniLifeColors.MutedForeground, fontSize = 12.sp)
        }
    }
}
