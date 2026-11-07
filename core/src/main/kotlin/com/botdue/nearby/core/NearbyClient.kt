package com.botdue.nearby.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 차량에서 1~2초마다 들어오는 신호 묶음. 실제로는 Vehicle SDK·CarPropertyManager·NaviHelper 콜백을 모은다 */
data class VehicleSample(
    val at: Long,
    val pos: RoadPos,
    val speedKmh: Int,
    val brakeOn: Boolean = false,
    val hazardOn: Boolean = false,
    val turnSignalOn: Boolean = false,
    val trunkOpen: Boolean = false,
    val doorOpen: Boolean = false,
    val parked: Boolean = false,
    /** NaviHelper: 지금 터널 안인가 (TBT 터널 안내 구간) */
    val inTunnel: Boolean = false,
    /** NaviHelper: 경로상 다음 합류 지점과 도착 예정 시간 */
    val merge: MergePlan? = null,
)

/** 화면에 띄우는 알림 하나 */
data class Notice(
    val key: String,
    val kind: Kind,
    val title: String,
    val detail: String,
    /** 앞길 띠에 표시할 지점 (없으면 표시 안 함) */
    val at: RoadPos? = null,
    val inTunnel: Boolean = false,
    /** 받은 순서. 위험 알림이 아니면 가장 최근 것을 크게 보여 준다 */
    val seq: Int = 0,
) {
    enum class Kind { MERGE, FLOW, HAZARD_AHEAD, HAZARD_BEHIND, STOPPED_AHEAD, THANKS, PROBLEM, INFO }
}

data class ReportMemo(val at: Long, val place: String)

/**
 * 차 한 대의 니어바이. 차량 신호·음성·서버 메시지를 받아
 * (1) 서버로 보낼 것을 [send]로 내보내고 (2) 화면 알림과 음성 문장을 만든다.
 * 음성은 알림마다 한 번, 짧고 차분하게. 터널 안 위험만 들어가기 직전(1km)에 한 번 더 말한다.
 */
