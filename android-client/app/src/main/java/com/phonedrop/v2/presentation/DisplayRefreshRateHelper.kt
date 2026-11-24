package com.phonedrop.v2.presentation

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.Window
import java.lang.reflect.Method

/**
 * Dynamically detects and requests the panel's highest supported refresh rate
 * (60Hz / 90Hz / 120Hz / 144Hz / 165Hz+) across Window mode preferences,
 * Android 15/16 View frame-rate voting, and video decoder Surfaces.
 */
object DisplayRefreshRateHelper {

    private val setFrameRateBoostOnTouchMethod: Method? by lazy {
        runCatching {
            Window::class.java.getMethod(
                "setFrameRateBoostOnTouchEnabled",
                Boolean::class.javaPrimitiveType
            )
        }.getOrNull()
    }

    private val setRequestedFrameRateMethod: Method? by lazy {
        runCatching {
            View::class.java.getMethod(
                "setRequestedFrameRate",
                Float::class.javaPrimitiveType
            )
        }.getOrNull()
    }

    fun resolveDisplay(context: Context, activity: Activity? = null): Display? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && activity != null) {
            runCatching { activity.display }.getOrNull()?.let { return it }
        }
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        return dm.getDisplay(Display.DEFAULT_DISPLAY)
    }

    fun selectBestMode(display: Display): Display.Mode? {
        val modes = display.supportedModes
        if (modes.isNullOrEmpty()) return null
        val active = display.mode
        val sameResModes = modes.filter {
            it.physicalWidth == active.physicalWidth && it.physicalHeight == active.physicalHeight
        }
        val candidates = if (sameResModes.isNotEmpty()) sameResModes else modes.toList()
        return candidates.maxByOrNull { it.refreshRate }
    }

    fun getMaxSupportedRefreshRate(context: Context, activity: Activity? = null): Float {
        val display = resolveDisplay(context, activity) ?: return 60f
        val best = selectBestMode(display)
        return (best?.refreshRate ?: display.refreshRate).coerceAtLeast(60f)
    }

    fun applyMaxRefreshRate(activity: Activity): Float {
        val display = resolveDisplay(activity, activity) ?: return 60f
        val bestMode = selectBestMode(display)
        val targetHz = (bestMode?.refreshRate ?: display.refreshRate).coerceAtLeast(60f)

        if (bestMode != null) {
            val lp = activity.window.attributes
            if (lp.preferredDisplayModeId != bestMode.modeId || lp.preferredRefreshRate != bestMode.refreshRate) {
                lp.preferredDisplayModeId = bestMode.modeId
                lp.preferredRefreshRate = bestMode.refreshRate
                activity.window.attributes = lp
            }
        }

        if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                setFrameRateBoostOnTouchMethod?.invoke(activity.window, true)
            }
        }

        if (Build.VERSION.SDK_INT >= 35) {
            runCatching {
                applyRequestedFrameRateRecursive(activity.window.decorView, targetHz)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val decor = activity.window.decorView
            decor.post {
                if (Build.VERSION.SDK_INT >= 35) {
                    runCatching {
                        applyRequestedFrameRateRecursive(decor, targetHz)
                    }
                }
                runCatching {
                    // Ensure RootSurfaceControl transaction pipeline stays awake for high-Hz frame voting.
                    decor.rootSurfaceControl
                }
            }
        }

        return targetHz
    }

    private fun applyRequestedFrameRateRecursive(view: View, hz: Float) {
        val method = setRequestedFrameRateMethod ?: return
        runCatching { method.invoke(view, hz) }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyRequestedFrameRateRecursive(view.getChildAt(i), hz)
            }
        }
    }

    fun applySurfaceFrameRate(surface: Surface, targetHz: Float) {
        if (!surface.isValid || targetHz <= 0f) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                surface.setFrameRate(
                    targetHz,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ALWAYS
                )
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                surface.setFrameRate(
                    targetHz,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
                )
            }
        }
    }
}
