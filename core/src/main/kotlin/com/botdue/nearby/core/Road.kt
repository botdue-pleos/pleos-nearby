package com.botdue.nearby.core

import kotlinx.serialization.Serializable

/** 도로 위 진행 방향. 상행(UP)은 거리값이 커지는 쪽 */
@Serializable
enum class Heading { UP, DOWN }

/** 도로 구간 길이. 차선이 아니라 이 단위로만 묶으니 일반 GPS 정확도로 충분하다 */
const val SEGMENT_M = 200

/**
 * 도로 위 위치. 실제 차량에서는 NaviHelper 경로(도로 ID)와 FusedLocation을 맞춰 얻는다.
 * 위도·경도 대신 "어느 도로의 몇 m 지점, 어느 방향"만 다룬다.
 */
@Serializable
data class RoadPos(val roadId: String, val meter: Int, val heading: Heading) {
    val segment: Int get() = meter / SEGMENT_M

    /** 이 구간의 가운데 지점. 서버는 정확한 위치 대신 이 값만 내보낸다 */
    fun segmentCenter(): RoadPos = copy(meter = segment * SEGMENT_M + SEGMENT_M / 2)

    fun sameLane(other: RoadPos) = roadId == other.roadId && heading == other.heading

    /** 진행 방향 기준으로 [target]이 얼마나 앞에 있는지(m). 뒤에 있으면 음수, 다른 도로면 null */
    fun distanceTo(target: RoadPos): Int? {
        if (!sameLane(target)) return null
        val d = target.meter - meter
        return if (heading == Heading.UP) d else -d
    }
}
