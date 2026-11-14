package com.botdue.nearby.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * 차 ↔ 서버 통신 형식.
 * - 자유 텍스트 칸이 없다. 사람 사이에 오가는 말은 모두 정해진 문구(enum)라 괴롭힘·보복에 쓸 수 없다.
 * - 차량 번호·차종·색상 칸이 없다. 보낼 방법 자체가 없게 만들었다.
 * - 시각은 차가 보내지 않는다. 서버가 받은 시각을 쓴다(조작 방지).
 * - 서버가 다른 차에 보내는 말에는 보낸 차의 익명 ID도 들어가지 않는다.
 */

@Serializable
enum class Problem { FUEL_DOOR, TRUNK, CARGO, BRAKE_LIGHT, HEADLIGHT_OFF }

@Serializable
enum class ThanksVia { VOICE, HAZARD_BLINK }

@Serializable
enum class SendKind { THANKS, PROBLEM }

/** 주의보가 받는 차 기준으로 앞(전방 주의)인지 뒤(후방 주의)인지 */
@Serializable
enum class Side { AHEAD, BEHIND }

@Serializable
data class MergePlan(val joinAt: RoadPos, val etaSec: Int)

@Serializable
sealed interface Uplink {
    val id: String
}

/** 1~2초마다: 대략적 위치·속도·다음 합류 지점 */
@Serializable @SerialName("presence")
data class Presence(override val id: String, val pos: RoadPos, val speedKmh: Int, val merge: MergePlan? = null) : Uplink

/** 급제동 자동 감지 (1점) */
@Serializable @SerialName("brake")
data class BrakeSignal(override val id: String, val pos: RoadPos, val inTunnel: Boolean = false) : Uplink

/** "방금 위험한 차 있었어" 음성 제보 (1점) */
@Serializable @SerialName("voice_report")
data class VoiceReport(override val id: String, val pos: RoadPos, val inTunnel: Boolean = false) : Uplink

/** 급제동 뒤 멈춰 섬 */
@Serializable @SerialName("stopped")
data class Stopped(override val id: String, val pos: RoadPos, val inTunnel: Boolean = false) : Uplink

@Serializable @SerialName("resumed")
data class Resumed(override val id: String) : Uplink

/**
 * 멈춘 차 옆을 지나간 뒤차의 답: 아직 서 있었나. [alertId]는 서버가 만든 정차 알림 번호라 멈춘 차를 가리키지 않는다.
 * 그 정차 알림을 받은 차의 답만 센다.
 */
@Serializable @SerialName("stop_check")
data class StopCheck(override val id: String, val alertId: String, val stillThere: Boolean) : Uplink

@Serializable @SerialName("thanks")
data class Thanks(override val id: String, val pos: RoadPos, val via: ThanksVia) : Uplink

@Serializable @SerialName("problem")
data class ProblemNotice(override val id: String, val pos: RoadPos, val problem: Problem) : Uplink

@Serializable
sealed interface Downlink

/** 본선 차에게: 앞 합류 지점으로 플레오스 차 [count]대가 들어올 예정 */
@Serializable @SerialName("merge_ahead")
data class MergeAhead(val joinAt: RoadPos, val count: Int) : Downlink

/** 합류할 차에게: 합류 지점 본선의 흐름(플레오스 차 평균 속도) */
@Serializable @SerialName("mainline_flow")
data class MainlineFlow(val joinAt: RoadPos, val avgSpeedKmh: Int, val count: Int) : Downlink

@Serializable @SerialName("hazard")
data class HazardAlert(val alertId: String, val at: RoadPos, val side: Side, val inTunnel: Boolean) : Downlink

@Serializable @SerialName("stopped_ahead")
data class StoppedAlert(val alertId: String, val at: RoadPos, val inTunnel: Boolean) : Downlink

/** 주의보 소멸(5분) 또는 정차 차량 출발 */
@Serializable @SerialName("cleared")
data class AlertCleared(val alertId: String) : Downlink

@Serializable @SerialName("thanks_received")
data class ThanksReceived(val via: ThanksVia) : Downlink

@Serializable @SerialName("problem_spotted")
data class ProblemSpotted(val problem: Problem) : Downlink

/** 보낸 사람에게: 받을 차가 근처에 있어 전했는지 */
@Serializable @SerialName("send_result")
data class SendResult(val kind: SendKind, val delivered: Boolean) : Downlink

val NearbyJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
}
