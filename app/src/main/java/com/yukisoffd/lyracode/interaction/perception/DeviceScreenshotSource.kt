package com.yukisoffd.lyracode.interaction.perception

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Captures the target window, excluding our overlay. Never writes screenshot files. */
internal object DeviceScreenshotSource {
    data class Frame(val dataUrl: String, val width: Int, val height: Int, val left: Int, val top: Int)
    @Volatile var service: AccessibilityService? = null
    fun matchesForeground(snapshot: com.yukisoffd.lyracode.interaction.model.ScreenSnapshot): Boolean {
        val active = service ?: return false
        if (Build.VERSION.SDK_INT < 35) return false
        val current = (ActionSnapshotSource(active).capture() as? SnapshotCaptureResult.Success)?.snapshot ?: return false
        return current.activePackage == snapshot.activePackage && current.activeWindowId == snapshot.activeWindowId &&
            current.uiFingerprint == snapshot.uiFingerprint
    }
    suspend fun capture(windowId: Int): Frame = suspendCancellableCoroutine { continuation ->
        val active = service
        if (active == null || Build.VERSION.SDK_INT < 34) {
            continuation.resumeWithException(IllegalStateException("窗口截图不可用")); return@suspendCancellableCoroutine
        }
        val bounds = android.graphics.Rect()
        val window = active.windows.firstOrNull { it.id == windowId }
        if (window == null) { continuation.resumeWithException(IllegalStateException("目标窗口已消失")); return@suspendCancellableCoroutine }
        window.getBoundsInScreen(bounds)
        active.takeScreenshotOfWindow(windowId, active.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                result.hardwareBuffer.use { buffer ->
                    if (!continuation.isActive) return
                    runCatching {
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: error("截图解码失败")
                        val bitmap = try { hardware.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware.recycle() }
                        try {
                            ByteArrayOutputStream().use { output ->
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                                Frame("data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP), bitmap.width, bitmap.height, bounds.left, bounds.top)
                            }
                        } finally { bitmap.recycle() }
                    }.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                }
            }
            override fun onFailure(errorCode: Int) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("窗口截图被系统拒绝：$errorCode"))
            }
        })
    }
}
