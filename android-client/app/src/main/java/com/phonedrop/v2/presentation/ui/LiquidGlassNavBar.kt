package com.phonedrop.v2.presentation.ui

import android.graphics.Canvas as AndroidCanvas
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
    val icon: @Composable (color: Color, filled: Boolean) -> Unit
)

@Stable
class LiquidGlassBackdropState internal constructor() {
    internal var isRecording: Boolean = false
    internal var hasContent: Boolean = false
    internal var sourceWidth: Int = 0
    internal var sourceHeight: Int = 0
    internal var sourcePosInRoot: Offset = Offset.Zero

    @Volatile
    internal var recordedSeq: Long = 0L

    @Volatile
    internal var drawnSeq: Long = -1L

    internal var onInvalidateNav: (() -> Unit)? = null

    internal val backdropNode: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        RenderNodeApi29.create("PhoneDropBackdrop")
    } else {
        null
    }
}

@Composable
fun rememberLiquidGlassBackdropState(): LiquidGlassBackdropState =
    remember { LiquidGlassBackdropState() }

fun Modifier.liquidGlassBackdropSource(state: LiquidGlassBackdropState): Modifier =
    this
        .onGloballyPositioned { coords ->
            state.sourcePosInRoot = coords.positionInRoot()
        }
        .drawWithContent {
            // 1. Always render real screen content first
            this@drawWithContent.drawContent()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                drawContext.canvas.nativeCanvas.isHardwareAccelerated &&
                !state.isRecording
            ) {
                val w = size.width.roundToInt().coerceAtLeast(1)
                val h = size.height.roundToInt().coerceAtLeast(1)
                val node = state.backdropNode
                if (node != null) {
                    state.isRecording = true
                    try {
                        RenderNodeApi29.record(node, w, h) { recCanvas ->
                            val prev = drawContext.canvas
                            drawContext.canvas = ComposeCanvas(recCanvas)
                            try {
                                this@drawWithContent.drawContent()
                            } finally {
                                drawContext.canvas = prev
                            }
                        }
                        state.sourceWidth = w
                        state.sourceHeight = h
                        state.hasContent = true
                        state.recordedSeq++
                        if (state.drawnSeq != state.recordedSeq) {
                            state.onInvalidateNav?.invoke()
                        }
                    } finally {
                        state.isRecording = false
                    }
                }
            }
        }

