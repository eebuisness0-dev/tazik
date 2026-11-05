package dev.tazik.inferno.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.tazik.inferno.MainActivity
import dev.tazik.inferno.R
import dev.tazik.inferno.data.DownloadPhase
import dev.tazik.inferno.data.DownloadToken
import dev.tazik.inferno.data.IpswDownloader
import kotlinx.coroutines.launch

class IpswDownloadService : LifecycleService() {
    private val token = DownloadToken()
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CANCEL -> {
                token.cancelled = true
                stopSelf()
            }
            ACTION_START -> {
                startForegroundCompat(0)
                if (!started) {
                    started = true
                    val url = intent.getStringExtra(EXTRA_URL) ?: ""
                    val version = intent.getStringExtra(EXTRA_VERSION) ?: ""
                    val buildId = intent.getStringExtra(EXTRA_BUILD) ?: ""
                    val sha1 = intent.getStringExtra(EXTRA_SHA1) ?: ""
                    lifecycleScope.launch {
                        val dl = IpswDownloader(applicationContext) { phase ->
                            if (phase is DownloadPhase.Downloading) {
                                startForegroundCompat(
                                    if (phase.bytesTotal > 0) (phase.bytesDone * 100 / phase.bytesTotal).toInt() else 0
                                )
                            }
                        }
                        dl.download(url, version, buildId, sha1, token)
                        stopSelf()
                    }
                }
            }
            else -> startForegroundCompat(0)
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(percent: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.download_notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, IpswDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.download_notification_title))
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_cancel), cancel)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        const val CHANNEL = "tazik_ipsw_download"
        const val NOTIF_ID = 3001
        const val ACTION_START = "dev.tazik.inferno.action.START_IPSW"
        const val ACTION_CANCEL = "dev.tazik.inferno.action.CANCEL_IPSW"
        const val EXTRA_URL = "url"
        const val EXTRA_VERSION = "version"
        const val EXTRA_BUILD = "buildid"
        const val EXTRA_SHA1 = "sha1"

        fun start(context: Context, url: String, version: String, buildId: String, sha1: String) {
            val intent = Intent(context, IpswDownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_VERSION, version)
                .putExtra(EXTRA_BUILD, buildId)
                .putExtra(EXTRA_SHA1, sha1)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, IpswDownloadService::class.java).setAction(ACTION_CANCEL)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
