package com.botdue.nearby.core

/**
 * 2차 사고 예방 행동요령(비트박스)을 차량 신호로 하나씩 확인한다.
 * 안내만 읽어 주는 대신, 이미 한 단계는 건너뛰고 아직 안 한 단계만 다시 말한다.
 *
 * | 단계 | 확인하는 신호 |
 * | 비상등 켜기 | Light: 비상등 |
 * | 트렁크 열기 | Door: 트렁크 |
 * | 차 밖으로 대피 | Door: 운전석 문 열림 (내린 것까지만 확인, 가드레일 밖인지는 알 수 없어 안내만) |
 * | 신고 | "신고했어" 음성 |
 */
class SafetyChecklist(val inTunnel: Boolean) {
    enum class Step { HAZARD, TRUNK, EVACUATE, REPORT }

    private val done = mutableSetOf<Step>()
    val completed: Set<Step> get() = done

    val current: Step? get() = Step.entries.firstOrNull { it !in done }

    /** 멈춘 순간의 차량 상태를 먼저 반영하고(이미 켠 비상등은 다시 말하지 않게) 첫 안내를 돌려준다 */
    fun start(hazardOn: Boolean, trunkOpen: Boolean, doorOpen: Boolean): List<String> {
        update(hazardOn, trunkOpen, doorOpen)
        val already = Step.entries.filter { it in done }.map(::label)
        val ack = if (already.isEmpty()) emptyList() else listOf("${already.joinToString(", ")} 확인했어요.")
        return opening() + ack + prompt()
    }

    private fun opening() = listOf(
        if (inTunnel) "터널 안에 멈춰 섰어요. 뒤차에 알렸어요. 지금부터 할 일을 하나씩 알려드릴게요."
        else "멈춰 선 걸 뒤차에 알렸어요. 지금부터 할 일을 하나씩 알려드릴게요.",
    )

    /** 차량 신호가 바뀔 때마다 부른다. 새로 끝난 단계가 있으면 다음 할 일을 돌려준다 */
    fun update(hazardOn: Boolean, trunkOpen: Boolean, doorOpen: Boolean, reported: Boolean = false): List<String> {
        val before = current
        if (hazardOn) done += Step.HAZARD
        if (trunkOpen) done += Step.TRUNK
        if (doorOpen) done += Step.EVACUATE
        if (reported) done += Step.REPORT
        if (current == before) return emptyList()
        return listOf("${label(before!!)} 확인했어요.") + prompt()
    }

    fun prompt(): List<String> = when (current) {
        null -> listOf("할 일을 모두 마쳤어요. 차로 돌아가지 말고 안전한 곳에서 기다리세요.")
        else -> listOf(say(current!!))
    }

    fun label(step: Step) = when (step) {
        Step.HAZARD -> "비상등"
        Step.TRUNK -> "트렁크"
        Step.EVACUATE -> "대피"
        Step.REPORT -> "신고"
    }

    fun title(step: Step) = when (step) {
        Step.HAZARD -> "비상등 켜기"
        Step.TRUNK -> "트렁크 열기"
        Step.EVACUATE -> if (inTunnel) "비상구 쪽으로 대피" else "가드레일 밖으로 대피"
        Step.REPORT -> "신고하기"
    }

    fun say(step: Step) = when (step) {
        Step.HAZARD -> "비상등을 켜세요."
        Step.TRUNK -> "트렁크를 열어 뒤차에 사고 차라는 걸 알리세요."
        Step.EVACUATE ->
            if (inTunnel) "차에서 내려 가까운 비상구 쪽으로 걸어서 피하세요."
            else "차에서 내려 가드레일 밖으로 피하세요."
        Step.REPORT -> "안전한 곳에서 112나 한국도로공사 1588-2504로 신고하세요. 마치면 \"신고했어\"라고 말씀하세요."
    }
}
