package pl.jack.floatchat.overlay

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import pl.jack.floatchat.data.ChatProvider
import pl.jack.floatchat.data.Prefs
import pl.jack.floatchat.data.Providers
import pl.jack.floatchat.ui.MicPermissionActivity
import kotlin.math.max
import kotlin.math.min

/**
 * Pływający panel z WebView czatu. Przeciągasz go za pasek tytułu,
 * zmieniasz rozmiar uchwytem w prawym dolnym rogu.
 *
 * Fokus klawiatury: panel jest „focusable", gdy go dotkniesz (możesz pisać),
 * a gdy dotkniesz aplikacji pod spodem — oddaje fokus (FLAG_NOT_FOCUSABLE),
 * żeby czytnik działał normalnie. Dotyk poza panelem zawsze trafia do aplikacji pod spodem.
 */
@SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled", "ViewConstructor")
class ChatPanel(
    private val context: Context,
    private val wm: WindowManager,
    private val prefs: Prefs,
    private val onMinimize: () -> Unit,
    private val onClose: () -> Unit,
    private val onScreenshot: () -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var provider: ChatProvider = prefs.provider()
    var isShown = false
        private set

    private lateinit var titleView: TextView
    val webView: WebView = createWebView()
    private val root: PanelRoot = buildLayout()

    val params = WindowManager.LayoutParams(
        initialW(), initialH(),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        val dm = context.resources.displayMetrics
        x = if (prefs.panelX >= 0) prefs.panelX else (dm.widthPixels - width) - dp(16)
        y = if (prefs.panelY >= 0) prefs.panelY else dp(80)
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        windowAnimations = 0 // e-ink: bez animacji
    }

    private fun initialW(): Int {
        val dm = context.resources.displayMetrics
        return if (prefs.panelW > 0) prefs.panelW else (dm.widthPixels * 0.48f).toInt()
    }

    private fun initialH(): Int {
        val dm = context.resources.displayMetrics
        return if (prefs.panelH > 0) prefs.panelH else (dm.heightPixels * 0.6f).toInt()
    }

    // ---------------------------------------------------------------- show / hide

    fun show() {
        if (isShown) return
        setFocusable(true)
        wm.addView(root, params)
        isShown = true
        if (webView.url == null) webView.loadUrl(provider.url)
    }

    fun hide() {
        if (!isShown) return
        wm.removeView(root) // WebView zostaje w pamięci — rozmowa nie znika
        isShown = false
    }

    fun destroy() {
        hide()
        webView.stopLoading()
        webView.destroy()
    }

    private var pageLoaded = false
    private var pendingText: String? = null

    /** Wkleja tekst w pole czatu; jeśli strona się jeszcze ładuje — po załadowaniu. */
    fun injectText(text: String) {
        val full = if (prefs.promptPrefix.isBlank()) text else prefs.promptPrefix.trim() + "\n\n" + text
        if (pageLoaded) TextInjector.inject(webView, provider.inputSelector, full, prefs.autoSend) { }
        else pendingText = full
    }

    /** Wkleja zrzut ekranu (JPEG w base64) do pola czatu. */
    fun injectImage(base64: String) {
        TextInjector.injectImage(webView, provider.inputSelector, base64, "image/jpeg", "zrzut-ekranu.jpg")
    }

    fun reloadProviderIfChanged() {
        val p = prefs.provider()
        if (p.id != provider.id || p.url != provider.url) {
            provider = p
            titleView.text = p.label
            webView.loadUrl(p.url)
        }
    }

    // ---------------------------------------------------------------- focus

    private fun setFocusable(focusable: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val newFlags = if (focusable) params.flags and flag.inv() else params.flags or flag
        if (newFlags != params.flags) {
            params.flags = newFlags
            if (isShown) wm.updateViewLayout(root, params)
        }
    }

    private inner class PanelRoot(ctx: Context) : FrameLayout(ctx) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                setFocusable(false) // oddaj fokus czytnikowi
                return false
            }
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) setFocusable(true)
            return super.dispatchTouchEvent(ev)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (webView.canGoBack()) webView.goBack() else onMinimize()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    // ---------------------------------------------------------------- views

    private fun createWebView(): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setSupportMultipleWindows(false)
        // Mobilny UA bez znacznika "; wv" — część serwisów blokuje WebView.
        settings.userAgentString = settings.userAgentString.replace("; wv", "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        overScrollMode = View.OVER_SCROLL_NEVER
        webChromeClient = object : WebChromeClient() {
            // Bez tego WebView po cichu odrzuca mikrofon — przycisk nagrywania „nie reaguje".
            override fun onPermissionRequest(request: PermissionRequest) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE !in request.resources) {
                    request.deny(); return
                }
                val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    request.deny()
                    context.startActivity(
                        Intent(context, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                pageLoaded = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                pageLoaded = true
                pendingText?.let { t ->
                    pendingText = null
                    TextInjector.inject(view, provider.inputSelector, t, prefs.autoSend) { }
                }
            }
        }
    }

    private fun buildLayout(): PanelRoot {
        val root = PanelRoot(context)
        root.setBackgroundResource(pl.jack.floatchat.R.drawable.bg_panel)
        root.setPadding(dp(3), dp(3), dp(3), dp(3))

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // --- pasek tytułu (przeciąganie) + przyciski
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), 0, 0, 0)
        }
        titleView = TextView(context).apply {
            text = provider.label
            setTextColor(Color.BLACK)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            // Dotknij tytułu → następny serwis
            setOnClickListener { cycleProvider() }
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, dp(48), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        titleView.gravity = Gravity.CENTER_VERTICAL

        header.addView(headerButton("📷") { onScreenshot() })
        header.addView(headerButton("Wklej") { pasteFromClipboard() })
        header.addView(headerButton("⟳") { webView.reload() })
        header.addView(headerButton("—") { onMinimize() })
        header.addView(headerButton("✕") { onClose() })
        attachDrag(header)
        attachDrag(titleView)

        val divider = View(context).apply { setBackgroundColor(Color.BLACK) }

        column.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        column.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        column.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // --- uchwyt zmiany rozmiaru (prawy dolny róg)
        val resize = TextView(context).apply {
            text = "◢"
            textSize = 22f
            setTextColor(Color.BLACK)
            gravity = Gravity.BOTTOM or Gravity.END
        }
        root.addView(resize, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM or Gravity.END))
        attachResize(resize)
        return root
    }

    private fun headerButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 18f
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        minWidth = dp(48)
        setPadding(dp(10), 0, dp(10), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48))
        setOnClickListener { onClick() }
    }

    private fun cycleProvider() {
        val ids = Providers.all.map { it.id }
        val next = ids[(ids.indexOf(provider.id) + 1).mod(ids.size)]
        prefs.providerId = next
        reloadProviderIfChanged()
    }

    private fun pasteFromClipboard() {
        // Czytanie schowka działa, bo panel ma w tym momencie fokus.
        val cm = context.getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrBlank()) {
            Toast.makeText(context, "Schowek jest pusty", Toast.LENGTH_SHORT).show()
        } else {
            injectText(text)
        }
    }

    // ---------------------------------------------------------------- drag & resize

    private fun attachDrag(handle: View) {
        var startX = 0f; var startY = 0f; var origX = 0; var origY = 0; var moved = false
        val slop = dp(8)
        handle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY; origX = params.x; origY = params.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - startX).toInt(); val dy = (e.rawY - startY).toInt()
                    if (moved || kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) {
                        moved = true
                        params.x = max(0, origX + dx)
                        params.y = max(0, origY + dy)
                        wm.updateViewLayout(root, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) { prefs.panelX = params.x; prefs.panelY = params.y }
                    else v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun attachResize(handle: View) {
        var startX = 0f; var startY = 0f; var origW = 0; var origH = 0
        val dm = context.resources.displayMetrics
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY; origW = params.width; origH = params.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.width = min(dm.widthPixels, max(dp(240), origW + (e.rawX - startX).toInt()))
                    params.height = min(dm.heightPixels, max(dp(240), origH + (e.rawY - startY).toInt()))
                    wm.updateViewLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.panelW = params.width; prefs.panelH = params.height
                    true
                }
                else -> false
            }
        }
    }
}
