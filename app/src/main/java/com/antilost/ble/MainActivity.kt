package com.antilost.ble

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
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

class MainActivity : AppCompatActivity() {

    companion object {
        const val COMPANY_ID = 0xABCD
        const val PROTOCOL_VER = 0x01
        const val TAG_TIMEOUT_MS = 10000L
        const val RSSI_WEAK_THRESHOLD = -85       // dBm，低于此值视为信号过弱
        const val VIBRATE_WEAK_MS = 300L           // 信号弱震动时长
        const val CHANNEL_ID = "ble_antilost"
        const val REQUEST_PERMISSIONS = 100
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private val tags = mutableMapOf<Int, TagInfo>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var vibrator: Vibrator
    private var notifId = 1000

    private lateinit var btnScan: Button
    private lateinit var btnClear: Button
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
            val protVer = data[0].toInt() and 0xFF
            val tagId   = data[1].toInt() and 0xFF
            val battery = data[2].toInt() and 0xFF
            if (protVer != PROTOCOL_VER) return

            val now = System.currentTimeMillis()
            val existing = tags[tagId]
            if (existing == null) {
                tags[tagId] = TagInfo(
                    tagId = tagId,
                    battery = battery,
                    rssi = result.rssi,
                    lastSeen = now,
                    firstSeen = android.text.format.DateFormat.format("HH:mm:ss", now).toString()
                )
            } else {
                existing.rssi = result.rssi
                existing.lastSeen = now
                existing.battery = battery
                existing.count++
                // 信号恢复，重置报警状态
                if (result.rssi > RSSI_WEAK_THRESHOLD) existing.notifiedWeak = false
                existing.notifiedLost = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnScan        = findViewById(R.id.btnScan)
        btnClear       = findViewById(R.id.btnClear)
        tvStatus       = findViewById(R.id.tvStatus)
        tvScanCount    = findViewById(R.id.tvScanCount)
        llTagContainer = findViewById(R.id.llTagContainer)
        tvEmpty        = findViewById(R.id.tvEmpty)
        etCompanyId    = findViewById(R.id.etCompanyId)

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        createNotificationChannel()

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        btnScan.setOnClickListener {
            if (isScanning) stopScan() else checkPermissionsAndScan()
        }
        btnClear.setOnClickListener {
            tags.clear()
            refreshUI()
        }
    }

    // ----------------------------------------------------------------
    //  权限检查
    // ----------------------------------------------------------------
    private fun checkPermissionsAndScan() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!granted(Manifest.permission.BLUETOOTH_SCAN))    perms.add(Manifest.permission.BLUETOOTH_SCAN)
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!granted(Manifest.permission.POST_NOTIFICATIONS)) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            startScan()
        }
    }

    private fun granted(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startScan()
        } else {
            tvStatus.text = "Need Bluetooth permission to scan"
        }
    }

    // ----------------------------------------------------------------
    //  扫描控制
    // ----------------------------------------------------------------
    private fun startScan() {
        bleScanner = bluetoothAdapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            bleScanner?.startScan(null, settings, scanCallback)
            isScanning = true
            btnScan.text = "Stop Scan"
            btnScan.setBackgroundColor(0xFFE53935.toInt())
            tvStatus.text = "Scanning... (0x${etCompanyId.text.toString().uppercase()})"
            handler.post(refreshRunnable)
        } catch (e: Exception) {
            tvStatus.text = "Start failed: ${e.message}"
        }
    }

    private fun stopScan() {
        try { bleScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        isScanning = false
        btnScan.text = "Start Scan"
        btnScan.setBackgroundColor(0xFF1976D2.toInt())
        tvStatus.text = "Stopped"
        handler.removeCallbacks(refreshRunnable)
    }

    // ----------------------------------------------------------------
    //  报警检测 + UI刷新（每秒执行一次）
    // ----------------------------------------------------------------
    private fun checkAlertsAndRefresh() {
        val now = System.currentTimeMillis()
        tags.values.forEach { tag ->
            val lost = (now - tag.lastSeen) >= TAG_TIMEOUT_MS

            // ① TAG失联超过10秒 → 推送通知
            if (lost && !tag.notifiedLost) {
                tag.notifiedLost = true
                sendNotification(
                    title = "TAG #${tag.tagId.toString(16).uppercase()} Lost!",
                    text  = "No signal for over 10 seconds, please check."
                )
                vibratePattern(longArrayOf(0, 400, 200, 400))   // 两次长震动
            }

            // ② 信号过弱（在线但RSSI低于阈值）→ 震动提醒
            if (!lost && tag.rssi < RSSI_WEAK_THRESHOLD && !tag.notifiedWeak) {
                tag.notifiedWeak = true
                vibrateOnce(VIBRATE_WEAK_MS)
                sendNotification(
                    title = "TAG #${tag.tagId.toString(16).uppercase()} Weak Signal",
                    text  = "RSSI ${tag.rssi} dBm, device may be going out of range."
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
        tvScanCount.text = "Total: ${tags.size}  |  Online: $onlineCount  |  Offline: ${tags.size - onlineCount}"

        llTagContainer.removeAllViews()
        if (tags.isEmpty()) { tvEmpty.visibility = View.VISIBLE; return }
        tvEmpty.visibility = View.GONE

        tags.values.sortedBy { it.tagId }.forEach { tag ->
            val online  = (now - tag.lastSeen) < TAG_TIMEOUT_MS
            val ageSec  = (now - tag.lastSeen) / 1000
            val weak    = online && tag.rssi < RSSI_WEAK_THRESHOLD
            val rssiBar = ((tag.rssi + 100) * 2).coerceIn(0, 100)

            val card = layoutInflater.inflate(R.layout.item_tag, llTagContainer, false)

            card.findViewById<TextView>(R.id.tvTagId).text =
                "TAG #${tag.tagId.toString(16).uppercase().padStart(2, '0')}"

            card.findViewById<TextView>(R.id.tvOnline).apply {
                text = when {
                    !online -> "LOST"
                    weak    -> "WEAK"
                    else    -> "Online"
                }
                setTextColor(when {
                    !online -> 0xFFF44336.toInt()
                    weak    -> 0xFFFF9800.toInt()
                    else    -> 0xFF4CAF50.toInt()
                })
            }

            card.findViewById<TextView>(R.id.tvRssi).text    = "RSSI: ${tag.rssi} dBm"
            card.findViewById<TextView>(R.id.tvBattery).text = "Battery: ${tag.battery}%"
            card.findViewById<TextView>(R.id.tvLastSeen).text =
                if (online) "Last: ${ageSec}s ago" else "Lost: >${ageSec}s"
            card.findViewById<TextView>(R.id.tvCount).text =
                "First: ${tag.firstSeen}  Packets: ${tag.count}"

            card.findViewById<ProgressBar>(R.id.pbRssi).progress    = rssiBar
            card.findViewById<ProgressBar>(R.id.pbBattery).progress = tag.battery

            card.setBackgroundColor(when {
                !online -> 0xFF7F0000.toInt()
                weak    -> 0xFF4A3000.toInt()
                else    -> 0xFF1B5E20.toInt()
            })

            llTagContainer.addView(card)
        }
    }

    // ----------------------------------------------------------------
    //  震动
    // ----------------------------------------------------------------
    private fun vibrateOnce(ms: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(ms)
        }
    }

    private fun vibratePattern(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    // ----------------------------------------------------------------
    //  通知
    // ----------------------------------------------------------------
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "BLE Anti-Lost Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "TAG lost and weak signal alerts" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun sendNotification(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)) return
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(notifId++, notification)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
    }
}
