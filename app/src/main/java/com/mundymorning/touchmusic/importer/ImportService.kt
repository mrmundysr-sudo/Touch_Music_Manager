package com.mundymorning.touchmusic.importer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mundymorning.touchmusic.MainActivity
import com.mundymorning.touchmusic.R
import com.mundymorning.touchmusic.TouchMusicApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps imports running while the app is in the background, with a progress notification. */
class ImportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = (application as TouchMusicApplication).container.importer
        createChannel()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            // Without foreground status the import still runs while the app is open.
        }
        if (intent?.action == ACTION_CANCEL) controller.cancel()

        if (worker?.isActive != true) {
            worker = scope.launch {
                val manager = getSystemService(NotificationManager::class.java)
                val updates = launch {
                    controller.progress.collect { p ->
                        if (p != null) manager.notify(NOTIFICATION_ID, build(p))
                        delay(700)
                    }
                }
                try {
                    controller.runAll()
                } finally {
                    updates.cancel()
                    ServiceCompat.stopForeground(this@ImportService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    controller.lastSummary?.let { manager.notify(DONE_NOTIFICATION_ID, buildDone(it)) }
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Music import", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of music imports"
            },
        )
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(p: ImportProgress?): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ImportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, "Cancel", cancel)
        if (p == null) {
            builder.setContentTitle("Preparing import…").setProgress(0, 0, true)
        } else {
            builder.setContentTitle(if (p.cancelling) "Cancelling import…" else "Importing music: ${p.finishedSources} of ${p.totalSources}")
                .setContentText("${p.imported} added · ${p.skipped} skipped · ${p.failed + p.failedSources} failed")
                .setSubText(p.currentSource)
                .setProgress(p.totalSources.coerceAtLeast(1), p.finishedSources, false)
        }
        return builder.build()
    }

    private fun buildDone(summary: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle("Music import finished")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    companion object {
        const val ACTION_CANCEL = "com.mundymorning.touchmusic.CANCEL_IMPORT"
        private const val CHANNEL = "imports"
        private const val NOTIFICATION_ID = 41
        private const val DONE_NOTIFICATION_ID = 42
    }
}
