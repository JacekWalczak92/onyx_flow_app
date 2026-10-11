package pl.jack.floatchat.overlay

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import org.lsposed.hiddenapibypass.HiddenApiBypass
import pl.jack.floatchat.data.Prefs

/**
 * Tryb eksperymentalny: otwiera natywną aplikację czatu w oknie obok bieżącej aplikacji.
 *
 * Android nie pozwala „osadzić" cudzej aplikacji w naszym oknie. Możemy jedynie poprosić
 * system o uruchomienie jej:
 *  - w trybie podzielonego ekranu (FLAG_ACTIVITY_LAUNCH_ADJACENT), albo
 *  - w oknie swobodnym o zadanych granicach (setLaunchBounds) — wymaga włączonych
 *    „okien swobodnych" (Opcje programisty → Enable freeform windows).
 *
 * Wykrywanie aplikacji (v3): Boox potrafi „zamrozić" aplikacje (wyłączyć je), a paczka może
 * się różnić od oczekiwanej — dlatego szukamy po nazwie paczki i etykiecie, a użytkownik
 * może też wybrać aplikację ręcznie z listy.
 */
object NativeAppLauncher {

    data class AppEntry(val pkg: String, val label: String)

    /** Słowa kluczowe do automatycznego wyszukania aplikacji danego serwisu. */
    private val keywords = mapOf(
        "chatgpt" to listOf("openai", "chatgpt"),
        "claude" to listOf("anthropic", "claude"),
        "gemini" to listOf("gemini", "bard"),
        "perplexity" to listOf("perplexity"),
    )

    /** Wszystkie aplikacje z ikoną w launcherze (posortowane po nazwie). */
    fun listLaunchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != context.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    /** Czy paczka jest zainstalowana (także gdy wyłączona / zamrożona). */
    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** false = aplikacja jest zainstalowana, ale wyłączona (np. zamrożona przez Boox). */
    fun isEnabled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS).enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun labelOf(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS).loadLabel(pm).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    /**
     * Paczka natywnej aplikacji do użycia: ręczny wybór z ustawień, a jeśli go nie ma —
     * domyślna paczka serwisu, a na końcu wyszukiwanie po słowach kluczowych.
     */
    fun resolvePackage(context: Context, prefs: Prefs): String? {
        prefs.nativePackage.takeIf { it.isNotBlank() && isInstalled(context, it) }?.let { return it }
        val provider = prefs.provider()
        provider.nativePackage?.takeIf { isInstalled(context, it) }?.let { return it }
        val words = keywords[provider.id] ?: return null
        return listLaunchableApps(context).firstOrNull { app ->
            words.any { w -> app.pkg.contains(w, ignoreCase = true) || app.label.contains(w, ignoreCase = true) }
        }?.pkg
    }

    private fun launcherIntent(context: Context, pkg: String): Intent? {
        val pm = context.packageManager
        pm.getLaunchIntentForPackage(pkg)?.let { return it }
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        val info = pm.queryIntentActivities(query, PackageManager.MATCH_ALL).firstOrNull() ?: return null
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(info.activityInfo.packageName, info.activityInfo.name))
    }

    /** WINDOWING_MODE_FREEFORM z ukrytego WindowConfiguration. */
    private const val WINDOWING_MODE_FREEFORM = 5

    /** Czy system ma włączone okna swobodne (Opcje programisty → Enable freeform windows). */
    fun isFreeformEnabled(context: Context): Boolean {
        val feature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)
        val setting = Settings.Global.getInt(context.contentResolver, "enable_freeform_support", 0) != 0
        return feature || setting
    }

    /** Wymusza tryb okna swobodnego przez ukryte API (działa tylko, gdy system ma freeform). */
    private fun setFreeform(options: ActivityOptions): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.invoke(ActivityOptions::class.java, options, "setLaunchWindowingMode", WINDOWING_MODE_FREEFORM)
        } else {
            ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, WINDOWING_MODE_FREEFORM)
        }
        true
    } catch (e: Throwable) {
        false
    }

    /** Otwiera aplikację; jeśli [text] != null — przekazuje go przez ACTION_SEND (ChatGPT to obsługuje). */
    fun launch(context: Context, pkg: String, text: String? = null, windowMode: String = "freeform"): Boolean {
        val pm = context.packageManager
        val sendIntent = text?.let {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage(pkg)
                putExtra(Intent.EXTRA_TEXT, it)
            }.takeIf { i -> pm.queryIntentActivities(i, PackageManager.MATCH_ALL).isNotEmpty() }
        }
        // Gdy aplikacja nie przyjmuje ACTION_SEND — zwykłe uruchomienie (tekst jest w schowku).
        val intent = sendIntent ?: launcherIntent(context, pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (windowMode == "split") intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)

        val dm = context.resources.displayMetrics
        // Prawa część ekranu — po lewej zostaje czytnik.
        val bounds = Rect(dm.widthPixels * 45 / 100, dm.heightPixels / 10, dm.widthPixels - 24, dm.heightPixels * 85 / 100)
        val options = ActivityOptions.makeBasic().setLaunchBounds(bounds)
        if (windowMode == "freeform") setFreeform(options)

        return try {
            context.startActivity(intent, options.toBundle())
            true
        } catch (e: Exception) {
            false
        }
    }
}
