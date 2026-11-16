package me.enivzoy.anilife.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.enivzoy.anilife.app.data.AccountLibrary
import me.enivzoy.anilife.app.data.AniListSync
import me.enivzoy.anilife.app.data.ContinueEntry
import me.enivzoy.anilife.app.data.ContinueStoreInstance
import me.enivzoy.anilife.app.data.CustomListStoreInstance
import me.enivzoy.anilife.app.data.LIST_DESTINATIONS
import me.enivzoy.anilife.app.data.ListEdits
import me.enivzoy.anilife.app.data.ListEntry
import me.enivzoy.anilife.app.data.ListProgress
import me.enivzoy.anilife.app.data.ListService
import me.enivzoy.anilife.app.data.aniListEntriesToEntries
import me.enivzoy.anilife.app.data.formatListScore
import me.enivzoy.anilife.app.data.listScoreLabel
import me.enivzoy.anilife.app.ui.AniLifeColors
import me.enivzoy.anilife.app.ui.RemoteImage

/** The account's row for a title, from whichever service is signed in. Null when it is on no list. */
@Composable
internal fun accountEntryFor(anilistId: Long): ListEntry? {
    val aniList by AniListSync.entries.collectAsState()
    val mal by AccountLibrary.malEntries.collectAsState()
    return remember(aniList, mal, anilistId) {
        (aniListEntriesToEntries(aniList) + mal).firstOrNull { it.anime.id == anilistId }
    }
}

private enum class Pane { MENU, SCORE, PROGRESS, DELETE }

/**
 * The menu for any poster, as Android's AnimeListActionsDialog: move the title between the account's
 * lists, score it, set its episode count, delete it, and keep or drop its saved copy on this PC.
 * [onToggleSaved] is null where a surface has no save control of its own.
 */
@Composable
fun AnimeListActionsDialog(
    anilistId: Long,
    title: String,
    cover: String?,
    metadata: String?,
    saved: Boolean,
    onRemoveSaved: () -> Unit,
    onDismiss: () -> Unit,
    kicker: String = "My list",
    /** What a custom list needs to draw this title; null leaves the "My lists" rows off. */
    listCard: me.enivzoy.anilife.core.model.AnimeCard? = null,
) {
    val service = AccountLibrary.activeService()
    val entry = accountEntryFor(anilistId)
    val currentStatus = entry?.status
    val currentScore = entry?.score?.takeIf { it > 0 }
    val scope = rememberCoroutineScope()
    var pane by remember(anilistId) { mutableStateOf(Pane.MENU) }
    var busy by remember(anilistId) { mutableStateOf<String?>(null) }
    var error by remember(anilistId) { mutableStateOf<String?>(null) }

    fun run(key: String, close: Boolean = true, block: suspend () -> Unit) {
        if (busy != null) return
        busy = key
        error = null
        scope.launch {
            try {
                block()
                if (close) onDismiss() else pane = Pane.MENU
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error = failure.message ?: "That didn't go through."
            } finally {
                busy = null
            }
        }
    }

    DialogShell(onDismiss = { if (busy == null) onDismiss() }, kicker = kicker, title = title, metadata = metadata, cover = cover) {
        when {
            pane == Pane.SCORE && service != null -> ScorePane(
                current = currentScore,
                busy = busy,
                service = service,
                onRate = { score -> run("score$score", close = false) { ListEdits.rate(anilistId, score) } },
                onBack = { pane = Pane.MENU },
            )
            pane == Pane.PROGRESS && service != null -> ProgressPane(
                anilistId = anilistId,
                service = service,
                busy = busy != null,
                onSave = { count -> run("progress", close = false) { ListEdits.setProgress(anilistId, count) } },
                onBack = { pane = Pane.MENU },
            )
            pane == Pane.DELETE && service != null -> {
                Text("Delete this from your list?", color = AniLifeColors.Foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(8.dp))
                Text(
                    "$title comes off your ${ListEdits.serviceLabel(service)} list completely, with its status, episode count " +
                        "and score, and there is no undo on either service. It leaves this PC's watchlist and Continue Watching too. " +
                        "Downloaded episodes stay.",
                    color = AniLifeColors.MutedForeground, fontSize = 13.sp,
                )
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { pane = Pane.MENU }, enabled = busy == null) { Text("Cancel") }
                    TextButton(onClick = { run("delete") { ListEdits.delete(anilistId) } }, enabled = busy == null) {
                        Text("Delete", color = AniLifeColors.Destructive)
                    }
                }
            }
            service == null -> {
                Text(
                    "Your watchlist is kept on this PC with no account needed. Sign in to AniList or MyAnimeList on the " +
                        "Accounts page for Watching, Paused and the rest.",
                    color = AniLifeColors.MutedForeground, fontSize = 13.sp,
                )
            }
            else -> {
                SectionLabel("Move on ${ListEdits.serviceLabel(service)} to")
                LIST_DESTINATIONS.forEach { destination ->
                    ActionRow(
                        label = destination.label,
                        hint = destination.hint,
                        selected = destination.status == currentStatus,
                        busy = busy == destination.status,
                        enabled = busy == null && destination.status != currentStatus,
                        destructive = destination.status == "DROPPED",
                        onClick = { run(destination.status) { ListEdits.moveTo(anilistId, destination.status) } },
                    )
                }
                if (currentStatus != null) {
                    SectionLabel("Your score")
                    ActionRow(
                        label = currentScore?.let { "${formatListScore(it)} / 10" } ?: "Rate this season",
                        hint = if (currentScore == null) "Sent to your ${ListEdits.serviceLabel(service)} list"
                        else listScoreLabel(currentScore.toInt()).takeIf { currentScore % 1.0 == 0.0 },
                        enabled = busy == null,
                        onClick = { pane = Pane.SCORE },
                    )
                    ActionRow(
                        label = "Episodes watched",
                        hint = "Change the count on ${ListEdits.serviceLabel(service)}, up or down",
                        enabled = busy == null,
                        onClick = { pane = Pane.PROGRESS },
                    )
                    SectionLabel("Remove entirely")
                    ActionRow(
                        label = "Delete from ${ListEdits.serviceLabel(service)}",
                        enabled = busy == null,
                        destructive = true,
                        busy = busy == "delete",
                        onClick = { pane = Pane.DELETE },
                    )
                }
            }
        }
        if (pane == Pane.MENU && listCard != null) {
            val customLists by CustomListStoreInstance.lists.collectAsState()
            var namingList by remember(anilistId) { mutableStateOf(false) }
            SectionLabel("My lists")
            customLists.forEach { list ->
                val member = list.entries.any { it.anime.id == anilistId }
                ActionRow(
                    label = list.name,
                    hint = "${list.entries.size} anime",
                    selected = member,
                    enabled = busy == null,
                    onClick = { CustomListStoreInstance.setMember(list.name, listCard, !member) },
                )
            }
            ActionRow(
                label = "New list…",
                hint = if (customLists.isEmpty()) "Group titles your own way: favourites, a marathon, anything" else null,
                enabled = busy == null,
                onClick = { namingList = true },
            )
            if (namingList) {
                CustomListNameDialog(
                    title = "New list",
                    confirmText = "Create",
                    service = service,
                    onSave = { name -> CustomListStoreInstance.createWith(name, listCard)?.also { namingList = false } },
                    onDismiss = { namingList = false },
                )
            }
        }
        error?.let { Text(it, color = AniLifeColors.Destructive, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)) }
        if (pane == Pane.MENU) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                if (saved) {
                    TextButton(onClick = { onRemoveSaved(); onDismiss() }, enabled = busy == null) {
                        Text("Remove from watchlist", color = AniLifeColors.Destructive)
                    }
                }
                TextButton(onClick = onDismiss, enabled = busy == null) { Text("Close") }
            }
        }
    }
}

