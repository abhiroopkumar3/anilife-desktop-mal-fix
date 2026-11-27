package me.enivzoy.anilife.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared corner language for the functional glass layer. */
object GlassShapes {
    val control = RoundedCornerShape(12.dp)
    val toolbar = RoundedCornerShape(20.dp)
    val grouped = RoundedCornerShape(16.dp)
    val panel = RoundedCornerShape(22.dp)
    val pill = RoundedCornerShape(50)
}

/**
 * A floating glass surface for menus, popovers and contextual tool groups.
 *
 * Deliberately not used for page content: content stays on its own plane, glass is reserved
 * for the functional layer.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShapes.panel,
    alpha: Float = 0.5f,
    accent: Color? = null,
    elevation: Dp = 10.dp,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .glassSurface(
                shape = shape,
                alpha = alpha,
                elevation = elevation,
                accent = accent,
                interactionSource = interactionSource,
            ),
        content = content,
    )
}

/**
 * Primary/secondary action button rendered as a piece of glass.
 *
 * Hover raises the ambient light, press adds a small optical scale-down and a stronger
 * specular response, focus adds a visible rim. Interaction state is shared with the glass
 * modifier through a single [MutableInteractionSource], so visuals and behaviour never drift.
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
    danger: Boolean = false,
    leadingIcon: ImageVector? = null,
    compact: Boolean = false,
    shape: Shape = GlassShapes.control,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by rememberAppFloat(if (pressed && enabled) 0.97f else 1f)
    val glow = when {
        danger -> AniLifeColors.Destructive
        accent -> AniLifeColors.Primary
        else -> null
    }
    val contentColor = when {
        !enabled -> AniLifeColors.MutedForeground
        danger -> AniLifeColors.Destructive
        accent -> AniLifeColors.Primary
        else -> AniLifeColors.Foreground
    }
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.55f
            }
            .clip(shape)
            .liquidGlass(
                shape = shape,
                alpha = 0.48f,
                accent = glow,
                interactionSource = interaction,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .focusable(interactionSource = interaction, enabled = enabled)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .defaultMinSize(minHeight = if (compact) 30.dp else 38.dp)
            .padding(
                horizontal = if (compact) 12.dp else 16.dp,
                vertical = if (compact) 5.dp else 9.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            content()
        }
    }
}

/** Compact glass icon control used by the sidebar, toolbars and overlay actions. */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    accent: Color? = null,
    size: Dp = 40.dp,
    iconSize: Dp = 18.dp,
    shape: Shape = RoundedCornerShape(11.dp),
    /** Keep a quiet glass base while idle. Needed over artwork where a flat icon would vanish. */
    alwaysGlass: Boolean = false,
    /** Turn the icon, for a refresh that is running. */
    spin: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by rememberAppFloat(if (pressed && enabled) 0.94f else 1f)
    val tint = when {
        !enabled -> AniLifeColors.MutedForeground.copy(alpha = 0.6f)
        selected -> AniLifeColors.Foreground
        hovered -> AniLifeColors.Foreground
        else -> AniLifeColors.MutedForeground
    }
    val lit = selected || hovered || alwaysGlass
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.55f
            }
            .clip(shape)
            .glassIconSurface(
                shape = shape,
                lit = lit,
                selected = selected,
                accent = accent,
                interaction = interaction,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .focusable(interactionSource = interaction, enabled = enabled)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
        contentAlignment = Alignment.Center,
    ) {
        if (spin) {
            SpinningRefreshIcon(iconSize, tint, icon = icon)
        } else {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}

/**
 * Sidebar/toolbar icon treatment: idle icons stay flat and quiet; selected or hovered icons
 * become small glass lenses.
 */
@Composable
private fun Modifier.glassIconSurface(
    shape: Shape,
    lit: Boolean,
    selected: Boolean,
    accent: Color?,
    interaction: MutableInteractionSource,
): Modifier {
    if (!lit) return this
    val glow = accent ?: if (selected) AniLifeColors.Primary else null
    val hovered by interaction.collectIsHoveredAsState()
    return this.liquidGlass(
        shape = shape,
        alpha = when {
            selected -> 0.66f
            hovered -> 0.44f
            else -> 0.30f
        },
        accent = glow,
        interactionSource = interaction,
    )
}

/** A connected floating bar of glass controls. */
@Composable
fun GlassToolbar(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShapes.toolbar,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .clip(shape)
            .glassSurface(shape = shape, alpha = 0.5f, elevation = 12.dp)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/**
 * Tab bar with a sliding glass selection lens.
 *
 * The selected pill is drawn as its own surface and animated with a spring, instead of the
 * old opaque full-width indicator bar.
 */
@Composable
fun GlassTabBar(
    labels: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    shape: Shape = GlassShapes.grouped,
    onSelect: (Int) -> Unit,
) {
    if (labels.isEmpty()) return
    val reducedMotion = LocalReducedMotion.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .liquidGlass(shape = shape, alpha = 0.42f),
    ) {
        val inner = 4.dp
        val segmentWidth = (maxWidth - inner * 2) / labels.size
        val offsetX by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
        )
        Box(Modifier.padding(inner)) {
            Box(
                Modifier
                    .offset(x = offsetX)
                    .width(segmentWidth)
                    .height(34.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                AniLifeColors.Foreground.copy(alpha = 0.14f),
                                AniLifeColors.Foreground.copy(alpha = 0.05f),
                            ),
                        ),
                    )
                    .border(1.dp, AniLifeColors.Foreground.copy(alpha = 0.20f), CircleShape),
            )
            Row(Modifier.fillMaxWidth().height(34.dp).selectableGroup()) {
                labels.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    val interaction = remember(index) { MutableInteractionSource() }
                    val color by animateColorAsState(
                        targetValue = if (selected) AniLifeColors.Foreground else AniLifeColors.MutedForeground,
                        animationSpec = tween(if (reducedMotion) 0 else 180),
                        label = "tabColor",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .selectable(
                                selected = selected,
                                interactionSource = interaction,
                                indication = null,
                                role = Role.Tab,
                                onClick = { onSelect(index) },
                            )
                            .focusable(interactionSource = interaction)
                            .pointerHoverIcon(PointerIcon.Hand),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = color,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Small mutually-exclusive control (filters, modes) with a sliding glass lens.
 */
@Composable
fun GlassSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = GlassShapes.grouped,
) {
    if (options.isEmpty()) return
    val reducedMotion = LocalReducedMotion.current
    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .liquidGlass(shape = shape, alpha = 0.40f),
    ) {
        val inner = 3.dp
        val segmentWidth = (maxWidth - inner * 2) / options.size
        val offsetX by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        )
        Box(Modifier.padding(inner)) {
            Box(
                Modifier
                    .offset(x = offsetX)
                    .width(segmentWidth)
                    .height(30.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                AniLifeColors.Primary.copy(alpha = 0.34f),
                                AniLifeColors.Primary.copy(alpha = 0.16f),
                            ),
                        ),
                    )
                    .border(1.dp, AniLifeColors.Primary.copy(alpha = 0.45f), CircleShape),
            )
            Row(Modifier.fillMaxWidth().height(30.dp).selectableGroup()) {
                options.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    val interaction = remember(index) { MutableInteractionSource() }
                    val color by animateColorAsState(
                        targetValue = if (selected) AniLifeColors.Primary else AniLifeColors.MutedForeground,
                        animationSpec = tween(if (reducedMotion) 0 else 180),
                        label = "segmentColor",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .selectable(
                                selected = selected,
                                interactionSource = interaction,
                                indication = null,
                                role = Role.RadioButton,
                                onClick = { onSelect(index) },
                            )
                            .focusable(interactionSource = interaction)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = color,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
