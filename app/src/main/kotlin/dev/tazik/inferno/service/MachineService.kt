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
import dev.tazik.inferno.TazikEngine
import dev.tazik.inferno.engine.L
import dev.tazik.inferno.engine.RestoreSession
import kotlinx.coroutines.launch

class MachineService : LifecycleService() {
    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // Стадия восстановления — в уведомлении: экранов четыре, шторка —
        // та, что видна всегда. Пока машина просто поднята, текст прежний;
        // как начался рестор, человек видит этап и процент, не открывая
        // приложения.
        lifecycleScope.launch {
            RestoreSession.shared.stage.collect {
                notifyStage(it)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                TazikEngine.model.shutdown()
                stopSelf()
            }
            else -> {
                startForegroundCompat()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MachineService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.machine_service_title))
            .setContentText(stageText(RestoreSession.shared.stage.value))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
        started = true
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    /** Текст уведомления под текущую стадию восстановления. */
    private fun stageText(stage: RestoreSession.Stage): String = when (stage) {
        is RestoreSession.Stage.Booting -> L("Восстановление: машина грузится")
        is RestoreSession.Stage.WaitingForDevice -> L("Восстановление: жду USB гостя")
        is RestoreSession.Stage.Ready -> L("Восстановление: %@ готов", stage.type)
        is RestoreSession.Stage.Restoring ->
            if (stage.done > 0) {
                L("Восстановление: %@, %.0f %%", stage.what, stage.done * 100)
            } else {
                L("Восстановление: %@", stage.what)
            }
        is RestoreSession.Stage.Done -> L("Восстановление завершено")
        is RestoreSession.Stage.Failed -> L("Восстановление не удалось: %@", stage.why)
        RestoreSession.Stage.Idle -> getString(R.string.machine_service_text)
    }

    /** Обновляет текст уведомления, когда стадия поменялась. */
    private fun notifyStage(stage: RestoreSession.Stage) {
        if (!started) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.machine_service_title))
            .setContentText(stageText(stage))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        manager.notify(NOTIF_ID, notif)
    }

    override fun onDestroy() {
        // После останова службы ни одна стадия не должна рисовать уведомление.
        started = false
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, getString(R.string.emulator_notification_channel), NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    companion object {
        const val CHANNEL = "tazik_vm_running"
        const val NOTIF_ID = 2001
        const val ACTION_STOP = "dev.tazik.inferno.action.STOP_MACHINE"

        /** Уведомление поднято: пока нет, обновления текста ни к чему. */
        @Volatile
        private var started = false

        fun ensureForeground(context: Context) {
            val intent = Intent(context, MachineService::class.java)
            // Система может отвергнуть запуск службы из фона (Android 12+,
            // MIUI при погашенном экране): рестарт рестора уронил весь
            // процесс. Машина работает и без уведомления — глотаем отказ.
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                android.util.Log.w("MachineService", L("startForegroundService отклонён: %s", e.message))
            }
        }

        fun stopForegroundQuiet(context: Context) {
            context.stopService(Intent(context, MachineService::class.java))
        }
    }
}


