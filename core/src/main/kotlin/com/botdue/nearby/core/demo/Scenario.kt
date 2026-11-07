package com.botdue.nearby.core.demo

import com.botdue.nearby.core.Heading
import com.botdue.nearby.core.MergePlan
import com.botdue.nearby.core.RoadPos
import com.botdue.nearby.core.VehicleSample
import java.time.LocalDateTime
import java.time.ZoneId

enum class CarId { A, B, C, D }

const val MAIN = "본선"
const val RAMP = "진입로"

/** 시연 한 편: 차마다 시각(ms) → 차량 신호, 그리고 정해진 시각에 하는 말 */
class Scenario(
    val title: String,
    val summary: String,
    val durationMs: Long,
    val startAt: Long,
    val roles: Map<CarId, String>,
    private val cars: Map<CarId, (Long) -> VehicleSample>,
    val voices: List<Voice>,
    /** 앞길 띠에 그릴 터널 구간 (본선 m) */
    val tunnel: IntRange? = null,
    /** "5분 뒤로" 버튼을 보여 줄지 (주의보 소멸 시연) */
    val canJump: Boolean = false,
) {
    data class Voice(val atMs: Long, val car: CarId, val text: String)

    fun sample(car: CarId, t: Long): VehicleSample = cars.getValue(car)(t)
}

/** 속도 함수를 적분해 위치를 구한다 (0.1초 단위) */
private class Track(private val start: Int, private val speedKmh: (Double) -> Double) {
    private val cache = mutableListOf(start.toDouble())
    fun meterAt(t: Long): Int {
        val steps = (t / 100).toInt()
        while (cache.size <= steps) {
            val s = (cache.size - 1) / 10.0
            cache += cache.last() + speedKmh(s) / 3.6 * 0.1
        }
        return cache[steps].toInt()
    }
}

private fun lerp(a: Double, b: Double, x: Double) = a + (b - a) * x.coerceIn(0.0, 1.0)

private fun at(base: Long, t: Long, pos: RoadPos, speed: Double, block: VehicleSample.() -> VehicleSample = { this }) =
    VehicleSample(base + t, pos, speed.toInt()).block()

private val SEOUL = ZoneId.of("Asia/Seoul")
private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(SEOUL).toInstant().toEpochMilli()

object Scenarios {

    /**
     * 시연 1 — 합류 예고와 감사 인사.
     * 차 A가 진입로에서 본선 5km 지점으로 합류할 예정이고, 본선의 B·C가 그 지점으로 다가온다.
     * D는 이미 합류 지점을 지나 알림을 받지 않는다. 합류 뒤 A는 비상등을 짧게 켜 고맙다고 하고,
     * B는 A의 트렁크가 열린 걸 보고 말로 알려 준다.
     */
    fun merge(): Scenario {
        val base = epoch(2026, 11, 20, 18, 20)
        val main = { start: Int -> Track(start) { 90.0 } }
        val b = main(3800); val c = main(3300); val d = main(5600)
        val aMain = Track(5000) { 80.0 }
        val joinAt = RoadPos(MAIN, 5000, Heading.UP)
        return Scenario(
            title = "시연 1 · 합류 예고",
            summary = "진입로 차 A의 합류를 본선 차 B·C가 미리 알아요. A는 비상등으로, B는 말로 인사를 나눠요.",
            durationMs = 62_000,
            startAt = base,
            roles = mapOf(CarId.A to "진입로에서 합류", CarId.B to "본선, 합류 지점 1.2km 뒤", CarId.C to "본선, 1.7km 뒤", CarId.D to "본선, 이미 지나감"),
            cars = mapOf(
                CarId.A to { t ->
                    val s = t / 1000.0
                    if (s < 44) at(base, t, RoadPos(RAMP, (10 * s).toInt(), Heading.UP), 36.0) {
                        copy(merge = MergePlan(joinAt, (44 - s).toInt()), turnSignalOn = s >= 36)
                    } else at(base, t, RoadPos(MAIN, aMain.meterAt(t - 44_000), Heading.UP), 80.0) {
                        copy(turnSignalOn = s < 45, hazardOn = s >= 47 && s < 48.5)
                    }
                },
                CarId.B to { t -> at(base, t, RoadPos(MAIN, b.meterAt(t), Heading.UP), 90.0) },
                CarId.C to { t -> at(base, t, RoadPos(MAIN, c.meterAt(t), Heading.UP), 90.0) },
                CarId.D to { t -> at(base, t, RoadPos(MAIN, d.meterAt(t), Heading.UP), 90.0) },
            ),
            voices = listOf(Scenario.Voice(56_000, CarId.B, "앞차 트렁크 열렸어")),
        )
    }