// 5-band chromatic dispersion + convex pebble dome shader (pure float math for driver safety).
private const val AGSL_LIQUID_PEBBLE = """
uniform shader content;
uniform float4 uPebbleRect;
uniform float uMeniscusPx;
uniform float uRefractPx;
uniform float uActivity;

half4 main(float2 fragCoord) {
    float left = uPebbleRect.x;
    float top = uPebbleRect.y;
    float right = uPebbleRect.z;
    float bottom = uPebbleRect.w;

    float halfH = max((bottom - top) * 0.5, 1.0);
    float halfW = max((right - left) * 0.5, halfH);
    float2 center = float2((left + right) * 0.5, (top + bottom) * 0.5);
    float spineHalf = max(halfW - halfH, 0.0);

    float2 p = fragCoord - center;
    float2 q = float2(clamp(p.x, -spineHalf, spineHalf), 0.0);
    float2 diff = p - q;
    float distFromSpine = length(diff);
    float signedDist = distFromSpine - halfH;

    if (signedDist > 0.0) {
        return half4(0.0);
    }

    float depth = -signedDist;
    float2 normal = distFromSpine > 0.001 ? (diff / distFromSpine) : float2(0.0, -1.0);
    float menPx = max(uMeniscusPx, 1.0);
    float edgeT = clamp(1.0 - depth / menPx, 0.0, 1.0);
    float lensCurve = pow(edgeT, 2.05);

    float maxShift = uRefractPx * (0.85 + 0.25 * uActivity);
    float2 baseSample = fragCoord - normal * (lensCurve * maxShift * 0.65);

    float splitPx = (lensCurve * maxShift * 0.52) * (0.55 + 0.45 * abs(normal.y) + 0.30 * uActivity);
    half4 cR = content.eval(baseSample + normal * splitPx);
    half4 cY = content.eval(baseSample + normal * (splitPx * 0.48));
    half4 cG = content.eval(baseSample);
    half4 cC = content.eval(baseSample - normal * (splitPx * 0.48));
    half4 cB = content.eval(baseSample - normal * splitPx);

    float rCh = float(cR.r) * 0.72 + float(cY.r) * 0.28;
    float gCh = float(cY.g) * 0.24 + float(cG.g) * 0.52 + float(cC.g) * 0.24;
    float bCh = float(cC.b) * 0.30 + float(cB.b) * 0.70;
    float3 refractedRgb = float3(rCh, gCh, bCh);

    float poleFactor = 0.28 + 0.72 * pow(abs(normal.y), 1.15) + 0.25 * uActivity;
    float3 prism = float3(0.0);
    if (edgeT > 0.05) {
        float3 colRed  = float3(1.00, 0.12, 0.26);
        float3 colGold = float3(1.00, 0.84, 0.06);
        float3 colGrn  = float3(0.04, 0.90, 0.46);
        float3 colCyan = float3(0.02, 0.88, 1.00);
        float3 colBlue = float3(0.14, 0.38, 1.00);

        float x = clamp((edgeT - 0.10) / 0.88, 0.0, 1.0);
        if (x < 0.25) {
            prism = mix(colRed, colGold, x / 0.25);
        } else if (x < 0.50) {
            prism = mix(colGold, colGrn, (x - 0.25) / 0.25);
        } else if (x < 0.75) {
            prism = mix(colGrn, colCyan, (x - 0.50) / 0.25);
        } else {
            prism = mix(colCyan, colBlue, (x - 0.75) / 0.25);
        }
    }
    float prismMask = smoothstep(0.10, 0.44, edgeT) * (1.0 - smoothstep(0.86, 0.99, edgeT)) * clamp(poleFactor, 0.0, 1.0);

    float vFrac = clamp((fragCoord.y - top) / max(bottom - top, 1.0), 0.0, 1.0);
    float3 upperSmoke = float3(0.14, 0.16, 0.17);
    float3 lowerPewter = float3(0.36, 0.39, 0.41);
    float3 domeTint = mix(upperSmoke, lowerPewter, smoothstep(0.18, 0.92, vFrac));

    float interiorFog = mix(0.70, 0.20, smoothstep(0.16, 0.90, edgeT));
    float3 glassRgb = mix(refractedRgb, domeTint, interiorFog);
    glassRgb = mix(glassRgb, refractedRgb * 0.42 + prism * 0.94, clamp(prismMask * 0.82, 0.0, 0.86));

    float rimLine = smoothstep(0.80, 0.985, edgeT);
    float sideFresnel = 0.36 + 0.64 * smoothstep(0.12, 0.95, abs(normal.x));
    float topGlint = smoothstep(0.55, 0.98, 1.0 - abs(vFrac - 0.08)) * (1.0 - smoothstep(0.0, 0.45, edgeT)) * 0.14;
    glassRgb += float3(0.92, 0.95, 0.98) * (rimLine * sideFresnel * 0.74 + topGlint);

    float alpha = clamp(-signedDist * 0.9 + 0.15, 0.0, 1.0);
    return half4(glassRgb * alpha, alpha);
}
"""

@RequiresApi(Build.VERSION_CODES.Q)
private object RenderNodeApi29 {
    fun create(name: String): RenderNode = RenderNode(name)

    fun record(nodeObj: Any, w: Int, h: Int, draw: (AndroidCanvas) -> Unit) {
        val node = nodeObj as RenderNode
        node.setPosition(0, 0, w, h)
        val rc = node.beginRecording(w, h)
        try {
            draw(rc)
        } finally {
            node.endRecording()
        }
    }

