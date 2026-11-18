package me.enivzoy.anilife.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.enivzoy.anilife.app.data.AccountAdoption
import me.enivzoy.anilife.app.data.AniListRepository
import me.enivzoy.anilife.app.data.AniListSync
import me.enivzoy.anilife.app.data.WatchActivityInstance
import me.enivzoy.anilife.app.data.mergedActivity
import me.enivzoy.anilife.app.ui.ActivityHeatmap
import me.enivzoy.anilife.app.data.MyAnimeListSync
import me.enivzoy.anilife.app.ui.AniLifeColors

@Composable
fun MyAnimeListScreen(
    repository: AniListRepository,
    settings: me.enivzoy.anilife.app.data.AppSettings,
    onSettingsChange: ((me.enivzoy.anilife.app.data.AppSettings) -> me.enivzoy.anilife.app.data.AppSettings) -> Unit,
    onOpenAccount: () -> Unit = {},
) {
    var showTags by remember { mutableStateOf(false) }
    val user by MyAnimeListSync.user.collectAsState()
    val configured by MyAnimeListSync.configured.collectAsState()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refreshMalList() {
        loading = true
        error = null
        try {
            me.enivzoy.anilife.app.data.AccountLibrary.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.message ?: "Couldn't sync your MyAnimeList account."
        } finally {
            loading = false
        }
    }

    var showApiDialog by remember { mutableStateOf(false) }

    fun connectMal() {
        MyAnimeListSync.configure()
        scope.launch {
            loading = true
            error = null
            try {
                AccountAdoption.connectMyAnimeList()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error = failure.message ?: "MyAnimeList sign-in failed."
            } finally {
                loading = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp)) {
        Text("Accounts", color = AniLifeColors.Foreground, fontSize = 30.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text("Connect your tracking accounts to sync your list and watched progress.", color = AniLifeColors.MutedForeground, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AniLifeColors.Card).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (user == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Connect your MyAnimeList account", color = AniLifeColors.Foreground, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    me.enivzoy.anilife.app.ui.EditApiValuesButton(me.enivzoy.anilife.app.ui.ApiService.MAL) { showApiDialog = true }
                }
                Text(
                    "Sign in to sync your list and watched progress.",
                    color = AniLifeColors.MutedForeground,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Button(
                    onClick = {
                        // No client ID yet: ask for it first, and sign in once it is saved.
                        if (me.enivzoy.anilife.app.data.ApiCredentials.shared.isSet(me.enivzoy.anilife.app.data.ApiKey.MAL_CLIENT_ID)) connectMal()
                        else showApiDialog = true
                    },
                    enabled = !loading,
                ) {
                    if (loading) CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Person, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (loading) "Connecting…" else "Connect MyAnimeList")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    me.enivzoy.anilife.app.ui.AccountIdentity(
                        name = user?.name.orEmpty(),
                        avatar = user?.picture,
                        subtitle = "Connected to MyAnimeList",
                        onClick = onOpenAccount,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { showTags = true }) { Text("Tags shown as lists") }
                    OutlinedButton(
                        onClick = { scope.launch { me.enivzoy.anilife.app.data.AppBusy.hold { refreshMalList() } } },
                        enabled = !loading,
                    ) {
                        // Turns while the list is read, and the whole window waits for it.
                        if (loading) me.enivzoy.anilife.app.ui.SpinningRefreshIcon(24.dp, androidx.compose.material3.LocalContentColor.current)
                        else Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Sync now")
                    }
                    TextButton(onClick = {
                        scope.launch {
                            MyAnimeListSync.disconnect()
                        }
                    }) { Text("Disconnect") }
                    me.enivzoy.anilife.app.ui.EditApiValuesButton(me.enivzoy.anilife.app.ui.ApiService.MAL) { showApiDialog = true }
                }
                Text("Completed episodes sync to your MAL list after playback finishes.", color = AniLifeColors.MutedForeground, fontSize = 12.sp)
            }
            error?.let { Text(it, color = AniLifeColors.Destructive, fontSize = 12.sp) }
        }

        if (showApiDialog) {
            me.enivzoy.anilife.app.ui.ApiCredentialsDialog(
                service = me.enivzoy.anilife.app.ui.ApiService.MAL,
                canConnect = user == null,
                onSaved = { MyAnimeListSync.configure() },
                onConnect = { showApiDialog = false; connectMal() },
                onDismiss = { showApiDialog = false },
            )
        }

        if (showTags) {
            MalTagsDialog(
                settings = settings,
                onUpdate = { names ->
                    onSettingsChange { it.copy(hiddenMalTags = names) }
                    scope.launch { me.enivzoy.anilife.app.data.AccountLibrary.refresh() }
                },
                onDismiss = { showTags = false },
            )
        }

        Spacer(Modifier.height(18.dp))
        AniListAccountSettings(repository, onOpenAccount)
    }
}
