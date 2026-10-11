package pl.jack.floatchat.data

import android.content.Context

/** Proste ustawienia w SharedPreferences (pozycja/rozmiar okna, wybrany serwis). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("floatchat", Context.MODE_PRIVATE)

    var providerId: String
        get() = sp.getString("provider", "chatgpt")!!
        set(v) = sp.edit().putString("provider", v).apply()

    var customUrl: String
        get() = sp.getString("custom_url", "https://")!!
        set(v) = sp.edit().putString("custom_url", v).apply()

    /** Wysyłaj od razu po wklejeniu tekstu (Enter). Domyślnie nie — możesz dopisać pytanie. */
    var autoSend: Boolean
        get() = sp.getBoolean("auto_send", false)
        set(v) = sp.edit().putBoolean("auto_send", v).apply()

    /** Prefiks dodawany przed zaznaczonym tekstem, np. "Wyjaśnij:". */
    var promptPrefix: String
        get() = sp.getString("prompt_prefix", "")!!
        set(v) = sp.edit().putString("prompt_prefix", v).apply()

    /**
     * Eksperymentalne: bąbel uruchamia NATYWNĄ aplikację (np. ChatGPT) w oknie obok,
     * zamiast strony w pływającym panelu. Działa tylko, gdy system Boox pozwala na
     * okna swobodne / podział ekranu.
     */
    var nativeMode: Boolean
        get() = sp.getBoolean("native_mode", false)
        set(v) = sp.edit().putBoolean("native_mode", v).apply()

    /** Ręcznie wybrana paczka natywnej aplikacji ("" = wykryj automatycznie). */
    var nativePackage: String
        get() = sp.getString("native_package", "")!!
        set(v) = sp.edit().putString("native_package", v).apply()

    /** Jak otwierać natywną aplikację: "freeform" (pływające okno) albo "split" (podział ekranu). */
    var windowMode: String
        get() = sp.getString("window_mode", "freeform")!!
        set(v) = sp.edit().putString("window_mode", v).apply()

    /** Rozmiar pływającego okna natywnej aplikacji: "small" / "medium" / "large". */
    var windowSize: String
        get() = sp.getString("window_size", "medium")!!
        set(v) = sp.edit().putString("window_size", v).apply()

    /** Aplikacja czytnika, do której wraca przycisk „Wróć do czytnika" ("" = automatycznie). */
    var readerPackage: String
        get() = sp.getString("reader_package", "")!!
        set(v) = sp.edit().putString("reader_package", v).apply()

    /** Ostatnia aplikacja, z której przyszedł zaznaczony tekst (wykrywana automatycznie). */
    var lastSourcePackage: String
        get() = sp.getString("last_source_package", "")!!
        set(v) = sp.edit().putString("last_source_package", v).apply()

    var bubbleX: Int
        get() = sp.getInt("bubble_x", -1)
        set(v) = sp.edit().putInt("bubble_x", v).apply()
    var bubbleY: Int
        get() = sp.getInt("bubble_y", 300)
        set(v) = sp.edit().putInt("bubble_y", v).apply()

    var panelX: Int
        get() = sp.getInt("panel_x", -1)
        set(v) = sp.edit().putInt("panel_x", v).apply()
    var panelY: Int
        get() = sp.getInt("panel_y", -1)
        set(v) = sp.edit().putInt("panel_y", v).apply()
    var panelW: Int
        get() = sp.getInt("panel_w", -1)
        set(v) = sp.edit().putInt("panel_w", v).apply()
    var panelH: Int
        get() = sp.getInt("panel_h", -1)
        set(v) = sp.edit().putInt("panel_h", v).apply()

    fun provider(): ChatProvider = Providers.byId(providerId, customUrl)
}
