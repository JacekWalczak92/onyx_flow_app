# CLAUDE.md — FloatChat

Kontekst projektu dla Claude Code. Odpowiadaj po polsku.

## Co to jest
Aplikacja Android (Kotlin) na Onyx Boox Note Air 5C (Android 13+, e-ink).
Pływające okno czatu AI (ChatGPT / Claude / Gemini / Perplexity w WebView) nad dowolną
aplikacją — np. nad czytnikiem NeoReader. Zaznaczony tekst trafia do okna przez menu
„Zapytaj AI" (ACTION_PROCESS_TEXT), okno minimalizuje się do bąbla, rozmowa zostaje.

## Architektura
```
app/src/main/java/pl/jack/floatchat/
├── data/
│   ├── Prefs.kt            ustawienia (SharedPreferences): serwis, pozycje/rozmiary okien, tryby
│   └── Providers.kt        lista serwisów czatu: URL, selektor CSS pola tekstu, paczka natywnej app
├── overlay/
│   ├── OverlayService.kt   usługa FGS (specialUse): bąbel + panel, akcje SHOW/OPEN/TOGGLE/INJECT/STOP
│   ├── ChatPanel.kt        okno WebView: przeciąganie, resize, fokus klawiatury, kolejka tekstu
│   ├── TextInjector.kt     JS wklejający tekst do pola (execCommand insertText, retry 8 s)
│   ├── NativeAppLauncher.kt eksperyment: natywna app obok (LAUNCH_ADJACENT + setLaunchBounds)
│   └── BubbleTileService.kt kafelek szybkich ustawień
└── ui/
    ├── MainActivity.kt     ekran ustawień w Compose (czarno-biały)
    └── ProcessTextActivity.kt niewidoczna aktywność: „Zapytaj AI" / Udostępnij → INJECT
```

Okna nakładki są zwykłymi View (nie Compose) — prościej z WindowManager i bez LifecycleOwnera.

## Kluczowe decyzje
- **Nie da się osadzić cudzej aplikacji (natywny ChatGPT) w naszym oknie** — Android tego nie pozwala.
  Dlatego domyślnie WebView ze stroną; natywna aplikacja tylko w trybie eksperymentalnym (podział ekranu).
- **Fokus**: panel jest focusable po dotknięciu (klawiatura działa), po dotknięciu poza nim
  dostaje FLAG_NOT_FOCUSABLE (czytnik odzyskuje fokus). FLAG_NOT_TOUCH_MODAL — dotyk poza panelem idzie do app pod spodem.
- **Minimalizacja** = `removeView` panelu; obiekt WebView żyje dalej, więc rozmowa i odpowiedzi w toku zostają.
- **E-ink**: brak animacji (`windowAnimations = 0`), czarne obramowania 2–3 dp, duże cele dotyku (48 dp).
- UA bez `; wv`. Logowanie Google w WebView jest blokowane przez Google (`disallowed_useragent`) — logowanie e-mailem.
- Wersjonowanie: przy każdej nowej paczce podbij `versionCode`/`versionName` w `app/build.gradle.kts`
  (oraz tekst wersji w MainActivity) i nazwę paczki `FloatChat_vN`.

## Budowanie
```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Wymaga JDK 17+ i Android SDK (platforma 35). `local.properties` z `sdk.dir=...` tworzy Android Studio
albo ustaw zmienną `ANDROID_HOME`.

Logi: `adb logcat | grep -i floatchat`

## Status
v8 (8.0.0) — wykrywanie zamrożonej (wyłączonej) natywnej aplikacji, automatyczny zapas: okno strony;
skrót do ustawień aplikacji (menu bąbla i ekran ustawień).
v7 (7.0.0) — bąbel: 1× dotknięcie = otwórz okno, 2× = menu (okno 400 ms na drugie dotknięcie).
v6 (6.0.0) — menu bąbla w trybie natywnym (otwórz/zmniejsz do okienka, rozmiar mały/średni/duży,
wróć do czytnika, okno strony); czytnik wykrywany z referrera „Zapytaj AI” lub wybierany ręcznie.
v5 (5.0.0) — przyciski otwierające stockowe „Informacje o urządzeniu” i „Opcje programisty”
(Boox ukrywa numer kompilacji w swojej nakładce).
v4 (4.0.0) — natywna aplikacja w pływającym oknie: ukryte ActivityOptions.setLaunchWindowingMode(5)
przez HiddenApiBypass; wymaga włączonych okien swobodnych w opcjach programisty. Wybór: okno / podział ekranu.
v3 (3.0.0) — lepsze wykrywanie natywnej aplikacji (QUERY_ALL_PACKAGES, aplikacje zamrożone przez
Boox, wyszukiwanie po nazwie/etykiecie, ręczny wybór aplikacji z listy).
v2 (2.0.0) — build w chmurze (GitHub Actions), stały klucz podpisu; kompiluje się, testowane na Boox.

## Build w chmurze (bez komputera)
`.github/workflows/build.yml`: każdy push na `main` buduje APK i publikuje go jako GitHub Release
(`FloatChat-<wersja>-build<N>.apk`). Na Boox: Releases → pobierz APK → zainstaluj.
Podpis: `keystore/floatchat.jks` (hasło `floatchat`) — celowo w repo, by aktualizacje się instalowały.
Repo: https://github.com/JacekWalczak92/onyx_flow_app

## Pomysły na kolejne wersje
- Onyx SDK (`com.onyx.android.sdk:onyxsdk-device`) — wymuszanie odświeżenia A2/Regal przy przeciąganiu okna.
- Szybkie prompty w pasku (Przetłumacz / Wyjaśnij / Streszczenie).
- Przyciąganie bąbla do krawędzi ekranu.
- Tryb ciemny strony (wymuszenie jasnego motywu przez `WebSettingsCompat.setAlgorithmicDarkeningAllowed(false)`).
