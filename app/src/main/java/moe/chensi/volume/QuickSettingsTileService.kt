package moe.chensi.volume

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.util.Log

class QuickSettingsTileService : TileService() {

    companion object {
        private const val TAG = "QuickSettingsTile"
    }

    override fun onClick() {
        super.onClick()
        launchActivity(Intent(this, PopupActivity::class.java))
    }

    private fun launchActivity(intent: Intent) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }.onFailure { Log.w(TAG, "Failed to launch popup", it) }
    }
}
