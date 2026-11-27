package me.enivzoy.anilife.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.enivzoy.anilife.app.data.AccountAdoption
import me.enivzoy.anilife.app.data.AniListConnection
import me.enivzoy.anilife.app.data.AniListRepository
import me.enivzoy.anilife.app.data.AniListSync
import me.enivzoy.anilife.app.ui.AniLifeColors
import me.enivzoy.anilife.app.ui.PageScrollbar
import me.enivzoy.anilife.app.ui.appEnter

@Composable
fun AniListAccountSettings(repository: AniListRepository, onOpenAccount: () -> Unit = {}) {
    val user by AniListSync.user.collectAsState()
    val entries by AniListSync.entries.collectAsState()
    val syncStatus by AniListSync.status.collectAsState()
    val localEntries by repository.libraryEntries.collectAsState()
    val scope = rememberCoroutineScope()
    var showTokenField by remember { mutableStateOf(false) }
    var tokenText by remember { mutableStateOf("") }
    var actionError by remember { mutableStateOf<String?>(null) }
    var showApiDialog by remember { mutableStateOf(false) }
    var showImportReview by remember { mutableStateOf(false) }
    var importingId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) { AniListSync.restore() }

    val remoteIds = remember(entries) { entries.mapTo(mutableSetOf()) { it.anime.id } }
    val localOnly = remember(localEntries, remoteIds) { localEntries.filterNot { it.id in remoteIds } }
    var syncingNow by remember { mutableStateOf(false) }
    val connecting = syncStatus.connection == AniListConnection.CONNECTING || syncStatus.syncing

    fun startAniListSignIn() {
        try {
            AniListSync.beginAuth()
            showTokenField = true
        } catch (failure: Throwable) {
            actionError = failure.message ?: "Couldn't open AniList sign-in."
        }
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(AniLifeColors.Card).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val connected = user
            if (connected != null) {
                me.enivzoy.anilife.app.ui.AccountIdentity(
                    name = connected.name,
                    avatar = connected.avatar,
                    subtitle = "Connected to AniList",
                    onClick = onOpenAccount,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Icon(Icons.Rounded.Person, contentDescription = null, tint = AniLifeColors.Primary)
                Column(Modifier.weight(1f)) {
                    Text("AniList", color = AniLifeColors.Foreground, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(
                        "Your AniList account is the synced library.",
                        color = AniLifeColors.MutedForeground,
                        fontSize = 12.sp,
                    )
                }
            }
            if (user != null) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            actionError = null
                            syncingNow = true
                            try {
                                // The whole window waits until the list has been read.
                                me.enivzoy.anilife.app.data.AppBusy.hold { AniListSync.refreshList() }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                actionError = failure.message ?: "Couldn't refresh AniList."
                            } finally {
                                syncingNow = false
                            }
                        }
                    },
                    enabled = !connecting,
                ) {
                    if (syncingNow || connecting) me.enivzoy.anilife.app.ui.SpinningRefreshIcon(24.dp, androidx.compose.material3.LocalContentColor.current)
                    else Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Sync now")
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            actionError = null
                            try {
                                AniListSync.disconnect()
                            } catch (failure: Throwable) {
                                actionError = failure.message ?: "Couldn't disconnect AniList."
                            }
                        }
                    },
                ) { Text("Disconnect") }
            }
            me.enivzoy.anilife.app.ui.EditApiValuesButton(me.enivzoy.anilife.app.ui.ApiService.ANILIST) { showApiDialog = true }
        }

        if (user != null) {
            Text(
                "Your account has ${entries.size} anime. Episode progress syncs when playback reaches the end.",
                color = AniLifeColors.MutedForeground,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Text(
                "Score format: ${user!!.scoreFormat.name.replace("POINT_", "").replace('_', ' ')}",
                color = AniLifeColors.MutedForeground,
                fontSize = 11.sp,
            )
            val syncMessage = syncStatus.message
            if (syncMessage != null) {
                Text(
                    syncMessage,
                    color = if (syncStatus.connection == AniListConnection.OFFLINE) AniLifeColors.Warning else AniLifeColors.Destructive,
                    fontSize = 12.sp,
                )
            } else if (syncStatus.pendingProgress > 0) {
                Text(
                    "${syncStatus.pendingProgress} episode update(s) will retry at the next sync.",
                    color = AniLifeColors.Warning,
                    fontSize = 12.sp,
                )
            } else {
                syncStatus.lastSyncedAt?.let {
                    Text(
                        "Last synced ${java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(
                            java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm"),
                        )}",
                        color = AniLifeColors.MutedForeground,
                        fontSize = 11.sp,
                    )
                }
            }
            if (localOnly.isNotEmpty()) {
                OutlinedButton(onClick = { showImportReview = true }) {
                    Icon(Icons.Rounded.BookmarkAdd, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Review ${localOnly.size} local title(s) to import")
                }
            }
            if (syncStatus.connection == AniListConnection.EXPIRED) {
                TextButton(onClick = { showTokenField = true }) { Text("Reconnect AniList") }
            }
        } else {
            Text(
                "Use the official implicit sign-in flow. It needs only your client ID; the secret, if you save one, is kept but not used by this sign-in.",
                color = AniLifeColors.MutedForeground,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        actionError = null
                        // No client ID yet: ask for it first, and open the sign-in once it is saved.
                        if (me.enivzoy.anilife.app.data.AniListConfig.clientId().isBlank()) {
                            showApiDialog = true
                        } else {
                            startAniListSignIn()
                        }
                    },
                    enabled = !connecting,
                ) {
                    if (connecting) CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Person, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Connect AniList")
                }
                TextButton(onClick = { showTokenField = !showTokenField }) {
                    Text(if (showTokenField) "Hide token field" else "Paste token")
                }
            }
            if (showTokenField) {
                Text(
                    "After approving access in your browser, paste the access token from the redirect URL (or paste the full URL). Tokens are private and expire.",
                    color = AniLifeColors.MutedForeground,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
                OutlinedTextField(
                    value = tokenText,
                    onValueChange = { tokenText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("AniList access token or redirect URL") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions.Default,
                )
                Button(
                    onClick = {
                        scope.launch {
                            actionError = null
                            try {
                                AccountAdoption.connectAniList(tokenText)
                                tokenText = ""
                                showTokenField = false
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                actionError = failure.message ?: "AniList sign-in failed."
                            }
                        }
                    },
                    enabled = tokenText.isNotBlank() && !connecting,
                ) {
                    if (connecting) CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp)
                    else Text("Connect and sync list")
                }
            }
        }
        actionError?.let { Text(it, color = AniLifeColors.Destructive, fontSize = 12.sp) }
    }

    if (showApiDialog) {
        me.enivzoy.anilife.app.ui.ApiCredentialsDialog(
            service = me.enivzoy.anilife.app.ui.ApiService.ANILIST,
            canConnect = user == null,
            onSaved = {},
            onConnect = { showApiDialog = false; startAniListSignIn() },
            onDismiss = { showApiDialog = false },
        )
    }

    if (showImportReview) {
        AlertDialog(
            onDismissRequest = { showImportReview = false },
            modifier = Modifier.appEnter(),
            title = { Text("Review local saves") },
            text = {
                val scrollState = rememberScrollState()
                Box(Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Nothing is uploaded automatically. Add each local title to AniList as Planning only when you choose.",
                        color = AniLifeColors.MutedForeground,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    localOnly.forEach { anime ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                anime.title,
                                Modifier.weight(1f),
                                color = AniLifeColors.Foreground,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        importingId = anime.id
                                        actionError = null
                                        try {
                                            AniListSync.addToList(anime)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Throwable) {
                                            actionError = failure.message ?: "Couldn't add ${anime.title} to AniList."
                                        } finally {
                                            importingId = null
                                        }
                                    }
                                },
                                enabled = importingId == null,
                            ) {
                                if (importingId == anime.id) {
                                    CircularProgressIndicator(Modifier.width(14.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("Add")
                                }
                            }
                        }
                    }
                    actionError?.let { Text(it, color = AniLifeColors.Destructive, fontSize = 12.sp) }
                }
                PageScrollbar(scrollState, Modifier.align(Alignment.CenterEnd))
                }
            },
            confirmButton = { TextButton(onClick = { showImportReview = false }) { Text("Done") } },
            containerColor = AniLifeColors.Card,
            titleContentColor = AniLifeColors.Foreground,
            textContentColor = AniLifeColors.Foreground,
        )
    }
}
