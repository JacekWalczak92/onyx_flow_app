package pl.jack.floatchat.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Krótki dziennik zdarzeń (ostatnie ~30 wpisów) i ostatni błąd aplikacji.
 * Widoczny na ekranie ustawień — pozwala ustalić, czemu bąbel zniknął
 * (ubity przez system? błąd? zamknięty ręcznie?).
 */
object Diagnostics {
    private const val FILE = "floatchat_diag"
    private const val MAX = 30

    private fun sp(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun log(context: Context, message: String) {
        val time = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date())
        val old = sp(context).getString("log", "")!!.lines().filter { it.isNotBlank() }
        val updated = (old + "$time  $message").takeLast(MAX).joinToString("\n")
        sp(context).edit().putString("log", updated).commit()
    }

    fun recordCrash(context: Context, t: Throwable) {
        val time = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date())
        val trace = t.stackTraceToString().lines().take(12).joinToString("\n")
        // commit(), bo proces zaraz zginie
        sp(context).edit().putString("crash", "$time\n$trace").commit()
        log(context, "BŁĄD: ${t.javaClass.simpleName}: ${t.message}")
    }

    /**
     * Zapisuje, czemu poprzedni proces zginął (Android 11+). Na Boox typowe jest
     * „wymuszone zatrzymanie" przy uśpieniu, gdy aplikacja ma włączone „Blokowanie aplikacji".
     */
    fun logPreviousExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val exit = runCatching { am.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull() }.getOrNull() ?: return
        if (exit.timestamp <= sp(context).getLong("lastExit", 0)) return
        sp(context).edit().putLong("lastExit", exit.timestamp).apply()
        val reason = when (exit.reason) {
            ApplicationExitInfo.REASON_USER_REQUESTED -> "wymuszone zatrzymanie (Boox: „Blokowanie aplikacji”?)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "brak pamięci"
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "błąd aplikacji"
            ApplicationExitInfo.REASON_ANR -> "aplikacja nie odpowiadała"
            ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_OTHER -> "ubity przez system"
            ApplicationExitInfo.REASON_USER_STOPPED -> "zatrzymany przez użytkownika"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "aktualizacja"
            else -> "kod ${exit.reason}"
        }
        val time = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(exit.timestamp))
        log(context, "Proces zakończony $time: $reason" + (exit.description?.let { " [$it]" } ?: ""))
    }

    fun logText(context: Context): String = sp(context).getString("log", "")!!
    fun crashText(context: Context): String = sp(context).getString("crash", "")!!

    fun clear(context: Context) {
        sp(context).edit().clear().commit()
    }
}
