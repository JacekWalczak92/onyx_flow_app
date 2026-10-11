package pl.jack.floatchat.overlay

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Kafelek w szybkich ustawieniach: włącza / wyłącza bąbel czatu. */
class BubbleTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = if (OverlayService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        if (OverlayService.isRunning) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        } else {
            OverlayService.start(this, OverlayService.ACTION_SHOW_BUBBLE)
        }
        qsTile?.apply {
            state = if (OverlayService.isRunning) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            updateTile()
        }
    }
}
