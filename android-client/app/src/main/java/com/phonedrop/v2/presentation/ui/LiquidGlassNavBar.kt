package com.phonedrop.v2.presentation.ui

import android.graphics.Canvas as AndroidCanvas
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
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

// Outer 64.dp frosted Liquid Glass dock shader (chained after 16.dp Gaussian blur).
private const val AGSL_DOCK_GLASS = """
uniform shader content;
uniform float4 uBoxRect;
uniform float uCornerRadius;
uniform float uRefractHeight;
uniform float uRefractAmount;
uniform float uDispersion;

float circleMap(float x) {
    return 1.0 - sqrt(max(0.0, 1.0 - x * x));
}

half4 main(float2 fragCoord) {
    float2 center = float2((uBoxRect.x + uBoxRect.z) * 0.5, (uBoxRect.y + uBoxRect.w) * 0.5);
    float2 halfSize = float2(
        max((uBoxRect.z - uBoxRect.x) * 0.5, 1.0),
        max((uBoxRect.w - uBoxRect.y) * 0.5, 1.0)
    );
    float r = min(uCornerRadius, min(halfSize.x, halfSize.y));

    float2 p = fragCoord - center;
    float2 b = halfSize - float2(r);
    float2 q = abs(p) - b;
    float sd = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;

    if (sd > 0.75) {
        return half4(0.0);
    }
    float edgeCoverage = clamp(0.5 - sd, 0.0, 1.0);

    float2 signP = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
    float2 grad;
    if (q.x > 0.0 && q.y > 0.0) {
        grad = signP * (q / max(length(q), 0.0001));
    } else if (q.x > q.y) {
        grad = float2(signP.x, 0.0);
    } else {
        grad = float2(0.0, signP.y);
    }

    float depth = max(-sd, 0.0);
    float rimT = clamp(1.0 - depth / max(uRefractHeight, 1.0), 0.0, 1.0);
    float lens = circleMap(rimT);
    float2 baseOffset = grad * (lens * uRefractAmount);

    float3 refracted;
    if (uDispersion > 0.001 && lens > 0.002) {
        float2 spread = grad * (pow(rimT, 1.35) * uRefractAmount * uDispersion);
        float3 s0 = float3(content.eval(fragCoord + baseOffset - spread * 1.00).rgb);
        float3 s1 = float3(content.eval(fragCoord + baseOffset - spread * 0.66).rgb);
        float3 s2 = float3(content.eval(fragCoord + baseOffset - spread * 0.33).rgb);
        float3 s3 = float3(content.eval(fragCoord + baseOffset).rgb);
        float3 s4 = float3(content.eval(fragCoord + baseOffset + spread * 0.33).rgb);
        float3 s5 = float3(content.eval(fragCoord + baseOffset + spread * 0.66).rgb);
        float3 s6 = float3(content.eval(fragCoord + baseOffset + spread * 1.00).rgb);

        float rCh = s0.r * 0.35 + s1.r * 0.35 + s2.r * 0.20 + s3.r * 0.10;
        float gCh = s1.g * 0.12 + s2.g * 0.26 + s3.g * 0.36 + s4.g * 0.20 + s5.g * 0.06;
        float bCh = s3.b * 0.10 + s4.b * 0.26 + s5.b * 0.34 + s6.b * 0.30;
        refracted = float3(rCh, gCh, bCh);
    } else {
        refracted = float3(content.eval(fragCoord + baseOffset).rgb);
    }

    float luma = dot(refracted, float3(0.2126, 0.7152, 0.0722));
    float3 vibrant = clamp(float3(luma) + (refracted - float3(luma)) * 1.42, 0.0, 1.0);

    float vFrac = clamp((fragCoord.y - uBoxRect.y) / max(uBoxRect.w - uBoxRect.y, 1.0), 0.0, 1.0);
    float3 dockTint = mix(float3(0.10, 0.11, 0.12), float3(0.07, 0.08, 0.09), vFrac);
    float3 col = mix(vibrant, dockTint, 0.56);

    // Subtle crisp 0.85dp specular border around dock
    float hairline = exp(-depth * depth * 0.55) * 0.38;
    col = clamp(col + float3(0.92, 0.95, 0.98) * hairline, 0.0, 1.0);

    return half4(col * edgeCoverage, edgeCoverage);
}
"""

