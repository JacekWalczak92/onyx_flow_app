package pl.jack.floatchat.data

/**
 * Serwisy czatu, które można otworzyć w pływającym oknie (WebView).
 * [inputSelector] — selektor CSS pola wpisywania; używany do wklejenia zaznaczonego tekstu.
 * [nativePackage] — paczka natywnej aplikacji (tryb eksperymentalny „okno natywnej aplikacji").
 */
data class ChatProvider(
    val id: String,
    val label: String,
    val url: String,
    val inputSelector: String,
    val nativePackage: String? = null,
)

object Providers {
    val all = listOf(
        ChatProvider(
            id = "chatgpt",
            label = "ChatGPT",
            url = "https://chatgpt.com/",
            inputSelector = "#prompt-textarea, div[contenteditable='true'], textarea",
            nativePackage = "com.openai.chatgpt",
        ),
        ChatProvider(
            id = "claude",
            label = "Claude",
            url = "https://claude.ai/new",
            inputSelector = "div[contenteditable='true'], textarea",
            nativePackage = "com.anthropic.claude",
        ),
        ChatProvider(
            id = "gemini",
            label = "Gemini",
            url = "https://gemini.google.com/app",
            inputSelector = "rich-textarea div[contenteditable='true'], div[contenteditable='true'], textarea",
        ),
        ChatProvider(
            id = "perplexity",
            label = "Perplexity",
            url = "https://www.perplexity.ai/",
            inputSelector = "textarea, div[contenteditable='true']",
        ),
    )

    fun byId(id: String, customUrl: String): ChatProvider =
        if (id == "custom") ChatProvider("custom", "Własny", customUrl, "textarea, div[contenteditable='true']")
        else all.firstOrNull { it.id == id } ?: all.first()
}
