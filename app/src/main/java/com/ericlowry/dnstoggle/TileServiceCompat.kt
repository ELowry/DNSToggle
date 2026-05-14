package com.ericlowry.dnstoggle

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService

object TileServiceCompat {
    fun requestListeningState(context: Context, componentName: ComponentName) {
        TileService.requestListeningState(context, componentName)
    }
}
