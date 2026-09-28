package com.example.tracksim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat

class SimulationService : Service() {

    companion object {
        const val ACTION_START = "com.example.tracksim.action.START"
        const val ACTION_STOP = "com.example.tracksim.action.STOP"

        const val EXTRA_POINTS = "extra_points"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_LOOP = "extra_loop"

        private const val CHANNEL_ID = "track_sim_channel"
        private const val NOTIF_ID = 1001
        private const val TICK_MS = 100L

        @Volatile
        var isRunning: Boolean = false
            private set

        var listener: Listener? = null

        interface Listener {
            fun onStarted()
            fun onSample(sample: TrackEngine.Sample, elapsedSeconds: Double)
            fun onFinished()
            fun onError(message: String)
        }
    }

    private val engine = TrackEngine()
    private val handler = Handler(Looper.getMainLooper())
    private var mock: MockLocationController? = null

    private var speedKmh = 5.0
    private var elapsedSeconds = 0.0
    private var traveledMeters = 0.0
    private var lastTickMs = 0L
    private var ticking = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!ticking) return

            val now = SystemClock.elapsedRealtime()
            var dt = (now - lastTickMs) / 1000.0
            lastTickMs = now
            if (dt > 0.5) dt = 0.5
            if (dt <= 0.0) dt = 0.001

            elapsedSeconds += dt
            traveledMeters += (speedKmh / 3.6) * dt

            val sample = engine.sampleAt(traveledMeters)
            if (sample == null) {
                shutdown()
                return
            }
            traveledMeters = sample.traveled

            mock?.push(
                sample.lat, sample.lng,
                sample.bearing.toFloat(),
                (speedKmh / 3.6).toFloat()
            )

            listener?.onSample(sample, elapsedSeconds)
            updateNotification(sample)

            if (sample.finished) {
                listener?.onFinished()
                shutdown()
                return
            }

            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val pts = intent.getDoubleArrayExtra(EXTRA_POINTS)
                speedKmh = intent.getDoubleExtra(EXTRA_SPEED, 5.0)
                val loop = intent.getBooleanExtra(EXTRA_LOOP, false)

                if (pts == null || pts.size < 4) {
                    listener?.onError("至少需要 2 个路径点")
                    shutdown()
                    return START_NOT_STICKY
                }

                val list = ArrayList<GeoPoint>(pts.size / 2)
                var i = 0
                while (i + 1 < pts.size) {
                    list.add(GeoPoint(pts[i], pts[i + 1]))
                    i += 2
                }
                engine.setPoints(list)
                engine.loop = loop
                engine.speedKmh = speedKmh

                if (engine.totalLength < 1.0) {
                    listener?.onError("轨迹长度过短，请拉开路径点间距")
                    shutdown()
                    return START_NOT_STICKY
                }

                startForegroundCompat()

                val controller = MockLocationController(applicationContext)
                if (!controller.install()) {
                    listener?.onError(
                        "无法注册模拟位置。\n\n" +
                        "请先开启「开发者选项」，并在\n" +
                        "「选择模拟位置信息应用」中选中本应用，然后重试。"
                    )
                    shutdown()
                    return START_NOT_STICKY
                }
                mock = controller

                elapsedSeconds = 0.0
                traveledMeters = 0.0
                lastTickMs = SystemClock.elapsedRealtime()
                ticking = true
                isRunning = true
                listener?.onStarted()

                handler.removeCallbacks(tickRunnable)
                handler.post(tickRunnable)
            }
        }
        return START_NOT_STICKY
    }

    private fun shutdown() {
        ticking = false
        isRunning = false
        handler.removeCallbacks(tickRunnable)
        mock?.uninstall()
        mock = null
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        ticking = false
        isRunning = false
        handler.removeCallbacks(tickRunnable)
        mock?.uninstall()
        mock = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "轨迹模拟",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "正在模拟移动位置"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val contentPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, SimulationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("轨迹模拟运行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(contentPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundCompat() {
        val notif = buildNotification("准备中…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(sample: TrackEngine.Sample) {
        val text = String.format(
            "%.1f km/h · 已行进 %.0f m · %.5f, %.5f",
            speedKmh, traveledMeters, sample.lat, sample.lng
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
