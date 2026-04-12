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
import java.text.SimpleDateFormat
import java.util.*

/*──────────────────────────────────────────────────────────────────
 *  常量
 *──────────────────────────────────────────────────────────────────*/
const val COMPANY_ID_INT   = 0xABCD
const val PROTOCOL_VER     = 0x06
const val PREF_FILE        = "antilost"
const val NOTIF_CHANNEL_ID = "antilost_alarm"
val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT

/*──────────────────────────────────────────────────────────────────
 *  TAG 数据模型
 *──────────────────────────────────────────────────────────────────*/
data class TagDevice(
    val address:    String,
    var name:       String    = "ANTI TAG",
    var rssi:       Int       = -100,
    var tempX100:   Int       = 0,
    var humidX100:  Int       = 0,
    var battPct:    Int       = 0,
    var alarmFlag:  Boolean   = false,
    var lastSeen:   Long      = 0L
) {
    val tempStr  get() = "%.1f°C".format(tempX100 / 100.0)
    val humidStr get() = "%.0f%%".format(humidX100 / 100.0)
    val rssiStr  get() = "${rssi}dBm"
    val lastSeenStr get(): String {
        if (lastSeen == 0L) return ""
        val secs = (System.currentTimeMillis() - lastSeen) / 1000
        return when {
            secs < 5   -> "刚刚"
            secs < 60  -> "${secs}秒前"
            secs < 3600 -> "${secs/60}分钟前"
            else       -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastSeen))
        }
    }
    val signalLevel get() = when {
        rssi >= -60 -> 3
        rssi >= -75 -> 2
        rssi >= -90 -> 1
        else        -> 0
    }
}

/*──────────────────────────────────────────────────────────────────
 *  MainActivity — 设备列表主界面
 *──────────────────────────────────────────────────────────────────*/
class MainActivity : AppCompatActivity() {