    fun drawNode(canvas: AndroidCanvas, nodeObj: Any) {
        canvas.drawRenderNode(nodeObj as RenderNode)
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private object RenderEffectApi31 {
    fun applyBlur(nodeObj: Any, radiusPx: Float) {
        val node = nodeObj as RenderNode
        val r = radiusPx.coerceAtLeast(1f)
        node.setRenderEffect(
            AndroidRenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
        )
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object RuntimeShaderApi33 {
    fun createShaderOrNull(): RuntimeShader? =
        runCatching { RuntimeShader(AGSL_LIQUID_PEBBLE) }.getOrNull()

    fun applyPebbleEffect(
        nodeObj: Any,
        shaderObj: Any,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        meniscusPx: Float,
        refractPx: Float,
        activity: Float
    ): Boolean = runCatching {
        val node = nodeObj as RenderNode
        val shader = shaderObj as RuntimeShader
        shader.setFloatUniform("uPebbleRect", left, top, right, bottom)
        shader.setFloatUniform("uMeniscusPx", meniscusPx)
        shader.setFloatUniform("uRefractPx", refractPx)
        shader.setFloatUniform("uActivity", activity)
        node.setRenderEffect(AndroidRenderEffect.createRuntimeShaderEffect(shader, "content"))
    }.isSuccess
}

/**
 * Reference-matched iOS 26 protruding smoky-frosted 3D chromatic Liquid Glass pebble navbar:
 * - 64.dp blurred frosted dock centered inside a 78.dp track so the 78.dp pebble bulges 7.dp top & bottom
 * - Live GPU RenderNode backdrop capture + BlurEffect + AGSL 5-band chromatic meniscus refraction
 * - Filled luminous active icons inside the pebble vs crisp outline icons outside (zero text scaling/tilt)
 */
@Composable
fun LiquidGlassNavBar(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdropState: LiquidGlassBackdropState? = null,
    modifier: Modifier = Modifier
) {
    val count = items.size.coerceAtLeast(1)
    val latestOnSelect by rememberUpdatedState(onSelect)
    var isDragging by remember { mutableStateOf(false) }
    var dragIndexFloat by remember { mutableFloatStateOf(selectedIndex.toFloat()) }

    val targetPos = if (isDragging) dragIndexFloat else selectedIndex.toFloat()

    // Coupled fluid edge physics: smooth gliding stretch with no forced jittery wobble
    val posA by animateFloatAsState(
        targetValue = targetPos,
        animationSpec = spring(
            dampingRatio = if (isDragging) 0.94f else 0.85f,
            stiffness = if (isDragging) 1100f else 410f
        ),
        label = "LiquidPebbleA"
    )
    val posB by animateFloatAsState(
        targetValue = targetPos,
        animationSpec = spring(
            dampingRatio = if (isDragging) 0.91f else 0.89f,
            stiffness = if (isDragging) 680f else 290f
        ),
        label = "LiquidPebbleB"
    )

    val isActiveSlide = isDragging || abs(posA - posB) > 0.015f || abs(posA - targetPos) > 0.015f
    val glassActivity by animateFloatAsState(
        targetValue = if (isActiveSlide) 1f else 0f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "LiquidPebbleActivity"
    )

    val density = LocalDensity.current
    val hostView: View = LocalView.current
    var backdropTick by remember { mutableLongStateOf(0L) }
    var navPosInRoot by remember { mutableStateOf(Offset.Zero) }

    backdropState?.onInvalidateNav = {
        hostView.post {
            backdropTick++
        }
    }

    val dockBlurNode = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RenderNodeApi29.create("PhoneDropDockBlur") else null
    }
    val underPebbleNode = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RenderNodeApi29.create("PhoneDropUnderPebble") else null
    }
    val pebbleShaderNode = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RenderNodeApi29.create("PhoneDropPebbleFX") else null
    }
    val pebbleShader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RuntimeShaderApi33.createShaderOrNull() else null
    }
    val dockBorderPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            color = Color.White.copy(alpha = 0.34f).toArgb()
        }
    }
    val dockBgPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = Color(0xFF171A1C).copy(alpha = 0.72f).toArgb()
        }
    }
    val dockClipAndroidPath = remember { android.graphics.Path() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(78.dp)
                .onGloballyPositioned { coords ->
                    navPosInRoot = coords.positionInRoot()
                }
        ) {
            val totalW = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
            val totalH = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

            val sideMargin = with(density) { 4.dp.toPx() }
            val dockLeft = sideMargin
            val dockRight = (totalW - sideMargin).coerceAtLeast(dockLeft + 1f)
            val dockW = (dockRight - dockLeft).coerceAtLeast(1f)
            val dockTop = with(density) { 7.dp.toPx() }
            val dockH = with(density) { 64.dp.toPx() }.coerceAtMost(totalH)
            val dockBottom = dockTop + dockH
            val dockRadius = dockH * 0.5f

            val pebbleTop = 0f
            val pebbleBottom = totalH
            val pebbleH = totalH
            val pebbleRadius = pebbleH * 0.5f

            val slotW = (dockW / count).coerceAtLeast(1f)
            val pebbleExtraW = with(density) { 10.dp.toPx() }
            val minPebbleLeft = dockLeft - with(density) { 2.dp.toPx() }
            val maxPebbleRight = dockRight + with(density) { 2.dp.toPx() }

            val rawLeft = dockLeft + min(posA, posB) * slotW - pebbleExtraW * 0.5f
            val rawRight = dockLeft + max(posA, posB) * slotW + slotW + pebbleExtraW * 0.5f
            val pebbleLeft = rawLeft.coerceIn(minPebbleLeft, (maxPebbleRight - pebbleH).coerceAtLeast(minPebbleLeft))
            val pebbleRight = rawRight.coerceIn((pebbleLeft + pebbleH).coerceAtMost(maxPebbleRight), maxPebbleRight)

            val dockRect = Rect(dockLeft, dockTop, dockRight, dockBottom)
            val dockPath = Path().apply {
                addRoundRect(RoundRect(dockRect, CornerRadius(dockRadius, dockRadius)))
            }
            val pebbleRect = Rect(pebbleLeft, pebbleTop, pebbleRight, pebbleBottom)
            val pebblePath = Path().apply {
                addRoundRect(RoundRect(pebbleRect, CornerRadius(pebbleRadius, pebbleRadius)))
            }

            // 1. Frosted Live-Blurred Outer Dock (64.dp centered at y = 7..71.dp)
            val blurSigmaPx = with(density) { 20.dp.toPx() }
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Read backdropTick so scroll redraws trigger dock blur updates without looping
                @Suppress("UNUSED_EXPRESSION")
                backdropTick

                if (backdropState != null) {
                    backdropState.drawnSeq = backdropState.recordedSeq
                }

                drawFrostedBlurredDock(
                    dockRect = dockRect,
                    dockPath = dockPath,
                    dockRadius = dockRadius,
                    totalW = size.width,
                    totalH = size.height,
                    blurSigmaPx = blurSigmaPx,
                    backdropState = backdropState,
                    navPosInRoot = navPosInRoot,
                    dockBlurNode = dockBlurNode
                )
            }

            // 2. Stationary Inactive Tabs outside the protruding pebble only (Zero scaling/wobble)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 7.dp)
                    .drawWithContent {
                        clipPath(pebblePath, ClipOp.Difference) {
                            this@drawWithContent.drawContent()
                        }
                    }
            ) {
                items.forEachIndexed { idx, item ->
                    PebbleTabItem(
                        item = item,
                        tint = Color(0xFF9EA3A6),
                        fontWeight = FontWeight.Medium,
                        filled = false,
                        isSelected = idx == selectedIndex,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 3. Protruding 3D Chromatic-Refraction Liquid Glass Pebble (78.dp bulging 7.dp top & bottom)
            val meniscusPx = with(density) { 9.5.dp.toPx() }
            val refractPx = with(density) { 22.dp.toPx() }
            Canvas(modifier = Modifier.fillMaxSize()) {
                @Suppress("UNUSED_EXPRESSION")
                backdropTick

                drawChromaticLiquidPebble(
                    pebbleRect = pebbleRect,
                    pebblePath = pebblePath,
                    pebbleRadius = pebbleRadius,
                    dockRect = dockRect,
                    dockRadius = dockRadius,
                    totalW = size.width,
                    totalH = size.height,
                    meniscusPx = meniscusPx,
                    refractPx = refractPx,
                    activity = glassActivity,
                    backdropState = backdropState,
                    navPosInRoot = navPosInRoot,
                    dockBlurNode = dockBlurNode,
                    underPebbleNode = underPebbleNode,
                    pebbleShaderNode = pebbleShaderNode,
                    pebbleShader = pebbleShader,
                    dockBorderPaint = dockBorderPaint,
                    dockBgPaint = dockBgPaint,
                    clipAndroidPath = dockClipAndroidPath
                )
            }

            // 4. Active Crisp Filled Icon & Label clipped strictly inside the protruding pebble
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        clipPath(pebblePath, ClipOp.Intersect) {
                            this@drawWithContent.drawContent()
                        }
                    }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp, vertical = 7.dp)
                ) {
                    items.forEachIndexed { idx, item ->
                        PebbleTabItem(
                            item = item,
                            tint = Color(0xFFF5F6F8),
                            fontWeight = FontWeight.SemiBold,
                            filled = true,
                            isSelected = idx == selectedIndex,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 5. Unified Tap + Smooth Horizontal Drag Gesture Overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(count, dockLeft, slotW) {
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
                                        val relX = (lastX - dockLeft).coerceAtLeast(0f)
                                        val tapped = (relX / slotW).toInt().coerceIn(0, count - 1)
                                        latestOnSelect(tapped)
                                    }
                                    break
                                }

                                lastX = change.position.x
                                val totalDx = lastX - startX
                                if (!draggingThisGesture && abs(totalDx) > touchSlop) {
                                    draggingThisGesture = true
                                    isDragging = true
                                    dragIndexFloat = ((lastX - dockLeft) / slotW - 0.5f)
                                        .coerceIn(0f, (count - 1).toFloat())
                                }
                                if (draggingThisGesture && change.positionChanged()) {
                                    change.consume()
                                    dragIndexFloat = ((lastX - dockLeft) / slotW - 0.5f)
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

@Composable
private fun PebbleTabItem(
    item: GlassNavItem,
    tint: Color,
    fontWeight: FontWeight,
    filled: Boolean,
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
        item.icon(tint, filled)
        Spacer(Modifier.height(2.dp))
        Text(
            text = item.label,
            color = tint,
            fontSize = 11.5.sp,
            fontWeight = fontWeight,
            maxLines = 1
        )
    }
}

private fun DrawScope.drawFrostedBlurredDock(
    dockRect: Rect,
    dockPath: Path,
    dockRadius: Float,
    totalW: Float,
    totalH: Float,
    blurSigmaPx: Float,
    backdropState: LiquidGlassBackdropState?,
    navPosInRoot: Offset,
    dockBlurNode: Any?
) {
    val wInt = totalW.roundToInt().coerceAtLeast(1)
    val hInt = totalH.roundToInt().coerceAtLeast(1)
    val nativeCanvas = drawContext.canvas.nativeCanvas

    // Subtle soft ambient drop shadow under the 64.dp floating dock
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.42f),
        topLeft = Offset(dockRect.left + 1.dp.toPx(), dockRect.top + 3.dp.toPx()),
        size = Size(dockRect.width - 2.dp.toPx(), dockRect.height),
        cornerRadius = CornerRadius(dockRadius, dockRadius)
    )

    clipPath(dockPath) {
        var drewLiveBlur = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            nativeCanvas.isHardwareAccelerated &&
            backdropState != null &&
            backdropState.hasContent &&
            backdropState.backdropNode != null &&
            dockBlurNode != null
        ) {
            drewLiveBlur = runCatching {
                val relX = navPosInRoot.x - backdropState.sourcePosInRoot.x
                val relY = navPosInRoot.y - backdropState.sourcePosInRoot.y
                RenderNodeApi29.record(dockBlurNode, wInt, hInt) { rc ->
                    rc.drawColor(Color(0xFF0B110E).toArgb())
                    rc.save()
                    rc.translate(-relX, -relY)
                    RenderNodeApi29.drawNode(rc, backdropState.backdropNode)
                    rc.restore()
                }
                RenderEffectApi31.applyBlur(dockBlurNode, blurSigmaPx)
                RenderNodeApi29.drawNode(nativeCanvas, dockBlurNode)
                true
            }.getOrDefault(false)
        }

        if (!drewLiveBlur) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color(0xFF1F2325),
                    1f to Color(0xFF131618),
                    startY = dockRect.top,
                    endY = dockRect.bottom
                ),
                topLeft = Offset(dockRect.left, dockRect.top),
                size = Size(dockRect.width, dockRect.height)
            )
        }

        // Dark smoky frosted overlay matching Photos 1 & 2
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color(0xFF181B1D).copy(alpha = 0.62f),
                0.5f to Color(0xFF111415).copy(alpha = 0.72f),
                1f to Color(0xFF1A1E20).copy(alpha = 0.66f),
                startY = dockRect.top,
                endY = dockRect.bottom
            ),
            topLeft = Offset(dockRect.left, dockRect.top),
            size = Size(dockRect.width, dockRect.height)
        )
    }

    // Crisp outer silver-white hairline dock border
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.28f),
                Color.White.copy(alpha = 0.14f),
                Color.White.copy(alpha = 0.22f)
            ),
            startY = dockRect.top,
            endY = dockRect.bottom
        ),
        topLeft = Offset(dockRect.left, dockRect.top),
        size = Size(dockRect.width, dockRect.height),
        cornerRadius = CornerRadius(dockRadius, dockRadius),
        style = Stroke(width = 1.dp.toPx())
    )
}