/** The menu for a Continue Watching card: details, take it off the row, or move it on the account. */
@Composable
fun ContinueActionsDialog(entry: ContinueEntry, onOpenDetails: () -> Unit, onDismiss: () -> Unit) {
    val anilistId = entry.anime.id
    val service = AccountLibrary.activeService()
    val currentStatus = accountEntryFor(anilistId)?.status
    val scope = rememberCoroutineScope()
    var busy by remember(anilistId) { mutableStateOf<String?>(null) }
    var error by remember(anilistId) { mutableStateOf<String?>(null) }
    val episode = entry.progress.episode.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }

    DialogShell(
        onDismiss = { if (busy == null) onDismiss() },
        kicker = "Continue watching",
        title = entry.anime.title,
        metadata = "Episode $episode",
        cover = entry.anime.cover,
    ) {
        SectionLabel("This title")
        ActionRow("Details", "Synopsis, seasons and every episode", enabled = busy == null, onClick = { onOpenDetails(); onDismiss() })
        ActionRow(
            "Remove from Continue watching",
            service?.let { "Only from this row. Your ${ListEdits.serviceLabel(it)} list stays as it is" } ?: "Only from this row",
            enabled = busy == null,
            destructive = true,
            onClick = { ContinueStoreInstance.remove(anilistId); onDismiss() },
        )
        if (service == null) {
            SectionLabel("Your list")
            Text(
                "Sign in to AniList or MyAnimeList to move this title between lists.",
                color = AniLifeColors.MutedForeground, fontSize = 13.sp,
            )
        } else {
            SectionLabel("Move on ${ListEdits.serviceLabel(service)}")
            LIST_DESTINATIONS.forEach { destination ->
                ActionRow(
                    label = destination.label,
                    hint = destination.hint,
                    selected = destination.status == currentStatus,
                    busy = busy == destination.status,
                    enabled = busy == null && destination.status != currentStatus,
                    destructive = destination.status == "DROPPED",
                    onClick = {
                        busy = destination.status
                        error = null
                        scope.launch {
                            try {
                                ListEdits.moveTo(anilistId, destination.status)
                                onDismiss()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                error = failure.message ?: "That didn't go through."
                                busy = null
                            }
                        }
                    },
                )
            }
        }
        error?.let { Text(it, color = AniLifeColors.Destructive, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss, enabled = busy == null) { Text("Close") }
        }
    }
}