    private lateinit var btAdapter:  BluetoothAdapter
    private var bleScanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler  = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREF_FILE, MODE_PRIVATE) }

    /* 设备列表（用 address 做 key 去重）*/
    private val deviceMap = LinkedHashMap<String, TagDevice>()
    private val deviceList = mutableListOf<TagDevice>()
    private var adapter: DeviceAdapter? = null

    /* Views */
    private lateinit var btnScan:    Button
    private lateinit var tvScanHint: TextView
    private lateinit var rvDevices:  RecyclerView
    private lateinit var tvEmpty:    TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        buildUI()
        setupBluetooth()
        checkPermissions()
    }

    /*──────────── UI ────────────*/
    private fun buildUI() {
        val bg = Color.parseColor("#0D1117")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        /* 顶栏 */
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#161B22"))
            setPadding(dp(20), dp(44), dp(20), dp(16))
        }
        val tvTitle = TextView(this).apply {
            text     = "防丢标签"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E6EDF3"))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        btnScan = Button(this).apply {
            text      = "扫描"
            textSize  = 13f
            setTextColor(Color.parseColor("#58A6FF"))
            setBackgroundColor(Color.parseColor("#1F2937"))
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setOnClickListener { toggleScan() }
        }
        topBar.addView(tvTitle)
        topBar.addView(btnScan)
        root.addView(topBar)

        /* 扫描提示 */
        tvScanHint = TextView(this).apply {
            text       = ""
            textSize   = 12f
            setTextColor(Color.parseColor("#E3B341"))
            gravity    = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#161B22"))
        }
        root.addView(tvScanHint)

        /* 空状态提示 */
        tvEmpty = TextView(this).apply {
            text     = "暂未发现设备\n点击「扫描」开始搜索附近的防丢标签"
            textSize = 14f
            setTextColor(Color.parseColor("#8B949E"))
            gravity  = Gravity.CENTER
            setPadding(dp(40), dp(80), dp(40), dp(40))
        }
        root.addView(tvEmpty)

        /* 设备列表 */
        rvDevices = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setBackgroundColor(bg)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        adapter = DeviceAdapter(deviceList) { device ->
            /* 点击设备 → 进入详情页 */
            val intent = Intent(this, DeviceDetailActivity::class.java).apply {
                putExtra("address", device.address)
                putExtra("name",    device.name)
                putExtra("temp",    device.tempX100)
                putExtra("humid",   device.humidX100)
                putExtra("batt",    device.battPct)
                putExtra("rssi",    device.rssi)
                putExtra("alarm",   device.alarmFlag)
            }
            startActivity(intent)
        }
        rvDevices.adapter = adapter
        root.addView(rvDevices)

        setContentView(root)
    }

    /*──────────── 蓝牙 ────────────*/
    private fun setupBluetooth() {
        val bm = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter  = bm.adapter
        bleScanner = btAdapter.bluetoothLeScanner
    }

    private fun toggleScan() {
        if (scanning) stopScan() else startScan()
    }

    private fun startScan() {
        if (!checkPermissions()) return
        scanning = true
        btnScan.text = "停止"
        btnScan.setTextColor(Color.parseColor("#DA3633"))
        tvScanHint.text       = "正在扫描..."
        tvScanHint.visibility = View.VISIBLE

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bleScanner?.startScan(null, settings, scanCallback)

        /* 15秒自动停止 */
        handler.postDelayed({ if (scanning) stopScan() }, 15000)
    }

    private fun stopScan() {
        scanning = false
        btnScan.text = "扫描"
        btnScan.setTextColor(Color.parseColor("#58A6FF"))
        bleScanner?.stopScan(scanCallback)
        tvScanHint.text = if (deviceList.isEmpty()) "" else "找到 ${deviceList.size} 个设备"
        handler.postDelayed({ tvScanHint.visibility = View.GONE }, 2000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mfg = result.scanRecord?.getManufacturerSpecificData(COMPANY_ID_INT) ?: return
            if (mfg.size < 8) return
            if ((mfg[0].toInt() and 0xFF) != PROTOCOL_VER) return

            val addr  = result.device.address
            val name  = result.device.name?.takeIf { it.isNotEmpty() } ?: "ANTI TAG"
            val temp  = (((mfg[3].toInt() and 0xFF) shl 8) or (mfg[4].toInt() and 0xFF)).toShort().toInt()
            val humid = ((mfg[5].toInt() and 0xFF) shl 8) or (mfg[6].toInt() and 0xFF)
            val batt  = mfg[2].toInt() and 0xFF
            val alarm = (mfg[7].toInt() and 0x01) != 0

            handler.post {
                val dev = deviceMap.getOrPut(addr) { TagDevice(addr) }
                dev.name      = name
                dev.rssi      = result.rssi
                dev.tempX100  = temp
                dev.humidX100 = humid
                dev.battPct   = batt
                dev.alarmFlag = alarm
                dev.lastSeen  = System.currentTimeMillis()

                /* 刷新列表 */
                deviceList.clear()
                deviceList.addAll(deviceMap.values.sortedByDescending { it.rssi })
                adapter?.notifyDataSetChanged()
                tvEmpty.visibility    = if (deviceList.isEmpty()) View.VISIBLE else View.GONE
                rvDevices.visibility  = if (deviceList.isEmpty()) View.GONE else View.VISIBLE

                if (alarm) sendAlarmNotification(name)
            }
        }
    }

    /*──────────── 通知 ────────────*/
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

    private fun sendAlarmNotification(name: String) {
        val intent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠ 防丢报警")
            .setContentText("$name 检测到运动！请确认物品安全。")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(1001, n)
    }

    /*──────────── 权限 ────────────*/
    private fun checkPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        return false
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        if (scanning) bleScanner?.stopScan(scanCallback)
    }
}

/*──────────────────────────────────────────────────────────────────
 *  设备列表 Adapter
 *──────────────────────────────────────────────────────────────────*/
