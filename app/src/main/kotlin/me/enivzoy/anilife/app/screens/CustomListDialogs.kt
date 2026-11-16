package me.enivzoy.anilife.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.enivzoy.anilife.app.data.AppSettings
import me.enivzoy.anilife.app.data.CustomList
import me.enivzoy.anilife.app.data.CustomListStoreInstance
import me.enivzoy.anilife.app.data.ListService
import me.enivzoy.anilife.app.data.MAX_CUSTOM_LIST_NAME
import me.enivzoy.anilife.app.data.hiddenTagNames
import me.enivzoy.anilife.app.data.hiddenTagSet
import me.enivzoy.anilife.app.data.listKey
import me.enivzoy.anilife.app.data.normalizedListName
import me.enivzoy.anilife.app.ui.AniLifeColors

/** Where a list lives, in the words the naming and managing dialogs use (Android `customListHome`). */
fun customListHome(service: ListService?): String = when (service) {
    ListService.ANILIST -> "It syncs to your AniList account as a custom list."
    ListService.MAL -> "MyAnimeList has no custom lists, so it syncs as a tag on each title in it."
    null -> "It stays on this PC until you sign in to AniList or MyAnimeList."
}

/**
 * Asks for a list's name, for a new list or a renamed one. [onSave] answers with the name it stored,
 * or null to keep the dialog open: the name is taken, or has nothing left once tidied.
 */
@Composable
fun CustomListNameDialog(
    title: String,
    confirmText: String,
    service: ListService?,
    onSave: (String) -> String?,
    onDismiss: () -> Unit,
    initial: String = "",
) {
    var value by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    fun save() {
        val name = normalizedListName(value)
        if (name == null) {
            error = "Give the list a name."
            return
        }
        if (onSave(name) == null) error = "You already have a list called $name."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = AniLifeColors.Card,
        title = { Text(title, color = AniLifeColors.Foreground) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(customListHome(service), color = AniLifeColors.MutedForeground, fontSize = 13.sp)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(MAX_CUSTOM_LIST_NAME); error = null },
                    placeholder = { Text("List name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                error?.let { Text(it, color = AniLifeColors.Destructive, fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(onClick = ::save) { Text(confirmText, color = AniLifeColors.Primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

private enum class ManageMode { CHOOSE, RENAME, DELETE }

/** Rename or delete one list: the Edit button on a list's section (Android `CustomListManageDialog`). */
@Composable
fun CustomListManageDialog(list: CustomList, service: ListService?, onDismiss: () -> Unit) {
    var mode by remember(list.name) { mutableStateOf(ManageMode.CHOOSE) }
    when (mode) {
        ManageMode.RENAME -> CustomListNameDialog(
            title = "Rename ${list.name}",
            confirmText = "Rename",
            service = service,
            initial = list.name,
            onSave = { name -> CustomListStoreInstance.rename(list.name, name)?.also { onDismiss() } },
            onDismiss = onDismiss,
        )
        ManageMode.DELETE -> AlertDialog(
            onDismissRequest = onDismiss,
            shape = RectangleShape,
            containerColor = AniLifeColors.Card,
            title = { Text("Delete ${list.name}?", color = AniLifeColors.Foreground) },
            text = {
                Text(
                    buildString {
                        append(
                            when (list.entries.size) {
                                0 -> "The list goes. It is empty. "
                                1 -> "The list goes, and the one anime in it leaves it. "
                                else -> "The list goes, and the ${list.entries.size} anime in it leave it. "
                            },
                        )
                        append(
                            when (service) {
                                ListService.ANILIST ->
                                    "It is deleted from your AniList account too. Titles that were only in this list " +
                                        "come off AniList with it; anything you are watching, planning or finished keeps its status."
                                ListService.MAL ->
                                    "The tag is taken off those titles on MyAnimeList too. Their status, progress and score stay."
                                null -> "Nothing else in your library changes."
                            },
                        )
                    },
                    color = AniLifeColors.MutedForeground,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { CustomListStoreInstance.delete(list.name); onDismiss() }) {
                    Text("Delete", color = AniLifeColors.Destructive)
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        ManageMode.CHOOSE -> AlertDialog(
            onDismissRequest = onDismiss,
            shape = RectangleShape,
            containerColor = AniLifeColors.Card,
            title = { Text(list.name, color = AniLifeColors.Foreground) },
            text = {
                Text(
                    "${list.entries.size} anime. ${customListHome(service)}",
                    color = AniLifeColors.MutedForeground,
                    fontSize = 13.sp,
                )
            },
            confirmButton = { TextButton(onClick = { mode = ManageMode.RENAME }) { Text("Rename", color = AniLifeColors.Primary) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { mode = ManageMode.DELETE }) { Text("Delete", color = AniLifeColors.Destructive) }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            },
        )
    }
}

/**
 * Which of the MyAnimeList account's tags show up as lists on the Library (Android `MalTagsDialog`).
 * Unticking one only stops it being shown here: the tag stays on MyAnimeList as it was, and is
 * written back unchanged whenever a title that carries it is touched.
 */
@Composable
fun MalTagsDialog(settings: AppSettings, onUpdate: (String) -> Unit, onDismiss: () -> Unit) {
    val tags by CustomListStoreInstance.malTags.collectAsState()
    val hidden = hiddenTagSet(settings.hiddenMalTags)
    fun isHidden(tag: String) = hidden.any { listKey(it) == listKey(tag) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = AniLifeColors.Card,
        title = { Text("Tags shown as lists", color = AniLifeColors.Foreground) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "MyAnimeList has no custom lists, so the Library shows your tags as lists instead. " +
                        "Untick a tag to keep it off the Library. It stays on MyAnimeList untouched.",
                    color = AniLifeColors.MutedForeground,
                    fontSize = 13.sp,
                )
                if (tags.isEmpty()) {
                    Text(
                        "No tags on your MyAnimeList yet. Lists you make here become tags, and any tags you add " +
                            "on MyAnimeList appear after the next sync.",
                        color = AniLifeColors.MutedForeground,
                        fontSize = 13.sp,
                    )
                } else {
                    Text("${tags.count { !isHidden(it) }} of ${tags.size} shown", color = AniLifeColors.MutedForeground, fontSize = 12.sp)
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(tags, key = { listKey(it) }) { tag ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = !isHidden(tag),
                                    onCheckedChange = { show ->
                                        val others = hidden.filterNot { listKey(it) == listKey(tag) }
                                        onUpdate(hiddenTagNames(if (show) others else others + tag))
                                    },
                                )
                                Text(tag, color = AniLifeColors.Foreground, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = AniLifeColors.Primary) } },
    )
}
