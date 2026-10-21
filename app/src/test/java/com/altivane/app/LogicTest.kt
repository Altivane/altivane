package com.altivane.app

import com.altivane.app.domain.GattParser
import com.altivane.app.domain.RideStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 纯逻辑离线单测：统计算法 + BLE 协议解析（数据正确性是第一优先级） */
class LogicTest {

    @Test
    fun haversine_samePoint_isZero() {
        assertEquals(0.0, RideStats.haversine(40.0, 116.0, 40.0, 116.0), 1e-6)
    }

    @Test
    fun haversine_001degLat_about111m() {
        val d = RideStats.haversine(40.0, 116.0, 40.001, 116.0)
        assertTrue("actual=$d", abs(d - 111.2) < 2.0)
    }

    @Test
    fun haversine_001degLon_atLat40() {
        // 纬度 40° 处 0.001° 经度 ≈ 111.2 * cos(40°) ≈ 85.2m
        val d = RideStats.haversine(40.0, 116.0, 40.0, 116.001)
        assertTrue("actual=$d", abs(d - 85.2) < 2.0)
    }

    @Test
    fun elevGain_filtersNoise_andTracksValleyPeak() {
        // 手算：min=100 → 105 触发 gain+5(min=105) → 103 min=103 → 108 gain+5(min=108)
        //       → 100 min=100 → 110 gain+10 → 总 20
        val g = RideStats.elevGain(listOf(100.0, 102.0, 105.0, 103.0, 108.0, 100.0, 110.0), 3.0)
        assertEquals(20.0, g, 0.01)
    }

    @Test
    fun elevGain_pureNoise_isZero() {
        val g = RideStats.elevGain(listOf(100.0, 101.5, 99.0, 101.0, 100.5, 101.2), 3.0)
        assertEquals(0.0, g, 0.01)
    }

    @Test
    fun heartRate_uint8() {
        assertEquals(60, GattParser.heartRate(byteArrayOf(0x00, 0x3C)))
    }

    @Test
    fun heartRate_uint16() {
        assertEquals(0x013C, GattParser.heartRate(byteArrayOf(0x01, 0x3C, 0x01)))
    }

    @Test
    fun heartRate_shortData_isNull() {
        assertNull(GattParser.heartRate(byteArrayOf(0x00)))
    }

    @Test
    fun cadence_firstPacket_returnsStateOnly() {
        val (rpm, st) = GattParser.cadence(null, byteArrayOf(0x02, 10, 0, 0x00, 0x04))
        assertNull(rpm)
        assertEquals(10, st?.revs)
        assertEquals(1024, st?.time1024)
    }

    @Test
    fun cadence_secondPacket_computesRpm() {
        val (_, st) = GattParser.cadence(null, byteArrayOf(0x02, 10, 0, 0x00, 0x04))
        // rev+1, time 1024→1638 (Δ0.6s) → 100 rpm
        val (rpm, _) = GattParser.cadence(st, byteArrayOf(0x02, 11, 0, 0x66, 0x06))
        assertEquals(100, rpm)
    }

    @Test
    fun cadence_stoppedPedaling_timeAdvances_returnsZero() {
        val (_, st) = GattParser.cadence(null, byteArrayOf(0x02, 10, 0, 0x00, 0x04))
        // 转数不变、时间戳推进（1024→1638）→ 真停踩 → 0
        val (rpm, _) = GattParser.cadence(st, byteArrayOf(0x02, 10, 0, 0x66, 0x06))
        assertEquals(0, rpm)
    }

    @Test
    fun cadence_identicalPacket_noNewInfo_keepsPrev() {
        // 完全相同的包 = 无新事件 → null（UI 保持上次值），不参与 rpm 计算
        val (_, st) = GattParser.cadence(null, byteArrayOf(0x02, 10, 0, 0x00, 0x04))
        val (rpm, st2) = GattParser.cadence(st, byteArrayOf(0x02, 10, 0, 0x00, 0x04))
        assertNull(rpm)
        assertEquals(st, st2)
    }

    @Test
    fun cadence_noCrankFlag_keepsPrev() {
        val (_, st) = GattParser.cadence(null, byteArrayOf(0x02, 10, 0, 0x00, 0x04))
        val (rpm, st2) = GattParser.cadence(st, byteArrayOf(0x00, 1, 2, 3, 4))
        assertNull(rpm)
        assertEquals(st, st2)
    }
}
