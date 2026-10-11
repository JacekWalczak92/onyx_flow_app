package pl.jack.floatchat.overlay

import android.webkit.WebView
import org.json.JSONObject

/**
 * Wkleja tekst w pole czatu na stronie (ChatGPT / Claude / Gemini...).
 *
 * Pola tych serwisów to najczęściej edytory contenteditable (ProseMirror),
 * więc zwykłe `value = ...` nie działa. Używamy `document.execCommand('insertText')`,
 * który wywołuje te same zdarzenia co wpisywanie z klawiatury.
 * Strona może się jeszcze ładować — skrypt ponawia próbę przez ~8 s.
 */
object TextInjector {

    fun inject(webView: WebView, selector: String, text: String, autoSend: Boolean, onResult: (Boolean) -> Unit) {
        val js = """
            (function(){
              var sel = ${JSONObject.quote(selector)};
              var txt = ${JSONObject.quote(text)};
              var send = $autoSend;
              var tries = 0;
              function attempt(){
                var el = document.querySelector(sel);
                if (!el) {
                  if (++tries < 40) { setTimeout(attempt, 200); }
                  return;
                }
                el.focus();
                if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
                  var setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value').set;
                  setter.call(el, (el.value ? el.value + '\n' : '') + txt);
                  el.dispatchEvent(new Event('input', {bubbles: true}));
                } else {
                  var r = document.createRange();
                  r.selectNodeContents(el); r.collapse(false);
                  var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);
                  if (!document.execCommand('insertText', false, txt)) {
                    el.textContent += txt;
                    el.dispatchEvent(new InputEvent('input', {bubbles: true}));
                  }
                }
                if (send) {
                  setTimeout(function(){
                    var btn = document.querySelector("button[data-testid='send-button'], button[aria-label*='Send' i], button[aria-label*='Wyślij' i]");
                    if (btn && !btn.disabled) { btn.click(); }
                    else {
                      el.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true}));
                    }
                  }, 300);
                }
              }
              attempt();
              return !!document.querySelector(sel);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result -> onResult(result == "true") }
    }
}
