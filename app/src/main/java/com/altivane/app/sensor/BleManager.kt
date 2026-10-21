package com.altivane.app.sensor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.altivane.app.domain.GattParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class BleDevice(
    val name: String,
    val addr: String,
    val rssi: Int,
    val isHr: Boolean,
    val isCad: Boolean
)

/** 记住的设备：连接成功过即保存，供快速重连与自动连接 */
data class SavedDevice(
    val addr: String,
    val name: String,
    val isHr: Boolean,
    val isCad: Boolean
)

/** 常用外设类型（按名称启发式 + 广播服务综合判断） */
enum class DevKind(val labelRes: Int) {
    BAND(com.altivane.app.R.string.dev_band), WATCH(com.altivane.app.R.string.dev_watch),
    HR_BELT(com.altivane.app.R.string.dev_hr_belt), CADENCE(com.altivane.app.R.string.dev_cadence),
    OTHER(com.altivane.app.R.string.dev_other)
}

/** 识别设备类型：名称关键词优先，服务类型兜底 */
fun classifyDevice(name: String, isHr: Boolean, isCad: Boolean): DevKind {
    val n = name.lowercase()
    return when {
        // 名称命中最准
        listOf("band", "手环", "charge", "mi band", "smart band", "honor band", "fit").any { n.contains(it) } -> DevKind.BAND
        listOf("watch", "手表", "amazfit", "gtr", "gts", "gt ", "t-rex").any { n.contains(it) } -> DevKind.WATCH
        listOf("hrm", "polar", "tickr", "chest", "心率带", "heart rate", "hr belt").any { n.contains(it) } -> DevKind.HR_BELT
        // 服务类型兜底：仅踏频服务=踏频器
        isCad && !isHr -> DevKind.CADENCE
        isHr -> DevKind.OTHER
        else -> DevKind.OTHER
    }
}

/** 已保存设备持久化（SharedPreferences JSON，系统自带 org.json） */
object BleSaved {
    private const val PREF = "altivane_ble"
    private const val KEY = "devices"

