package me.enivzoy.anilife.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import me.enivzoy.anilife.app.Screen

 



@Composable
fun AppSidebar(
    current: Screen,
    onNavigate: (Screen) -> Unit,
    onRandom: () -> Unit,
    onSearch: () -> Unit = { onNavigate(Screen.Browse) },
    modifier: Modifier = Modifier,
    /** Set only when the refresh button is placed in this bar; [refreshShown] says if this screen has one. */
    refresh: RefreshHost? = null,
    refreshShown: Boolean = false,
) {
    BoxWithConstraints(
        modifier = modifier
            .width(64.dp)
            .fillMaxHeight()
            .background(AniLifeColors.Sidebar)
            .drawBehind {
                val x = size.width - 0.5f
                drawLine(
                    color = AniLifeColors.Border,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            },
    ) {
        val compact = maxHeight < 560.dp
        val iconSize = if (compact) 34.dp else 40.dp
        val gap = if (compact) 1.dp else 2.dp
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
            BrandMark(size = if (compact) 30.dp else 36.dp)
            Spacer(Modifier.height(if (compact) 8.dp else 18.dp))

            NavIcon(Icons.Rounded.Search, "Search (Ctrl+K)", selected = false, size = iconSize) { onSearch() }
            Spacer(Modifier.height(gap))
            NavIcon(Icons.Rounded.Home, "Home", selected = current is Screen.Home, size = iconSize) { onNavigate(Screen.Home) }
            Spacer(Modifier.height(gap))
            NavIcon(Icons.Rounded.Explore, "Browse", selected = current is Screen.Browse, size = iconSize) { onNavigate(Screen.Browse) }
            Spacer(Modifier.height(gap))
            NavIcon(Icons.Rounded.CalendarMonth, "Schedule", selected = current is Screen.Schedule, size = iconSize) { onNavigate(Screen.Schedule) }
            Spacer(Modifier.height(gap))
            NavIcon(Icons.Rounded.VideoLibrary, "Library", selected = current is Screen.Library, size = iconSize) { onNavigate(Screen.Library) }
            Spacer(Modifier.height(gap))
            NavIcon(Icons.Rounded.Download, "Downloads", selected = current is Screen.Downloads, size = iconSize) { onNavigate(Screen.Downloads) }
            Spacer(Modifier.height(gap))
            NavIcon(Icons.Rounded.Extension, "Providers", selected = current is Screen.Providers, size = iconSize) { onNavigate(Screen.Providers) }
            Spacer(Modifier.height(gap))
            NavIcon(Icons.Rounded.Casino, "Random pick", selected = false, size = iconSize) { onRandom() }

            if (compact) Spacer(Modifier.height(6.dp)) else Spacer(Modifier.weight(1f))

            if (refresh != null) {
                // Kept on screens that have nothing to refresh, greyed out and not clickable, so the slot
                // never empties and the icons below never jump.
                NavIcon(
                    Icons.Rounded.Refresh,
                    when {
                        !refreshShown -> "Refresh (nothing to refresh here)"
                        refresh.busy -> "Refreshing"
                        else -> "Refresh"
                    },
                    selected = refreshShown && refresh.busy,
                    size = iconSize,
                    spin = refreshShown && refresh.busy,
                    enabled = refreshShown,
                ) {
                    refresh.request()
                }
                Spacer(Modifier.height(gap))
            }
            NavIcon(Icons.Rounded.Notifications, "Notifications", selected = current is Screen.Notifications, size = iconSize) {
                onNavigate(Screen.Notifications)
            }
            Spacer(Modifier.height(gap))
            NavIcon(Icons.Rounded.Person, "Profile", selected = current is Screen.Profile, size = iconSize) { onNavigate(Screen.Profile) }
            Spacer(Modifier.height(gap))
            NavIcon(Icons.Rounded.Settings, "Settings", selected = current is Screen.Settings, size = iconSize) { onNavigate(Screen.Settings) }
            Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
        }
    }
}

@Composable
private fun NavIcon(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    spin: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background: Color = when {
        !enabled -> Color.Transparent
        selected -> AniLifeColors.SidebarAccent
        hovered -> AniLifeColors.SidebarAccent.copy(alpha = 0.65f)
        else -> Color.Transparent
    }
    val tint = when {
        !enabled -> AniLifeColors.MutedForeground.copy(alpha = 0.35f)
        selected || hovered -> AniLifeColors.Foreground
        else -> AniLifeColors.MutedForeground
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp).let { if (spin) it.rotate(spinAngle()) else it },
        )
    }
}

/** One turn every 0.9 s, the same speed as the floating refresh button's. */
@Composable
private fun spinAngle(): Float {
    val transition = rememberInfiniteTransition(label = "sidebar refresh spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "sidebar refresh angle",
    )
    return angle
}
