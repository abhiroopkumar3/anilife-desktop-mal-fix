package me.enivzoy.anilife.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * How long the spinner stays up at least. The Android app holds its refresh indicator for the same
 * 550 ms: a cached or quick reload can finish inside one blink, which reads as "nothing happened"
 * rather than "already up to date".
 */
const val MIN_REFRESH_INDICATOR_MS = 550L

/**
 * Carries "the viewer pressed refresh" from the floating button to whichever screen is showing.
 *
 * The button lives in the app shell and the data lives in the screens, which are rebuilt as the
 * viewer moves around, so the two meet here. A counter rather than an event: it is read during
 * composition, so a press survives a recomposition between the click and the screen seeing it.
 *
 * One refresh at a time. A press while one is running is dropped, and [busy] stays up until the
 * screen reports it is done through [RefreshOn].
 */
@Stable
class RefreshHost {
    var requests by mutableIntStateOf(0)
        private set

    var busy by mutableStateOf(false)
        private set

    /** Asks the screen on show to reload. Returns false, and does nothing, while one is running. */
    fun request(): Boolean {
        if (busy) return false
        busy = true
        requests++
        return true
    }

    internal fun finish() {
        busy = false
    }
}

/**
 * Runs [reload] each time the viewer presses refresh while this screen is showing.
 *
 * A press made before the screen existed is not replayed: a screen opened after a refresh loads
 * itself the way it always did. [reload] runs to completion even if the button is pressed again,
 * and the spinner is held for at least [MIN_REFRESH_INDICATOR_MS].
 */
@Composable
fun RefreshOn(host: RefreshHost, reload: suspend () -> Unit) {
    val latest by rememberUpdatedState(reload)
    var handled by remember { mutableIntStateOf(host.requests) }
    LaunchedEffect(host.requests) {
        if (host.requests == handled) return@LaunchedEffect
        handled = host.requests
        val startedAt = System.nanoTime()
        try {
            latest()
        } finally {
            withContext(NonCancellable) {
                val shownMs = (System.nanoTime() - startedAt) / 1_000_000
                if (shownMs < MIN_REFRESH_INDICATOR_MS) delay(MIN_REFRESH_INDICATOR_MS - shownMs)
                host.finish()
            }
        }
    }
}

/** The round refresh button that floats in a screen's bottom right corner. */
@Composable
fun RefreshButton(host: RefreshHost, modifier: Modifier = Modifier) {
    val spin = rememberInfiniteTransition(label = "refresh spin")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "refresh angle",
    )
    Box(
        modifier = modifier
            .shadow(8.dp, CircleShape)
            .size(48.dp)
            .clip(CircleShape)
            .background(AniLifeColors.Card)
            .border(1.dp, AniLifeColors.Border, CircleShape)
            .clickable(role = Role.Button, onClick = { host.request() }),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = if (host.busy) "Refreshing" else "Refresh",
            tint = if (host.busy) AniLifeColors.Primary else AniLifeColors.Foreground,
            modifier = Modifier.size(22.dp).rotate(if (host.busy) angle else 0f),
        )
    }
}