class NearbyClient(
    private val send: (Uplink) -> Unit,
    private val ids: AnonymousIds = AnonymousIds(),
    private val guard: ReportGuard = ReportGuard(),
    private val intents: IntentReader = KeywordIntentReader,
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) {
    private val brake = HardBrakeDetector()
    private val stop = StopDetector()
    private val thanks = HazardThanksDetector()

    var last: VehicleSample? = null
        private set
    val notices = linkedMapOf<String, Notice>()
    private val remindedTunnel = mutableSetOf<String>()
    var checklist: SafetyChecklist? = null
        private set
    val memos = mutableListOf<ReportMemo>()
    /** 말한 문장 기록 (화면 자막·시연 확인용) */
    val spoken = mutableListOf<String>()
    var showReportGuide = false
        private set

    private fun say(vararg lines: String) { spoken += lines }

    fun onSample(s: VehicleSample) {
        val prev = last
        last = s
        val id = ids.current(s.at)
        send(Presence(id, s.pos, s.speedKmh, s.merge))

        if (brake.feed(s.at, s.speedKmh, s.brakeOn)) send(BrakeSignal(id, s.pos, s.inTunnel))

        when (stop.feed(s.at, s.speedKmh, s.hazardOn, brake.lastEventAtOrNull)) {
            StopDetector.NowStopped -> {
                ids.hold = true
                send(Stopped(id, s.pos, s.inTunnel))
                val list = SafetyChecklist(s.inTunnel)
                checklist = list
                say(*list.start(s.hazardOn, s.trunkOpen, s.doorOpen).toTypedArray())
            }
            StopDetector.NowResumed -> {
                send(Resumed(id))
                ids.hold = false
                checklist = null
            }
            null -> checklist?.let { say(*it.update(s.hazardOn, s.trunkOpen, s.doorOpen).toTypedArray()) }
        }

        if (thanks.feed(s.at, s.hazardOn, s.turnSignalOn, s.speedKmh)) send(Thanks(id, s.pos, ThanksVia.HAZARD_BLINK))

        if (s.parked && prev?.parked != true && memos.isNotEmpty()) {
            showReportGuide = true
            say("차를 세웠어요. 기록해 둔 ${memos.size}건의 신고 방법을 화면에 띄웠어요.")
        }
        if (!s.parked) showReportGuide = false
        // 합류를 마치면 합류 준비 안내는 내린다
        if (prev?.merge != null && s.merge == null) notices.remove("flow")

        refreshDistances(s)
    }

    fun onVoice(text: String) {
        val s = last ?: return
        val id = ids.current(s.at)
        when (val intent = intents.read(text)) {
            Intent.SayThanks -> send(Thanks(id, s.pos, ThanksVia.VOICE))
            Intent.ReportHazard -> {
                if (guard.allow(s.at)) {
                    send(VoiceReport(id, s.pos, s.inTunnel))
                    say("제보를 받았어요. 근처 차의 신호와 겹치면 주의보로 알릴게요.")
                } else {
                    say("방금 제보하셨어요. 잠시 뒤에 다시 말씀해 주세요.")
                }
            }
            Intent.MemoForReport -> {
                memos += ReportMemo(s.at, placeOf(s.pos))
                say("${clock(s.at)}, 기록해 뒀어요. 차를 세운 뒤에 신고 방법을 알려드릴게요.")
            }
            Intent.Reported -> checklist?.let { say(*it.update(s.hazardOn, s.trunkOpen, s.doorOpen, reported = true).toTypedArray()) }
            is Intent.TellProblem -> send(ProblemNotice(id, s.pos, intent.problem))
            null -> say("잘 못 알아들었어요.")
        }
    }

    fun onDownlink(d: Downlink) {
        val s = last ?: return
        when (d) {
            is MergeAhead -> put(mergeNotice(s.pos, d))
            is MainlineFlow -> put(flowNotice(d))
            is HazardAlert -> {
                // 정차 알림이 바로 근처(300m)에 이미 있으면 더 급한 정차 알림 하나로 둔다
                val covered = notices.values.any {
                    it.kind == Notice.Kind.STOPPED_AHEAD && it.at != null && (d.at.distanceTo(it.at) ?: 999).let { g -> kotlin.math.abs(g) <= 300 }
                }
                if (!covered) put(hazardNotice(s.pos, d))
            }
            is StoppedAlert -> {
                notices.values.filter {
                    it.kind == Notice.Kind.HAZARD_AHEAD && it.at != null && kotlin.math.abs(d.at.distanceTo(it.at) ?: 999) <= 300
                }.forEach { notices.remove(it.key) }
                put(stoppedNotice(s.pos, d))
            }
            is AlertCleared -> notices.remove(d.alertId)?.let {
                if (it.kind == Notice.Kind.STOPPED_AHEAD) say("앞에 멈춰 있던 차가 다시 출발했어요.")
            }
            is ThanksReceived -> put(
                Notice(
                    "thanks", Notice.Kind.THANKS,
                    "앞차가 고맙대요",
                    if (d.via == ThanksVia.HAZARD_BLINK) "비상등으로 보낸 인사예요" else "말로 보낸 인사예요",
                )
            )
            is ProblemSpotted -> put(problemNotice(d.problem))
            is SendResult -> say(
                when (d.kind to d.delivered) {
                    SendKind.THANKS to true -> "뒤차에 고맙다고 전했어요."
                    SendKind.THANKS to false -> "바로 뒤에 플레오스 차가 없어 전하지 못했어요."
                    SendKind.PROBLEM to true -> "앞차에 알려 줬어요."
                    else -> "바로 앞에 플레오스 차가 없어 전하지 못했어요."
                }
            )
        }
    }

    private var seq = 0

    private fun put(n: Notice) {
        notices[n.key] = n.copy(seq = ++seq)
        say(n.title + ". " + n.detail)
    }

    /** 내가 움직이면 남은 거리를 다시 쓰고, 지나친 지점은 지운다 */
    private fun refreshDistances(s: VehicleSample) {
        for (n in notices.values.toList()) {
            val at = n.at ?: continue
            val d = s.pos.distanceTo(at) ?: continue
            if (n.kind in setOf(Notice.Kind.MERGE, Notice.Kind.HAZARD_AHEAD, Notice.Kind.STOPPED_AHEAD) && d < -50) {
                notices.remove(n.key)
                continue
            }
            val updated = when (n.kind) {
                Notice.Kind.MERGE, Notice.Kind.HAZARD_AHEAD, Notice.Kind.STOPPED_AHEAD -> n.copy(title = n.title.replace(Regex("^[0-9.]+(킬로미터|미터)"), distanceText(d)))
                else -> n
            }
            notices[n.key] = updated
            if (n.inTunnel && n.kind != Notice.Kind.HAZARD_BEHIND && d in 1..1000 && remindedTunnel.add(n.key)) {
                say("곧 터널이에요. 터널 안 ${distanceText(d)} 앞 상황이에요. 지금 속도를 줄이고 차로를 바꾸지 마세요.")
            }
        }
    }

    private fun mergeNotice(me: RoadPos, d: MergeAhead) = Notice(
        "merge", Notice.Kind.MERGE,
        "${distanceText(me.distanceTo(d.joinAt) ?: 0)} 앞 진입로에서 ${d.count}대 합류 예정",
        "플레오스 차가 경로상 곧 들어와요. 참고만 하세요.",
        d.joinAt,
    )

    private fun flowNotice(d: MainlineFlow) = Notice(
        "flow", Notice.Kind.FLOW,
        if (d.avgSpeedKmh < 40) "합류 지점 본선이 느려요" else "합류 지점 본선은 시속 ${d.avgSpeedKmh / 10 * 10}km 흐름",
        if (d.avgSpeedKmh < 40) "본선이 시속 ${d.avgSpeedKmh}km 정도예요. 미리 속도를 맞추세요."
        else "합류 전에 속도를 충분히 올리세요.",
    )

    private fun hazardNotice(me: RoadPos, d: HazardAlert): Notice {
        val where = if (d.inTunnel) "터널 안 " else ""
        return when (d.side) {
            Side.AHEAD -> Notice(
                d.alertId, Notice.Kind.HAZARD_AHEAD,
                "${distanceText(me.distanceTo(d.at) ?: 0)} 앞 ${where}위험 운전 제보",
                if (d.inTunnel) "터널은 피할 곳이 없어요. 들어가기 전에 속도를 줄이세요." else "차간 거리를 넉넉히 두세요.",
                d.at, d.inTunnel,
            )
            Side.BEHIND -> Notice(
                d.alertId, Notice.Kind.HAZARD_BEHIND,
                "뒤쪽에 위험 운전 제보",
                "빠르게 다가오는 차가 있을 수 있어요. 무리한 차로 변경은 피하세요.",
                d.at, d.inTunnel,
            )
        }
    }

    private fun stoppedNotice(me: RoadPos, d: StoppedAlert) = Notice(
        d.alertId, Notice.Kind.STOPPED_AHEAD,
        "${distanceText(me.distanceTo(d.at) ?: 0)} 앞 ${if (d.inTunnel) "터널 안 " else ""}멈춘 차",
        if (d.inTunnel) "터널 안에 차가 서 있어요. 터널 앞에서 속도를 줄일 준비를 하세요." else "급제동 뒤 멈춘 차가 있어요. 비상등을 켜고 속도를 줄이세요.",
        d.at, d.inTunnel,
    )

    private fun problemNotice(p: Problem) = Notice(
        "problem", Notice.Kind.PROBLEM,
        when (p) {
            Problem.FUEL_DOOR -> "주유구가 열려 있대요"
            Problem.TRUNK -> "트렁크가 열려 있대요"
            Problem.CARGO -> "짐이 떨어질 것 같대요"
            Problem.BRAKE_LIGHT -> "브레이크등이 안 켜진대요"
            Problem.HEADLIGHT_OFF -> "전조등이 꺼져 있대요"
        },
        "근처 차가 알려 줬어요. 다음에 세울 때 확인해 보세요.",
    )

    fun reportGuide(): List<String> = buildList {
        memos.forEach { m -> add("${clock(m.at)} · ${m.place}") }
        add("블랙박스에서 이 시각 전후 영상을 따로 저장하세요.")
        add("과태료 처분을 원하면 안전신문고 앱에, 형사 처벌을 원하면 영상을 가지고 경찰서에 신고하세요.")
        add("판단은 경찰이 해요. 니어바이는 시각과 위치만 이 차 안에 적어 두었어요.")
    }

    fun clock(at: Long): String =
        Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("H시 m분"))

    private fun placeOf(p: RoadPos) = "${p.roadId} ${"%.1f".format(p.meter / 1000.0)}km 지점"

    companion object {
        fun distanceText(m: Int): String =
            if (m >= 1000) "${"%.1f".format(m / 1000.0).removeSuffix(".0")}킬로미터" else "${(m / 100 * 100).coerceAtLeast(100)}미터"
    }
}
