package pl.jack.floatchat.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import pl.jack.floatchat.R
import pl.jack.floatchat.data.Prefs
import pl.jack.floatchat.ui.MainActivity
import kotlin.math.abs
import kotlin.math.max

/**
 * Usługa pierwszoplanowa trzymająca nad innymi aplikacjami:
 *  - bąbel (zminimalizowany czat) — dotknij: otwórz, przeciągnij: przesuń, przytrzymaj: zamknij,
 *  - panel czatu ([ChatPanel]).
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW_BUBBLE = "pl.jack.floatchat.SHOW_BUBBLE"
        const val ACTION_OPEN = "pl.jack.floatchat.OPEN"
        const val ACTION_TOGGLE = "pl.jack.floatchat.TOGGLE"
        const val ACTION_INJECT = "pl.jack.floatchat.INJECT"
        const val ACTION_STOP = "pl.jack.floatchat.STOP"
        const val EXTRA_TEXT = "text"

        private const val CHANNEL_ID = "floatchat_overlay"
        private const val NOTIF_ID = 1

        @Volatile var isRunning = false
            private set

        fun start(context: Context, action: String, text: String? = null) {
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "Najpierw nadaj uprawnienie „Wyświetlanie nad innymi aplikacjami”", Toast.LENGTH_LONG).show()
                return
            }
            val i = Intent(context, OverlayService::class.java).setAction(action)
            if (text != null) i.putExtra(EXTRA_TEXT, text)
            context.startForegroundService(i)
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private var bubble: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var panel: ChatPanel? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        startInForeground()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_OPEN -> expand()
            ACTION_TOGGLE -> if (panel?.isShown == true) minimize() else expand()
            ACTION_INJECT -> intent.getStringExtra(EXTRA_TEXT)?.let { injectText(it) } ?: expand()
            else -> showBubble()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        panel?.destroy()
        panel = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- logika

    private fun expand() {
        if (prefs.nativeMode) {
            val pkg = prefs.provider().nativePackage
            if (pkg != null && NativeAppLauncher.launch(this, pkg)) { showBubble(); return }
            Toast.makeText(this, "Nie udało się otworzyć aplikacji — używam okna", Toast.LENGTH_SHORT).show()
        }
        hideBubble()
        val p = panel ?: ChatPanel(this, wm, prefs, onMinimize = ::minimize, onClose = { stopSelf() }).also { panel = it }
        p.reloadProviderIfChanged()
        p.show()
    }

    private fun minimize() {
        panel?.hide()
        showBubble()
    }

    private fun injectText(text: String) {
        // Zawsze kopiujemy też do schowka — awaryjnie wystarczy „wklej" w polu czatu.
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("FloatChat", text))

        if (prefs.nativeMode) {
            val pkg = prefs.provider().nativePackage
            if (pkg != null && NativeAppLauncher.launch(this, pkg, text)) { showBubble(); return }
        }
        expand()
        // Mała zwłoka, żeby okno zdążyło się podpiąć; skrypt i tak czeka na pole tekstowe.
        handler.postDelayed({ panel?.injectText(text) }, 250)
    }

    // ---------------------------------------------------------------- bąbel

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null) return
        val d = resources.displayMetrics.density
        val size = (64 * d).toInt()

        val view = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_bubble)
            val pad = (10 * d).toInt()
            setPadding(pad, pad, pad, pad)
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_bubble) })
        }

        bubbleParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val dm = resources.displayMetrics
            x = if (prefs.bubbleX >= 0) prefs.bubbleX else dm.widthPixels - size - (8 * d).toInt()
            y = prefs.bubbleY
            windowAnimations = 0
        }

        var startX = 0f; var startY = 0f; var origX = 0; var origY = 0
        var moved = false; var downTime = 0L
        val slop = (10 * d).toInt()
        val longPress = Runnable {
            if (!moved) {
                Toast.makeText(this, "FloatChat zamknięty", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
        }

        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY
                    origX = bubbleParams.x; origY = bubbleParams.y
                    moved = false; downTime = e.eventTime
                    handler.postDelayed(longPress, 900)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - startX).toInt(); val dy = (e.rawY - startY).toInt()
                    if (moved || abs(dx) > slop || abs(dy) > slop) {
                        moved = true
                        handler.removeCallbacks(longPress)
                        bubbleParams.x = max(0, origX + dx)
                        bubbleParams.y = max(0, origY + dy)
                        bubble?.let { wm.updateViewLayout(it, bubbleParams) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (moved) {
                        prefs.bubbleX = bubbleParams.x; prefs.bubbleY = bubbleParams.y
                    } else if (e.eventTime - downTime < 900) {
                        expand()
                    }
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
            }
            true
        }

        wm.addView(view, bubbleParams)
        bubble = view
    }

    private fun hideBubble() {
        bubble?.let { wm.removeView(it) }
        bubble = null
    }

    // ---------------------------------------------------------------- powiadomienie

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val openSettings = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_bubble)
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openSettings)
            .addAction(Notification.Action.Builder(icon, "Pokaż / ukryj", toggle).build())
            .addAction(Notification.Action.Builder(icon, "Zamknij", stop).build())
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
