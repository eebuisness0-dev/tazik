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
import dev.tazik.inferno.engine.QemuBridge
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
                notifyStage()
            }
        }
        // машина умерла — служба не нужна
        lifecycleScope.launch {
            var wasRunning = false
            QemuBridge.shared.state.collect {
                when (it) {
                    is QemuBridge.State.Running -> {
                        wasRunning = true
                        stopWhenUp = false
                        if (started) notifyStage()
                    }
                    is QemuBridge.State.Stopped, is QemuBridge.State.Failed -> {
                        // Stopped бывает и от прошлого прогона: гасим службу
                        // только после того, как машина в этой жизни вставала.
                        if (!wasRunning) return@collect
                        wasRunning = false
                        // рестор сам поднимает машину — служба нужна дальше
                        if (RestoreSession.shared.stage.value.isBusy) return@collect
                        // stopSelf до startForeground роняет процесс
                        if (started) stopSelf() else stopWhenUp = true
                    }
                    QemuBridge.State.Idle -> {}
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                startForegroundCompat()
                TazikEngine.model.shutdown()
                stopSelf()
            }
            ACTION_QUIET_STOP -> {
                startForegroundCompat()
                stopSelf()
            }
            else -> {
                startForegroundCompat()
                if (stopWhenUp) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        started = true
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotif())
        }
    }

    /** Одно место сборки уведомления: с кнопкой стопа в любой стадии. */
    private fun buildNotif(): Notification {
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
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.machine_service_title))
            .setContentText(stageText(RestoreSession.shared.stage.value))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
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
    private fun notifyStage() {
        if (!started) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIF_ID, buildNotif())
    }

    override fun onDestroy() {
        // После останова службы ни одна стадия не должна рисовать уведомление.
        started = false
        stopWhenUp = false
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // Тихий канал (LOW) прятался в свёрнутый раздел шторки: «не всегда
        // появляется». Перезаводим с обычной важностью и системным звуком:
        // писк — один раз при появлении (onlyAlertOnce), стадии молчат.
        for (old in listOf("tazik_vm_running", "tazik_machine_v2")) {
            nm.deleteNotificationChannel(old)
        }
        val ch = NotificationChannel(CHANNEL, getString(R.string.emulator_notification_channel), NotificationManager.IMPORTANCE_DEFAULT)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
    }

    companion object {
        const val CHANNEL = "tazik_machine_v3"
        const val NOTIF_ID = 2001
        const val ACTION_STOP = "dev.tazik.inferno.action.STOP_MACHINE"
        const val ACTION_QUIET_STOP = "dev.tazik.inferno.action.QUIET_STOP_MACHINE"

        /** Гасит службу без выключения машины — отказ запуска и т. п. */
        fun stopQuiet(context: Context) {
            try {
                context.startService(
                    Intent(context, MachineService::class.java).setAction(ACTION_QUIET_STOP),
                )
            } catch (e: Exception) {
                android.util.Log.w("MachineService", L("startService отклонён: %s", e.message))
            }
        }

        /** Уведомление поднято: пока нет, обновления текста ни к чему. */
        @Volatile
        private var started = false

        /** Служба обязана подняться, но машина уже мертва: гасим после startForeground. */
        @Volatile
        private var stopWhenUp = false

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

    }
}

