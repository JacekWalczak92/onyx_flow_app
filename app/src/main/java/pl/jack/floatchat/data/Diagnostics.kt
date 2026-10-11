package pl.jack.floatchat.data

import android.content.Context
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

    fun logText(context: Context): String = sp(context).getString("log", "")!!
    fun crashText(context: Context): String = sp(context).getString("crash", "")!!

    fun clear(context: Context) {
        sp(context).edit().clear().commit()
    }
}
