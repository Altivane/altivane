package com.altivane.app.domain

/** BLE GATT 标准特征解析（纯函数，可离线单测） */
object GattParser {

    /** 0x2A37 Heart Rate Measurement -> bpm */
    fun heartRate(data: ByteArray): Int? {
        if (data.size < 2) return null
        val flags = data[0].toInt() and 0xFF
        return if (flags and 0x01 != 0) {
            // uint16 LE
            if (data.size >= 3)
                (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
            else null
        } else {
            data[1].toInt() and 0xFF
        }
    }

    data class CrankState(val revs: Int, val time1024: Int)

    /**
     * 0x2A5B CSC Measurement -> rpm。
     * 累计曲柄转数与 1/1024s 计时均为 uint16，需处理回绕。
     * 返回 (rpm|null, 新状态)。flags bit1 = 曲柄数据存在。
     */
    fun cadence(prev: CrankState?, data: ByteArray): Pair<Int?, CrankState?> {
        if (data.isEmpty()) return null to prev
        val flags = data[0].toInt() and 0xFF
        if (flags and 0x02 == 0) return null to prev
        var off = 1
        if (flags and 0x01 != 0) off += 6 // 轮转数据在前
        if (data.size < off + 4) return null to prev
        val revs = (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
        val time = (data[off + 2].toInt() and 0xFF) or ((data[off + 3].toInt() and 0xFF) shl 8)
        val st = CrankState(revs, time)
        if (prev == null) return null to st
        val dRev = (revs - prev.revs) and 0xFFFF
        val dTime = (time - prev.time1024) and 0xFFFF
        if (dTime == 0) return null to st
        if (dRev == 0) return 0 to st // 停踩
        val rpm = dRev * 60.0 * 1024.0 / dTime
        if (rpm > 240.0) return null to st // 回绕误判，丢弃
        return rpm.toInt() to st
    }
}
