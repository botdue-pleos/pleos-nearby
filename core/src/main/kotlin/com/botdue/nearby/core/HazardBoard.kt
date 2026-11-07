package com.botdue.nearby.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * 앞길 주의보 판정 ("증인이 두 명은 있어야 인정").
 * - 같은 도로·같은 방향, 같은 구간이나 바로 옆 구간에서 30초 안에 서로 다른 차의 신호가 2개 이상 → 주의보
 *   (옆 구간까지 보는 이유: 200m 경계에서 두 차의 급제동이 서로 다른 구간으로 갈라지는 걸 막으려고)
 * - 주의보에는 도로 구간·방향·생성 시각·터널 여부만 남는다. 누가 신호를 냈는지는 남기지 않는다.
 * - 5분 뒤 소멸. 그동안 새 신호가 또 들어오면 그 시점부터 5분 연장.
 * - 지도용 통계는 (구간, 요일, 시) 단위 건수만 센다.
 */
class HazardBoard(
    private val windowMs: Long = 30_000,
    private val ttlMs: Long = 5 * 60_000L,
    private val minSignals: Int = 2,
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) {
    private data class Signal(val carId: String, val pos: RoadPos, val at: Long, val inTunnel: Boolean)

    class Advisory(
        val alertId: String,
        val at: RoadPos,
        val createdAt: Long,
        var expiresAt: Long,
        var inTunnel: Boolean,
    ) {
        /** 이미 알린 차(익명 ID). 같은 주의보를 두 번 말하지 않으려고. 주의보와 함께 사라진다 */
        val notified = mutableSetOf<String>()
    }

    data class StatKey(val roadId: String, val heading: Heading, val segment: Int, val dayOfWeek: Int, val hour: Int)

    private val signals = mutableListOf<Signal>()
    private val advisories = mutableListOf<Advisory>()
    private val stats = mutableMapOf<StatKey, Int>()
    private var seq = 0

    val active: List<Advisory> get() = advisories

    /** 새로 생긴 주의보를 돌려준다 (없으면 null) */
    fun add(carId: String, pos: RoadPos, at: Long, inTunnel: Boolean): Advisory? {
        signals.removeAll { it.at < at - windowMs }
        signals += Signal(carId, pos, at, inTunnel)
        val near = signals.filter { it.pos.sameLane(pos) && abs(it.pos.segment - pos.segment) <= 1 }

        val existing = advisories.firstOrNull { it.at.sameLane(pos) && abs(it.at.segment - pos.segment) <= 1 }
        if (existing != null) {
            existing.expiresAt = at + ttlMs
            existing.inTunnel = existing.inTunnel || inTunnel
            return null
        }
        if (near.map { it.carId }.distinct().size < minSignals) return null

        val advisory = Advisory("h${++seq}", pos.segmentCenter(), at, at + ttlMs, near.any { it.inTunnel })
        // 신호를 낸 차는 이미 알고 있으니 다시 알리지 않는다
        advisory.notified += near.map { it.carId }
        advisories += advisory
        signals.removeAll { it in near }
        val time = Instant.ofEpochMilli(at).atZone(zone)
        val key = StatKey(pos.roadId, pos.heading, pos.segment, time.dayOfWeek.value, time.hour)
        stats[key] = (stats[key] ?: 0) + 1
        return advisory
    }

    /** 소멸한 주의보를 돌려준다 */
    fun expire(now: Long): List<Advisory> {
        val gone = advisories.filter { it.expiresAt <= now }
        advisories -= gone.toSet()
        return gone
    }

    /** 위험 지점 지도: 건수가 [min]건 이상 쌓인 칸만 보여 준다 */
    fun hotspots(min: Int = 5): Map<StatKey, Int> = stats.filterValues { it >= min }

    /** 시연용: 지난 몇 주치 통계를 미리 채운다 */
    fun seedStats(seed: Map<StatKey, Int>) {
        seed.forEach { (k, v) -> stats[k] = (stats[k] ?: 0) + v }
    }
}