class DeviceAdapter(
    private val data:    List<TagDevice>,
    private val onClick: (TagDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    inner class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val tvName:    TextView = root.findViewWithTag("name")
        val tvAddr:    TextView = root.findViewWithTag("addr")
        val tvTemp:    LinearLayout = root.findViewWithTag("temp")
        val tvHumid:   LinearLayout = root.findViewWithTag("humid")
        val tvBatt:    LinearLayout = root.findViewWithTag("batt")
        val tvRssi:    LinearLayout = root.findViewWithTag("rssi")
        val tvAlarm:   TextView = root.findViewWithTag("alarm")
        val tvLastSeen:TextView = root.findViewWithTag("lastseen")
        val vSignal:   View     = root.findViewWithTag("signal")
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int): VH {
        val ctx = parent.context
        fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

        /* 卡片根布局 */
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161B22"))
            setPadding(dp(20), dp(16), dp(20), dp(16))
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        /* 第一行：设备名 + 报警标签 + 信号 */
        val row1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
        }
        val tvName = makeText(ctx, "", 15f, "#E6EDF3", bold = true).apply { tag = "name"
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        val tvAlarm = makeText(ctx, "⚠ 报警", 11f, "#DA3633").apply { tag = "alarm"
            setBackgroundColor(Color.parseColor("#3D0F0F"))
            setPadding(dp(8), dp(3), dp(8), dp(3))
            visibility = View.GONE }
        val vSignal = View(ctx).apply {
            tag = "signal"
            setBackgroundColor(Color.parseColor("#56D364"))
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).also { it.marginStart = dp(10) }
        }
        row1.addView(tvName); row1.addView(tvAlarm); row1.addView(vSignal)
        card.addView(row1)

        /* 第二行：地址 + 最后更新 */
        val row2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(12))
        }
        val tvAddr = makeText(ctx, "", 11f, "#8B949E").apply { tag = "addr"
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        val tvLastSeen = makeText(ctx, "", 11f, "#8B949E").apply { tag = "lastseen" }
        row2.addView(tvAddr); row2.addView(tvLastSeen)
        card.addView(row2)

        /* 分割线 */
        card.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#21262D"))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).also {
                it.bottomMargin = dp(12) }
        })

        /* 第三行：温度 湿度 电量 信号 */
        val row3 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvTemp  = makeMetric(ctx, "--.-°C", "温度").apply { tag = "temp" }
        val tvHumid = makeMetric(ctx, "--%",    "湿度").apply { tag = "humid" }
        val tvBatt  = makeMetric(ctx, "--%",    "电量").apply { tag = "batt" }
        val tvRssi  = makeMetric(ctx, "--dBm",  "信号").apply { tag = "rssi" }
        listOf(tvTemp, tvHumid, tvBatt, tvRssi).forEach {
            it.layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            row3.addView(it)
        }
        card.addView(row3)

        /* 点击进详情 - pos在onBindViewHolder里绑定 */
        card.setOnClickListener(null)

        /* 分隔条 */
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        wrapper.addView(card)
        wrapper.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#0D1117"))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(8))
        })

        return VH(card)
    }

    private fun makeText(ctx: android.content.Context, txt: String, size: Float,
                         color: String, bold: Boolean = false): TextView {
        return TextView(ctx).apply {
            text     = txt
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }

    private fun makeMetric(ctx: android.content.Context, value: String, label: String): LinearLayout {
        fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        col.addView(TextView(ctx).apply {
            text = value; textSize = 16f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E6EDF3")); gravity = Gravity.CENTER
        })
        col.addView(TextView(ctx).apply {
            text = label; textSize = 10f
            setTextColor(Color.parseColor("#8B949E")); gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        })
        return col
    }

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val dev = data[pos]
        holder.tvName.text     = dev.name
        holder.tvAddr.text     = dev.address
        (holder.tvTemp  as LinearLayout).getChildAt(0).let { (it as TextView).text = dev.tempStr }
        (holder.tvHumid as LinearLayout).getChildAt(0).let { (it as TextView).text = dev.humidStr }
        (holder.tvBatt  as LinearLayout).getChildAt(0).let { (it as TextView).text = "${dev.battPct}%" }
        (holder.tvRssi  as LinearLayout).getChildAt(0).let { (it as TextView).text = dev.rssiStr }
        holder.tvLastSeen.text = dev.lastSeenStr
        holder.tvAlarm.visibility = if (dev.alarmFlag) View.VISIBLE else View.GONE
        /* 信号颜色 */
        val sigColor = when (dev.signalLevel) {
            3    -> "#56D364"
            2    -> "#E3B341"
            1    -> "#F97316"
            else -> "#DA3633"
        }
        holder.vSignal.setBackgroundColor(Color.parseColor(sigColor))
        /* 卡片背景：报警时加深红色调 */
        holder.root.setBackgroundColor(
            if (dev.alarmFlag) Color.parseColor("#1A0808") else Color.parseColor("#161B22")
        )
        /* 点击跳转 */
        holder.root.setOnClickListener { onClick(dev) }
    }

    override fun getItemCount() = data.size
}

