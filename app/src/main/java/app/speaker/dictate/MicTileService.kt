package app.speaker.dictate

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: pull down the shade and tap "Dictate" from any app. */
class MicTileService : TileService() {

    companion object {
        fun requestUpdate(ctx: Context) {
            runCatching {
                requestListeningState(ctx, ComponentName(ctx, MicTileService::class.java))
            }
        }
    }

    override fun onTileAdded() { android.util.Log.i("Speaker.tile", "Tile added to Quick Settings") }
    override fun onTileRemoved() { android.util.Log.i("Speaker.tile", "Tile removed from Quick Settings") }

    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = when {
            !BubbleService.isRunning -> Tile.STATE_INACTIVE
            BubbleService.currentState == DictationEngine.State.IDLE -> Tile.STATE_INACTIVE
            else -> Tile.STATE_ACTIVE
        }
        tile.label = when (BubbleService.currentState) {
            DictationEngine.State.RECORDING -> "Stop & type"
            DictationEngine.State.WORKING -> "Transcribing…"
            DictationEngine.State.IDLE -> "Dictate"
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        android.util.Log.i("Speaker.tile", "Quick Settings tile tapped (bubbleRunning=${BubbleService.isRunning})")
        if (BubbleService.isRunning) {
            startService(Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_TOGGLE))
            return
        }
        // Service not running: open the app so the user can start it (and grant permissions).
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
