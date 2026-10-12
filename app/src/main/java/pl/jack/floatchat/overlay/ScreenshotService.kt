package pl.jack.floatchat.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * Usługa ułatwień dostępu służąca WYŁĄCZNIE do zrzutu ekranu (AccessibilityService.takeScreenshot,
 * Android 11+). Nie czyta zawartości okien ani zdarzeń — dzięki niej zdjęcie ekranu pod oknem czatu
 * robi się bez systemowego pytania o udostępnianie ekranu przy każdym zrzucie.
 */
class ScreenshotService : AccessibilityService() {

    companion object {
        @Volatile private var instance: ScreenshotService? = null

        val isEnabled: Boolean get() = instance != null

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