@Composable
private fun ScorePane(current: Double?, busy: String?, service: ListService, onRate: (Int) -> Unit, onBack: () -> Unit) {
    SectionLabel("Score this season on ${ListEdits.serviceLabel(service)}")
    (10 downTo 1).forEach { score ->
        ActionRow(
            label = score.toString(),
            hint = listScoreLabel(score),
            selected = current == score.toDouble(),
            busy = busy == "score$score",
            enabled = busy == null,
            onClick = { onRate(score) },
        )
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
        if (current != null) {
            TextButton(onClick = { onRate(0) }, enabled = busy == null) { Text("Remove score", color = AniLifeColors.Destructive) }
        }
        TextButton(onClick = onBack, enabled = busy == null) { Text("Back") }
    }
}

@Composable
private fun ProgressPane(anilistId: Long, service: ListService, busy: Boolean, onSave: (Int) -> Unit, onBack: () -> Unit) {
    var loaded by remember(anilistId) { mutableStateOf<ListProgress?>(null) }
    var loading by remember(anilistId) { mutableStateOf(true) }
    var count by remember(anilistId) { mutableStateOf(0) }
    var problem by remember(anilistId) { mutableStateOf<String?>(null) }
    LaunchedEffect(anilistId) {
        try {
            val progress = ListEdits.progressOf(anilistId)
            loaded = progress
            count = progress?.progress ?: 0
            if (progress == null) problem = "This title isn't on your ${ListEdits.serviceLabel(service)} list any more."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            problem = failure.message ?: "Couldn't read your list."
        }
        loading = false
    }
    val max = loaded?.maxEpisodes
    fun nudge(by: Int) {
        val next = (count + by).coerceAtLeast(0)
        count = max?.let { next.coerceAtMost(it) } ?: next
    }
    SectionLabel("Episodes watched")
    if (loading) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    } else if (loaded != null) {
        Text(
            "Set the count on ${ListEdits.serviceLabel(service)}. Lowering it is allowed; the status stays as it is.",
            color = AniLifeColors.MutedForeground, fontSize = 12.sp,
        )
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { nudge(-10) }, enabled = !busy && count > 0) { Text("−10") }
            TextButton(onClick = { nudge(-1) }, enabled = !busy && count > 0) { Text("−") }
            Text(max?.let { "$count / $it" } ?: count.toString(), color = AniLifeColors.Foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { nudge(1) }, enabled = !busy && (max == null || count < max)) { Text("+") }
            TextButton(onClick = { nudge(10) }, enabled = !busy && (max == null || count < max)) { Text("+10") }
        }
        ActionRow(
            label = if (count == loaded?.progress) "No change" else "Save $count episodes",
            hint = "Sent to your ${ListEdits.serviceLabel(service)} list",
            enabled = !busy,
            busy = busy,
            onClick = { if (count == loaded?.progress) onBack() else onSave(count) },
        )
    }
    problem?.let { Text(it, color = AniLifeColors.Destructive, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onBack, enabled = !busy) { Text("Back") }
    }
}

@Composable
private fun DialogShell(
    onDismiss: () -> Unit,
    kicker: String,
    title: String,
    metadata: String?,
    cover: String?,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(420.dp).heightIn(max = 640.dp).clip(RoundedCornerShape(12.dp)).background(AniLifeColors.Card).padding(20.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 48.dp, height = 72.dp).clip(RoundedCornerShape(6.dp)).background(AniLifeColors.CardHover)) {
                    RemoteImage(url = cover, contentDescription = title, modifier = Modifier.size(width = 48.dp, height = 72.dp), placeholderColor = AniLifeColors.CardHover)
                }
                Column(Modifier.weight(1f)) {
                    Text(kicker.uppercase(), color = AniLifeColors.MutedForeground, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Text(title, color = AniLifeColors.Foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    metadata?.let { Text(it, color = AniLifeColors.MutedForeground, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = AniLifeColors.MutedForeground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

@Composable
private fun ActionRow(
    label: String,
    hint: String? = null,
    selected: Boolean = false,
    busy: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (selected) AniLifeColors.Primary.copy(alpha = 0.14f) else AniLifeColors.Card)
            .clickable(enabled = enabled, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (destructive) AniLifeColors.Destructive else AniLifeColors.Foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            hint?.let { Text(it, color = AniLifeColors.MutedForeground, fontSize = 12.sp) }
        }
        when {
            busy -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            selected -> Icon(Icons.Rounded.Check, contentDescription = "Current", tint = AniLifeColors.Primary, modifier = Modifier.size(16.dp))
        }
    }
}