    fun load(ctx: Context): List<SavedDevice> {
        val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SavedDevice(
                    o.optString("addr"), o.optString("name"),
                    o.optBoolean("hr"), o.optBoolean("cad")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun add(ctx: Context, d: SavedDevice) {
        val list = load(ctx).toMutableList()
        list.removeAll { it.addr == d.addr }
        list.add(d)
        persist(ctx, list)
    }

    fun remove(ctx: Context, addr: String) {
        persist(ctx, load(ctx).filterNot { it.addr == addr })
    }

    private fun persist(ctx: Context, list: List<SavedDevice>) {
        val arr = org.json.JSONArray()
        list.forEach { d ->
            arr.put(org.json.JSONObject()
                .put("addr", d.addr).put("name", d.name)
                .put("hr", d.isHr).put("cad", d.isCad))
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}

/**
 * BLE 外设管理：扫描（只列心率/CSC 设备）、多设备连接、标准特征解析。
 */
class BleManager(private val ctx: Context) {

    companion object {
        val UUID_HR_SVC: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val UUID_HR_MEAS: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val UUID_CSC_SVC: UUID = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
        val UUID_CSC_MEAS: UUID = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")
        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bt = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = bt.adapter

    private val _scanResults = MutableStateFlow<List<BleDevice>>(emptyList())
    val scanResults: StateFlow<List<BleDevice>> = _scanResults

    private val _hr = MutableStateFlow(-1)
    val hr: StateFlow<Int> = _hr
    private val _cad = MutableStateFlow(-1)
    val cad: StateFlow<Int> = _cad
    private val _hrName = MutableStateFlow("")
    val hrName: StateFlow<String> = _hrName
    private val _cadName = MutableStateFlow("")
    val cadName: StateFlow<String> = _cadName

    /** 记住的设备（连接成功过即保存） */
    private val _saved = MutableStateFlow(BleSaved.load(ctx))
    val saved: StateFlow<List<SavedDevice>> = _saved

    /** 每设备链路状态：addr → "connecting" / "connected"（面板显示用） */
    private val _linkState = MutableStateFlow<Map<String, String>>(emptyMap())
    val linkState: StateFlow<Map<String, String>> = _linkState

    /** 当前生效的心率/踏频设备 addr（同类型新设备连接时自动切换断开旧的） */
    private val _activeHr = MutableStateFlow<String?>(null)
    val activeHr: StateFlow<String?> = _activeHr
    private val _activeCad = MutableStateFlow<String?>(null)
    val activeCad: StateFlow<String?> = _activeCad

    /** 用户手动点"连接"的设备：连接成功后强制接管生效设备（autoConnect 重连不算） */
    private val manualTakeover = mutableSetOf<String>()
    /** 各设备能力表：addr → 是否含心率/踏频服务（服务发现后登记） */
    private val hrCaps = mutableMapOf<String, Boolean>()
    private val cadCaps = mutableMapOf<String, Boolean>()
    /** 已完成服务发现的设备（可安全 enableNotify，才能被提升为生效设备） */
    private val svcReady = mutableSetOf<String>()

    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var scanJob: Job? = null
    private val gatts = mutableMapOf<String, BluetoothGatt>()
    private var crankPrev: GattParser.CrankState? = null
    /** 用户主动断开时不触发自动重连 */
    private val userDisconnect = java.util.concurrent.atomic.AtomicBoolean(false)

    fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
                ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    fun bluetoothReady(): Boolean = adapter != null && adapter.isEnabled

    @SuppressLint("MissingPermission")
    fun startScan(scope: CoroutineScope) {
        if (scanning || !hasBtPermission() || !bluetoothReady()) return
        stopScan()
        _scanResults.value = emptyList()
        scanner = adapter.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        try {
            scanner?.startScan(null, settings, scanCb)
        } catch (_: SecurityException) {
            scanning = false
            return
        }
        scanJob = scope.launch { delay(8000); stopScan() }
    }

    fun stopScan() {
        if (scanning) {
            try { scanner?.stopScan(scanCb) } catch (_: Exception) {}
            scanning = false
        }
        scanJob?.cancel()
        scanJob = null
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            val dev = result.device ?: return
            val name = try { dev.name } catch (_: SecurityException) { null } ?: return
            val uuids: List<ParcelUuid> = result.scanRecord?.serviceUuids ?: emptyList()
            val isHr = uuids.any { it.uuid == UUID_HR_SVC }
            val isCad = uuids.any { it.uuid == UUID_CSC_SVC }
            if (!isHr && !isCad) return
            val d = BleDevice(name, dev.address, result.rssi, isHr, isCad)
            val list = _scanResults.value.toMutableList()
            list.removeAll { it.addr == d.addr }
            list.add(d)
            _scanResults.value = list.sortedByDescending { it.rssi }
        }
    }

    /**
     * 连接设备。auto=true 时用 autoConnect 模式（系统后台持续重连，适合启动恢复）；
     * 手动点击用 auto=false 直连更快。连接成功发现服务后自动记入已保存设备。
     */
    @SuppressLint("MissingPermission")
    fun connect(addr: String, auto: Boolean = false) {
        if (!hasBtPermission()) return
        if (gatts.containsKey(addr)) {
            // 已连接但处于待机：手动点连接 = 立即接管生效设备
            if (!auto && svcReady.contains(addr)) manualTakeover.add(addr).also { takeOver(addr) }
            return
        }
        val dev = adapter.getRemoteDevice(addr) ?: return
        userDisconnect.set(false)
        // 手动连接 = 明确想用这台设备：服务发现后强制接管（自动重连不接管，防双设备互踢）
        if (!auto) manualTakeover.add(addr)
        com.altivane.app.domain.AppLog.i("BLE", "connect $addr auto=$auto")
        _linkState.value = _linkState.value + (addr to "connecting")
        try {
            val g = dev.connectGatt(ctx, auto, gattCb, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
            gatts[addr] = g
        } catch (_: Exception) {}
    }

    /** 强制把 addr 提为生效设备（断开旧生效设备）；仅对服务已就绪的连接有效 */
    @SuppressLint("MissingPermission")
    private fun takeOver(addr: String) {
        if (hrCaps[addr] == true) {
            val old = _activeHr.value
            if (old != null && old != addr && old in gatts) {
                gatts.remove(old)?.let { og -> try { og.disconnect(); og.close() } catch (_: Exception) {} }
                _linkState.value = _linkState.value - old
            }
            _activeHr.value = addr
            _hrName.value = try { gatts[addr]?.device?.name } catch (_: SecurityException) { null } ?: addr
            com.altivane.app.domain.AppLog.i("BLE", "HR manual takeover ${old ?: "?"} -> $addr")
        }
        if (cadCaps[addr] == true) {
            val old = _activeCad.value
            if (old != null && old != addr && old in gatts) {
                gatts.remove(old)?.let { og -> try { og.disconnect(); og.close() } catch (_: Exception) {} }
                _linkState.value = _linkState.value - old
            }
            _activeCad.value = addr
            _cadName.value = try { gatts[addr]?.device?.name } catch (_: SecurityException) { null } ?: addr
            com.altivane.app.domain.AppLog.i("BLE", "CAD manual takeover ${old ?: "?"} -> $addr")
        }
    }

    /**
     * 自动连接全部记住的设备（App 启动 / 运动开始时调用）：
     * 未在连接中的保存设备用 autoConnect 挂上，系统在设备可连时自动完成连接。
     * 已存在但已死（断线未回调/半开）的 GATT 对象先清掉再连，防止永久卡住不重连。
     */
    @SuppressLint("MissingPermission")
    fun reconnectSaved() {
        if (!hasBtPermission() || !bluetoothReady()) return
        userDisconnect.set(false)
        _saved.value.forEach { d ->
            val g = gatts[d.addr]
            if (g != null) {
                val live = try {
                    val st = bt.getConnectionState(
                        g.device, android.bluetooth.BluetoothProfile.GATT
                    )
                    st == android.bluetooth.BluetoothProfile.STATE_CONNECTED ||
                            st == android.bluetooth.BluetoothProfile.STATE_CONNECTING
                } catch (_: Exception) { false }
                if (live) return@forEach
                // 死连接：清掉重建
                gatts.remove(d.addr)
                try { g.disconnect(); g.close() } catch (_: Exception) {}
            }
            try {
                val dev = adapter.getRemoteDevice(d.addr) ?: return@forEach
                val ng = dev.connectGatt(ctx, true, gattCb, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
                gatts[d.addr] = ng
            } catch (_: Exception) {}
        }
    }

    /** 移除记住的设备（正在连接则一并断开且不自动重连，生效数据流同步清理） */
    @SuppressLint("MissingPermission")
    fun removeSaved(addr: String) {
        BleSaved.remove(ctx, addr)
        _saved.value = BleSaved.load(ctx)
        userDisconnect.set(true)
        _linkState.value = _linkState.value - addr
        svcReady.remove(addr); hrCaps.remove(addr); cadCaps.remove(addr); manualTakeover.remove(addr)
        gatts.remove(addr)?.let { g ->
            try { g.disconnect(); g.close() } catch (_: Exception) {}
        }
        if (_activeHr.value == addr) { _activeHr.value = null; _hr.value = -1; _hrName.value = "" }
        if (_activeCad.value == addr) { _activeCad.value = null; _cad.value = -1; _cadName.value = "" }
    }

    @SuppressLint("MissingPermission")
    fun disconnectAll() {
        userDisconnect.set(true)
        gatts.values.forEach { g ->
            try { g.disconnect(); g.close() } catch (_: Exception) {}
        }
        gatts.clear()
        svcReady.clear(); hrCaps.clear(); cadCaps.clear(); manualTakeover.clear()
        _hr.value = -1; _cad.value = -1
        _hrName.value = ""; _cadName.value = ""
        crankPrev = null
    }

    // 显式类型：回调内 connectGatt 引用 gattCb 自身，类型推断会递归爆栈
    private val gattCb: BluetoothGattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    com.altivane.app.domain.AppLog.i("BLE", "GATT connected ${try { g.device?.address } catch (_: Exception) { "?" }}")
                    try { g.discoverServices() } catch (_: Exception) {}
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val addr = try { g.device?.address } catch (_: SecurityException) { null }
                    com.altivane.app.domain.AppLog.w("BLE", "GATT disconnected addr=$addr user=${userDisconnect.get()}")
                    try { g.close() } catch (_: Exception) {}
                    if (addr == null) return
                    svcReady.remove(addr)
                    val remembered = _saved.value.any { it.addr == addr }
                    if (!userDisconnect.get() && remembered) {
                        // 意外断线（手环超时/走远/广播重启）：autoConnect 挂回，系统后台自动重连
                        _linkState.value = _linkState.value + (addr to "connecting")
                        try {
                            val dev = adapter.getRemoteDevice(addr)
                            val ng = dev.connectGatt(ctx, true, gattCb, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
                            gatts[addr] = ng
                        } catch (_: Exception) {
                            gatts.remove(addr)
                            _linkState.value = _linkState.value - addr
                        }
                    } else {
                        gatts.remove(addr)
                        _linkState.value = _linkState.value - addr
                    }
                    // 仅当断开的是当前生效设备才清空数据流（防止误清新设备数据）
                    if (_activeHr.value == addr) {
                        _activeHr.value = null; _hr.value = -1; _hrName.value = ""
                        // 乒乓防护的关键：生效设备掉了，把另一台已就绪的心率设备原地提升，
                        // 而不是等它重连后再触发"新设备抢生效"的互踢循环
                        promoteStandby(addr, hrCaps, _activeHr, _hrName, "HR")
                    }
                    if (_activeCad.value == addr) {
                        _activeCad.value = null; _cad.value = -1; _cadName.value = ""
                        promoteStandby(addr, cadCaps, _activeCad, _cadName, "CAD")
                    }
                }
            }
        }

        /** 生效设备断开后，从仍连接且服务就绪的设备里提升下一台接管数据流 */
        private fun promoteStandby(
            dropped: String,
            caps: Map<String, Boolean>,
            active: MutableStateFlow<String?>,
            nameFlow: MutableStateFlow<String>,
            tag: String
        ) {
            @Suppress("ControlFlowWithEmptyBody")
            val next = gatts.keys.firstOrNull {
                it != dropped && caps[it] == true && svcReady.contains(it)
            } ?: return
            active.value = next
            nameFlow.value = try { gatts[next]?.device?.name } catch (_: SecurityException) { null } ?: next
            com.altivane.app.domain.AppLog.i("BLE", "$tag standby promoted $dropped -> $next")
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            try {
                val addr = g.device?.address ?: return
                val hasHr = g.getService(UUID_HR_SVC)?.getCharacteristic(UUID_HR_MEAS) != null
                val hasCad = g.getService(UUID_CSC_SVC)?.getCharacteristic(UUID_CSC_MEAS) != null
                if (hasHr || hasCad) svcReady.add(addr)
                // 心率设备接管判定：仅三种情况换生效设备——
                // ① 当前无生效设备（autoConnect 重连归位）
                // ② 用户手动点了连接（manualTakeover）
                // ③ 记录的旧生效设备已不在连接表（掉线未清）
                // 其余情况一律做"待机"：不抢生效、不断旧设备——两台已保存设备
                // 双双 autoConnect 时不再互踢（crash.txt 10-15：750ms 一次乒乓循环）
                if (hasHr) {
                    hrCaps[addr] = true
                    val old = _activeHr.value
                    val takeover = old == null || old !in gatts || manualTakeover.remove(addr)
                    if (takeover) {
                        takeOver(addr)
                    } else {
                        com.altivane.app.domain.AppLog.i("BLE", "HR device $addr standby (active=$old)")
                    }
                    g.getService(UUID_HR_SVC)!!.getCharacteristic(UUID_HR_MEAS)!!.let { enableNotify(g, it) }
                }
                if (hasCad) {
                    cadCaps[addr] = true
                    val old = _activeCad.value
                    val takeover = old == null || old !in gatts || manualTakeover.remove(addr)
                    if (takeover) {
                        takeOver(addr)
                    } else {
                        com.altivane.app.domain.AppLog.i("BLE", "CAD device $addr standby (active=$old)")
                    }
                    g.getService(UUID_CSC_SVC)!!.getCharacteristic(UUID_CSC_MEAS)!!.let { enableNotify(g, it) }
                }
                // 发现任一目标服务 → 已连接 + 记住设备（下次启动/运动自动连）
                if (hasHr || hasCad) {
                    val nm = (try { g.device?.name } catch (_: SecurityException) { null }) ?: addr
                    BleSaved.add(ctx, SavedDevice(addr, nm, hasHr, hasCad))
                    _saved.value = BleSaved.load(ctx)
                    _linkState.value = _linkState.value + (addr to "connected")
                    com.altivane.app.domain.AppLog.i("BLE", "services discovered $nm hr=$hasHr cad=$hasCad")
                } else {
                    com.altivane.app.domain.AppLog.w("BLE", "services discovered but no HR/CSC svc, status=$status")
                }
            } catch (_: Exception) {}
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) = handleData(g, c, value)

        @Deprecated("legacy API 29-32")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic
        ) {
            val v = c.value ?: return
            handleData(g, c, v)
        }
    }

    /** 数据只从当前生效设备收（多设备连接时不混淆） */
    private fun handleData(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray) {
        val a = try { g.device?.address } catch (_: SecurityException) { null }
        when (c.uuid) {
            UUID_HR_MEAS -> if (a == _activeHr.value) GattParser.heartRate(data)?.let { _hr.value = it }
            UUID_CSC_MEAS -> if (a == _activeCad.value) {
                val (rpm, st) = GattParser.cadence(crankPrev, data)
                crankPrev = st
                rpm?.let { _cad.value = it }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        try {
            g.setCharacteristicNotification(ch, true)
            val d = ch.getDescriptor(UUID_CCCD) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        } catch (_: Exception) {}
    }
}