private fun DrawScope.drawChromaticLiquidPebble(
    pebbleRect: Rect,
    pebblePath: Path,
    pebbleRadius: Float,
    dockRect: Rect,
    dockRadius: Float,
    totalW: Float,
    totalH: Float,
    meniscusPx: Float,
    refractPx: Float,
    activity: Float,
    backdropState: LiquidGlassBackdropState?,
    navPosInRoot: Offset,
    dockBlurNode: Any?,
    underPebbleNode: Any?,
    pebbleShaderNode: Any?,
    pebbleShader: Any?,
    dockBorderPaint: android.graphics.Paint,
    dockBgPaint: android.graphics.Paint,
    clipAndroidPath: android.graphics.Path
) {
    val wInt = totalW.roundToInt().coerceAtLeast(1)
    val hInt = totalH.roundToInt().coerceAtLeast(1)
    val nativeCanvas = drawContext.canvas.nativeCanvas

    // Soft elevation shadow behind the protruding pebble
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.44f),
        topLeft = Offset(pebbleRect.left + 1.dp.toPx(), pebbleRect.top + 2.dp.toPx()),
        size = Size(pebbleRect.width - 2.dp.toPx(), pebbleRect.height - 1.dp.toPx()),
        cornerRadius = CornerRadius(pebbleRadius, pebbleRadius)
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        nativeCanvas.isHardwareAccelerated &&
        underPebbleNode != null &&
        pebbleShaderNode != null &&
        pebbleShader != null
    ) {
        val drewShader = runCatching {
            dockBorderPaint.strokeWidth = 1.2.dp.toPx()
            clipAndroidPath.reset()
            clipAndroidPath.addRoundRect(
                dockRect.left,
                dockRect.top,
                dockRect.right,
                dockRect.bottom,
                dockRadius,
                dockRadius,
                android.graphics.Path.Direction.CW
            )

            // Composite live protruding top/bottom screen backdrop + blurred dock borders under the pebble lens
            RenderNodeApi29.record(underPebbleNode, wInt, hInt) { rc ->
                rc.drawColor(Color(0xFF0B110E).toArgb())
                if (backdropState != null && backdropState.hasContent && backdropState.backdropNode != null) {
                    val relX = navPosInRoot.x - backdropState.sourcePosInRoot.x
                    val relY = navPosInRoot.y - backdropState.sourcePosInRoot.y
                    rc.save()
                    rc.translate(-relX, -relY)
                    RenderNodeApi29.drawNode(rc, backdropState.backdropNode)
                    rc.restore()
                }
                // Overlay blurred dock + crisp y=7dp/71dp borders inside dock bounds so meniscus refracts them
                rc.save()
                rc.clipPath(clipAndroidPath)
                if (dockBlurNode != null && backdropState != null && backdropState.hasContent) {
                    RenderNodeApi29.drawNode(rc, dockBlurNode)
                }
                rc.drawRoundRect(
                    dockRect.left, dockRect.top, dockRect.right, dockRect.bottom,
                    dockRadius, dockRadius, dockBgPaint
                )
                rc.restore()
                rc.drawRoundRect(
                    dockRect.left, dockRect.top, dockRect.right, dockRect.bottom,
                    dockRadius, dockRadius, dockBorderPaint
                )
            }

            RenderNodeApi29.record(pebbleShaderNode, wInt, hInt) { rc ->
                RenderNodeApi29.drawNode(rc, underPebbleNode)
            }
            val applied = RuntimeShaderApi33.applyPebbleEffect(
                nodeObj = pebbleShaderNode,
                shaderObj = pebbleShader,
                left = pebbleRect.left,
                top = pebbleRect.top,
                right = pebbleRect.right,
                bottom = pebbleRect.bottom,
                meniscusPx = meniscusPx,
                refractPx = refractPx,
                activity = activity
            )
            if (applied) {
                RenderNodeApi29.drawNode(nativeCanvas, pebbleShaderNode)
                true
            } else {
                false
            }
        }.getOrDefault(false)

        if (drewShader) return
    }

    // High-precision Canvas fallback for API 26-32 or software canvas
    drawFallbackChromaticPebble(pebbleRect, pebblePath, pebbleRadius, activity)
}

