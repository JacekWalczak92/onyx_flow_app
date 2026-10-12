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
import pl.jack.floatchat.data.Diagnostics
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
        Watchdog.schedule(this)
    }

    /** Usunięcie FloatChat z listy ostatnich aplikacji zabija usługę na części systemów — wstań za chwilę. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Diagnostics.log(this, "Zadanie usunięte z ostatnich aplikacji")
        if (prefs.bubbleWanted) Watchdog.restartSoon(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.bubbleWanted = false
                Diagnostics.log(this, "Zamknięty przez użytkownika")
                stopSelf(); return START_NOT_STICKY
            }
            ACTION_OPEN -> expand()
            ACTION_TOGGLE -> if (panel?.isShown == true) minimize() else expand()
            ACTION_INJECT -> intent.getStringExtra(EXTRA_TEXT)?.let { injectText(it) } ?: expand()
            else -> {
                if (intent == null) Diagnostics.log(this, "Usługa wznowiona przez system po ubiciu")
                showBubble()
            }
        }
        if (intent?.action != ACTION_STOP) prefs.bubbleWanted = true
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        if (prefs.bubbleWanted) {
            Diagnostics.log(this, "Usługa zatrzymana nieoczekiwanie — watchdog ją przywróci")
            Watchdog.restartSoon(this)
        } else {
            Watchdog.cancel(this)
        }
        hideNativeMenu()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        panel?.destroy()
        panel = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- logika

    private fun expand() {
        if (prefs.nativeMode && tryOpenNative(null, prefs.windowSize)) { showBubble(); return }
        hideBubble()
        val p = ensurePanel()
        p.reloadProviderIfChanged()
        p.show()
    }

    private fun ensurePanel(): ChatPanel = panel ?: ChatPanel(
        this, wm, prefs,
        onMinimize = ::minimize,
        onClose = { prefs.bubbleWanted = false; stopSelf() },
        onScreenshot = { takeScreenshot(toNative = false) },
    ).also { panel = it }

    private fun minimize() {
        panel?.hide()
        showBubble()
    }

    private fun injectText(text: String) {
        // Zawsze kopiujemy też do schowka — awaryjnie wystarczy „wklej" w polu czatu.
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("FloatChat", text))

        if (prefs.nativeMode && tryOpenNative(text, prefs.windowSize)) { showBubble(); return }
        openWebPanel()
        // Mała zwłoka, żeby okno zdążyło się podpiąć; skrypt i tak czeka na pole tekstowe.
        handler.postDelayed({ panel?.injectText(text) }, 250)
    }

    // ---------------------------------------------------------------- menu trybu natywnego

    private var menu: View? = null
    private var menuHiddenAt = 0L

    /**
     * Menu pod bąblem (tryb natywnej aplikacji). Duże przyciski działają zawsze, także gdy
     * ChatGPT jest na pełnym ekranie, a systemowy „✕" wypada pod panelem Boox.
     */
    private fun toggleNativeMenu() {
        if (menu != null) { hideNativeMenu(); return }
        // Dotknięcie bąbla przy otwartym menu najpierw je zamyka (ACTION_OUTSIDE) — nie otwieraj od razu ponownie.
        if (android.os.SystemClock.uptimeMillis() - menuHiddenAt < 300) return
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun item(label: String, action: () -> Unit) {
            val tv = android.widget.TextView(this).apply {
                text = label
                textSize = 18f
                setTextColor(android.graphics.Color.BLACK)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener { hideNativeMenu(); action() }
            }
            if (column.childCount > 0) {
                column.addView(View(this).apply { setBackgroundColor(android.graphics.Color.BLACK) },
                    android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            }
            column.addView(tv, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        }

        val chatPkg = NativeAppLauncher.resolvePackage(this, prefs)
        val chatLabel = chatPkg?.let { NativeAppLauncher.labelOf(this, it) } ?: "czat"
        val readerPkg = NativeAppLauncher.resolveReader(this, prefs)

        if (chatPkg != null) {
            item("▣  Otwórz / zmniejsz $chatLabel") { openNativeWindow(prefs.windowSize) }
            item("▫  Małe okienko") { prefs.windowSize = "small"; openNativeWindow("small") }
            item("▢  Duże okienko") { prefs.windowSize = "large"; openNativeWindow("large") }
        }
        if (readerPkg != null) {
            item("↩  Wróć do: ${NativeAppLauncher.labelOf(this, readerPkg)}") {
                if (!NativeAppLauncher.bringToFront(this, readerPkg)) {
                    Toast.makeText(this, "Nie udało się otworzyć czytnika", Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (chatPkg != null) item("📷  Zrzut ekranu → $chatLabel") { takeScreenshot(toNative = true) }
        item("⌂  Okno strony (WebView)") { forceWebPanel() }
        if (chatPkg != null) {
            if (!NativeAppLauncher.isEnabled(this, chatPkg)) {
                item("⚠  $chatLabel zamrożony — odblokuj") { NativeAppLauncher.openBooxFreezeSettings(this, chatPkg) }
            }
            item("⚙  Ustawienia $chatLabel") { NativeAppLauncher.openAppDetails(this, chatPkg) }
        }
        item("✕  Zamknij menu") { }
        item("⏻  Zamknij FloatChat") { prefs.bubbleWanted = false; Diagnostics.log(this, "Zamknięty z menu"); stopSelf() }

        val dm = resources.displayMetrics
        val width = dp(300)
        val params = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val bubbleW = bubble?.width ?: dp(64)
            x = if (bubbleParams.x > dm.widthPixels / 2) max(0, bubbleParams.x - width - dp(8))
                else bubbleParams.x + bubbleW + dp(8)
            y = max(0, bubbleParams.y)
            windowAnimations = 0
        }
        column.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                hideNativeMenu()
                menuHiddenAt = android.os.SystemClock.uptimeMillis()
            }
            false
        }
        if (runCatching { wm.addView(column, params) }.isSuccess) menu = column
    }

    private fun hideNativeMenu() {
        menu?.let { runCatching { wm.removeView(it) } }
        menu = null
    }

    /** Otwiera natywny czat w pływającym okienku — także „zmniejsza" go z pełnego ekranu. */
    private fun openNativeWindow(size: String) {
        if (!tryOpenNative(null, size)) openWebPanel()
    }

    /**
     * Próbuje otworzyć natywny czat. Zwraca false, gdy się nie da — wtedy wołający
     * otwiera okno strony, żeby użytkownik zawsze dostał czat.
     *
     * Najczęstsza przyczyna porażki na Boox: aplikacja jest „zamrożona" (wyłączona przez
     * system Boox w tle) — wyłączonej aplikacji nie da się uruchomić z innej aplikacji.
     */
    private fun tryOpenNative(text: String?, size: String): Boolean {
        val pkg = NativeAppLauncher.resolvePackage(this, prefs)
        if (pkg == null) {
            Toast.makeText(this, "Nie znaleziono aplikacji czatu — otwieram okno strony", Toast.LENGTH_SHORT).show()
            return false
        }
        val label = NativeAppLauncher.labelOf(this, pkg)
        if (!NativeAppLauncher.isEnabled(this, pkg)) {
            Toast.makeText(
                this,
                "$label jest zamrożony przez Boox — otwieram okno strony. " +
                    "Wyłącz blokadę: 2× bąbel → „$label zamrożony — odblokuj”",
                Toast.LENGTH_LONG,
            ).show()
            return false
        }
        if (NativeAppLauncher.launch(this, pkg, text, prefs.windowMode, size)) return true
        // Druga próba bez przekazywania tekstu (tekst i tak jest w schowku).
        if (text != null && NativeAppLauncher.launch(this, pkg, null, prefs.windowMode, size)) {
            Toast.makeText(this, "Tekst jest w schowku — wklej go w $label", Toast.LENGTH_SHORT).show()
            return true
        }
        Toast.makeText(this, "Nie udało się otworzyć $label — otwieram okno strony", Toast.LENGTH_SHORT).show()
        return false
    }

    // ---------------------------------------------------------------- zrzut ekranu

    /**
     * 📷: chowa okna FloatChat, robi zdjęcie ekranu pod nimi (usługa ułatwień dostępu),
     * przywraca okna i przekazuje zdjęcie: do pola czatu na stronie albo do natywnej aplikacji.
     * Zawsze też kopiuje je do schowka (awaryjnie: przytrzymaj pole czatu → Wklej).
     */
    private fun takeScreenshot(toNative: Boolean) {
        if (!ScreenshotService.isEnabled) {
            Toast.makeText(
                this, "Włącz w Ułatwieniach dostępu: „FloatChat — zrzut ekranu”", Toast.LENGTH_LONG,
            ).show()
            ScreenshotService.openSettings(this)
            return
        }
        val panelWasShown = panel?.isShown == true
        val bubbleWasShown = bubble != null
        hideNativeMenu()
        panel?.hide()
        hideBubble()
        // Chwila, żeby system przerysował ekran bez naszych okien.
        handler.postDelayed({
            ScreenshotService.capture(this) { bitmap ->
                if (bubbleWasShown) showBubble()
                if (panelWasShown) panel?.show()
                if (bitmap == null) {
                    Toast.makeText(this, "Nie udało się zrobić zrzutu ekranu", Toast.LENGTH_SHORT).show()
                    return@capture
                }
                Thread {
                    val shot = runCatching { Screenshots.save(this, bitmap) }.getOrNull()
                    bitmap.recycle()
                    handler.post { if (shot != null) deliverScreenshot(shot, toNative) }
                }.start()
            }
        }, 400)
    }

    private fun deliverScreenshot(shot: Screenshots.Shot, toNative: Boolean) {
        runCatching {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newUri(contentResolver, "Zrzut ekranu", shot.uri))
        }
        if (toNative) {
            val pkg = NativeAppLauncher.resolvePackage(this, prefs)
            val ok = pkg != null && NativeAppLauncher.isEnabled(this, pkg) &&
                NativeAppLauncher.acceptsImage(this, pkg) &&
                NativeAppLauncher.launch(this, pkg, windowMode = prefs.windowMode, size = prefs.windowSize, imageUri = shot.uri)
            Toast.makeText(
                this,
                if (ok) "Zrzut ekranu wysłany do czatu" else "Zrzut ekranu jest w schowku — wklej go w czacie",
                Toast.LENGTH_SHORT,
            ).show()
        } else {
            panel?.injectImage(android.util.Base64.encodeToString(shot.bytes, android.util.Base64.NO_WRAP))
            Toast.makeText(this, "Zrzut ekranu dodany do wiadomości", Toast.LENGTH_SHORT).show()
        }
    }

    /** Okno strony (WebView) — także jako zapas, gdy natywna aplikacja nie działa. */
    private fun openWebPanel(textToInject: String? = null) {
        hideBubble()
        val p = ensurePanel()
        p.reloadProviderIfChanged()
        p.show()
        textToInject?.let { t -> handler.postDelayed({ panel?.injectText(t) }, 250) }
    }

    /** Jednorazowo otwiera panel ze stroną, nawet w trybie natywnym. */
    private fun forceWebPanel() {
        hideBubble()
        val p = ensurePanel()
        p.reloadProviderIfChanged()
        p.show()
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
        // v9: przytrzymanie NIE zamyka już FloatChat (na e-inku dotyk łatwo trwa dłużej) —
        // otwiera menu. Zamykanie: menu → „Zamknij FloatChat" albo powiadomienie.
        var longPressed = false
        val longPress = Runnable {
            if (!moved) {
                longPressed = true
                handler.removeCallbacks(singleTap)
                toggleNativeMenu()
            }
        }

        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY
                    origX = bubbleParams.x; origY = bubbleParams.y
                    moved = false; downTime = e.eventTime; longPressed = false
                    handler.postDelayed(longPress, 900)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - startX).toInt(); val dy = (e.rawY - startY).toInt()
                    if (moved || abs(dx) > slop || abs(dy) > slop) {
                        moved = true
                        handler.removeCallbacks(longPress)
                        bubbleParams.x = max(0, origX + dx)
                        bubbleParams.y = max(0, origY + dy)
                        bubble?.let { runCatching { wm.updateViewLayout(it, bubbleParams) } }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (moved) {
                        prefs.bubbleX = bubbleParams.x; prefs.bubbleY = bubbleParams.y
                    } else if (!longPressed) {
                        onBubbleTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
            }
            true
        }

        if (runCatching { wm.addView(view, bubbleParams) }.isSuccess) bubble = view
    }

    // Pojedyncze dotknięcie = otwórz okno; podwójne = menu.
    // Pojedyncze czeka chwilę na ewentualne drugie dotknięcie (na e-ink dajemy więcej czasu).
    private val doubleTapWindowMs = 400L
    private var lastTapAt = 0L
    private val singleTap = Runnable { hideNativeMenu(); expand() }

    private fun onBubbleTap() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTapAt < doubleTapWindowMs) {
            handler.removeCallbacks(singleTap)
            lastTapAt = 0L
            toggleNativeMenu()
        } else {
            lastTapAt = now
            handler.postDelayed(singleTap, doubleTapWindowMs)
        }
    }

    private fun hideBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
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
