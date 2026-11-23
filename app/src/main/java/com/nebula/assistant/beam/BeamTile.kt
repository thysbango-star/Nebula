package com.nebula.assistant.beam

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/** Quick Settings tile: opens Beam in the Notch from anywhere. */
class BeamTile : TileService() {
    override fun onClick() {
        val i = Intent(this, BeamActivity::class.java).putExtra("action", "open").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 11, i, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }
}
