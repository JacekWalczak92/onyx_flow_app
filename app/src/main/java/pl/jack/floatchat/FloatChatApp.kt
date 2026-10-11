package pl.jack.floatchat

import android.app.Application
import pl.jack.floatchat.data.Diagnostics
import pl.jack.floatchat.overlay.Watchdog

class FloatChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Zapisz każdy niezłapany błąd (widoczny potem w ustawieniach → Diagnostyka)
        // i poproś strażnika o przywrócenie bąbla po restarcie procesu.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                Diagnostics.recordCrash(this, error)
                Watchdog.restartSoon(this)
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
