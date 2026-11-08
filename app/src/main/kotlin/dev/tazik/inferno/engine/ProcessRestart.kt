package dev.tazik.inferno.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Process
import android.os.SystemClock

/** Гасит процесс целиком, приложение возвращает будильник.
 *
 * QEMU не переживает ни второй запуск, ни собственную уборку: тред-пул
 * блочного IO прыгает в чужую память (три прогона подряд). Чистый процесс —
 * единственный проверенный способ поднять машину заново.
 */
object ProcessRestart {

    /** `requestCode` различает поводы, чтобы будильники не затирали друг друга. */
    fun killAndReopen(requestCode: Int) {
        val app = EngineEnv.requireContext().applicationContext
        val pi = PendingIntent.getActivity(
            app, requestCode,
            Intent().setClassName(app, "dev.tazik.inferno.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        app.getSystemService(AlarmManager::class.java)
            ?.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 150, pi)
        Process.killProcess(Process.myPid())
    }
}
