package pl.jack.floatchat.overlay

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect

/**
 * Tryb eksperymentalny: otwiera natywną aplikację czatu w oknie obok bieżącej aplikacji.
 *
 * Android nie pozwala „osadzić" cudzej aplikacji w naszym oknie. Możemy jedynie poprosić
 * system o uruchomienie jej:
 *  - w trybie podzielonego ekranu (FLAG_ACTIVITY_LAUNCH_ADJACENT), albo
 *  - w oknie swobodnym o zadanych granicach (setLaunchBounds) — wymaga włączonych
 *    „okien swobodnych" (Opcje programisty → Enable freeform windows).
 * Efekt zależy od firmware Boox — dlatego to tylko opcja, a domyślnie używamy WebView.
 */
object NativeAppLauncher {

    fun isInstalled(context: Context, pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg) != null

    /** Otwiera aplikację; jeśli [text] != null — przekazuje go przez ACTION_SEND (ChatGPT to obsługuje). */
    fun launch(context: Context, pkg: String, text: String? = null): Boolean {
        val intent = if (text != null) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage(pkg)
                putExtra(Intent.EXTRA_TEXT, text)
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        }
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        )

        val dm = context.resources.displayMetrics
        // Prawa połowa ekranu — po lewej zostaje czytnik.
        val bounds = Rect(dm.widthPixels / 2, dm.heightPixels / 8, dm.widthPixels - 16, dm.heightPixels * 7 / 8)
        val options = ActivityOptions.makeBasic().setLaunchBounds(bounds)

        return try {
            context.startActivity(intent, options.toBundle())
            true
        } catch (e: Exception) {
            false
        }
    }
}
