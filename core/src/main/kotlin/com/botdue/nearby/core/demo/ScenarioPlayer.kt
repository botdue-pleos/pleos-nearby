package com.botdue.nearby.core.demo

import com.botdue.nearby.core.Delivery
import com.botdue.nearby.core.Downlink
import com.botdue.nearby.core.HazardBoard
import com.botdue.nearby.core.Heading
import com.botdue.nearby.core.NearbyClient
import com.botdue.nearby.core.NearbyHub
import com.botdue.nearby.core.Uplink

/** 차 → 서버 통로. 한 대 시연은 앱 안의 [LocalHubLink], 여러 대 시연은 WebSocket으로 진짜 서버에 붙는다 */
interface HubLink {
    fun send(car: CarId, up: Uplink)
    fun tick() {}
    var deliver: (CarId, Downlink) -> Unit
}

/** 앱 안에서 서버 판단([NearbyHub])을 그대로 돌린다. 익명 ID → 차 대응표는 배달용으로만 쓴다 */
class LocalHubLink(clock: () -> Long) : HubLink {
    val hub = NearbyHub(clock)
    private val route = mutableMapOf<String, CarId>()
    override var deliver: (CarId, Downlink) -> Unit = { _, _ -> }

    override fun send(car: CarId, up: Uplink) {
        route[up.id] = car
        dispatch(hub.receive(up))
    }

    override fun tick() = dispatch(hub.tick())

    private fun dispatch(list: List<Delivery>) = list.forEach { d -> route[d.to]?.let { deliver(it, d.msg) } }
}

/** 시연 시계. 재생기가 움직이고, 앱 안 서버([LocalHubLink])가 읽는다 */
class DemoClock(var ms: Long) {
    fun now() = ms
}

/**
 * 시나리오를 0.5초씩 재생한다. [cars]에 든 차만 이 기기에서 돌린다
 * (한 대 시연은 넷 다, 여러 대 시연은 기기마다 한 대).
 */
class ScenarioPlayer(
    val scenario: Scenario,
    private val link: HubLink,
    val cars: Set<CarId> = CarId.entries.toSet(),
    private val stepMs: Long = 500,
    private val clock: DemoClock = DemoClock(scenario.startAt),
) {
    var t = 0L
        private set
    /** 서버 시계를 앞당긴 양 ("5분 뒤로") */
    var jumpedMs = 0L
        private set

    val clients: Map<CarId, NearbyClient> = cars.associateWith { car -> NearbyClient(send = { link.send(car, it) }) }

    init {
        link.deliver = { car, msg -> clients[car]?.onDownlink(msg) }
        // 재생 전에도 앞길 띠가 보이도록 0초 상태를 한 번 넣는다
        for (car in cars) clients.getValue(car).onSample(scenario.sample(car, 0))
    }

    val finished get() = t >= scenario.durationMs

    fun step() {
        if (finished) return
        t += stepMs
        clock.ms = now
        for (car in cars) clients.getValue(car).onSample(scenario.sample(car, t))
        scenario.voices.filter { it.atMs in (t - stepMs + 1)..t && it.car in cars }
            .forEach { clients.getValue(it.car).onVoice(it.text) }
        link.tick()
    }

    fun runToEnd() { while (!finished) step() }

    fun jump(ms: Long = 5 * 60_000L) {
        jumpedMs += ms
        clock.ms = now
        link.tick()
    }

    val now: Long get() = scenario.startAt + t + jumpedMs
}

/** 시연용 위험 지점 지도 통계: 평일 저녁 터널 입구 앞뒤에 제보가 몰린 지난 4주 */
fun demoHotspotSeed(): Map<HazardBoard.StatKey, Int> = buildMap {
    for (day in 1..5) for (hour in 7..21) for (seg in 26..36) {
        val evening = hour in 17..19
        val morning = hour in 7..8
        val tunnelMouth = seg in 29..31
        val base = when {
            tunnelMouth && evening -> 9 + (day + seg) % 4
            tunnelMouth && morning -> 5 + (day * seg) % 3
            evening -> 2 + (day + hour) % 4
            else -> (day * hour + seg) % 3
        }
        if (base > 0) put(HazardBoard.StatKey(MAIN, Heading.UP, seg, day, hour), base)
    }
}
