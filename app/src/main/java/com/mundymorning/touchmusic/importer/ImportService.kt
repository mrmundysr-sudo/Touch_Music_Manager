package com.mundymorning.touchmusic.importer

import android.app.Service
import android.content.Intent
import android.os.IBinder

class ImportService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
