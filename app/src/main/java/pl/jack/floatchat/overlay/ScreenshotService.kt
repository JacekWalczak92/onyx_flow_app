package pl.jack.floatchat.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat

/**
 * Usługa ułatwień dostępu do zrzutu ekranu (AccessibilityService.takeScreenshot, Android 11+) —
 * bez systemowego pytania o udostępnianie ekranu przy każdym zrzucie.
 *
 * Okienka natywnego czatu (cudza aplikacja, tryb okien swobodnych) nie da się schować ani przesunąć
 * z innej aplikacji, a czytnika Boox (NeoReader) nie da się przywołać (nie eksportuje aktywności).
 * Dlatego usługa sprawdza położenie okien i „wciska" systemowy przycisk „—" na pasku okienka czatu —
 * czytnik zostaje na wierzchu. Treści okien nie czyta.
 */
class ScreenshotService : AccessibilityService() {

    companion object {
        @Volatile private var instance: ScreenshotService? = null

        val isEnabled: Boolean get() = instance != null

        /**
         * Minimalizuje okienko aplikacji [pkg] (okno swobodne), dotykając „—" na jego pasku.
         * [onDone] dostaje false, gdy okienka nie ma albo jest na pełnym ekranie (bez paska).
         */
        fun minimizeWindowOf(pkg: String, onDone: (Boolean) -> Unit) {
            val service = instance ?: return onDone(false)
            val window = runCatching {
                service.windows.firstOrNull {
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName == pkg
                }
            }.getOrNull() ?: return onDone(false)
            val bounds = Rect().also { window.getBoundsInScreen(it) }
            val dm = service.resources.displayMetrics
            if (bounds.width() >= dm.widthPixels && bounds.height() >= dm.heightPixels - (100 * dm.density)) {
                return onDone(false) // pełny ekran — brak paska z „—"
            }
            // Pasek okna swobodnego Boox: ✕, ▭, — od prawej; „—" ok. 108 dp od prawej krawędzi, 21 dp od góry.
            val x = bounds.right - CAPTION_MINIMIZE_FROM_RIGHT_DP * dm.density
            val y = bounds.top + CAPTION_CENTER_FROM_TOP_DP * dm.density
            val tap = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 60))
                .build()
            val sent = service.dispatchGesture(tap, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = onDone(true)
                override fun onCancelled(gestureDescription: GestureDescription?) = onDone(false)
            }, null)
            if (!sent) onDone(false)
        }

        private const val CAPTION_MINIMIZE_FROM_RIGHT_DP = 108f
        private const val CAPTION_CENTER_FROM_TOP_DP = 21f

        /** Robi zrzut całego ekranu; [onResult] dostaje null, gdy się nie udało. Wołane z wątku głównego. */
        fun capture(context: Context, onResult: (Bitmap?) -> Unit) {
            val service = instance
            if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { onResult(null); return }
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ContextCompat.getMainExecutor(context),
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        result.hardwareBuffer.close()
                        onResult(bmp)
                    }

                    override fun onFailure(errorCode: Int) = onResult(null)
                },
            )
        }

        fun openSettings(context: Context) {
            runCatching {
                context.startActivity(
                    android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