    /**
     * 시연 2 — 터널 안 앞길 주의보와 정차.
     * 터널(본선 6~7km) 안에서 B·C가 거의 동시에 급제동한다(자동 감지 2점 → 주의보).
     * C는 그대로 멈춰 서고, 행동요령을 차량 신호로 하나씩 확인받는다.
     * 뒤의 D는 터널에 들어가기 전에, 앞의 A는 후방 주의를 받는다. B는 "신고해줘"라고 말해 두고 나중에 세운다.
     */
    fun tunnel(): Scenario {
        val base = epoch(2026, 11, 20, 18, 40)
        val tunnel = 6000..7000
        val a = Track(6500) { 90.0 }
        val bSpeed = { s: Double ->
            when {
                s < 5 -> 90.0
                s < 6 -> lerp(90.0, 40.0, s - 5)
                s < 12 -> 40.0
                s < 16 -> lerp(40.0, 90.0, (s - 12) / 4)
                s < 62 -> 90.0
                // 휴게소로 빠져 천천히 세운다 (약 2m/s², 급제동 아님)
                else -> lerp(90.0, 0.0, (s - 62) / 12)
            }
        }
        val b = Track(6150, bSpeed)
        val cSpeed = { s: Double -> if (s < 5.5) 90.0 else lerp(90.0, 0.0, (s - 5.5) / 2) }
        val c = Track(6200, cSpeed)
        val d = Track(3800) { 90.0 }
        fun road(m: Int) = RoadPos(MAIN, m, Heading.UP)
        return Scenario(
            title = "시연 2 · 터널 안 주의보",
            summary = "터널 안에서 B·C가 함께 급제동해 주의보가 생겨요. 멈춘 C는 행동요령을, 뒤의 D는 터널 앞 경고를 받아요.",
            durationMs = 80_000,
            startAt = base,
            roles = mapOf(CarId.A to "터널 안, 앞서 감", CarId.B to "터널 안, 급제동 후 지나감", CarId.C to "터널 안, 급제동 후 멈춤", CarId.D to "터널 2km 뒤"),
            cars = mapOf(
                CarId.A to { t -> road(a.meterAt(t)).let { p -> at(base, t, p, 90.0) { copy(inTunnel = p.meter in tunnel) } } },
                CarId.B to { t ->
                    val s = t / 1000.0
                    road(b.meterAt(t)).let { p ->
                        at(base, t, p, bSpeed(s)) {
                            copy(brakeOn = s in 5.0..6.0 || s >= 62, inTunnel = p.meter in tunnel, parked = s >= 75)
                        }
                    }
                },
                CarId.C to { t ->
                    val s = t / 1000.0
                    road(c.meterAt(t)).let { p ->
                        at(base, t, p, cSpeed(s)) {
                            copy(brakeOn = s >= 5.5, hazardOn = s >= 9, trunkOpen = s >= 24, doorOpen = s >= 31, inTunnel = p.meter in tunnel)
                        }
                    }
                },
                CarId.D to { t -> road(d.meterAt(t)).let { p -> at(base, t, p, 90.0) { copy(inTunnel = p.meter in tunnel) } } },
            ),
            voices = listOf(
                Scenario.Voice(9_000, CarId.B, "신고해줘"),
                Scenario.Voice(42_000, CarId.C, "신고했어"),
            ),
            tunnel = tunnel,
            canJump = true,
        )
    }

    val all: List<() -> Scenario> = listOf(::merge, ::tunnel)
}
