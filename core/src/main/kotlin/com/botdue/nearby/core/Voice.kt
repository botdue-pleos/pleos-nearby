package com.botdue.nearby.core

/** 음성 의도. 실제 차량에서는 Gleo AI(STT + LLM)가 문장을 읽고 이 중 하나로 고른다 */
sealed interface Intent {
    data object SayThanks : Intent
    data object ReportHazard : Intent
    data object MemoForReport : Intent
    data object Reported : Intent
    data class TellProblem(val problem: Problem) : Intent
}

fun interface IntentReader {
    fun read(text: String): Intent?
}

/** Gleo AI 연결 전 시연용 낱말 규칙 */
object KeywordIntentReader : IntentReader {
    override fun read(text: String): Intent? {
        val t = text.replace(" ", "")
        return when {
            "신고했" in t -> Intent.Reported
            "신고해" in t || "기록해" in t -> Intent.MemoForReport
            "앞차" in t || "앞에차" in t -> problemOf(t)?.let { Intent.TellProblem(it) }
            listOf("위험한차", "조심", "난폭", "위험해").any { it in t } -> Intent.ReportHazard
            listOf("고마워", "고맙", "감사").any { it in t } -> Intent.SayThanks
            else -> null
        }
    }

    private fun problemOf(t: String) = when {
        "주유구" in t || "충전구" in t -> Problem.FUEL_DOOR
        "트렁크" in t -> Problem.TRUNK
        "짐" in t -> Problem.CARGO
        "브레이크등" in t || "브레이크" in t -> Problem.BRAKE_LIGHT
        "전조등" in t || "라이트" in t -> Problem.HEADLIGHT_OFF
        else -> null
    }
}

/**
 * 음성 제보 남용 막기 (기기 안에서만 센다): 1분에 1번, 하루 10번까지.
 * 익명 ID가 10분마다 바뀌어도 기기 쪽 횟수 제한은 그대로 남는다.
 */
class ReportGuard(
    private val gapMs: Long = 60_000,
    private val perDay: Int = 10,
    private val dayOf: (Long) -> Long = { it / 86_400_000L },
) {
    private val times = mutableListOf<Long>()

    fun allow(now: Long): Boolean {
        val today = dayOf(now)
        times.removeAll { dayOf(it) != today }
        if (times.size >= perDay) return false
        if (times.isNotEmpty() && now - times.last() < gapMs) return false
        times += now
        return true
    }
}
