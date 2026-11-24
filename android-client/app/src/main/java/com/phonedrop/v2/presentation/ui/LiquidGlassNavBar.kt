package com.phonedrop.v2.presentation.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

data class GlassNavItem(
    val label: String,
    val icon: @Composable (Color) -> Unit
)

/**
 * Apple-inspired Liquid Glass floating capsule navigation bar with translucent glass surface,
 * draggable/spring-animated refractive lens pill, and text/icon wobble as the lens sweeps across tabs.
 */
@Composable
fun LiquidGlassNavBar(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val count = items.size.coerceAtLeast(1)
    var isDragging by remember { mutableStateOf(false) }
    var dragFloat by remember { mutableFloatStateOf(selectedIndex.toFloat()) }

    val targetPos = if (isDragging) dragFloat else selectedIndex.toFloat()
    val lensPos by animateFloatAsState(
        targetValue = targetPos,
        animationSpec = spring(
            dampingRatio = if (isDragging) Spring.DampingRatioNoBouncy else 0.56f,
            stiffness = if (isDragging) Spring.StiffnessHigh else 340f
        ),
        label = "LiquidGlassLens"
    )

    val delta = abs(lensPos - targetPos)
    val velocityFactor = if (isDragging) 0.45f else (delta * 1.4f).coerceIn(0f, 1f)
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(66.dp)
                .shadow(
                    elevation = 22.dp,
                    shape = RoundedCornerShape(999.dp),
                    ambientColor = Color.Black.copy(alpha = 0.75f),
                    spotColor = Color.Black.copy(alpha = 0.75f)
                )
                .clip(RoundedCornerShape(999.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1F2D26).copy(alpha = 0.58f),
                            Color(0xFF0E1612).copy(alpha = 0.74f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.34f),
                            Color(0xFFC8A97E).copy(alpha = 0.16f),
                            Color.White.copy(alpha = 0.08f)
                        )
                    ),
                    shape = RoundedCornerShape(999.dp)
                )
                .padding(5.dp)
        ) {
            val slotWidth = maxWidth / count
            val slotWidthPx = with(density) { slotWidth.toPx() }.coerceAtLeast(1f)
            val lensOffsetPx = (lensPos * slotWidthPx).roundToInt()

            val stretchX = 1f + 0.16f * velocityFactor
            val stretchY = 1f - 0.07f * velocityFactor

            // Specular sliding Liquid Glass Lens Pill
            Box(
                modifier = Modifier
                    .offset { IntOffset(lensOffsetPx, 0) }
                    .width(slotWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        scaleX = stretchX
                        scaleY = stretchY
                    }
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.24f),
                                Color(0xFFDFC49B).copy(alpha = 0.18f),
                                Color(0xFFC8A97E).copy(alpha = 0.10f)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.52f),
                                Color(0xFFDFC49B).copy(alpha = 0.24f)
                            )
                        ),
                        shape = RoundedCornerShape(999.dp)
                    )
            )

            // Interactive Tab Row (tap + horizontal slide gesture)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(count, slotWidthPx) {
                        detectHorizontalDragGestures(
                            onDragStart = { startOffset ->
                                isDragging = true
                                dragFloat = (startOffset.x / slotWidthPx - 0.5f)
                                    .coerceIn(0f, (count - 1).toFloat())
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                dragFloat = (dragFloat + dragAmount / slotWidthPx)
                                    .coerceIn(0f, (count - 1).toFloat())
                            },
                            onDragEnd = {
                                val snapped = dragFloat.roundToInt().coerceIn(0, count - 1)
                                isDragging = false
                                onSelect(snapped)
                            },
                            onDragCancel = {
                                isDragging = false
                            }
                        )
                    }
            ) {
                items.forEachIndexed { idx, item ->
                    val dist = lensPos - idx
                    val prox = (1f - abs(dist)).coerceIn(0f, 1f)
                    val wave = sin(dist.coerceIn(-1f, 1f) * PI).toFloat()
                    val moving = velocityFactor.coerceAtLeast(if (prox in 0.05f..0.95f) 0.65f else 0f)

                    val activeTint = if (prox > 0.5f) Color(0xFFF5E6CC) else Color(0xFFA39C90)

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onSelect(idx)
                            }
                            .graphicsLayer {
                                val bounce = 0.08f * abs(wave) * moving
                                scaleX = 1f + 0.13f * prox + bounce
                                scaleY = 1f + 0.11f * prox - bounce * 0.55f
                                translationY = -with(density) { (2.2.dp * prox).toPx() }
                                rotationZ = wave * 6.0f * moving
                            }
                    ) {
                        item.icon(activeTint)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = item.label,
                            color = activeTint,
                            fontSize = 11.sp,
                            fontWeight = if (prox > 0.5f) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
