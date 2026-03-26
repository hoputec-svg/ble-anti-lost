package com.antilost.ble

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

data class TagInfo(
    val tagId: Int,
    var battery: Int,
    var rssi: Int,
    var lastSeen: Long,
    var count: Int = 1,
    val firstSeen: String,
    var notifiedLost: Boolean = false,
    var notifiedWeak: Boolean = false
)

// ================================================================
//  前台服务（保持后台扫描不被系统杀死）
// ================================================================
class BleScanService : Service() {
    companion object {
        const val NOTIF_ID = 1
        const val CHANNEL_FG = "ble_foreground"
        var isRunning = false
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_FG, "后台扫描", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(this, CHANNEL_FG)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("BLE防丢器")
            .setContentText("后台扫描运行中...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }
    override fun onDestroy() { super.onDestroy(); isRunning = false }
}

// ================================================================
//  主界面
// ================================================================
class MainActivity : AppCompatActivity() {

    companion object {
        const val COMPANY_ID = 0xABCD
        const val PROTOCOL_VER = 0x01
        const val TAG_TIMEOUT_MS = 10000L
        const val RSSI_WEAK_THRESHOLD = -85
        const val CHANNEL_ALERT = "ble_antilost"
        const val REQUEST_PERMISSIONS = 100
        // SharedPreferences keys
        const val PREF_NAME = "ble_prefs"
        const val PREF_BG_SCAN = "bg_scan_enabled"
        const val PREF_NOTIF = "notif_enabled"
        const val PREF_COMPANY_ID = "company_id"
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var bgScanEnabled = false
    private var notifEnabled = true   // 通知开关
    private val tags = mutableMapOf<Int, TagInfo>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var vibrator: Vibrator
    private lateinit var prefs: SharedPreferences
    private var notifId = 2000

    private lateinit var btnScan: Button
    private lateinit var btnClear: Button
    private lateinit var btnBackground: Button
    private lateinit var btnNotif: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvScanCount: TextView
    private lateinit var llTagContainer: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var etCompanyId: EditText

    private val refreshRunnable = object : Runnable {
        override fun run() {
            checkAlertsAndRefresh()
            handler.postDelayed(this, 1000)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mfgData = result.scanRecord?.manufacturerSpecificData ?: return
            val data = mfgData.get(COMPANY_ID) ?: return
            if (data.size < 3) return
            if ((data[0].toInt() and 0xFF) != PROTOCOL_VER) return
            val tagId   = data[1].toInt() and 0xFF
            val battery = data[2].toInt() and 0xFF
            val now = System.currentTimeMillis()
            val existing = tags[tagId]
            if (existing == null) {
                tags[tagId] = TagInfo(
                    tagId = tagId, battery = battery, rssi = result.rssi, lastSeen = now,
                    firstSeen = android.text.format.DateFormat.format("HH:mm:ss", now).toString()
                )
            } else {
                existing.rssi = result.rssi; existing.lastSeen = now
                existing.battery = battery; existing.count++
                if (result.rssi > RSSI_WEAK_THRESHOLD) existing.notifiedWeak = false
                existing.notifiedLost = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        btnScan        = findViewById(R.id.btnScan)
        btnClear       = findViewById(R.id.btnClear)
        btnBackground  = findViewById(R.id.btnBackground)
        btnNotif       = findViewById(R.id.btnNotif)
        tvStatus       = findViewById(R.id.tvStatus)
        tvScanCount    = findViewById(R.id.tvScanCount)
        llTagContainer = findViewById(R.id.llTagContainer)
        tvEmpty        = findViewById(R.id.tvEmpty)
        etCompanyId    = findViewById(R.id.etCompanyId)

        // 恢复上次保存的设置
        bgScanEnabled = prefs.getBoolean(PREF_BG_SCAN, false)
        notifEnabled  = prefs.getBoolean(PREF_NOTIF, true)
        etCompanyId.setText(prefs.getString(PREF_COMPANY_ID, "ABCD"))

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        createAlertChannel()
        bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        btnScan.setOnClickListener { if (isScanning) stopScan() else checkPermissionsAndScan() }
        btnClear.setOnClickListener { tags.clear(); refreshUI() }
        btnBackground.setOnClickListener { toggleBackgroundScan() }
        btnNotif.setOnClickListener { toggleNotif() }

        updateBgButton()
        updateNotifButton()

        // 如果上次后台扫描已开启，自动恢复扫描
        if (bgScanEnabled && BleScanService.isRunning) {
            restoreScanning()
        }
    }

    // ----------------------------------------------------------------
    //  后台扫描恢复（从后台回到前台）
    // ----------------------------------------------------------------
    private fun restoreScanning() {
        bleScanner = bluetoothAdapter.bluetoothLeScanner
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            bleScanner?.startScan(null, settings, scanCallback)
            isScanning = true
            btnScan.text = "停止扫描"
            btnScan.setBackgroundColor(0xFFE53935.toInt())
            tvStatus.text = "扫描中... (0x${etCompanyId.text.toString().uppercase()})"
            handler.post(refreshRunnable)
        } catch (_: Exception) {}
    }

    // ----------------------------------------------------------------
    //  后台扫描切换
    // ----------------------------------------------------------------
    private fun toggleBackgroundScan() {
        if (!isScanning) {
            Toast.makeText(this, "请先开始扫描", Toast.LENGTH_SHORT).show()
            return
        }
        bgScanEnabled = !bgScanEnabled
        prefs.edit().putBoolean(PREF_BG_SCAN, bgScanEnabled).apply()
        val intent = Intent(this, BleScanService::class.java)
        if (bgScanEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            Toast.makeText(this, "后台扫描已开启，可最小化APP", Toast.LENGTH_LONG).show()
        } else {
            stopService(intent)
            Toast.makeText(this, "后台扫描已关闭", Toast.LENGTH_SHORT).show()
        }
        updateBgButton()
    }

    private fun updateBgButton() {
        if (bgScanEnabled && BleScanService.isRunning) {
            btnBackground.text = "后台扫描：开启"
            btnBackground.setBackgroundColor(0xFF2E7D32.toInt())
        } else {
            bgScanEnabled = false
            btnBackground.text = "后台扫描：关闭"
            btnBackground.setBackgroundColor(0xFF455A64.toInt())
        }
    }

    // ----------------------------------------------------------------
    //  通知开关
    // ----------------------------------------------------------------
    private fun toggleNotif() {
        notifEnabled = !notifEnabled
        prefs.edit().putBoolean(PREF_NOTIF, notifEnabled).apply()
        updateNotifButton()
        Toast.makeText(this, if (notifEnabled) "通知已开启" else "通知已关闭", Toast.LENGTH_SHORT).show()
    }

    private fun updateNotifButton() {
        if (notifEnabled) {
            btnNotif.text = "报警通知：开启"
            btnNotif.setBackgroundColor(0xFF1565C0.toInt())
        } else {
            btnNotif.text = "报警通知：关闭"
            btnNotif.setBackgroundColor(0xFF455A64.toInt())
        }
    }

    override fun onResume() {
        super.onResume()
        updateBgButton()
    }

    override fun onPause() {
        super.onPause()
        // 保存Company ID
        prefs.edit().putString(PREF_COMPANY_ID, etCompanyId.text.toString()).apply()
    }

    // ----------------------------------------------------------------
    //  权限
    // ----------------------------------------------------------------
    private fun checkPermissionsAndScan() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!granted(Manifest.permission.BLUETOOTH_SCAN))    perms.add(Manifest.permission.BLUETOOTH_SCAN)
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            if (!granted(Manifest.permission.POST_NOTIFICATIONS)) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (perms.isNotEmpty()) ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQUEST_PERMISSIONS)
        else startScan()
    }

    private fun granted(perm: String) = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startScan()
        else tvStatus.text = "需要蓝牙权限才能扫描"
    }

