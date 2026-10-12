package pl.jack.floatchat.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

/**
 * Niewidoczna aktywność proszącą o dostęp do mikrofonu. Okno czatu jest nakładką (nie aktywnością),
 * więc samo nie może pokazać systemowego pytania o uprawnienie.
 */
class MicPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            close()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Toast.makeText(
            this,
            if (granted) "Mikrofon włączony — dotknij nagrywania jeszcze raz"
            else "Bez dostępu do mikrofonu nagrywanie w oknie strony nie zadziała",
            Toast.LENGTH_LONG,
        ).show()
        close()
    }

    private fun close() {
        finish()
        overridePendingTransition(0, 0)
    }
}
