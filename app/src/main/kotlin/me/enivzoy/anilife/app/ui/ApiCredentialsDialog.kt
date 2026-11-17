package me.enivzoy.anilife.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.enivzoy.anilife.app.data.ApiCredentials
import me.enivzoy.anilife.app.data.ApiKey

/** One account service whose API values the viewer can set. */
enum class ApiService(val label: String, val keys: List<ApiKey>) {
    MAL("MyAnimeList", listOf(ApiKey.MAL_CLIENT_ID)),
    ANILIST("AniList", listOf(ApiKey.ANILIST_CLIENT_ID, ApiKey.ANILIST_CLIENT_SECRET)),
}

private fun ApiKey.fieldLabel(): String = when (this) {
    ApiKey.MAL_CLIENT_ID -> "MyAnimeList client ID"
    ApiKey.ANILIST_CLIENT_ID -> "AniList client ID"
    ApiKey.ANILIST_CLIENT_SECRET -> "AniList client secret (optional)"
}

/** The pencil on an account row, with its hover hint. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditApiValuesButton(service: ApiService, onClick: () -> Unit) {
    TooltipArea(
        tooltip = {
            Box(
                Modifier.shadow(8.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp))
                    .background(AniLifeColors.Overlay).padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    if (service == ApiService.MAL) "Edit API client ID" else "Edit API client ID and secret",
                    color = AniLifeColors.Foreground,
                    fontSize = 11.sp,
                )
            }
        },
        delayMillis = 300,
        tooltipPlacement = androidx.compose.foundation.TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
    ) {
        IconButton(onClick = onClick) {
            Icon(Icons.Rounded.Edit, contentDescription = "Edit ${service.label} API values", tint = AniLifeColors.MutedForeground)
        }
    }
}

/**
 * Asks for a service's API client ID (and the AniList secret), masked like a password.
 *
 * Save keeps the values; Connect keeps them and starts the sign-in (offered only when the account is
 * not connected yet); Cancel changes nothing. The client ID is required, and the message says so under
 * its box. Saving one service writes only that service's keys, so the other's stay as they are.
 */
@Composable
fun ApiCredentialsDialog(
    service: ApiService,
    credentials: ApiCredentials = ApiCredentials.shared,
    canConnect: Boolean,
    onSaved: () -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val values = remember(service) {
        service.keys.associateWith { mutableStateOf(credentials.read(it)) }
    }
    var showRequired by remember(service) { mutableStateOf(false) }
    val idKey = service.keys.first()

    fun save(): Boolean {
        if (values.getValue(idKey).value.isBlank()) {
            showRequired = true
            return false
        }
        credentials.save(values.mapValues { it.value.value })
        onSaved()
        return true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${service.label} API values") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Kept in a file on this PC, and hidden as you paste them.",
                    color = AniLifeColors.MutedForeground,
                    fontSize = 12.sp,
                )
                service.keys.forEach { key ->
                    val state = values.getValue(key)
                    val missing = key == idKey && showRequired && state.value.isBlank()
                    OutlinedTextField(
                        value = state.value,
                        onValueChange = {
                            state.value = it.trim()
                            if (key == idKey) showRequired = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(key.fieldLabel()) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        isError = missing,
                        supportingText = if (missing) ({ Text("Value is required.", color = AniLifeColors.Destructive) }) else null,
                    )
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    OutlinedButton(onClick = { if (save()) onDismiss() }) { Text("Save") }
                    if (canConnect) Button(onClick = { if (save()) onConnect() }) { Text("Connect") }
                }
            }
        },
    )
}
