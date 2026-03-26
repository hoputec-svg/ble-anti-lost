package com.antilost.ble

import android.Manifest
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
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

data class TagInfo(
    val tagId: Int,
    val battery: Int,
    var rssi: Int,
    var lastSeen: Long,
    var count: Int = 1,
    val firstSeen: String
)

class MainActivity : AppCompatActivity() {

    companion object {
        const val COMPANY_ID = 0xABCD
        const val PROTOCOL_VER = 0x01
        const val TAG_TIMEOUT_MS = 10000L
        const val REQUEST_PERMISSIONS = 100
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private val tags = mutableMapOf<Int, TagInfo>()
    private val handler = Handler(Looper.getMainLooper())
    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Views
    private lateinit var btnScan: Button
    private lateinit var btnClear: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvScanCount: TextView
    private lateinit var llTagContainer: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var etCompanyId: EditText

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshTagList()
            handler.postDelayed(this, 1000)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mfgData = result.scanRecord?.manufacturerSpecificData ?: return
            val data = mfgData.get(COMPANY_ID) ?: return
            if (data.size < 3) return
            val protVer = data[0].toInt() and 0xFF
            val tagId = data[1].toInt() and 0xFF
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
                    firstSeen = sdf.format(Date(now))
                )
            } else {
                existing.rssi = result.rssi
                existing.lastSeen = now
                existing.count++
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnScan = findViewById(R.id.btnScan)
        btnClear = findViewById(R.id.btnClear)
        tvStatus = findViewById(R.id.tvStatus)
        tvScanCount = findViewById(R.id.tvScanCount)
        llTagContainer = findViewById(R.id.llTagContainer)
        tvEmpty = findViewById(R.id.tvEmpty)
        etCompanyId = findViewById(R.id.etCompanyId)

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        btnScan.setOnClickListener {
            if (isScanning) stopScan() else checkPermissionsAndScan()
        }

        btnClear.setOnClickListener {
            tags.clear()
            refreshTagList()
        }
    }

    private fun checkPermissionsAndScan() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            startScan()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startScan()
        } else {
            tvStatus.text = "❌ 需要蓝牙权限才能扫描"
        }
    }

    private fun startScan() {
        val cidText = etCompanyId.text.toString().trim()
        val cid = cidText.toIntOrNull(16) ?: COMPANY_ID

        bleScanner = bluetoothAdapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bleScanner?.startScan(null, settings, scanCallback)
            isScanning = true
            btnScan.text = "停止扫描"
            btnScan.setBackgroundColor(0xFFE53935.toInt())
            tvStatus.text = "🔍 扫描中... (COMPANY_ID: 0x${cidText.uppercase()})"
            handler.post(refreshRunnable)
        } catch (e: Exception) {
            tvStatus.text = "❌ 启动失败: ${e.message}"
        }
    }

    private fun stopScan() {
        try { bleScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        isScanning = false
        btnScan.text = "开始扫描"
        btnScan.setBackgroundColor(0xFF1976D2.toInt())
        tvStatus.text = "⏹ 已停止"
        handler.removeCallbacks(refreshRunnable)
    }

    private fun refreshTagList() {
        val now = System.currentTimeMillis()
        val onlineCount = tags.values.count { now - it.lastSeen < TAG_TIMEOUT_MS }
        tvScanCount.text = "共 ${tags.size} 个TAG  |  在线 $onlineCount  |  离线 ${tags.size - onlineCount}"

        llTagContainer.removeAllViews()
        if (tags.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            return
        }
        tvEmpty.visibility = View.GONE

        tags.values.sortedBy { it.tagId }.forEach { tag ->
            val online = now - tag.lastSeen < TAG_TIMEOUT_MS
            val ageSec = (now - tag.lastSeen) / 1000
            val rssiBar = ((tag.rssi + 100) * 2).coerceIn(0, 100)
            val batColor = when {
                tag.battery > 50 -> "#4CAF50"
                tag.battery > 20 -> "#FF9800"
                else -> "#F44336"
            }
            val card = layoutInflater.inflate(R.layout.item_tag, llTagContainer, false)
            card.findViewById<TextView>(R.id.tvTagId).text =
                "TAG #${tag.tagId.toString(16).uppercase().padStart(2, '0')}"
            card.findViewById<TextView>(R.id.tvOnline).apply {
                text = if (online) "在线" else "离线"
                setTextColor(if (online) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
            }
            card.findViewById<TextView>(R.id.tvRssi).text = "信号: ${tag.rssi} dBm"
            card.findViewById<TextView>(R.id.tvBattery).text = "电量: ${tag.battery}%"
            card.findViewById<TextView>(R.id.tvLastSeen).text =
                if (online) "最后: ${ageSec}秒前" else "失联: >${ageSec}秒"
            card.findViewById<TextView>(R.id.tvCount).text =
                "首次: ${tag.firstSeen}  广播: ${tag.count}次"
            card.findViewById<ProgressBar>(R.id.pbRssi).progress = rssiBar
            card.findViewById<ProgressBar>(R.id.pbBattery).progress = tag.battery
            card.setBackgroundColor(if (online) 0xFF1B5E20.toInt() else 0xFF7F0000.toInt())
            llTagContainer.addView(card)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
    }
}
