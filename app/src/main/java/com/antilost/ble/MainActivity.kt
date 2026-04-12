package com.antilost.ble

import android.Manifest
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

// BLE UUIDs
object UUIDS {
    val SERVICE          = UUID.fromString("0000ab00-0000-1000-8000-00805f9b34fb")
    val CHAR_TIME_SYNC   = UUID.fromString("0000ab01-0000-1000-8000-00805f9b34fb")
    val CHAR_HISTORY     = UUID.fromString("0000ab02-0000-1000-8000-00805f9b34fb")
    val CHAR_CONTROL     = UUID.fromString("0000ab03-0000-1000-8000-00805f9b34fb")
    val CHAR_DEVICE_INFO = UUID.fromString("0000ab04-0000-1000-8000-00805f9b34fb")
    val CCCD             = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

const val CMD_START_UPLOAD  = 0x01.toByte()
const val CMD_CLEAR_HISTORY = 0x02.toByte()
const val COMPANY_ID_INT    = 0xABCD
const val PREF_DEVICE_ADDR  = "pref_device_addr"
const val PREF_DEVICE_NAME  = "pref_device_name"
const val NOTIF_CHANNEL_ID  = "antilost_alarm"
const val PROTOCOL_VER      = 0x06

// 历史记录数据类
data class TagRecord(
    val timestamp:  Long,
    val tempX100:   Int,
    val humidX100:  Int,
    val eventType:  Int,
    val motionAxis: Int,
    val batteryPct: Int
) {
    val tempStr  get() = "%.1f°C".format(tempX100 / 100.0)
    val humidStr get() = "%.1f%%".format(humidX100 / 100.0)
    val timeStr  get(): String {
        if (timestamp == 0L) return "时间未同步"
        return SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(timestamp * 1000))
    }
    val eventStr get() = when (eventType) {
        1 -> "运动报警 [${buildString {
            if (motionAxis and 1 != 0) append("X")
            if (motionAxis and 2 != 0) append("Y")
            if (motionAxis and 4 != 0) append("Z")
        }}轴]"
        2 -> "设备上电"
        else -> "定时采样"
    }
    val isAlarm get() = eventType == 1
}

class MainActivity : AppCompatActivity() {

    private lateinit var btAdapter: BluetoothAdapter
    private var bleScanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var connected = false
    private var uploading = false

    // 状态数据
    private var deviceAddr = ""
    private var deviceName = "ANTI TAG"
    private var rssi = -100
    private var tempX100 = 0
    private var humidX100 = 0
    private var battPct = 0
    private var alarmFlag = false
    private var lastSeenTime = 0L
    private var deviceRecordCount = 0

