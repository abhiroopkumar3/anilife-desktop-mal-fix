package me.enivzoy.anilife.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * Work that must finish before the app is used again: a list sync, a backup restore, an import.
 * While anything is running inside [hold], the whole window ignores the mouse and the keyboard
 * (see the overlay in App.kt). A count rather than a flag, so two holds that overlap are both
 * waited for.
 */
object AppBusy {
    private val holds = MutableStateFlow(0)
    private val mutableActive = MutableStateFlow(false)
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()

    suspend fun <T> hold(work: suspend () -> T): T {
        holds.update { it + 1 }
        mutableActive.value = true
        try {
            return work()
        } finally {
            val left = holds.updateAndGet { it - 1 }
            mutableActive.value = left > 0
        }
    }
}

/** Swallows every mouse, touch and wheel event, so what is underneath cannot be used. */
fun Modifier.blockInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}

/** The refresh icon, turning for as long as it is shown. */
@Composable
fun SpinningRefreshIcon(size: Dp, tint: Color, modifier: Modifier = Modifier, icon: ImageVector = Icons.Rounded.Refresh) {
    val turn by rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "spinAngle",
    )
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size).rotate(turn))
}
