package app.silverpoint

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the app process alive while photos are being developed, so leaving the app right after the
 * shutter does not lose them. Does no work itself ([Developer] does); it watches the queue and
 * stops itself when nothing is pending (always after `startForeground`, as Android requires).
 */
class DevelopService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Developing photos", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Developing photos")
            .setOngoing(true)
            .build()
        runCatching { startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
        scope.launch {
            (application as SilverpointApp).developer.pending.first { it == 0 }
            // Stops only if no newer start came in meanwhile: stopping a start that has not reached
            // startForeground yet would crash the app.
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "develop"
        private const val ID = 1

        /** Starts the service if Android allows it right now; developing works without it, it just is not protected. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, DevelopService::class.java)) }
        }
    }
}