    private val records = mutableListOf<TagRecord>()
    private val prefs by lazy { getSharedPreferences("antilost", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    // Views
    private lateinit var tvDeviceName:    TextView
    private lateinit var tvStatus:        TextView
    private lateinit var tvTemp:          TextView
    private lateinit var tvHumid:         TextView
    private lateinit var tvBattery:       TextView
    private lateinit var tvRssi:          TextView
    private lateinit var tvLastSeen:      TextView
    private lateinit var tvRecordCount:   TextView
    private lateinit var tvDeviceRecords: TextView
    private lateinit var btnScan:         Button
    private lateinit var btnConnect:      Button
    private lateinit var btnDownload:     Button
    private lateinit var btnClearDevice:  Button
    private lateinit var progressBar:     ProgressBar
    private lateinit var tvProgress:      TextView
    private lateinit var tvAlarmBanner:   TextView
    private lateinit var layoutConnected: LinearLayout
    private lateinit var rvRecords:       RecyclerView
    private lateinit var tvEmptyHint:     TextView
    private var recordAdapter: RecordAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        deviceAddr = prefs.getString(PREF_DEVICE_ADDR, "") ?: ""
        deviceName = prefs.getString(PREF_DEVICE_NAME, "ANTI TAG") ?: "ANTI TAG"
        buildUI()
        setupBluetooth()
        checkPermissions()
        updateUI()
    }

    private fun buildUI() {
        val bg = Color.parseColor("#0D1117")

        val root = NestedScrollView(this).apply {
            setBackgroundColor(bg)
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        // ── 顶部 Header ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161B22"))
            setPadding(dp(20), dp(40), dp(20), dp(24))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).also {
                it.marginEnd = dp(10); it.topMargin = dp(2)
            }
            setBackgroundColor(Color.parseColor("#58A6FF"))
        }
        tvDeviceName = TextView(this).apply {
            text = deviceName
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E6EDF3"))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        tvStatus = TextView(this).apply {
            text = "搜索中..."
            textSize = 12f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundColor(Color.parseColor("#21262D"))
        }
        topRow.addView(dot)
        topRow.addView(tvDeviceName)
        topRow.addView(tvStatus)
        header.addView(topRow)

        // 大温度显示
        val tempRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(16), 0, dp(4))
        }
        tvTemp = TextView(this).apply {
            text = "--.-°C"
            textSize = 52f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E6EDF3"))
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also {
                it.marginEnd = dp(20)
            }
        }
        val humidCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
        }
        val tvHumidLabel = TextView(this).apply {
            text = "湿度"
            textSize = 11f
            setTextColor(Color.parseColor("#8B949E"))
        }
        tvHumid = TextView(this).apply {
            text = "--.-"
            textSize = 28f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#58A6FF"))
        }
        val tvHumidUnit = TextView(this).apply {
            text = "%"
            textSize = 14f
            setTextColor(Color.parseColor("#58A6FF"))
        }
        val humidValRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        humidValRow.addView(tvHumid)
        humidValRow.addView(tvHumidUnit)
        humidCol.addView(tvHumidLabel)
        humidCol.addView(humidValRow)
        tempRow.addView(tvTemp)
        tempRow.addView(humidCol)
        header.addView(tempRow)

        // 副信息行
        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        tvBattery = buildPill("电量 --%", "#1F6FEB", "#58A6FF")
        tvRssi    = buildPill("-- dBm",  "#1B4332", "#56D364")
        tvLastSeen = TextView(this).apply {
            text = "未扫描"
            textSize = 11f
            setTextColor(Color.parseColor("#8B949E"))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).also { it.marginStart = dp(8) }
        }
        metaRow.addView(tvBattery)
        metaRow.addView(tvRssi)
        metaRow.addView(tvLastSeen)
        header.addView(metaRow)

        // 按钮行
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20), 0, 0)
        }
        btnScan = buildButton("开始扫描", "#238636", "#2EA043").also {
            it.layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).also { lp -> lp.marginEnd = dp(10) }
            it.setOnClickListener { toggleScan() }
        }
        btnConnect = buildButton("连接设备", "#1F6FEB", "#58A6FF").also {
            it.layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            it.isEnabled = deviceAddr.isNotEmpty()
            it.setOnClickListener { handleConnect() }
        }
        btnRow.addView(btnScan)
        btnRow.addView(btnConnect)
        header.addView(btnRow)

        container.addView(header)

        // ── 报警横幅 ──
        tvAlarmBanner = TextView(this).apply {
            text = "⚠  检测到运动报警！设备可能被移动"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#DA3633"))
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            visibility = View.GONE
        }
        container.addView(tvAlarmBanner)

        // ── 连接操作区 ──
        layoutConnected = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161B22"))
            setPadding(dp(20), dp(20), dp(20), dp(20))
            visibility = View.GONE
        }
        val connTitle = TextView(this).apply {
            text = "已连接 — 设备操作"
            textSize = 13f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 0, 0, dp(14))
        }
        layoutConnected.addView(connTitle)

        tvDeviceRecords = TextView(this).apply {
            text = "设备记录：-- 条"
            textSize = 13f
            setTextColor(Color.parseColor("#C9D1D9"))
            setPadding(0, 0, 0, dp(12))
        }
        layoutConnected.addView(tvDeviceRecords)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(4)).also { it.bottomMargin = dp(6) }
            isIndeterminate = false
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#58A6FF"))
            visibility = View.GONE
        }
        layoutConnected.addView(progressBar)

        tvProgress = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.parseColor("#8B949E"))
            visibility = View.GONE
        }
        layoutConnected.addView(tvProgress)

        val connBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnDownload = buildButton("下载历史记录", "#1F6FEB", "#58A6FF").also {
            it.layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).also { lp -> lp.marginEnd = dp(10) }
            it.setOnClickListener { downloadHistory() }
        }
        btnClearDevice = buildButton("清除设备记录", "#6E2525", "#DA3633").also {
            it.layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            it.setOnClickListener { confirmClearHistory() }
        }
        connBtnRow.addView(btnDownload)
        connBtnRow.addView(btnClearDevice)
        layoutConnected.addView(connBtnRow)
        container.addView(layoutConnected)

        // ── 本地记录区 ──
        val recHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(12))
            setBackgroundColor(bg)
        }
        tvRecordCount = TextView(this).apply {
            text = "本地记录"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E6EDF3"))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val btnClearLocal = TextView(this).apply {
            text = "清除本地"
            textSize = 12f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                records.clear()
                recordAdapter?.notifyDataSetChanged()
                tvEmptyHint.visibility = View.VISIBLE
                tvRecordCount.text = "本地记录"
            }
        }
        recHeader.addView(tvRecordCount)
        recHeader.addView(btnClearLocal)
        container.addView(recHeader)

        tvEmptyHint = TextView(this).apply {
            text = "暂无记录\n连接设备后点击「下载历史记录」"
            textSize = 14f
            setTextColor(Color.parseColor("#8B949E"))
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(40), dp(20), dp(40))
            visibility = View.VISIBLE
        }
        container.addView(tvEmptyHint)

        rvRecords = RecyclerView(this).apply {
            isNestedScrollingEnabled = false
            layoutManager = LinearLayoutManager(this@MainActivity)
            setBackgroundColor(bg)
        }
        recordAdapter = RecordAdapter(records)
        rvRecords.adapter = recordAdapter
        container.addView(rvRecords)

        // 底部安全区
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(32))
        })

        root.addView(container)
        setContentView(root)
    }

    private fun buildPill(text: String, bg: String, fg: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.parseColor(fg))
            setBackgroundColor(Color.parseColor(bg))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also {
                it.marginEnd = dp(8)
            }
        }
    }

    private fun buildButton(label: String, bg: String, fg: String): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.parseColor(fg))
            setBackgroundColor(Color.parseColor(bg))
            setPadding(dp(16), 0, dp(16), 0)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun updateUI() {
        tvDeviceName.text = if (deviceName.isNotEmpty()) deviceName else "ANTI TAG"
        tvTemp.text  = if (tempX100 != 0) "%.1f°C".format(tempX100 / 100.0) else "--.-°C"
        tvHumid.text = if (humidX100 != 0) "%.1f".format(humidX100 / 100.0) else "--.-"
        (tvBattery as TextView).text = "电量 ${battPct}%"
        (tvRssi as TextView).text    = "${rssi} dBm"
        tvRecordCount.text = "本地记录  ${records.size} 条"
        tvEmptyHint.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE

        when {
            connected -> {
                tvStatus.text = "已连接"
                tvStatus.setTextColor(Color.parseColor("#56D364"))
                layoutConnected.visibility = View.VISIBLE
                btnConnect.text = "断开连接"
                btnConnect.setBackgroundColor(Color.parseColor("#6E2525"))
                btnConnect.setTextColor(Color.parseColor("#DA3633"))
            }
            scanning -> {
                tvStatus.text = "扫描中..."
                tvStatus.setTextColor(Color.parseColor("#E3B341"))
                layoutConnected.visibility = View.GONE
                btnConnect.isEnabled = deviceAddr.isNotEmpty()
            }
            deviceAddr.isNotEmpty() -> {
                tvStatus.text = "未连接"
                tvStatus.setTextColor(Color.parseColor("#8B949E"))
                layoutConnected.visibility = View.GONE
                btnConnect.isEnabled = true
                btnConnect.text = "连接设备"
                btnConnect.setBackgroundColor(Color.parseColor("#1F6FEB"))
                btnConnect.setTextColor(Color.parseColor("#58A6FF"))
            }
            else -> {
                tvStatus.text = "未发现设备"
                tvStatus.setTextColor(Color.parseColor("#8B949E"))
                layoutConnected.visibility = View.GONE
                btnConnect.isEnabled = false
            }
        }

        tvAlarmBanner.visibility = if (alarmFlag && !connected) View.VISIBLE else View.GONE

        if (lastSeenTime > 0) {
            val secs = (System.currentTimeMillis() - lastSeenTime) / 1000
            tvLastSeen.text = when {
                secs < 60  -> "${secs}秒前"
                secs < 3600 -> "${secs/60}分钟前"
                else       -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastSeenTime))
            }
        }
    }

    private fun setupBluetooth() {
        val bm = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = bm.adapter
        bleScanner = btAdapter.bluetoothLeScanner
    }

    private fun toggleScan() {
        if (scanning) stopScan() else startScan()
    }

    private fun startScan() {
        if (!checkPermissions()) return
        scanning = true
        btnScan.text = "停止扫描"
        btnScan.setBackgroundColor(Color.parseColor("#6E2525"))
        btnScan.setTextColor(Color.parseColor("#DA3633"))

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bleScanner?.startScan(null, settings, scanCallback)
        handler.postDelayed({ if (scanning) stopScan() }, 15000)
        updateUI()
    }

    private fun stopScan() {
        scanning = false
        btnScan.text = "开始扫描"
        btnScan.setBackgroundColor(Color.parseColor("#238636"))
        btnScan.setTextColor(Color.parseColor("#2EA043"))
        bleScanner?.stopScan(scanCallback)
        updateUI()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mfgData = result.scanRecord?.getManufacturerSpecificData(COMPANY_ID_INT) ?: return
            if (mfgData.size < 8) return
            if ((mfgData[0].toInt() and 0xFF) != PROTOCOL_VER) return

            val t  = ((mfgData[3].toInt() and 0xFF) shl 8) or (mfgData[4].toInt() and 0xFF)
            val h  = ((mfgData[5].toInt() and 0xFF) shl 8) or (mfgData[6].toInt() and 0xFF)
            val b  = mfgData[2].toInt() and 0xFF
            val f  = mfgData[7].toInt() and 0xFF

            tempX100  = t.toShort().toInt()
            humidX100 = h
            battPct   = b
            alarmFlag = (f and 0x01) != 0
            rssi      = result.rssi
            lastSeenTime = System.currentTimeMillis()

            val addr = result.device.address
            val name = result.device.name ?: "ANTI TAG"
            deviceAddr = addr
            deviceName = name
            prefs.edit().putString(PREF_DEVICE_ADDR, addr).putString(PREF_DEVICE_NAME, name).apply()

            if (alarmFlag) sendAlarmNotification()

            handler.post {
                if (scanning) stopScan()
                btnConnect.isEnabled = true
                updateUI()
            }
        }
    }

    private fun handleConnect() {
        if (connected) { gatt?.disconnect(); return }
        if (deviceAddr.isEmpty()) { toast("请先扫描设备"); return }
        btnConnect.isEnabled = false
        btnConnect.text = "连接中..."
        val device = btAdapter.getRemoteDevice(deviceAddr)
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected = true
                    g.discoverServices()
                    handler.post { btnConnect.isEnabled = true; updateUI() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    uploading = false
                    handler.post {
                        btnConnect.isEnabled = true
                        progressBar.visibility = View.GONE
                        tvProgress.visibility  = View.GONE
                        updateUI()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val svc = g.getService(UUIDS.SERVICE) ?: return
            // 开启 Notify
            svc.getCharacteristic(UUIDS.CHAR_HISTORY)?.let { c ->
                g.setCharacteristicNotification(c, true)
                c.getDescriptor(UUIDS.CCCD)?.let { d ->
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
            }
            handler.postDelayed({ syncTime() }, 600)
            handler.postDelayed({ readDeviceInfo() }, 1200)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, s: Int) {
            if (c.uuid != UUIDS.CHAR_DEVICE_INFO || s != BluetoothGatt.GATT_SUCCESS) return
            val data = c.value ?: return
            if (data.size >= 5) {
                deviceRecordCount = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
                battPct = if (data.size >= 6) data[5].toInt() and 0xFF else battPct
                handler.post {
                    tvDeviceRecords.text = "设备记录：$deviceRecordCount 条"
                    updateUI()
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid != UUIDS.CHAR_HISTORY) return
            val data = c.value ?: return
            // 结束标记
            if (data.size == 2 && data[0] == 0xFF.toByte() && data[1] == 0xFF.toByte()) {
                uploading = false
                handler.post {
                    progressBar.progress   = 100
                    tvProgress.text        = "下载完成 ${records.size} 条"
                    btnDownload.isEnabled  = true
                    recordAdapter?.notifyDataSetChanged()
                    tvEmptyHint.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
                    handler.postDelayed({
                        progressBar.visibility = View.GONE
                        tvProgress.visibility  = View.GONE
                    }, 2000)
                    toast("下载完成，共 ${records.size} 条")
                }
                return
            }
            val recSize = 12
            val batch = mutableListOf<TagRecord>()
            var off = 0
            while (off + recSize <= data.size) {
                val bb   = ByteBuffer.wrap(data, off, recSize).order(ByteOrder.LITTLE_ENDIAN)
                val ts   = bb.int.toLong() and 0xFFFFFFFFL
                val temp = bb.short.toInt()
                val hum  = bb.short.toInt() and 0xFFFF
                val evt  = bb.get().toInt() and 0xFF
                val ax   = bb.get().toInt() and 0xFF
                val bat  = bb.get().toInt() and 0xFF
                batch.add(TagRecord(ts, temp, hum, evt, ax, bat))
                off += recSize
            }
            handler.post {
                records.addAll(batch)
                recordAdapter?.notifyDataSetChanged()
                tvEmptyHint.visibility = View.GONE
                tvRecordCount.text = "本地记录  ${records.size} 条"
                if (deviceRecordCount > 0)
                    progressBar.progress = minOf((records.size * 100 / deviceRecordCount), 95)
                tvProgress.text = "已下载 ${records.size} / $deviceRecordCount 条..."
            }
        }
    }

    private fun syncTime() {
        val c = gatt?.getService(UUIDS.SERVICE)?.getCharacteristic(UUIDS.CHAR_TIME_SYNC) ?: return
        val ts = (System.currentTimeMillis() / 1000).toInt()
        c.value = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(ts).array()
        gatt?.writeCharacteristic(c)
    }

    private fun readDeviceInfo() {
        val c = gatt?.getService(UUIDS.SERVICE)?.getCharacteristic(UUIDS.CHAR_DEVICE_INFO) ?: return
        gatt?.readCharacteristic(c)
    }

    private fun downloadHistory() {
        val c = gatt?.getService(UUIDS.SERVICE)?.getCharacteristic(UUIDS.CHAR_CONTROL) ?: return
        records.clear()
        recordAdapter?.notifyDataSetChanged()
        tvEmptyHint.visibility = View.GONE
        uploading = true
        btnDownload.isEnabled  = false
        progressBar.progress   = 0
        progressBar.visibility = View.VISIBLE
        tvProgress.text        = "开始下载..."
        tvProgress.visibility  = View.VISIBLE
        c.value = byteArrayOf(CMD_START_UPLOAD)
        gatt?.writeCharacteristic(c)
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("清除设备记录")
            .setMessage("将永久删除设备上存储的所有 $deviceRecordCount 条历史记录，此操作不可恢复。")
            .setPositiveButton("确认清除") { _, _ ->
                val c = gatt?.getService(UUIDS.SERVICE)?.getCharacteristic(UUIDS.CHAR_CONTROL) ?: return@setPositiveButton
                c.value = byteArrayOf(CMD_CLEAR_HISTORY)
                gatt?.writeCharacteristic(c)
                deviceRecordCount = 0
                tvDeviceRecords.text = "设备记录：0 条"
                toast("设备记录已清除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(NOTIF_CHANNEL_ID, "防丢报警",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "TAG设备运动报警"
                enableLights(true)
                lightColor = Color.RED
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun sendAlarmNotification() {
        val intent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠ 防丢报警")
            .setContentText("$deviceName 检测到运动！请确认物品安全。")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(1001, n)
    }

    private fun checkPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        return false
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        gatt?.close()
        if (scanning) bleScanner?.stopScan(scanCallback)
    }
}

// RecyclerView Adapter
class RecordAdapter(private val data: List<TagRecord>) :
    RecyclerView.Adapter<RecordAdapter.VH>() {

    inner class VH(val layout: LinearLayout) : RecyclerView.ViewHolder(layout) {
        val tvTime:  TextView = layout.getChildAt(0) as TextView
        val tvEvent: TextView = layout.getChildAt(1) as TextView
        val tvSensor:TextView = layout.getChildAt(2) as TextView
        val tvBatt:  TextView = layout.getChildAt(3) as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int): VH {
        val ctx = parent.context
        val dp  = { v: Int -> (v * ctx.resources.displayMetrics.density).toInt() }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        val tvTime   = TextView(ctx).apply { textSize = 11f; setTextColor(Color.parseColor("#8B949E")) }
        val tvEvent  = TextView(ctx).apply { textSize = 14f; setTextColor(Color.parseColor("#E6EDF3"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, dp(2), 0, dp(2)) }
        val tvSensor = TextView(ctx).apply { textSize = 13f; setTextColor(Color.parseColor("#C9D1D9")) }
        val tvBatt   = TextView(ctx).apply { textSize = 11f; setTextColor(Color.parseColor("#8B949E")) }
        row.addView(tvTime); row.addView(tvEvent); row.addView(tvSensor); row.addView(tvBatt)
        return VH(row)
    }

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val rec = data[data.size - 1 - pos]
        holder.tvTime.text   = rec.timeStr
        holder.tvEvent.text  = rec.eventStr
        holder.tvSensor.text = "${rec.tempStr}  ${rec.humidStr}"
        holder.tvBatt.text   = "电量 ${rec.batteryPct}%"
        holder.layout.setBackgroundColor(
            if (rec.isAlarm) Color.parseColor("#1A0A0A") else Color.TRANSPARENT
        )
        if (rec.isAlarm) {
            holder.tvEvent.setTextColor(Color.parseColor("#DA3633"))
        } else {
            holder.tvEvent.setTextColor(Color.parseColor("#E6EDF3"))
        }
    }

    override fun getItemCount() = data.size
}

private val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
private val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
