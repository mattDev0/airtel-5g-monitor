package com.airtel.monitor.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.min

/** The four sections of the dashboard, in bar order. */
enum class DashTab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Rounded.Home),
    Network("Network", Icons.Rounded.Wifi),
    Tools("Tools", Icons.Rounded.NetworkCheck),
    Devices("Devices", Icons.Rounded.Devices)
}

/** Height of the floating bar, used by screens to leave room under their content. */
val GlassNavBarHeight = 72.dp

/**
 * Floating "liquid glass" bottom bar.
 *
 * A translucent pill sits over the content. The selected tab is marked by a glass lens that
 * springs to the new tab, stretching along its direction of travel and squashing slightly
 * (faster move = more stretch) before it settles. You can also drag a finger across the bar to
 * scrub between tabs.
 */
@Composable
fun GlassNavBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = DashTab.entries
    val shape = RoundedCornerShape(36.dp)
    val dark = isSystemInDarkTheme()
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme

    val position = remember { Animatable(selected.toFloat()) }
    LaunchedEffect(selected) {
        position.animateTo(selected.toFloat(), spring(dampingRatio = 0.6f, stiffness = 240f))
    }

    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    fun choose(index: Int) {
        if (index != currentSelected) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentOnSelect(index)
        }
    }

    val glass = scheme.surfaceContainerHigh
    val edge = Color.White
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(GlassNavBarHeight)
            .shadow(
                elevation = 20.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.25f),
                spotColor = Color.Black.copy(alpha = 0.25f)
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        glass.copy(alpha = if (dark) 0.66f else 0.80f),
                        glass.copy(alpha = if (dark) 0.52f else 0.66f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        edge.copy(alpha = if (dark) 0.40f else 0.95f),
                        edge.copy(alpha = 0.06f),
                        edge.copy(alpha = if (dark) 0.22f else 0.60f)
                    )
                ),
                shape = shape
            )
            .pointerInput(tabs.size) {
                detectHorizontalDragGestures { change, _ ->
                    val index = (change.position.x / size.width * tabs.size).toInt()
                        .coerceIn(0, tabs.size - 1)
                    choose(index)
                }
            }
    ) {
        val tabWidth = maxWidth / tabs.size

        // The lens.
        Box(
            Modifier
                .fillMaxHeight()
                .width(tabWidth)
                .graphicsLayer {
                    translationX = position.value * size.width
                    val speed = abs(position.velocity)
                    scaleX = 1f + min(speed * 0.07f, 0.45f)
                    scaleY = 1f - min(speed * 0.025f, 0.12f)
                }
                .padding(6.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            scheme.primary.copy(alpha = if (dark) 0.34f else 0.26f),
                            scheme.primary.copy(alpha = if (dark) 0.16f else 0.12f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(edge.copy(alpha = if (dark) 0.45f else 0.85f), edge.copy(alpha = 0.08f))
                    ),
                    shape = RoundedCornerShape(30.dp)
                )
        )

        Row(Modifier.fillMaxSize()) {
            tabs.forEachIndexed { i, tab ->
                // 1 when the lens is over this tab, fading to 0 one tab away.
                val near = (1f - abs(position.value - i)).coerceIn(0f, 1f)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab,
                            onClickLabel = tab.label
                        ) { choose(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = lerp(scheme.onSurfaceVariant, scheme.primary, near),
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer {
                                val s = 1f + 0.14f * near
                                scaleX = s
                                scaleY = s
                            }
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = tab.label,
                        fontSize = 11.sp,
                        fontWeight = if (near > 0.5f) FontWeight.SemiBold else FontWeight.Medium,
                        color = lerp(scheme.onSurfaceVariant, scheme.primary, near)
                    )
                }
            }
        }
    }
}
