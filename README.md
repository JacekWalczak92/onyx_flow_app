# FloatChat v9

Pływające okno czatu AI dla Onyx Boox. Czytasz książkę, zaznaczasz fragment → **Zapytaj AI** →
obok otwiera się okno z ChatGPT (lub Claude/Gemini/Perplexity) z wklejonym tekstem. Piszesz,
minimalizujesz do bąbla „—" i wracasz do czytania. Rozmowa czeka w bąblu.

## Instalacja bez komputera (GitHub Actions)
Każdy push na `main` buduje APK w chmurze i publikuje go w **Releases**:
https://github.com/JacekWalczak92/onyx_flow_app/releases
Na Boox otwórz ten link w przeglądarce, pobierz najnowszy `FloatChat-…apk` i zainstaluj
(za pierwszym razem zezwól przeglądarce na instalację nieznanych aplikacji).
Błędy kompilacji: zakładka **Actions** → nieudany build → log.

## Instalacja z komputera
1. Otwórz folder w VS Code (zalecane rozszerzenia podpowie `.vscode/extensions.json`).
2. Upewnij się, że masz JDK 17+ i Android SDK (`ANDROID_HOME` lub `local.properties`).
3. `./gradlew assembleDebug`
4. Boox: Ustawienia → Aplikacje → włącz debugowanie USB, potem
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`
5. W aplikacji nadaj uprawnienie „Wyświetlanie nad innymi aplikacjami" i powiadomienia, dotknij **Pokaż bąbel czatu**.

## Obsługa
| Gest | Działanie |
|---|---|
| Dotknij bąbla | otwórz okno |
| Dotknij bąbla 2× lub przytrzymaj | menu (zmniejsz okno, rozmiar, powrót do czytnika, zamknij FloatChat) |
| Przeciągnij bąbel | przesuń |
| Pasek tytułu | przeciąganie okna; dotknięcie nazwy = następny serwis |
| **Wklej** | wkleja schowek do pola czatu |
| **—** | minimalizuj (rozmowa zostaje) |
| **◢** | zmiana rozmiaru |
| Wstecz | cofnij w stronie / minimalizuj |

**Boox:** w ustawieniach aplikacji FloatChat (Optymalizacja aplikacji / App Management) wyłącz
zamrażanie w tle i optymalizację baterii, inaczej system może zamknąć bąbel. Gdy mimo to zniknie,
strażnik (alarm co ~1 min) przywraca go automatycznie; dziennik zdarzeń jest w ustawieniach → Diagnostyka.

**Zamrażanie ChatGPT:** jeśli natywny ChatGPT czasem się nie otwiera, Boox zamroził go w tle.
Wyłącz zamrażanie i optymalizację baterii dla ChatGPT (2× bąbel → Ustawienia ChatGPT). Do tego czasu
FloatChat automatycznie otwiera okno strony.

**Logowanie:** zaloguj się e-mailem i hasłem. Logowanie „Kontynuuj z Google" w oknie WebView
jest blokowane przez Google.

## Tryb natywnej aplikacji — menu bąbla
Jedno dotknięcie bąbla otwiera okno czatu, **dwa dotknięcia** otwierają menu:
**Otwórz / zmniejsz** (przywraca okienko także z pełnego ekranu), **Małe / Duże okienko**,
**Wróć do czytnika**, **Okno strony**. Zamknięcie: menu → **Zamknij FloatChat** (przytrzymanie bąbla też otwiera menu).

## Natywna aplikacja ChatGPT?
Android nie pozwala wyświetlić innej aplikacji wewnątrz naszego pływającego okna. Opcja
„Otwieraj natywną aplikację" prosi system o otwarcie jej obok (podział ekranu / okno swobodne) —
działanie zależy od firmware Boox. Domyślny tryb (strona w oknie) działa wszędzie.

## Praca z Claude Code
`CLAUDE.md` opisuje architekturę i decyzje — Claude Code wczyta go automatycznie.
