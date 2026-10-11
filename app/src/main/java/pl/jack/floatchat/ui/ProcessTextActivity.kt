package pl.jack.floatchat.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import pl.jack.floatchat.data.Prefs
import pl.jack.floatchat.overlay.OverlayService

/**
 * Niewidoczna aktywność wywoływana z menu zaznaczenia tekstu („Zapytaj AI")
 * albo z menu „Udostępnij". Przekazuje tekst do pływającego okna i od razu się zamyka,
 * więc czytnik zostaje na ekranie.
 */
class ProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.toString()?.trim()

        // Zapamiętaj aplikację-źródło (np. czytnik), by „Wróć do czytnika" wiedział, dokąd wrócić.
        val source = callingPackage ?: referrer?.takeIf { it.scheme == "android-app" }?.host
        if (source != null && source != packageName && source != "android") {
            Prefs(this).lastSourcePackage = source
        }

        if (!text.isNullOrEmpty()) {
            OverlayService.start(this, OverlayService.ACTION_INJECT, text)
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
