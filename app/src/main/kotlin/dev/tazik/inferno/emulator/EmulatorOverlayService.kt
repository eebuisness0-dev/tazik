package dev.tazik.inferno.emulator

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import dev.tazik.inferno.MainActivity
import dev.tazik.inferno.R
import dev.tazik.inferno.TazikEngine
import dev.tazik.inferno.data.DeviceStore
import dev.tazik.inferno.data.VirtualDevice
import dev.tazik.inferno.engine.QemuBridge
import kotlin.math.min
import dev.tazik.inferno.engine.L

/**
 * Virtual Phone Mode: маленькое плавающее окно с картинкой VNC,
 * можно перетаскивать, край экрана — магнит (прилипание).
 */
class EmulatorOverlayService : Service() {

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
        private const val CHANNEL_ID = "tazik-overlay"
        private const val NOTIF_ID = 2
        private const val SNAP = 24f
    }

    private lateinit var wm: WindowManager
    private var overlayView: OverlayPhoneView? = null
    private var device: VirtualDevice? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID)
            ?: DeviceStore.get(this).devices.value.firstOrNull()?.id
            ?: run { stopSelf(); return START_NOT_STICKY }
        device = DeviceStore.get(this).devices.value.firstOrNull { it.id == deviceId }

        startForeground(CHANNEL_ID, NOTIF_ID)
        ensureOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        overlayView?.stop()
        overlayView = null
        runCatching { wm.removeView(overlayView) }
        super.onDestroy()
    }

    private fun startForeground(channel: String, id: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channel, "Virtual Phone Mode", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Tazik: Virtual Phone")
            .setContentText(L("Окно iOS поверх приложений"))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(id, n)
    }

    private val layoutParams: WindowManager.LayoutParams
        get() {
            val dev = device
            val dm = resources.displayMetrics
            val w = ((dev?.panel?.widthPx ?: 828) * 0.45f).toInt().coerceAtLeast(240)
            val h = w * (dev?.panel?.heightPx ?: 1792) / (dev?.panel?.widthPx ?: 828)
            return WindowManager.LayoutParams(
                w,
                h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 200
            }
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureOverlay() {
        if (overlayView != null) return
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = OverlayPhoneView(this, device)
        overlayView = view
        val lp = layoutParams
        var downAt = 0L
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = System.currentTimeMillis()
                    touchX = ev.rawX
                    touchY = ev.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - touchX
                    val dy = ev.rawY - touchY
                    touchX = ev.rawX
                    touchY = ev.rawY
                    wm.updateViewLayout(v, (v.layoutParams as WindowManager.LayoutParams).apply {
                        x += dx.toInt()
                        y += dy.toInt()
                    })
                }
                MotionEvent.ACTION_UP -> {
                    // магнит к краю
                    val lp2 = v.layoutParams as WindowManager.LayoutParams
                    val snapX = if (lp2.x < resources.displayMetrics.widthPixels / 2f)
                        24 else resources.displayMetrics.widthPixels - v.width - 24
                    if (min(snapX - lp2.x, lp2.x - snapX) < SNAP * 4) {
                        lp2.x = snapX
                        runCatching { wm.updateViewLayout(v, lp2) }
                    }
                }
            }
            true
        }
        runCatching { wm.addView(view, lp) }
    }
}

private class OverlayPhoneView(
    context: Context,
    val device: VirtualDevice?,
) : View(context) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = android.graphics.Color.argb(200, 40, 44, 52)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.argb(180, 12, 14, 18)
    }
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = true
    private var dst = RectF(0f, 0f, 1f, 1f)

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val state = TazikEngine.model.qemuState.value
            if (state is QemuBridge.State.Stopped || state is QemuBridge.State.Failed) {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                EmulatorOverlayServiceUtil.stopOverlay(context)
                return
            }
            val b = TazikEngine.model.picture.image.value
            if (b != null) {
                layoutImage(b)
                invalidate()
            }
            handler.postDelayed(this, 33)
        }
    }

    private fun layoutImage(b: Bitmap) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 1f || vh <= 1f) return
        val scale = min(vw / b.width, vh / b.height)
        val w = b.width * scale
        val h = b.height * scale
        dst = RectF(0f, 0f, w, h)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 28f, 28f, shadowPaint)
        val b = TazikEngine.model.picture.image.value
        if (b != null && dst.width() > 1f) {
            canvas.save()
            canvas.clipRect(dst)
            canvas.drawBitmap(b, null, dst, paint)
            canvas.restore()
        } else {
            canvas.drawText("…", width / 2f, height / 2f, borderPaint)
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 28f, 28f, borderPaint)
    }
}

private object EmulatorOverlayServiceUtil {
    fun stopOverlay(context: Context) {
        context.stopService(Intent(context, EmulatorOverlayService::class.java))
    }
}