private fun DrawScope.drawFallbackChromaticPebble(
    pebbleRect: Rect,
    pebblePath: Path,
    radius: Float,
    activity: Float
) {
    val cr = CornerRadius(radius, radius)
    val tl = Offset(pebbleRect.left, pebbleRect.top)
    val sz = Size(pebbleRect.width, pebbleRect.height)

    clipPath(pebblePath) {
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color(0xFF232729).copy(alpha = 0.80f),
                0.55f to Color(0xFF3E4346).copy(alpha = 0.72f),
                1f to Color(0xFF646B70).copy(alpha = 0.70f),
                startY = pebbleRect.top,
                endY = pebbleRect.bottom
            ),
            topLeft = tl,
            size = sz
        )

        val bandColors = listOf(
            Color(0xFFFF2448),
            Color(0xFFFFD600),
            Color(0xFF00E676),
            Color(0xFF00E5FF),
            Color(0xFF1A5EFF)
        )
        bandColors.forEachIndexed { idx, col ->
            val inset = (8.dp.toPx() - idx * 1.5.dp.toPx()).coerceAtLeast(0.5.dp.toPx())
            drawRoundRect(
                color = col.copy(alpha = 0.34f + 0.14f * activity),
                topLeft = Offset(pebbleRect.left + inset, pebbleRect.top + inset),
                size = Size((pebbleRect.width - 2f * inset).coerceAtLeast(1f), (pebbleRect.height - 2f * inset).coerceAtLeast(1f)),
                cornerRadius = CornerRadius((radius - inset).coerceAtLeast(1f), (radius - inset).coerceAtLeast(1f)),
                style = Stroke(width = 2.0.dp.toPx()),
                blendMode = BlendMode.Plus
            )
        }
    }

    drawRoundRect(
        brush = Brush.horizontalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.82f),
                Color.White.copy(alpha = 0.25f),
                Color.White.copy(alpha = 0.25f),
                Color.White.copy(alpha = 0.78f)
            ),
            startX = pebbleRect.left,
            endX = pebbleRect.right
        ),
        topLeft = tl,
        size = sz,
        cornerRadius = cr,
        style = Stroke(width = 1.1.dp.toPx(), cap = StrokeCap.Round)
    )
}
