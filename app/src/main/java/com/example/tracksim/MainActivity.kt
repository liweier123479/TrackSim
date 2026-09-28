package com.example.tracksim

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), SimulationService.Listener {

    private lateinit var mapView: MapCanvasView
    private lateinit var speedSeek: SeekBar
    private lateinit var speedText: TextView
    private lateinit var loopCheck: CheckBox
    private lateinit var dirCheck: CheckBox
    private lateinit var playBtn: Button
    private lateinit var undoBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var statText: TextView
    private lateinit var badge: TextView

    private val engine = TrackEngine()

    companion object {
        private const val REQ_PERMS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        speedSeek = findViewById(R.id.speedSeek)
        speedText = findViewById(R.id.speedText)
        loopCheck = findViewById(R.id.loopCheck)
        dirCheck = findViewById(R.id.dirCheck)
        playBtn = findViewById(R.id.playBtn)
        undoBtn = findViewById(R.id.undoBtn)
        clearBtn = findViewById(R.id.clearBtn)
        statText = findViewById(R.id.statText)
        badge = findViewById(R.id.badge)

        mapView.engine = engine
        mapView.onTrackChanged = { updateStat() }

        speedSeek.max = 70
        speedSeek.progress = 20
        speedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = 3.0 + progress / 10.0
                engine.speedKmh = v
                speedText.text = String.format("%.1f km/h", v)
                if (!SimulationService.isRunning) updateStat()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        engine.speedKmh = 5.0
        speedText.text = "5.0 km/h"

        loopCheck.setOnCheckedChangeListener { _, checked ->
            engine.loop = checked
            mapView.invalidate()
            updateStat()
        }

        dirCheck.setOnCheckedChangeListener { _, checked ->
            mapView.showDirection = checked
        }

        playBtn.setOnClickListener {
            if (SimulationService.isRunning) stopSimulation() else startSimulation()
        }

        undoBtn.setOnClickListener {
            if (engine.points.isEmpty()) return@setOnClickListener
            engine.points.removeAt(engine.points.size - 1)
            engine.rebuild()
            mapView.fitView()
            updateStat()
        }

        clearBtn.setOnClickListener {
            if (engine.points.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setMessage("清空所有路径点？")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空") { _, _ ->
                    engine.points.clear()
                    engine.rebuild()
                    mapView.currentPos = null
                    mapView.fitView()
                    updateStat()
                    badge.text = "未开始"
                    playBtn.text = "▶ 开始"
                }
                .show()
        }

        updateStat()
        requestNeededPermissions()
        showFirstRunTip()
    }

    private fun showFirstRunTip() {
        val sp = getSharedPreferences("tips", MODE_PRIVATE)
        if (sp.getBoolean("shown", false)) return
        sp.edit().putBoolean("shown", true).apply()
        AlertDialog.Builder(this)
            .setTitle("使用前必读")
            .setMessage(
                "1. 打开手机「设置 → 关于手机」，连续点击版本号开启开发者选项。\n\n" +
                "2. 进入「开发者选项 → 选择模拟位置信息应用」，选中本应用。\n\n" +
                "3. 允许本应用获取位置权限。\n\n" +
                "完成后点按地图绘制路径，点击「开始」即可模拟定位移动。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        SimulationService.listener = this
        if (SimulationService.isRunning) {
            playBtn.text = "❚❚ 暂停"
            badge.text = "模拟中…"
        }
    }

    override fun onStop() {
        super.onStop()
        if (SimulationService.listener === this) {
            SimulationService.listener = null
        }
    }

    private fun requestNeededPermissions() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION)
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_PERMS)
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun startSimulation() {
        if (engine.points.size < 2) {
            Toast.makeText(this, "请至少添加 2 个路径点", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasLocationPermission()) {
            Toast.makeText(this, "需要位置权限才能模拟定位", Toast.LENGTH_SHORT).show()
            requestNeededPermissions()
            return
        }

        val arr = DoubleArray(engine.points.size * 2)
        engine.points.forEachIndexed { i, p ->
            arr[i * 2] = p.lat
            arr[i * 2 + 1] = p.lng
        }

        val intent = Intent(this, SimulationService::class.java).apply {
            action = SimulationService.ACTION_START
            putExtra(SimulationService.EXTRA_POINTS, arr)
            putExtra(SimulationService.EXTRA_SPEED, engine.speedKmh)
            putExtra(SimulationService.EXTRA_LOOP, engine.loop)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopSimulation() {
        val intent = Intent(this, SimulationService::class.java).apply {
            action = SimulationService.ACTION_STOP
        }
        startService(intent)
        playBtn.text = "▶ 继续"
        badge.text = "已暂停"
        mapView.currentPos = null
    }

    override fun onStarted() {
        playBtn.text = "❚❚ 暂停"
        badge.text = "模拟中…"
    }

    override fun onSample(sample: TrackEngine.Sample, elapsedSeconds: Double) {
        mapView.currentPos = GeoPoint(sample.lat, sample.lng)
        updateStat(sample, elapsedSeconds)
    }

    override fun onFinished() {
        playBtn.text = "▶ 开始"
        badge.text = "已完成"
        Toast.makeText(this, "轨迹已完成", Toast.LENGTH_SHORT).show()
    }

    override fun onError(message: String) {
        playBtn.text = "▶ 开始"
        badge.text = "未开始"
        AlertDialog.Builder(this)
            .setTitle("无法启动模拟定位")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun fmtTime(sec: Double): String {
        if (!sec.isFinite() || sec <= 0) return "--:--"
        val t = sec.toInt()
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    private fun fmtDist(m: Double): String =
        if (m >= 1000) String.format("%.2f km", m / 1000) else String.format("%.1f m", m)

    private fun updateStat(sample: TrackEngine.Sample? = null, elapsed: Double = 0.0) {
        val sb = StringBuilder()
        sb.append("点数 ").append(engine.points.size)
        sb.append(" · 总长 ").append(fmtDist(engine.totalLength))
        sb.append(" · 单圈用时 ").append(fmtTime(engine.lapSeconds()))

        if (sample != null) {
            sb.append("\n坐标 ")
                .append(String.format("%.6f, %.6f", sample.lat, sample.lng))
            sb.append("\n方位 ").append(String.format("%.0f°", sample.bearing))
                .append(" · 已行进 ").append(fmtDist(sample.traveled))
            sb.append("\n已用时 ").append(fmtTime(elapsed))
        } else if (engine.points.isEmpty()) {
            sb.append("\n点按地图开始绘制路径")
        } else if (engine.points.size == 1) {
            sb.append("\n再点一个点作为终点")
        }
        statText.text = sb.toString()
    }
}