/**
 * Lifted Crystal-Clear Liquid Glass Droplet shader (active during press / slide / glide):
 * - Samples the combined scene (live screen [-36dp..+114dp] + frosted dock + actual tab icons & text!).
 * - Center is crystal-clear with gentle 1.05x magnification so icons & text inside shine sharply.
 * - Inner bevel bends & stretches tab icons/letters (matching reference photo #2 Home <-> Search).
 * - Steep outer meniscus refracts dock borders & screen cards with 7-tap energy-conserving RGB
 *   chromatic aberration (zero fake painted rainbow ring on dark areas!).
 */
private const val AGSL_DROPLET_LENS = """
uniform shader content;
uniform float4 uBoxRect;
uniform float uCornerRadius;
uniform float uRefractHeight;
uniform float uRefractAmount;
uniform float uInnerBendPx;
uniform float uDispersionPx;
uniform float uLift;

float circleMap(float x) {
    return 1.0 - sqrt(max(0.0, 1.0 - x * x));
}

half4 main(float2 fragCoord) {
    float2 center = float2((uBoxRect.x + uBoxRect.z) * 0.5, (uBoxRect.y + uBoxRect.w) * 0.5);
    float2 halfSize = float2(
        max((uBoxRect.z - uBoxRect.x) * 0.5, 1.0),
        max((uBoxRect.w - uBoxRect.y) * 0.5, 1.0)
    );
    float r = min(uCornerRadius, min(halfSize.x, halfSize.y));

    float2 p = fragCoord - center;
    float2 b = halfSize - float2(r);
    float2 q = abs(p) - b;
    float sd = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;

    if (sd > 0.75 || uLift <= 0.004) {
        return half4(0.0);
    }
    float edgeCoverage = clamp(0.5 - sd, 0.0, 1.0);

    float2 signP = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
    float2 grad;
    if (q.x > 0.0 && q.y > 0.0) {
        grad = signP * (q / max(length(q), 0.0001));
    } else if (q.x > q.y) {
        grad = float2(signP.x, 0.0);
    } else {
        grad = float2(0.0, signP.y);
    }

    float depth = max(-sd, 0.0);
    float bevel = max(uRefractHeight, 1.0);
    float rimT = clamp(1.0 - depth / bevel, 0.0, 1.0);
    float lens = circleMap(rimT);

    // Gentle interior magnifying glass zoom tapering smoothly into the bevel
    float2 zoomOffset = -p * (0.055 * uLift * (1.0 - smoothstep(0.0, 0.80, rimT)));
    // Inner bevel magnifies/stretches letters outward (-grad), outer lip pulls surrounding scene (+grad)
    float bevelShift = (lens * uRefractAmount) - (sin(rimT * 3.14159265) * uInnerBendPx);
    float2 baseCoord = fragCoord + zoomOffset + grad * (bevelShift * uLift);

    float3 refracted;
    if (rimT > 0.02) {
        float spreadMag = pow(rimT, 1.25) * uDispersionPx * uLift;
        float2 spread = grad * spreadMag;
        float3 s0 = float3(content.eval(baseCoord - spread * 1.00).rgb);
        float3 s1 = float3(content.eval(baseCoord - spread * 0.66).rgb);
        float3 s2 = float3(content.eval(baseCoord - spread * 0.33).rgb);
        float3 s3 = float3(content.eval(baseCoord).rgb);
        float3 s4 = float3(content.eval(baseCoord + spread * 0.33).rgb);
        float3 s5 = float3(content.eval(baseCoord + spread * 0.66).rgb);
        float3 s6 = float3(content.eval(baseCoord + spread * 1.00).rgb);

        // Energy-conserving spectral weights (sum = 1.0, 1.0, 1.0: dark stays pure dark!)
        float rCh = s0.r * 0.36 + s1.r * 0.34 + s2.r * 0.20 + s3.r * 0.10;
        float gCh = s1.g * 0.12 + s2.g * 0.26 + s3.g * 0.36 + s4.g * 0.20 + s5.g * 0.06;
        float bCh = s3.b * 0.10 + s4.b * 0.26 + s5.b * 0.34 + s6.b * 0.30;
        refracted = float3(rCh, gCh, bCh);
    } else {
        refracted = float3(content.eval(baseCoord).rgb);
    }

    // Boost chromatic richness where real bright strokes/edges disperse in the bevel
    float maxC = max(refracted.r, max(refracted.g, refracted.b));
    float minC = min(refracted.r, min(refracted.g, refracted.b));
    float chroma = maxC - minC;
    float luma = dot(refracted, float3(0.2126, 0.7152, 0.0722));
    float satGain = 1.25 + 1.55 * smoothstep(0.015, 0.20, chroma) * rimT;
    float rimBri = 1.0 + 0.55 * smoothstep(0.05, 0.35, maxC) * lens;
    float3 col = clamp((float3(luma) + (refracted - float3(luma)) * satGain) * rimBri, 0.0, 1.0);

    // Subtle crystal-clear glass surface sheen: fades from resting 0.13 white inner pill (uLift->0)
    // to transparent water-glass with faint lower pewter reflection when lifted (uLift->1).
    float vFrac = clamp((fragCoord.y - uBoxRect.y) / max(uBoxRect.w - uBoxRect.y, 1.0), 0.0, 1.0);
    float restFill = 0.13 * (1.0 - uLift);
    float liftedSheen = (0.025 + 0.075 * smoothstep(0.35, 1.0, vFrac)) * uLift * (1.0 - 0.65 * lens);
    col = clamp(col + float3(0.94, 0.96, 0.99) * (restFill + liftedSheen), 0.0, 1.0);

    // Clean ~1.0dp white specular glass rim when lifted (fading out smoothly as it settles back inside dock)
    float sideWeight = 0.38 + 0.62 * max(abs(grad.x), abs(grad.x * 0.72 - grad.y * 0.68));
    float rimStroke = exp(-depth * depth * 0.48) * (0.14 + 0.68 * uLift) * sideWeight;
    col = clamp(col + float3(0.96, 0.98, 1.0) * rimStroke, 0.0, 1.0);

    return half4(col * edgeCoverage, edgeCoverage);
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

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object LiquidGlassShaderApi33 {
    fun createDockShaderOrNull(): RuntimeShader? =
        runCatching { RuntimeShader(AGSL_DOCK_GLASS) }.getOrNull()

    fun createDropletShaderOrNull(): RuntimeShader? =
        runCatching { RuntimeShader(AGSL_DROPLET_LENS) }.getOrNull()

    fun applyDockEffect(
        nodeObj: Any,
        shaderObj: Any,
        blurPx: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cornerRadius: Float,
        refractHeight: Float,
        refractAmount: Float,
        dispersion: Float
    ): Boolean = runCatching {
        val node = nodeObj as RenderNode
        val shader = shaderObj as RuntimeShader
        shader.setFloatUniform("uBoxRect", left, top, right, bottom)
        shader.setFloatUniform("uCornerRadius", cornerRadius)
        shader.setFloatUniform("uRefractHeight", refractHeight)
        shader.setFloatUniform("uRefractAmount", refractAmount)
        shader.setFloatUniform("uDispersion", dispersion)

        val lensFx = AndroidRenderEffect.createRuntimeShaderEffect(shader, "content")
        val sigma = blurPx.coerceAtLeast(1f)
        val blurFx = AndroidRenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP)
        node.setRenderEffect(AndroidRenderEffect.createChainEffect(lensFx, blurFx))
    }.isSuccess

    fun applyDropletEffect(
        nodeObj: Any,
        shaderObj: Any,
        microBlurPx: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cornerRadius: Float,
        refractHeight: Float,
        refractAmount: Float,
        innerBendPx: Float,
        dispersionPx: Float,
        lift: Float
    ): Boolean = runCatching {
        val node = nodeObj as RenderNode
        val shader = shaderObj as RuntimeShader
        shader.setFloatUniform("uBoxRect", left, top, right, bottom)
        shader.setFloatUniform("uCornerRadius", cornerRadius)
        shader.setFloatUniform("uRefractHeight", refractHeight)
        shader.setFloatUniform("uRefractAmount", refractAmount)
        shader.setFloatUniform("uInnerBendPx", innerBendPx)
        shader.setFloatUniform("uDispersionPx", dispersionPx)
        shader.setFloatUniform("uLift", lift)

        val lensFx = AndroidRenderEffect.createRuntimeShaderEffect(shader, "content")
        if (microBlurPx >= 0.8f) {
            val blurFx = AndroidRenderEffect.createBlurEffect(microBlurPx, microBlurPx, Shader.TileMode.CLAMP)
            node.setRenderEffect(AndroidRenderEffect.createChainEffect(lensFx, blurFx))
        } else {
            node.setRenderEffect(lensFx)
        }
    }.isSuccess
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

/**
 * Authentic 2-state Liquid Glass Bottom Navigation Bar matching all 4 reference photos:
 * 1. Idle (before press): Indicator sits 100% INSIDE the 64.dp navbar (4.dp inset, 56.dp tall)
 *    as a calm, soft translucent frosted pill with zero protrusion and zero distortion.
 * 2. Pressed / Dragging / Gliding: Inflates smoothly into a 78.dp crystal-clear refractive
 *    water-glass droplet floating ABOVE the navbar & tabs, magnifying and chromatically
 *    dispersing the actual tab icons, tab labels, dock borders, and background content underneath!
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
    var isPressed by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var dragIndexFloat by remember { mutableFloatStateOf(selectedIndex.toFloat()) }

    val targetPos = if (isDragging) dragIndexFloat else selectedIndex.toFloat()

    // Coupled fluid edge springs
    val posA by animateFloatAsState(
        targetValue = targetPos,
        animationSpec = spring(
            dampingRatio = if (isDragging) 0.92f else 0.84f,
            stiffness = if (isDragging) 980f else 390f
        ),
        label = "DropletEdgeA"
    )
    val posB by animateFloatAsState(
        targetValue = targetPos,
        animationSpec = spring(
            dampingRatio = if (isDragging) 0.88f else 0.88f,
            stiffness = if (isDragging) 640f else 280f
        ),
        label = "DropletEdgeB"
    )

    val glideRemain = abs(posA - targetPos)
    val shouldLift = isPressed || isDragging || glideRemain > 0.03f
    val liftProgress by animateFloatAsState(
        targetValue = if (shouldLift) 1f else 0f,
        animationSpec = spring(
            dampingRatio = if (shouldLift) 0.74f else 0.84f,
            stiffness = if (shouldLift) 430f else 360f
        ),
        label = "DropletLiftProgress"
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

    val dockRenderNode = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RenderNodeApi29.create("PhoneDropDockGlass") else null
    }
    val tabsOnlyNode = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RenderNodeApi29.create("PhoneDropTabsOnly") else null
    }
    val dropletLensNode = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RenderNodeApi29.create("PhoneDropDropletLens") else null
    }
    val dockShader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) LiquidGlassShaderApi33.createDockShaderOrNull() else null
    }
    val dropletShader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) LiquidGlassShaderApi33.createDropletShaderOrNull() else null
    }
    val dockRimPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            color = Color.White.copy(alpha = 0.42f).toArgb()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
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

            // Outer 64.dp dock sits at y = [7.dp .. 71.dp], leaving 7.dp room top & bottom for lifted droplet
            val sideMargin = with(density) { 6.dp.toPx() }
            val dockLeft = sideMargin
            val dockRight = (totalW - sideMargin).coerceAtLeast(dockLeft + 1f)
            val dockW = (dockRight - dockLeft).coerceAtLeast(1f)
            val dockTop = with(density) { 7.dp.toPx() }
            val dockH = with(density) { 64.dp.toPx() }.coerceAtMost(totalH)
            val dockBottom = dockTop + dockH
            val dockRadius = dockH * 0.5f

            // IDLE bounds (liftProgress == 0f): strictly 4.dp INSIDE the 64.dp dock (56.dp tall, concentric)
            val innerInset = with(density) { 4.dp.toPx() }
            val innerLeft = dockLeft + innerInset
            val innerRight = (dockRight - innerInset).coerceAtLeast(innerLeft + 1f)
            val innerTrackW = (innerRight - innerLeft).coerceAtLeast(1f)
            val innerSlotW = (innerTrackW / count).coerceAtLeast(1f)

            val idlePillLeft = innerLeft + min(posA, posB) * innerSlotW
            val idlePillRight = innerLeft + max(posA, posB) * innerSlotW + innerSlotW
            val idlePillTop = dockTop + innerInset
            val idlePillBottom = dockBottom - innerInset

            // LIFTED bounds (liftProgress == 1f): inflates to 78.dp tall (7.dp proud top & bottom)
            // and +10.dp wider per side (overhanging up to 6.dp at dock ends like Reference Photo #3)
            val expandSidePx = with(density) { 10.dp.toPx() }
            val liftedLeft = (idlePillLeft - expandSidePx).coerceAtLeast(0.5f)
            val liftedRight = (idlePillRight + expandSidePx).coerceAtMost(totalW - 0.5f)

            val curLeft = lerpFloat(idlePillLeft, liftedLeft, liftProgress)
            val curRight = lerpFloat(idlePillRight, liftedRight, liftProgress)
            val curTop = lerpFloat(idlePillTop, 0f, liftProgress)
            val curBottom = lerpFloat(idlePillBottom, totalH, liftProgress)
            val curH = (curBottom - curTop).coerceAtLeast(1f)
            val curRadius = curH * 0.5f

            val dockRect = Rect(dockLeft, dockTop, dockRight, dockBottom)
            val dockPath = Path().apply {
                addRoundRect(RoundRect(dockRect, CornerRadius(dockRadius, dockRadius)))
            }
            val pillRect = Rect(curLeft, curTop, curRight, curBottom)
            val pillPath = Path().apply {
                addRoundRect(RoundRect(pillRect, CornerRadius(curRadius, curRadius)))
            }

            val bleedPx = with(density) { 36.dp.toPx() }
            val dockBlurPx = with(density) { 16.dp.toPx() }
            val dockRefractH = with(density) { 10.dp.toPx() }
            val dockRefractAmt = with(density) { 16.dp.toPx() }

            // Pass 1: Outer Frosted Dock + Resting Inner Frosted Pill (visible at idle, fades out as droplet lifts)
            Canvas(modifier = Modifier.fillMaxSize()) {
                @Suppress("UNUSED_EXPRESSION")
                backdropTick
                if (backdropState != null) {
                    backdropState.drawnSeq = backdropState.recordedSeq
                }

                drawOuterDockAndRestingPill(
                    dockRect = dockRect,
                    dockPath = dockPath,
                    dockRadius = dockRadius,
                    pillRect = pillRect,
                    pillRadius = curRadius,
                    liftProgress = liftProgress,
                    totalW = size.width,
                    totalH = size.height,
                    bleedPx = bleedPx,
                    dockBlurPx = dockBlurPx,
                    dockRefractH = dockRefractH,
                    dockRefractAmt = dockRefractAmt,
                    backdropState = backdropState,
                    navPosInRoot = navPosInRoot,
                    dockRenderNode = dockRenderNode,
                    dockShader = dockShader
                )
            }

            // Pass 2: Unified Tab Row (also recorded into leaf `tabsOnlyNode` when droplet is lifted so
            // the floating glass droplet refracts & chromatically splits the actual tab icons & labels!)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        // When droplet is lifted on API 33+, hide raw un-refracted tab pixels strictly
                        // inside pillPath because Pass 3 draws the refracted tabs through the clear lens!
                        val useShaderDroplet = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            drawContext.canvas.nativeCanvas.isHardwareAccelerated &&
                            liftProgress > 0.01f &&
                            tabsOnlyNode != null &&
                            dropletLensNode != null &&
                            dropletShader != null

                        if (useShaderDroplet && tabsOnlyNode != null) {
                            val wInt = size.width.roundToInt().coerceAtLeast(1)
                            val hInt = size.height.roundToInt().coerceAtLeast(1)
                            RenderNodeApi29.record(tabsOnlyNode, wInt, hInt) { rec ->
                                val prev = drawContext.canvas
                                drawContext.canvas = ComposeCanvas(rec)
                                try {
                                    this@drawWithContent.drawContent()
                                } finally {
                                    drawContext.canvas = prev
                                }
                            }
                            clipPath(pillPath, androidx.compose.ui.graphics.ClipOp.Difference) {
                                this@drawWithContent.drawContent()
                            }
                        } else {
                            this@drawWithContent.drawContent()
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                items.forEachIndexed { idx, item ->
                    val isSelected = idx == selectedIndex
                    TabSlotContent(
                        item = item,
                        tint = if (isSelected) Color(0xFFF6F7F9) else Color(0xFF9EA3A6),
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        filled = isSelected,
                        isSelected = isSelected,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Pass 3: Lifted Crystal-Clear Refractive Liquid Glass Droplet (only when pressed / sliding!)
            if (liftProgress > 0.01f) {
                val dropletMicroBlurPx = with(density) { 0.9.dp.toPx() }
                val dropletRefractH = with(density) { 15.dp.toPx() }
                val dropletRefractAmt = with(density) { 30.dp.toPx() }
                val dropletInnerBendPx = with(density) { 9.5.dp.toPx() }
                val dropletDispersionPx = with(density) { 15.dp.toPx() }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    @Suppress("UNUSED_EXPRESSION")
                    backdropTick

                    drawLiftedLiquidDroplet(
                        pillRect = pillRect,
                        pillRadius = curRadius,
                        dockRect = dockRect,
                        dockRadius = dockRadius,
                        liftProgress = liftProgress,
                        totalW = size.width,
                        totalH = size.height,
                        bleedPx = bleedPx,
                        microBlurPx = dropletMicroBlurPx,
                        refractHeight = dropletRefractH,
                        refractAmount = dropletRefractAmt,
                        innerBendPx = dropletInnerBendPx,
                        dispersionPx = dropletDispersionPx,
                        backdropState = backdropState,
                        navPosInRoot = navPosInRoot,
                        dockRenderNode = dockRenderNode,
                        tabsOnlyNode = tabsOnlyNode,
                        dropletLensNode = dropletLensNode,
                        dropletShader = dropletShader,
                        dockRimPaint = dockRimPaint
                    )
                }
            }

            // Pass 4: Gesture Layer (Press to inflate in place, Drag to slide droplet across tabs, Tap to jump)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(count, innerLeft, innerSlotW) {
                        val dragThreshold = viewConfiguration.touchSlop * 0.45f
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startX = down.position.x
                            val downSlot = ((startX - innerLeft) / innerSlotW).toInt().coerceIn(0, count - 1)
                            // Immediately inflate the pill on press; if user presses directly on another slot,
                            // begin gliding the droplet toward their finger right away!
                            isPressed = true
                            if (downSlot != selectedIndex) {
                                isDragging = true
                                dragIndexFloat = ((startX - innerLeft) / innerSlotW - 0.5f)
                                    .coerceIn(0f, (count - 1).toFloat())
                            }
                            var draggingThisGesture = downSlot != selectedIndex
                            var lastX = startX

                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        if (draggingThisGesture) {
                                            val snapped = dragIndexFloat.roundToInt().coerceIn(0, count - 1)
                                            isDragging = false
                                            latestOnSelect(snapped)
                                        } else {
                                            val tapped = ((lastX - innerLeft) / innerSlotW).toInt().coerceIn(0, count - 1)
                                            latestOnSelect(tapped)
                                        }
                                        break
                                    }

                                    lastX = change.position.x
                                    val totalDx = lastX - startX
                                    if (!draggingThisGesture && abs(totalDx) > dragThreshold) {
                                        draggingThisGesture = true
                                        isDragging = true
                                        dragIndexFloat = ((lastX - innerLeft) / innerSlotW - 0.5f)
                                            .coerceIn(0f, (count - 1).toFloat())
                                    }
                                    if (draggingThisGesture && change.positionChanged()) {
                                        change.consume()
                                        dragIndexFloat = ((lastX - innerLeft) / innerSlotW - 0.5f)
                                            .coerceIn(0f, (count - 1).toFloat())
                                    }
                                }
                            } finally {
                                isPressed = false
                                isDragging = false
                            }
                        }
                    }
            )
        }
    }
}

@Composable
private fun TabSlotContent(
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
            fontSize = 11.sp,
            fontWeight = fontWeight,
            maxLines = 1
        )
    }
}

private fun DrawScope.drawOuterDockAndRestingPill(
    dockRect: Rect,
    dockPath: Path,
    dockRadius: Float,
    pillRect: Rect,
    pillRadius: Float,
    liftProgress: Float,
    totalW: Float,
    totalH: Float,
    bleedPx: Float,
    dockBlurPx: Float,
    dockRefractH: Float,
    dockRefractAmt: Float,
    backdropState: LiquidGlassBackdropState?,
    navPosInRoot: Offset,
    dockRenderNode: Any?,
    dockShader: Any?
) {
    val nativeCanvas = drawContext.canvas.nativeCanvas

    // Ambient shadow under the 64.dp dock
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.40f),
        topLeft = Offset(dockRect.left + 1.dp.toPx(), dockRect.top + 2.dp.toPx()),
        size = Size((dockRect.width - 2.dp.toPx()).coerceAtLeast(1f), dockRect.height),
        cornerRadius = CornerRadius(dockRadius, dockRadius)
    )

    var drewShaderDock = false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        nativeCanvas.isHardwareAccelerated &&
        dockRenderNode != null &&
        dockShader != null
    ) {
        val wInt = totalW.roundToInt().coerceAtLeast(1)
        val bleedInt = bleedPx.roundToInt().coerceAtLeast(0)
        val hBleedInt = (totalH.roundToInt() + bleedInt * 2).coerceAtLeast(1)
        val bleedF = bleedInt.toFloat()

        drewShaderDock = runCatching {
            RenderNodeApi29.record(dockRenderNode, wInt, hBleedInt) { rc ->
                rc.drawColor(Color(0xFF0B110E).toArgb())
                if (backdropState != null && backdropState.hasContent && backdropState.backdropNode != null) {
                    val relX = navPosInRoot.x - backdropState.sourcePosInRoot.x
                    val relY = navPosInRoot.y - backdropState.sourcePosInRoot.y
                    rc.save()
                    rc.translate(-relX, -relY + bleedF)
                    RenderNodeApi29.drawNode(rc, backdropState.backdropNode)
                    rc.restore()
                }
            }
            val ok = LiquidGlassShaderApi33.applyDockEffect(
                nodeObj = dockRenderNode,
                shaderObj = dockShader,
                blurPx = dockBlurPx,
                left = dockRect.left,
                top = dockRect.top + bleedF,
                right = dockRect.right,
                bottom = dockRect.bottom + bleedF,
                cornerRadius = dockRadius,
                refractHeight = dockRefractH,
                refractAmount = dockRefractAmt,
                dispersion = 0.45f
            )
            if (ok) {
                nativeCanvas.save()
                nativeCanvas.translate(0f, -bleedF)
                RenderNodeApi29.drawNode(nativeCanvas, dockRenderNode)
                nativeCanvas.restore()
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    if (!drewShaderDock) {
        clipPath(dockPath) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color(0xFF1A1D1F).copy(alpha = 0.86f),
                    1f to Color(0xFF111315).copy(alpha = 0.90f),
                    startY = dockRect.top,
                    endY = dockRect.bottom
                ),
                topLeft = Offset(dockRect.left, dockRect.top),
                size = Size(dockRect.width, dockRect.height)
            )
        }
        drawRoundRect(
            color = Color.White.copy(alpha = 0.24f),
            topLeft = Offset(dockRect.left, dockRect.top),
            size = Size(dockRect.width, dockRect.height),
            cornerRadius = CornerRadius(dockRadius, dockRadius),
            style = Stroke(width = 0.85.dp.toPx())
        )
    }

    // IDLE STATE (Reference Photo #1): Soft frosted inner capsule strictly 4.dp INSIDE the dock!
    // Fades out smoothly as `liftProgress` rises into the 3D transparent droplet.
    val idleAlpha = (1f - liftProgress).coerceIn(0f, 1f)
    if (idleAlpha > 0.005f) {
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.11f * idleAlpha),
                0.55f to Color.White.copy(alpha = 0.14f * idleAlpha),
                1f to Color.White.copy(alpha = 0.17f * idleAlpha),
                startY = pillRect.top,
                endY = pillRect.bottom
            ),
            topLeft = Offset(pillRect.left, pillRect.top),
            size = Size(pillRect.width, pillRect.height),
            cornerRadius = CornerRadius(pillRadius, pillRadius)
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.10f * idleAlpha),
            topLeft = Offset(pillRect.left, pillRect.top),
            size = Size(pillRect.width, pillRect.height),
            cornerRadius = CornerRadius(pillRadius, pillRadius),
            style = Stroke(width = 0.7.dp.toPx())
        )
    }
}

private fun DrawScope.drawLiftedLiquidDroplet(
    pillRect: Rect,
    pillRadius: Float,
    dockRect: Rect,
    dockRadius: Float,
    liftProgress: Float,
    totalW: Float,
    totalH: Float,
    bleedPx: Float,
    microBlurPx: Float,
    refractHeight: Float,
    refractAmount: Float,
    innerBendPx: Float,
    dispersionPx: Float,
    backdropState: LiquidGlassBackdropState?,
    navPosInRoot: Offset,
    dockRenderNode: Any?,
    tabsOnlyNode: Any?,
    dropletLensNode: Any?,
    dropletShader: Any?,
    dockRimPaint: android.graphics.Paint
) {
    val nativeCanvas = drawContext.canvas.nativeCanvas

    // Soft floating droplet shadow when lifted
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.34f * liftProgress),
        topLeft = Offset(pillRect.left + 1.dp.toPx(), pillRect.top + 2.dp.toPx()),
        size = Size((pillRect.width - 2.dp.toPx()).coerceAtLeast(1f), pillRect.height),
        cornerRadius = CornerRadius(pillRadius, pillRadius)
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        nativeCanvas.isHardwareAccelerated &&
        dropletLensNode != null &&
        dropletShader != null
    ) {
        val wInt = totalW.roundToInt().coerceAtLeast(1)
        val bleedInt = bleedPx.roundToInt().coerceAtLeast(0)
        val hBleedInt = (totalH.roundToInt() + bleedInt * 2).coerceAtLeast(1)
        val bleedF = bleedInt.toFloat()

        val ok = runCatching {
            dockRimPaint.strokeWidth = 1.1.dp.toPx()
            RenderNodeApi29.record(dropletLensNode, wInt, hBleedInt) { rc ->
                rc.drawColor(Color(0xFF0B110E).toArgb())
                if (backdropState != null && backdropState.hasContent && backdropState.backdropNode != null) {
                    val relX = navPosInRoot.x - backdropState.sourcePosInRoot.x
                    val relY = navPosInRoot.y - backdropState.sourcePosInRoot.y
                    rc.save()
                    rc.translate(-relX, -relY + bleedF)
                    RenderNodeApi29.drawNode(rc, backdropState.backdropNode)
                    rc.restore()
                }
                if (dockRenderNode != null) {
                    RenderNodeApi29.drawNode(rc, dockRenderNode)
                }
                // Crisp dock specular border so top & bottom protruding rims disperse it cleanly
                rc.drawRoundRect(
                    dockRect.left,
                    dockRect.top + bleedF,
                    dockRect.right,
                    dockRect.bottom + bleedF,
                    dockRadius,
                    dockRadius,
                    dockRimPaint
                )
                // Draw the actual Tab Icons & Labels into the scene so the droplet refracts &
                // chromatically splits them as it slides over tabs (Reference Photos #2, #3, #4)!
                if (tabsOnlyNode != null) {
                    rc.save()
                    rc.translate(0f, bleedF)
                    RenderNodeApi29.drawNode(rc, tabsOnlyNode)
                    rc.restore()
                }
            }

            val applied = LiquidGlassShaderApi33.applyDropletEffect(
                nodeObj = dropletLensNode,
                shaderObj = dropletShader,
                microBlurPx = microBlurPx,
                left = pillRect.left,
                top = pillRect.top + bleedF,
                right = pillRect.right,
                bottom = pillRect.bottom + bleedF,
                cornerRadius = pillRadius,
                refractHeight = refractHeight,
                refractAmount = refractAmount,
                innerBendPx = innerBendPx,
                dispersionPx = dispersionPx,
                lift = liftProgress
            )
            if (applied) {
                nativeCanvas.save()
                nativeCanvas.translate(0f, -bleedF)
                RenderNodeApi29.drawNode(nativeCanvas, dropletLensNode)
                nativeCanvas.restore()
                true
            } else {
                false
            }
        }.getOrDefault(false)

        if (ok) return
    }

    // Fallback lifted outline for API 26-32
    drawRoundRect(
        color = Color.White.copy(alpha = 0.14f),
        topLeft = Offset(pillRect.left, pillRect.top),
        size = Size(pillRect.width, pillRect.height),
        cornerRadius = CornerRadius(pillRadius, pillRadius)
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.65f * liftProgress),
        topLeft = Offset(pillRect.left, pillRect.top),
        size = Size(pillRect.width, pillRect.height),
        cornerRadius = CornerRadius(pillRadius, pillRadius),
        style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
    )
}
