package com.botdue.nearby.core

/** 서버가 어느 차(익명 ID)에 무엇을 보낼지 */
data class Delivery(val to: String, val msg: Downlink)

/**
 * 서버의 판단 부분. 통신(WebSocket)과 떼어 두어 차 앱 안에서도 그대로 돌릴 수 있다(한 대 시연 모드).
 *
 * 서버가 기억하는 것
 * - 차별 최근 위치·속도·다음 합류 지점: 익명 ID 기준, 10초 동안 소식이 없으면 지운다
 * - 주의보·정차 알림: 도로 구간과 시각만, 5~10분 뒤 지운다
 * 기억하지 않는 것: 차량 번호·차종·색상(받을 칸이 없다), 제보자 고정 ID, 자유 텍스트
 */
class NearbyHub(
    private val now: () -> Long,
    val board: HazardBoard = HazardBoard(),
) {
    private data class Car(val pos: RoadPos, val speedKmh: Int, val merge: MergePlan?, val seenAt: Long)

    private class Stop(val alertId: String, val at: RoadPos, val inTunnel: Boolean, var lastSeen: Long) {
        val notified = mutableSetOf<String>()
    }

    private val cars = mutableMapOf<String, Car>()
    private val stops = mutableMapOf<String, Stop>()
    private val sent = mutableMapOf<String, Long>()
    private var stopSeq = 0

    fun receive(up: Uplink): List<Delivery> {
        val t = now()
        val out = mutableListOf<Delivery>()
        when (up) {
            is Presence -> {
                cars[up.id] = Car(up.pos, up.speedKmh, up.merge, t)
                stops[up.id]?.lastSeen = t
                out += mergeForecasts(t)
                out += catchUp(up.id, up.pos)
            }
            is BrakeSignal -> board.add(up.id, up.pos, t, up.inTunnel)?.let { out += spread(it) }
            is VoiceReport -> board.add(up.id, up.pos, t, up.inTunnel)?.let { out += spread(it) }
            is Stopped -> {
                val stop = stops.getOrPut(up.id) { Stop("s${++stopSeq}", up.pos.segmentCenter(), up.inTunnel, t) }
                out += cars.filterKeys { it != up.id }.mapNotNull { (id, car) -> notifyStop(id, car.pos, stop) }
            }
            is Resumed -> stops.remove(up.id)?.let { stop ->
                out += stop.notified.map { Delivery(it, AlertCleared(stop.alertId)) }
            }
            is Thanks -> {
                // 바로 뒤(150m 안) 같은 방향 차 한 대에만
                val target = nearest(up.id, up.pos, 1..150, behind = true)
                if (target != null) out += Delivery(target, ThanksReceived(up.via))
                out += Delivery(up.id, SendResult(SendKind.THANKS, target != null))
            }
            is ProblemNotice -> {
                // 바로 앞(100m 안) 같은 방향 차 한 대에만
                val target = nearest(up.id, up.pos, 1..100, behind = false)
                if (target != null) out += Delivery(target, ProblemSpotted(up.problem))
                out += Delivery(up.id, SendResult(SendKind.PROBLEM, target != null))
            }
        }
        return out
    }

    /** 주기적으로 불러 오래된 기억을 지운다 */
    fun tick(): List<Delivery> {
        val t = now()
        val out = mutableListOf<Delivery>()
        cars.entries.removeAll { t - it.value.seenAt > 10_000 }
        board.expire(t).forEach { a -> out += a.notified.filter { it in cars }.map { Delivery(it, AlertCleared(a.alertId)) } }
        stops.entries.removeAll { (_, s) ->
            val gone = t - s.lastSeen > 10 * 60_000L
            if (gone) out += s.notified.map { Delivery(it, AlertCleared(s.alertId)) }
            gone
        }
        sent.entries.removeAll { t - it.value > 60_000 }
        return out
    }

    /** 주의보가 생기면: 뒤쪽 2km(터널이면 3km) 차엔 "전방 주의", 앞쪽 1km 차엔 "후방 주의" */
    private fun spread(a: HazardBoard.Advisory): List<Delivery> =
        cars.mapNotNull { (id, car) -> notifyHazard(id, car.pos, a) }

    private fun notifyHazard(id: String, pos: RoadPos, a: HazardBoard.Advisory): Delivery? {
        if (id in a.notified) return null
        val d = pos.distanceTo(a.at) ?: return null
        val side = when {
            d in 0..behindRange(a.inTunnel) -> Side.AHEAD
            -d in 1..1000 -> Side.BEHIND
            else -> return null
        }
        a.notified += id
        return Delivery(id, HazardAlert(a.alertId, a.at, side, a.inTunnel))
    }

    private fun notifyStop(id: String, pos: RoadPos, s: Stop): Delivery? {
        if (id in s.notified) return null
        val d = pos.distanceTo(s.at) ?: return null
        if (d !in 0..behindRange(s.inTunnel)) return null
        s.notified += id
        return Delivery(id, StoppedAlert(s.alertId, s.at, s.inTunnel))
    }

    /** 이미 떠 있는 주의보·정차 알림 범위에 새로 들어온 차에게도 알린다 */
    private fun catchUp(id: String, pos: RoadPos): List<Delivery> =
        board.active.mapNotNull { notifyHazard(id, pos, it) } +
            stops.filterKeys { it != id }.values.mapNotNull { notifyStop(id, pos, it) }

    /**
     * 합류 예고. 내비 경로상 60초 안에 합류할 차(진입로)와, 그 합류 지점이 1km 안 앞에 있는 본선 차를 묶는다.
     * - 본선 차: "n대 합류 예정"
     * - 합류할 차: 합류 지점 앞뒤 본선 플레오스 차의 평균 속도
     * 실시간 깜빡이가 아니라 수십 초 앞 경로를 쓰니 통신 지연 1~2초는 문제가 되지 않는다.
     */
    private fun mergeForecasts(t: Long): List<Delivery> {
        val out = mutableListOf<Delivery>()
        val mergers = cars.filter { (_, c) -> c.merge != null && c.merge.etaSec in 0..60 && c.pos.roadId != c.merge.joinAt.roadId }
        val byJoin = mergers.entries.groupBy { it.value.merge!!.joinAt.segmentCenter() }
        for ((join, group) in byJoin) {
            val mainline = cars.filter { (id, c) -> id !in mergers && (c.pos.distanceTo(join) ?: -1) in 0..1000 }
            for ((id, _) in mainline) {
                once(t, "ma:$id:$join:${group.size}") { out += Delivery(id, MergeAhead(join, group.size)) }
            }
            val nearJoin = cars.filter { (id, c) -> id !in mergers && (c.pos.distanceTo(join) ?: Int.MIN_VALUE) in -200..500 }
            if (nearJoin.isEmpty()) continue
            val avg = nearJoin.values.map { it.speedKmh }.average().toInt()
            // 10km/h 단위로 바뀔 때만 다시 알린다
            for ((id, _) in group) {
                once(t, "mf:$id:$join:${avg / 10}") { out += Delivery(id, MainlineFlow(join, avg, nearJoin.size)) }
            }
        }
        return out
    }

    private inline fun once(t: Long, key: String, block: () -> Unit) {
        if (key in sent) return
        sent[key] = t
        block()
    }

    private fun nearest(from: String, pos: RoadPos, range: IntRange, behind: Boolean): String? =
        cars.filterKeys { it != from }.mapNotNull { (id, c) ->
            // behind: 상대가 나보다 뒤 = 상대 기준으로 내가 앞
            val d = (if (behind) c.pos.distanceTo(pos) else pos.distanceTo(c.pos)) ?: return@mapNotNull null
            if (d in range) id to d else null
        }.minByOrNull { it.second }?.first

    private fun behindRange(inTunnel: Boolean) = if (inTunnel) 3000 else 2000
}