/*──────────────────────────────────────────────────────────────────
 *  设备详情页 Activity
 *──────────────────────────────────────────────────────────────────*/
class DeviceDetailActivity : AppCompatActivity() {

    private var address  = ""
    private var tempX100 = 0
    private var humidX100 = 0
    private var battPct  = 0
    private var rssi     = -100
    private var alarm    = false
    private var name     = "ANTI TAG"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address   = intent.getStringExtra("address") ?: ""
        name      = intent.getStringExtra("name")    ?: "ANTI TAG"
        tempX100  = intent.getIntExtra("temp",  0)
        humidX100 = intent.getIntExtra("humid", 0)
        battPct   = intent.getIntExtra("batt",  0)
        rssi      = intent.getIntExtra("rssi",  -100)
        alarm     = intent.getBooleanExtra("alarm", false)
        buildUI()
    }

    private fun buildUI() {
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        val bg = Color.parseColor("#0D1117")

        val scroll = NestedScrollView(this).apply {
            setBackgroundColor(bg)
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        /* 顶栏 */
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#161B22"))
            setPadding(dp(16), dp(44), dp(20), dp(16))
        }
        val btnBack = TextView(this).apply {
            text     = "‹ 返回"
            textSize = 16f
            setTextColor(Color.parseColor("#58A6FF"))
            setPadding(0, 0, dp(16), 0)
            setOnClickListener { finish() }
        }
        val tvTitle = TextView(this).apply {
            text     = name
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E6EDF3"))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        topBar.addView(btnBack)
        topBar.addView(tvTitle)
        root.addView(topBar)

        /* 报警横幅 */
        if (alarm) {
            root.addView(TextView(this).apply {
                text    = "⚠  检测到运动报警"
                textSize = 14f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#DA3633"))
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(14))
            })
        }

        /* 大温湿度卡 */
        val cardMain = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161B22"))
            setPadding(dp(24), dp(28), dp(24), dp(28))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                it.topMargin = dp(12); it.bottomMargin = dp(8)
                it.marginStart = dp(16); it.marginEnd = dp(16)
            }
        }
        val tempRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val tvTemp = TextView(this).apply {
            text = "%.1f°C".format(tempX100 / 100.0)
            textSize = 56f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E6EDF3"))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val humidCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
        humidCol.addView(TextView(this).apply { text = "湿度"; textSize = 12f; setTextColor(Color.parseColor("#8B949E")) })
        humidCol.addView(TextView(this).apply {
            text = "%.0f%%".format(humidX100 / 100.0)
            textSize = 32f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#58A6FF"))
        })
        tempRow.addView(tvTemp); tempRow.addView(humidCol)
        cardMain.addView(tempRow)
        cardMain.addView(TextView(this).apply {
            text = address; textSize = 12f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, dp(16), 0, 0)
        })
        root.addView(cardMain)

        /* 指标网格 */
        val grid = GridLayout(this).apply {
            columnCount = 2
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                it.marginStart = dp(16); it.marginEnd = dp(16); it.bottomMargin = dp(8)
            }
        }
        listOf(
            Triple("${battPct}%",  "电量",   "#56D364"),
            Triple("${rssi}dBm",   "信号强度", if (rssi >= -75) "#56D364" else "#E3B341"),
            Triple(if (alarm) "有报警" else "正常", "运动状态", if (alarm) "#DA3633" else "#56D364"),
            Triple("--",           "历史记录", "#58A6FF")
        ).forEach { (value, label, color) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#161B22"))
                setPadding(dp(20), dp(20), dp(20), dp(20))
                layoutParams = GridLayout.LayoutParams().apply {
                    width  = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
            }
            cell.addView(TextView(this).apply {
                text = label; textSize = 12f; setTextColor(Color.parseColor("#8B949E"))
            })
            cell.addView(TextView(this).apply {
                text = value; textSize = 22f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(color))
                setPadding(0, dp(6), 0, 0)
            })
            grid.addView(cell)
        }
        root.addView(grid)

        /* 说明 */
        root.addView(TextView(this).apply {
            text    = "点击「下载记录」可查看完整的温湿度历史和报警记录\n（需连接设备后操作）"
            textSize = 13f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(dp(24), dp(16), dp(24), dp(32))
        })

        scroll.addView(root)
        setContentView(scroll)
    }
}
