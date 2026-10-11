package pl.jack.floatchat.overlay

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import pl.jack.floatchat.data.Diagnostics
import pl.jack.floatchat.data.Prefs

/**
 * Strażnik bąbla: Boox potrafi ubić FloatChat w tle (np. po wyjściu do ekranu głównego).
 * Co ok. minutę sprawdza, czy bąbel powinien być widoczny, i w razie potrzeby wznawia usługę.
 * Alarm nie budzi urządzenia (ELAPSED_REALTIME), więc nie zużywa baterii w uśpieniu.
 *
 * Uruchomienie usługi pierwszoplanowej z tła jest dozwolone, bo aplikacja ma uprawnienie
 * „Wyświetlanie nad innymi aplikacjami".
 */
object Watchdog {
    private const val INTERVAL_MS = 60_000L

    private fun pending(context: Context, requestCode: Int) = PendingIntent.getBroadcast(
        context, requestCode,
        Intent(context, WatchdogReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.setRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            INTERVAL_MS,
            pending(context, 0),
        )
    }

    /** Jednorazowe szybkie wznowienie (np. gdy usługa została właśnie zatrzymana). */
    fun restartSoon(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 2_000, pending(context, 1))
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pending(context, 0))
        am.cancel(pending(context, 1))
    }

    /** Wznawia bąbel, jeśli powinien działać, a nie działa. */
    fun ensureRunning(context: Context, reason: String) {
        val prefs = Prefs(context)
        if (!prefs.bubbleWanted || OverlayService.isRunning) return
        if (!Settings.canDrawOverlays(context)) return
        Diagnostics.log(context, "Przywrócono bąbel ($reason)")
        runCatching {
            context.startForegroundService(
                Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW_BUBBLE)
            )
        }.onFailure { Diagnostics.log(context, "Nie udało się przywrócić: ${it.javaClass.simpleName}") }
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Watchdog.ensureRunning(context, intent.action!!.substringAfterLast('.'))
            }
            else -> Watchdog.ensureRunning(context, "strażnik")
        }
    }
}
