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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.image.BufferedImage
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Swallows every mouse, touch and wheel event, so what is underneath cannot be used. */
fun Modifier.blockInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}

/**
 * The grey wash and the "not allowed" mouse pointer shown over the window while it is blocked. It
 * takes every mouse event, so nothing underneath can be used, and the pointer changes over anything
 * that would normally be clickable.
 */
@Composable
fun BusyOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(AniLifeColors.Scrim.copy(alpha = 0.35f))
            .blockInput()
            .pointerHoverIcon(NotAllowedPointer, overrideDescendants = true),
    )
}

/** A circle with a slash, drawn here because Java has no stock "not allowed" cursor. */
val NotAllowedPointer: PointerIcon by lazy {
    val cursor = runCatching {
        val size = 32
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        fun mark(color: java.awt.Color, width: Float) {
            g.color = color
            g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.drawOval(6, 6, 20, 20)
            g.drawLine(10, 10, 22, 22)
        }
        mark(java.awt.Color.WHITE, 6f)
        mark(java.awt.Color(0xC62828), 3f)
        g.dispose()
        Toolkit.getDefaultToolkit().createCustomCursor(image, Point(16, 16), "not-allowed")
    }.getOrDefault(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR))
    PointerIcon(cursor)
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