    // ----------------------------------------------------------------
    //  扫描
    // ----------------------------------------------------------------
    private fun startScan() {
        bleScanner = bluetoothAdapter.bluetoothLeScanner
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            bleScanner?.startScan(null, settings, scanCallback)
            isScanning = true
            btnScan.text = "停止扫描"
            btnScan.setBackgroundColor(0xFFE53935.toInt())
            val cid = etCompanyId.text.toString().uppercase()
            prefs.edit().putString(PREF_COMPANY_ID, cid).apply()
            tvStatus.text = "扫描中... (0x$cid)"
            handler.post(refreshRunnable)
        } catch (e: Exception) { tvStatus.text = "启动失败：${e.message}" }
    }

    private fun stopScan() {
        try { bleScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        isScanning = false
        bgScanEnabled = false
        prefs.edit().putBoolean(PREF_BG_SCAN, false).apply()
        stopService(Intent(this, BleScanService::class.java))
        btnScan.text = "开始扫描"
        btnScan.setBackgroundColor(0xFF1976D2.toInt())
        tvStatus.text = "已停止"
        handler.removeCallbacks(refreshRunnable)
        updateBgButton()
    }

    // ----------------------------------------------------------------
    //  报警检测 + 刷新
    // ----------------------------------------------------------------
    private fun checkAlertsAndRefresh() {
        val now = System.currentTimeMillis()
        tags.values.forEach { tag ->
            val lost = (now - tag.lastSeen) >= TAG_TIMEOUT_MS
            if (lost && !tag.notifiedLost) {
                tag.notifiedLost = true
                vibratePattern(longArrayOf(0, 400, 200, 400))
                if (notifEnabled) sendAlert(
                    "TAG #${tag.tagId.toString(16).uppercase()} 失联！",
                    "超过10秒未收到信号，请检查设备。"
                )
            }
            if (!lost && tag.rssi < RSSI_WEAK_THRESHOLD && !tag.notifiedWeak) {
                tag.notifiedWeak = true
                vibrateOnce(300L)
                if (notifEnabled) sendAlert(
                    "TAG #${tag.tagId.toString(16).uppercase()} 信号过弱",
                    "当前信号 ${tag.rssi} dBm，设备可能即将超出范围。"
                )
            }
        }
        refreshUI()
    }

    // ----------------------------------------------------------------
    //  UI渲染
    // ----------------------------------------------------------------
    private fun refreshUI() {
        val now = System.currentTimeMillis()
        val onlineCount = tags.values.count { now - it.lastSeen < TAG_TIMEOUT_MS }
        tvScanCount.text = "共 ${tags.size} 个TAG  |  在线 $onlineCount  |  离线 ${tags.size - onlineCount}"
        llTagContainer.removeAllViews()
        if (tags.isEmpty()) { tvEmpty.visibility = View.VISIBLE; return }
        tvEmpty.visibility = View.GONE
        tags.values.sortedBy { it.tagId }.forEach { tag ->
            val online  = (now - tag.lastSeen) < TAG_TIMEOUT_MS
            val ageSec  = (now - tag.lastSeen) / 1000
            val weak    = online && tag.rssi < RSSI_WEAK_THRESHOLD
            val rssiBar = ((tag.rssi + 100) * 2).coerceIn(0, 100)
            val card = layoutInflater.inflate(R.layout.item_tag, llTagContainer, false)
            card.findViewById<TextView>(R.id.tvTagId).text = "TAG #${tag.tagId.toString(16).uppercase().padStart(2,'0')}"
            card.findViewById<TextView>(R.id.tvOnline).apply {
                text = when { !online -> "失联"; weak -> "信号弱"; else -> "在线" }
                setTextColor(when { !online -> 0xFFF44336.toInt(); weak -> 0xFFFF9800.toInt(); else -> 0xFF4CAF50.toInt() })
            }
            card.findViewById<TextView>(R.id.tvRssi).text    = "信号：${tag.rssi} dBm"
            card.findViewById<TextView>(R.id.tvBattery).text = "电量：${tag.battery}%"
            card.findViewById<TextView>(R.id.tvLastSeen).text = if (online) "最后：${ageSec}秒前" else "失联：>${ageSec}秒"
            card.findViewById<TextView>(R.id.tvCount).text = "首次：${tag.firstSeen}  广播：${tag.count}次"
            card.findViewById<ProgressBar>(R.id.pbRssi).progress    = rssiBar
            card.findViewById<ProgressBar>(R.id.pbBattery).progress = tag.battery
            card.setBackgroundColor(when { !online -> 0xFF7F0000.toInt(); weak -> 0xFF4A3000.toInt(); else -> 0xFF1B5E20.toInt() })
            llTagContainer.addView(card)
        }
    }

    // ----------------------------------------------------------------
    //  震动 / 通知
    // ----------------------------------------------------------------
    private fun vibrateOnce(ms: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vibrator.vibrate(ms)
    }

    private fun vibratePattern(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        else @Suppress("DEPRECATION") vibrator.vibrate(pattern, -1)
    }

    private fun createAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ALERT, "TAG报警", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "失联和信号过弱报警" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun sendAlert(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) return
        try {
            val notif = NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title).setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
            NotificationManagerCompat.from(this).notify(notifId++, notif)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!bgScanEnabled) stopScan()
        else handler.removeCallbacks(refreshRunnable)
    }
}
