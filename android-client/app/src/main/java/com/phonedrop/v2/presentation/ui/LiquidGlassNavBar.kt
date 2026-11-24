package com.phonedrop.v2.presentation.ui

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class GlassNavItem(
    val label: String,
    val icon: @Composable (Color) -> Unit
)

private const val AGSL_PILL_REFRACT = """
uniform shader content;
uniform float4 uPillRect;
uniform float uBevelPx;
uniform float uRefractPx;
uniform float uStrength;

half4 main(float2 fragCoord) {
    float left = uPillRect.x;
    float top = uPillRect.y;
    float right = uPillRect.z;
    float bottom = uPillRect.w;

    float halfH = max((bottom - top) * 0.5, 1.0);
    float halfW = max((right - left) * 0.5, halfH);
    float2 center = float2((left + right) * 0.5, (top + bottom) * 0.5);
    float spineHalf = max(halfW - halfH, 0.0);

    float2 p = fragCoord - center;
    float2 q = float2(clamp(p.x, -spineHalf, spineHalf), 0.0);
    float2 diff = p - q;
    float distFromSpine = length(diff);
    float signedDist = distFromSpine - halfH;

    if (signedDist >= 0.0 || uStrength <= 0.001) {
        return content.eval(fragCoord);
    }

    float innerDepth = -signedDist;
    if (innerDepth >= uBevelPx) {
        return content.eval(fragCoord);
    }

    float t = clamp(1.0 - innerDepth / max(uBevelPx, 1.0), 0.0, 1.0);
    float lensCurve = t * t * (3.0 - 2.0 * t);
    float2 outwardNormal = distFromSpine > 0.001 ? (diff / distFromSpine) : float2(0.0, 0.0);

    float2 sampleCoord = fragCoord - outwardNormal * (lensCurve * uRefractPx * uStrength);
    return content.eval(sampleCoord);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun createPillLensShaderOrNull(): RuntimeShader? =
    runCatching { RuntimeShader(AGSL_PILL_REFRACT) }.getOrNull()

/**
 * Apple iOS 26-inspired high-gloss prismatic Liquid Glass floating capsule navbar:
 * - Zero scale/bounce distortion on external tab text or outer capsule geometry
 * - Coupled leading/trailing edge fluid slide keeping exact semicircular end-caps
 * - Chromatic rainbow light dispersion at both curved ends + glossy top specular dome
 * - In-pill optical AGSL bevel refraction clipped strictly inside the sliding glass button
 */
@Composable
fun LiquidGlassNavBar(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val count = items.size.coerceAtLeast(1)
    val latestOnSelect by rememberUpdatedState(onSelect)
    var isDragging by remember { mutableStateOf(false) }
    var dragIndexFloat by remember { mutableFloatStateOf(selectedIndex.toFloat()) }

    val targetPos = if (isDragging) dragIndexFloat else selectedIndex.toFloat()

    // Coupled fluid edge physics: leading edge glides smoothly ahead, trailing edge catches up
    // organically like a real droplet while maintaining true semicircular pill corners.
    val posA by animateFloatAsState(
        targetValue = targetPos,
        animationSpec = spring(
            dampingRatio = if (isDragging) 0.95f else 0.86f,
            stiffness = if (isDragging) 1100f else 430f
        ),
        label = "LiquidEdgeA"
    )
    val posB by animateFloatAsState(
        targetValue = targetPos,
        animationSpec = spring(
            dampingRatio = if (isDragging) 0.92f else 0.90f,
            stiffness = if (isDragging) 680f else 290f
        ),
        label = "LiquidEdgeB"
    )

    val isActiveSlide = isDragging || abs(posA - posB) > 0.015f || abs(posA - targetPos) > 0.015f
    val glassActivity by animateFloatAsState(
        targetValue = if (isActiveSlide) 1f else 0f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "LiquidGlassActivity"
    )

    val density = LocalDensity.current
    val lensShader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            createPillLensShaderOrNull()
        } else {
            null
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .shadow(
                    elevation = 24.dp,
                    shape = RoundedCornerShape(percent = 50),
                    ambientColor = Color.Black.copy(alpha = 0.72f),
                    spotColor = Color.Black.copy(alpha = 0.72f)
                )
        ) {
            val outerWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
            val outerHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
            val insetPx = with(density) { 4.dp.toPx() }
            val trackWidthPx = (outerWidthPx - 2f * insetPx).coerceAtLeast(1f)
            val pillHeightPx = (outerHeightPx - 2f * insetPx).coerceAtLeast(1f)
            val slotWidthPx = (trackWidthPx / count).coerceAtLeast(1f)
            val maxLeftPx = (trackWidthPx - slotWidthPx).coerceAtLeast(0f)

            val pillLeftPx = (min(posA, posB) * slotWidthPx).coerceIn(0f, maxLeftPx)
            val pillRightPx = (max(posA, posB) * slotWidthPx + slotWidthPx)
                .coerceIn(pillLeftPx + pillHeightPx.coerceAtMost(trackWidthPx), trackWidthPx)

            val pillRect = Rect(pillLeftPx, 0f, pillRightPx, pillHeightPx)
            val pillRadius = pillHeightPx * 0.5f
            val pillPath = Path().apply {
                addRoundRect(RoundRect(pillRect, CornerRadius(pillRadius, pillRadius)))
            }

            // Outer capsule glossy dark glass backdrop + outer curved rainbow end fringes
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawOuterGlassCapsule(
                    w = size.width,
                    h = size.height,
                    activity = glassActivity
                )
            }

            // Inner track containing strict in-pill vs outside-pill layers
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
            ) {
                // Layer 1: Stationary inactive tabs clipped OUTSIDE the sliding pill only.
                // Zero scaling, zero bounce, zero tilt on non-pill labels/icons.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            clipPath(pillPath, ClipOp.Difference) {
                                this@drawWithContent.drawContent()
                            }
                        }
                ) {
                    items.forEachIndexed { idx, item ->
                        TabItemContent(
                            item = item,
                            tint = Color(0xFFB5B0A4),
                            fontWeight = FontWeight.Medium,
                            isSelected = idx == selectedIndex,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Layer 2: Active luminous content + subtle AGSL bevel refraction clipped STRICTLY INSIDE pillPath.
                val bevelPx = with(density) { 13.dp.toPx() }
                val refractPx = with(density) { 5.5.dp.toPx() }
                val lensEffect = remember(lensShader, pillLeftPx, pillRightPx, pillHeightPx, glassActivity) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && lensShader != null) {
                        runCatching {
                            lensShader.setFloatUniform("uPillRect", pillLeftPx, 0f, pillRightPx, pillHeightPx)
                            lensShader.setFloatUniform("uBevelPx", bevelPx)
                            lensShader.setFloatUniform("uRefractPx", refractPx)
                            lensShader.setFloatUniform("uStrength", 0.18f + 0.82f * glassActivity)
                            AndroidRenderEffect.createRuntimeShaderEffect(lensShader, "content").asComposeRenderEffect()
                        }.getOrNull()
                    } else {
                        null
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            clipPath(pillPath, ClipOp.Intersect) {
                                this@drawWithContent.drawContent()
                            }
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                if (lensEffect != null) {
                                    renderEffect = lensEffect
                                }
                            }
                    ) {
                        items.forEachIndexed { idx, item ->
                            TabItemContent(
                                item = item,
                                tint = Color(0xFFF8EBD2),
                                fontWeight = FontWeight.SemiBold,
                                isSelected = idx == selectedIndex,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Layer 3: Glossy crystal dome + prismatic rainbow light dispersion on both semicircular ends
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawSlidingGlassPill(
                        pillRect = pillRect,
                        pillPath = pillPath,
                        radius = pillRadius,
                        activity = glassActivity,
                        motionLead = (posA - posB)
                    )
                }

                // Layer 4: Unified tap + smooth horizontal slide gesture across the capsule track
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(count, slotWidthPx) {
                            val touchSlop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val startX = down.position.x
                                var draggingThisGesture = false
                                var lastX = startX

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        if (draggingThisGesture) {
                                            val snapped = dragIndexFloat.roundToInt().coerceIn(0, count - 1)
                                            isDragging = false
                                            latestOnSelect(snapped)
                                        } else {
                                            val tapped = (lastX / slotWidthPx).toInt().coerceIn(0, count - 1)
                                            latestOnSelect(tapped)
                                        }
                                        break
                                    }

                                    lastX = change.position.x
                                    val totalDx = lastX - startX
                                    if (!draggingThisGesture && abs(totalDx) > touchSlop) {
                                        draggingThisGesture = true
                                        isDragging = true
                                        dragIndexFloat = (lastX / slotWidthPx - 0.5f)
                                            .coerceIn(0f, (count - 1).toFloat())
                                    }
                                    if (draggingThisGesture && change.positionChanged()) {
                                        change.consume()
                                        dragIndexFloat = (lastX / slotWidthPx - 0.5f)
                                            .coerceIn(0f, (count - 1).toFloat())
                                    }
                                }
                                isDragging = false
                            }
                        }
                )
            }
        }
    }
}

@Composable
private fun TabItemContent(
    item: GlassNavItem,
    tint: Color,
    fontWeight: FontWeight,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxSize()
            .semantics {
                role = Role.Tab
                selected = isSelected
            }
    ) {
        item.icon(tint)
        Spacer(Modifier.height(2.dp))
        Text(
            text = item.label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = fontWeight,
            maxLines = 1
        )
    }
}

private fun DrawScope.drawOuterGlassCapsule(w: Float, h: Float, activity: Float) {
    val r = h * 0.5f
    val cr = CornerRadius(r, r)
    val outerPath = Path().apply {
        addRoundRect(RoundRect(Rect(0f, 0f, w, h), cr))
    }

    clipPath(outerPath) {
        // Frosted botanical dark translucent glass substrate
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color(0xFF28362F).copy(alpha = 0.60f),
                0.5f to Color(0xFF131B17).copy(alpha = 0.72f),
                1f to Color(0xFF1A241E).copy(alpha = 0.66f)
            )
        )

        // Top curved overhead glossy sheen across the dock
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.17f),
                0.36f to Color.White.copy(alpha = 0.04f),
                0.44f to Color.Transparent
            )
        )

        // Subtle chromatic dispersion arcs tucked along the left & right rounded outer ends
        val rimStroke = 1.8.dp.toPx()
        val endMargin = 1.5.dp.toPx()
        val leftCenter = Offset(r, r)
        drawArc(
            brush = Brush.sweepGradient(
                0.00f to Color.Transparent,
                0.28f to Color.Transparent,
                0.38f to Color(0xFF66E8FF).copy(alpha = 0.22f + 0.06f * activity),
                0.50f to Color(0xFFD884FF).copy(alpha = 0.24f + 0.06f * activity),
                0.62f to Color(0xFFFFD870).copy(alpha = 0.20f + 0.06f * activity),
                0.72f to Color.Transparent,
                1.00f to Color.Transparent,
                center = leftCenter
            ),
            startAngle = 98f,
            sweepAngle = 164f,
            useCenter = false,
            topLeft = Offset(endMargin, endMargin),
            size = Size(h - 2f * endMargin, h - 2f * endMargin),
            style = Stroke(width = rimStroke, cap = StrokeCap.Round),
            blendMode = BlendMode.Plus
        )

        val rightCenter = Offset(w - r, r)
        drawArc(
            brush = Brush.sweepGradient(
                0.00f to Color(0xFFFF82B2).copy(alpha = 0.22f + 0.06f * activity),
                0.10f to Color(0xFF7B9CFF).copy(alpha = 0.22f + 0.06f * activity),
                0.22f to Color.Transparent,
                0.78f to Color.Transparent,
                0.90f to Color(0xFF75F5C8).copy(alpha = 0.22f + 0.06f * activity),
                1.00f to Color(0xFFFF82B2).copy(alpha = 0.22f + 0.06f * activity),
                center = rightCenter
            ),
            startAngle = -82f,
            sweepAngle = 164f,
            useCenter = false,
            topLeft = Offset(w - h + endMargin, endMargin),
            size = Size(h - 2f * endMargin, h - 2f * endMargin),
            style = Stroke(width = rimStroke, cap = StrokeCap.Round),
            blendMode = BlendMode.Plus
        )
    }

    // Crisp outer specular Fresnel border
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.48f),
                Color.White.copy(alpha = 0.11f),
                Color(0xFFDFC49B).copy(alpha = 0.16f),
                Color.White.copy(alpha = 0.28f)
            ),
            start = Offset(0f, 0f),
            end = Offset(w, h)
        ),
        cornerRadius = cr,
        style = Stroke(width = 1.dp.toPx())
    )
}

private fun DrawScope.drawSlidingGlassPill(
    pillRect: Rect,
    pillPath: Path,
    radius: Float,
    activity: Float,
    motionLead: Float
) {
    val pillW = pillRect.width.coerceAtLeast(1f)
    val pillH = pillRect.height.coerceAtLeast(1f)
    val cr = CornerRadius(radius, radius)
    val topLeft = Offset(pillRect.left, pillRect.top)
    val pillSize = Size(pillW, pillH)

    clipPath(pillPath) {
        // 1. Airy translucent crystal body
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.19f + 0.05f * activity),
                0.42f to Color(0xFFEBD7B9).copy(alpha = 0.08f + 0.03f * activity),
                0.82f to Color.White.copy(alpha = 0.09f),
                1f to Color.White.copy(alpha = 0.18f + 0.04f * activity),
                startY = pillRect.top,
                endY = pillRect.bottom
            ),
            topLeft = topLeft,
            size = pillSize
        )

        // 2. Glossy curved overhead specular dome reflection
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.44f + 0.12f * activity),
                0.18f to Color.White.copy(alpha = 0.18f + 0.05f * activity),
                0.46f to Color.White.copy(alpha = 0.03f),
                0.52f to Color.Transparent,
                startY = pillRect.top,
                endY = pillRect.bottom
            ),
            topLeft = topLeft,
            size = pillSize
        )

        val glossInsetX = min(pillW * 0.14f, 10.dp.toPx())
        val glossW = (pillW - 2f * glossInsetX).coerceAtLeast(1f)
        val glossH = max(pillH * 0.24f, 8.dp.toPx())
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.36f + 0.08f * activity),
                1f to Color.Transparent,
                startY = pillRect.top + 1.5.dp.toPx(),
                endY = pillRect.top + 1.5.dp.toPx() + glossH
            ),
            topLeft = Offset(pillRect.left + glossInsetX, pillRect.top + 1.5.dp.toPx()),
            size = Size(glossW, glossH),
            cornerRadius = CornerRadius(glossH * 0.5f, glossH * 0.5f)
        )

        // Bottom inner caustic bounce-light
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.76f to Color.Transparent,
                0.92f to Color(0xFFF6E6CA).copy(alpha = 0.12f + 0.04f * activity),
                1f to Color.White.copy(alpha = 0.22f + 0.05f * activity),
                startY = pillRect.top,
                endY = pillRect.bottom
            ),
            topLeft = topLeft,
            size = pillSize
        )

        // 3. Prismatic Rainbow Dispersion strictly along the curved left & right ends
        val prismShift = (motionLead * 0.04f).coerceIn(-0.04f, 0.04f)
        val prismGain = 0.78f + 0.22f * activity
        val softBevelW = 4.2.dp.toPx()
        val crispBevelW = 1.6.dp.toPx()
        val arcInset = 1.4.dp.toPx()
        val arcDia = (pillH - 2f * arcInset).coerceAtLeast(2f)
        val arcSize = Size(arcDia, arcDia)

        // Left end-cap rainbow dispersion (centered on 9 o'clock = 0.50)
        val leftCenter = Offset(pillRect.left + radius, radius)
        val leftPrismBrush = Brush.sweepGradient(
            0.00f to Color.Transparent,
            (0.24f + prismShift).coerceIn(0.18f, 0.30f) to Color.Transparent,
            0.33f to Color(0xFF64ECFF).copy(alpha = 0.85f * prismGain),
            0.41f to Color(0xFF7B9CFF).copy(alpha = 0.88f * prismGain),
            0.50f to Color(0xFFD884FF).copy(alpha = 0.94f * prismGain),
            0.59f to Color(0xFFFF82B2).copy(alpha = 0.90f * prismGain),
            0.67f to Color(0xFFFFD870).copy(alpha = 0.82f * prismGain),
            (0.76f + prismShift).coerceIn(0.70f, 0.82f) to Color.Transparent,
            1.00f to Color.Transparent,
            center = leftCenter
        )
        drawArc(
            brush = leftPrismBrush,
            startAngle = 92f,
            sweepAngle = 176f,
            useCenter = false,
            topLeft = Offset(pillRect.left + arcInset, arcInset),
            size = arcSize,
            alpha = 0.42f + 0.16f * activity,
            style = Stroke(width = softBevelW, cap = StrokeCap.Round),
            blendMode = BlendMode.Plus
        )
        drawArc(
            brush = leftPrismBrush,
            startAngle = 94f,
            sweepAngle = 172f,
            useCenter = false,
            topLeft = Offset(pillRect.left + arcInset, arcInset),
            size = arcSize,
            alpha = 0.86f,
            style = Stroke(width = crispBevelW, cap = StrokeCap.Round),
            blendMode = BlendMode.Plus
        )

        // Right end-cap rainbow dispersion (centered on 3 o'clock = 0.0 / 1.0)
        val rightCenter = Offset(pillRect.right - radius, radius)
        val rightPrismBrush = Brush.sweepGradient(
            0.00f to Color(0xFFFF82B2).copy(alpha = 0.92f * prismGain),
            0.09f to Color(0xFFD884FF).copy(alpha = 0.90f * prismGain),
            0.17f to Color(0xFF7B9CFF).copy(alpha = 0.84f * prismGain),
            (0.25f - prismShift).coerceIn(0.20f, 0.30f) to Color.Transparent,
            0.27f to Color.Transparent,
            0.73f to Color.Transparent,
            (0.75f - prismShift).coerceIn(0.70f, 0.80f) to Color.Transparent,
            0.83f to Color(0xFF64ECFF).copy(alpha = 0.84f * prismGain),
            0.91f to Color(0xFF75F5C8).copy(alpha = 0.86f * prismGain),
            0.96f to Color(0xFFFFD870).copy(alpha = 0.88f * prismGain),
            1.00f to Color(0xFFFF82B2).copy(alpha = 0.92f * prismGain),
            center = rightCenter
        )
        drawArc(
            brush = rightPrismBrush,
            startAngle = -88f,
            sweepAngle = 176f,
            useCenter = false,
            topLeft = Offset(pillRect.right - arcDia - arcInset, arcInset),
            size = arcSize,
            alpha = 0.42f + 0.16f * activity,
            style = Stroke(width = softBevelW, cap = StrokeCap.Round),
            blendMode = BlendMode.Plus
        )
        drawArc(
            brush = rightPrismBrush,
            startAngle = -86f,
            sweepAngle = 172f,
            useCenter = false,
            topLeft = Offset(pillRect.right - arcDia - arcInset, arcInset),
            size = arcSize,
            alpha = 0.86f,
            style = Stroke(width = crispBevelW, cap = StrokeCap.Round),
            blendMode = BlendMode.Plus
        )
    }

    // 4. Razor-sharp outer wet specular white Fresnel rim around the pill
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.86f),
                Color.White.copy(alpha = 0.24f),
                Color(0xFFDFC49B).copy(alpha = 0.30f),
                Color.White.copy(alpha = 0.58f)
            ),
            start = Offset(pillRect.left, pillRect.top),
            end = Offset(pillRect.right, pillRect.bottom)
        ),
        topLeft = topLeft,
        size = pillSize,
        cornerRadius = cr,
        style = Stroke(width = 1.15.dp.toPx())
    )
}
