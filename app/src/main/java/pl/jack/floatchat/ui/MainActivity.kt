package pl.jack.floatchat.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.jack.floatchat.data.Diagnostics
import pl.jack.floatchat.data.Prefs
import pl.jack.floatchat.data.Providers
import pl.jack.floatchat.overlay.NativeAppLauncher
import pl.jack.floatchat.overlay.OverlayService
import pl.jack.floatchat.overlay.Watchdog

/** Ekran startowy: uprawnienia, wybór serwisu, uruchomienie bąbla. Styl czarno-biały pod e-ink. */
class MainActivity : ComponentActivity() {

    private var refreshTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color.Black, onPrimary = Color.White,
                    background = Color.White, surface = Color.White,
                    onBackground = Color.Black, onSurface = Color.Black,
                    outline = Color.Black,
                )
            ) {
                SettingsScreen(prefs, refreshTick)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick++ // odśwież status uprawnień po powrocie z ustawień systemu
        Watchdog.ensureRunning(this, "otwarcie ustawień")
    }

    @Composable
    private fun SettingsScreen(prefs: Prefs, tick: Int) {
        val canOverlay = remember(tick) { Settings.canDrawOverlays(this) }
        val canNotify = remember(tick) {
            Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        var providerId by remember { mutableStateOf(prefs.providerId) }
        var customUrl by remember { mutableStateOf(prefs.customUrl) }
        var prefix by remember { mutableStateOf(prefs.promptPrefix) }
        var autoSend by remember { mutableStateOf(prefs.autoSend) }
        var nativeMode by remember { mutableStateOf(prefs.nativeMode) }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("FloatChat", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Pływające okno czatu AI nad czytnikiem. Wersja 10.0.0")

            Section("1. Uprawnienia")
            StatusRow("Wyświetlanie nad innymi aplikacjami", canOverlay) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            StatusRow("Powiadomienia (do pracy w tle)", canNotify) {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }

            Section("2. Serwis czatu")
            (Providers.all.map { it.id to it.label } + ("custom" to "Własny adres")).forEach { (id, label) ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = providerId == id) {
                        providerId = id; prefs.providerId = id
                    }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = providerId == id, onClick = null)
                    Text(label, Modifier.padding(start = 12.dp), fontSize = 18.sp)
                }
            }
            if (providerId == "custom") {
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it; prefs.customUrl = it },
                    label = { Text("Adres URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Section("3. Zaznaczony tekst")
            OutlinedTextField(
                value = prefix,
                onValueChange = { prefix = it; prefs.promptPrefix = it },
                label = { Text("Prefiks (np. „Wyjaśnij prościej:”) — opcjonalnie") },
                modifier = Modifier.fillMaxWidth(),
            )
            ToggleRow("Wyślij od razu po wklejeniu", autoSend) { autoSend = it; prefs.autoSend = it }

            Section("4. Tryb natywnej aplikacji (eksperymentalny)")
            var manualPkg by remember { mutableStateOf(prefs.nativePackage) }
            var showPicker by remember { mutableStateOf(false) }
            val pkg = remember(tick, providerId, manualPkg) { NativeAppLauncher.resolvePackage(this@MainActivity, prefs) }
            val enabledApp = pkg != null && NativeAppLauncher.isEnabled(this@MainActivity, pkg)
            Text(
                when {
                    pkg == null -> "Nie znaleziono natywnej aplikacji tego serwisu. Wybierz ją ręcznie z listy."
                    !enabledApp -> "✗ Znaleziono: ${NativeAppLauncher.labelOf(this@MainActivity, pkg)} ($pkg), " +
                        "ale Boox ją zamroził. Uruchom ją raz z pulpitu i wyłącz dla niej " +
                        "„Blokowanie aplikacji” (przycisk niżej)."
                    else -> "Aplikacja: ${NativeAppLauncher.labelOf(this@MainActivity, pkg)} ($pkg)" +
                        (if (manualPkg.isBlank()) " — wykryta automatycznie." else " — wybrana ręcznie.") +
                        "\nBąbel otworzy ją obok czytnika (podział ekranu / okno swobodne). Zależy od firmware Boox."
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showPicker = true }, border = BorderStroke(2.dp, Color.Black)) {
                    Text("Wybierz aplikację z listy")
                }
                if (pkg != null) {
                    OutlinedButton(
                        onClick = { NativeAppLauncher.openAppDetails(this@MainActivity, pkg) },
                        border = BorderStroke(2.dp, Color.Black),
                    ) { Text("Ustawienia aplikacji") }
                }
                if (manualPkg.isNotBlank()) {
                    OutlinedButton(
                        onClick = { manualPkg = ""; prefs.nativePackage = "" },
                        border = BorderStroke(2.dp, Color.Black),
                    ) { Text("Wykrywaj automatycznie") }
                }
            }
            Text(
                "Jeśli czasem nie da się otworzyć aplikacji: Boox zamraża ją przy uśpieniu. " +
                    "W „Blokowanie aplikacji” Boox przełącz ją oraz FloatChat na OFF. Gdy aplikacja jest " +
                    "niedostępna, FloatChat sam otworzy okno strony."
            )
            OutlinedButton(
                onClick = { NativeAppLauncher.openBooxFreezeSettings(this@MainActivity, pkg ?: packageName) },
                border = BorderStroke(2.dp, Color.Black),
            ) { Text("Blokowanie aplikacji (Boox)") }
            ToggleRow("Otwieraj natywną aplikację zamiast okna", nativeMode && pkg != null, enabled = pkg != null) {
                nativeMode = it; prefs.nativeMode = it
            }
            var windowMode by remember { mutableStateOf(prefs.windowMode) }
            val freeform = remember(tick) { NativeAppLauncher.isFreeformEnabled(this@MainActivity) }
            Text("Sposób otwarcia:", fontWeight = FontWeight.Bold)
            listOf("freeform" to "Pływające okno (okna swobodne)", "split" to "Podział ekranu").forEach { (id, label) ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = windowMode == id) {
                        windowMode = id; prefs.windowMode = id
                    }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = windowMode == id, onClick = null)
                    Text(label, Modifier.padding(start = 12.dp), fontSize = 17.sp)
                }
            }
            if (windowMode == "freeform") {
                Text(
                    if (freeform) "✓ Okna swobodne są włączone w systemie."
                    else "✗ Okna swobodne są wyłączone — aplikacja otworzy się na pełnym ekranie.\n" +
                        "Włącz: Opcje programisty → „Enable freeform windows” (oraz „Force activities to be resizable”), " +
                        "potem uruchom Boox ponownie. Przed testem zamknij ChatGPT z listy ostatnich aplikacji."
                )
                Text(
                    "Krok 1: „Informacje o urządzeniu” → stuknij 7× w „Numer kompilacji” (Build number).\n" +
                        "Krok 2: „Opcje programisty” → włącz okna swobodne i wymuszanie zmiany rozmiaru → restart."
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            openSystemScreen(
                                Settings.ACTION_DEVICE_INFO_SETTINGS,
                                "com.android.settings.Settings\$MyDeviceInfoActivity",
                            )
                        },
                        border = BorderStroke(2.dp, Color.Black),
                    ) { Text("Informacje o urządzeniu") }
                    OutlinedButton(
                        onClick = {
                            openSystemScreen(
                                Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                                "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity",
                            )
                        },
                        border = BorderStroke(2.dp, Color.Black),
                    ) { Text("Opcje programisty") }
                }
            }
            if (showPicker) {
                val apps = remember { NativeAppLauncher.listLaunchableApps(this@MainActivity) }
                AlertDialog(
                    onDismissRequest = { showPicker = false },
                    confirmButton = { TextButton(onClick = { showPicker = false }) { Text("Anuluj") } },
                    title = { Text("Wybierz aplikację czatu") },
                    text = {
                        LazyColumn(Modifier.height(420.dp)) {
                            items(apps) { app ->
                                Column(
                                    Modifier.fillMaxWidth().clickable {
                                        manualPkg = app.pkg; prefs.nativePackage = app.pkg; showPicker = false
                                    }.padding(vertical = 10.dp)
                                ) {
                                    Text(app.label, fontSize = 18.sp)
                                    Text(app.pkg, fontSize = 13.sp)
                                }
                                HorizontalDivider(color = Color.Black)
                            }
                        }
                    },
                    containerColor = Color.White,
                )
            }

            if (nativeMode) {
                Text("Rozmiar okienka:", fontWeight = FontWeight.Bold)
                var windowSize by remember { mutableStateOf(prefs.windowSize) }
                listOf("small" to "Małe", "medium" to "Średnie", "large" to "Duże").forEach { (id, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = windowSize == id) {
                            windowSize = id; prefs.windowSize = id
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = windowSize == id, onClick = null)
                        Text(label, Modifier.padding(start = 12.dp), fontSize = 17.sp)
                    }
                }

                var readerPkg by remember { mutableStateOf(prefs.readerPackage) }
                var showReaderPicker by remember { mutableStateOf(false) }
                val resolvedReader = remember(tick, readerPkg) { NativeAppLauncher.resolveReader(this@MainActivity, prefs) }
                Text(
                    "Czytnik (przycisk „Wróć do…” w menu bąbla): " +
                        (resolvedReader?.let { "${NativeAppLauncher.labelOf(this@MainActivity, it)} ($it)" }
                            ?: "nie wykryto — wybierz z listy lub użyj raz „Zapytaj AI” w czytniku")
                )
                OutlinedButton(onClick = { showReaderPicker = true }, border = BorderStroke(2.dp, Color.Black)) {
                    Text("Wybierz czytnik z listy")
                }
                Text("Bąbel: jedno dotknięcie otwiera okno czatu, dwa dotknięcia — menu (zmniejsz okno, rozmiar, powrót do czytnika).")
                if (showReaderPicker) {
                    AppPickerDialog("Wybierz czytnik", onDismiss = { showReaderPicker = false }) { app ->
                        readerPkg = app.pkg; prefs.readerPackage = app.pkg; showReaderPicker = false
                    }
                }
            }

            Section("5. Start")
            OutlinedButton(
                onClick = { OverlayService.start(this@MainActivity, OverlayService.ACTION_SHOW_BUBBLE); moveTaskToBack(true) },
                enabled = canOverlay,
                border = BorderStroke(2.dp, Color.Black),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Pokaż bąbel czatu", fontSize = 18.sp) }
            OutlinedButton(
                onClick = { startService(Intent(this@MainActivity, OverlayService::class.java).setAction(OverlayService.ACTION_STOP)) },
                border = BorderStroke(2.dp, Color.Black),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Zamknij bąbel", fontSize = 18.sp) }

            Section("6. Działanie w tle")
            val pm = remember { getSystemService(android.os.PowerManager::class.java) }
            val ignoringBattery = remember(tick) { pm.isIgnoringBatteryOptimizations(packageName) }
            Text(
                if (ignoringBattery) "✓ FloatChat jest wyłączony z optymalizacji baterii."
                else "✗ System może usypiać FloatChat — bąbel będzie znikał."
            )
            if (!ignoringBattery) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                            )
                        }
                    },
                    border = BorderStroke(2.dp, Color.Black),
                ) { Text("Wyłącz optymalizację baterii") }
            }
            Text(
                "Na Boox najważniejsze: Ustawienia → Aplikacje → „Blokowanie aplikacji” — przełącz FloatChat " +
                    "(i natywny czat) na OFF. Włączona blokada = Boox przy uśpieniu zatrzymuje i zamraża " +
                    "aplikację; wtedy znika bąbel i strażnik nie może go przywrócić. " +
                    "Zamykanie bąbla: 2× bąbel → „Zamknij FloatChat”."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { NativeAppLauncher.openBooxFreezeSettings(this@MainActivity, packageName) },
                    border = BorderStroke(2.dp, Color.Black),
                ) { Text("Blokowanie aplikacji (Boox)") }
                OutlinedButton(
                    onClick = { NativeAppLauncher.openAppDetails(this@MainActivity, packageName) },
                    border = BorderStroke(2.dp, Color.Black),
                ) { Text("Ustawienia aplikacji FloatChat") }
            }

            Section("Diagnostyka")
            var diagTick by remember { mutableIntStateOf(0) }
            val log = remember(tick, diagTick) { Diagnostics.logText(this@MainActivity) }
            val crash = remember(tick, diagTick) { Diagnostics.crashText(this@MainActivity) }
            Text(if (log.isBlank()) "Brak zdarzeń." else log, fontSize = 13.sp)
            if (crash.isNotBlank()) {
                Text("Ostatni błąd:", fontWeight = FontWeight.Bold)
                Text(crash, fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = { Diagnostics.clear(this@MainActivity); diagTick++ },
                border = BorderStroke(2.dp, Color.Black),
            ) { Text("Wyczyść dziennik") }

            Section("Jak używać")
            Text(
                "• W czytniku zaznacz tekst → w menu wybierz „Zapytaj AI” (lub Udostępnij → FloatChat). " +
                    "Okno otworzy się z wklejonym tekstem.\n" +
                    "• Albo skopiuj tekst i w oknie dotknij „Wklej”.\n" +
                    "• „—” minimalizuje do bąbla, rozmowa zostaje. Dwa dotknięcia lub przytrzymanie bąbla = menu (tam też „Zamknij FloatChat”).\n" +
                    "• Przeciągaj okno za pasek tytułu, zmieniaj rozmiar uchwytem ◢. Dotknij nazwy serwisu, aby przełączyć.\n" +
                    "• Logowanie: użyj e-maila i hasła — logowanie „przez Google” w oknie WebView jest blokowane przez Google.\n" +
                    "• Kafelek „FloatChat” możesz dodać do szybkich ustawień."
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    /**
     * Otwiera standardowy ekran ustawień Androida (Boox chowa część z nich w swojej nakładce).
     * Najpierw jawnie aplikacja com.android.settings, potem zwykła akcja systemowa.
     */
    private fun openSystemScreen(action: String, stockActivity: String) {
        val attempts = listOf(
            Intent().setClassName("com.android.settings", stockActivity),
            Intent(action).setPackage("com.android.settings"),
            Intent(action),
        )
        for (i in attempts) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(i) }.isSuccess) return
        }
        Toast.makeText(this, "Nie udało się otworzyć ustawień systemu", Toast.LENGTH_LONG).show()
    }

    @Composable
    private fun AppPickerDialog(
        title: String,
        onDismiss: () -> Unit,
        onPick: (NativeAppLauncher.AppEntry) -> Unit,
    ) {
        val apps = remember { NativeAppLauncher.listLaunchableApps(this@MainActivity) }
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
            title = { Text(title) },
            text = {
                LazyColumn(Modifier.height(420.dp)) {
                    items(apps) { app ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 10.dp)) {
                            Text(app.label, fontSize = 18.sp)
                            Text(app.pkg, fontSize = 13.sp)
                        }
                        HorizontalDivider(color = Color.Black)
                    }
                }
            },
            containerColor = Color.White,
        )
    }

    @Composable
    private fun Section(title: String) {
        Spacer(Modifier.height(8.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        HorizontalDivider(thickness = 2.dp, color = Color.Black)
    }

    @Composable
    private fun StatusRow(label: String, ok: Boolean, onFix: () -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text((if (ok) "✓ " else "✗ ") + label, Modifier.weight(1f), fontSize = 17.sp)
            if (!ok) OutlinedButton(onClick = onFix, border = BorderStroke(2.dp, Color.Black)) { Text("Nadaj") }
        }
    }

    @Composable
    private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 17.sp)
            Switch(
                checked = checked, onCheckedChange = onChange, enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White, checkedTrackColor = Color.Black,
                    uncheckedThumbColor = Color.Black, uncheckedTrackColor = Color.White,
                    uncheckedBorderColor = Color.Black,
                ),
            )
        }
    }
}
