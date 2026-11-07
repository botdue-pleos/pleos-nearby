package com.botdue.nearby.core

/**
 * 급제동 감지: 브레이크를 밟은 채 1.5초 안에 3.5m/s² 이상 감속 (시속 30km 이상에서 출발할 때만).
 * 3.5m/s²는 보험사 운전 점수에서 흔히 쓰는 급감속 기준(약 0.35g) 근처로 잡았다. 에뮬레이터 실험으로 다듬을 값.
 */
class HardBrakeDetector(
    private val minDecel: Double = 3.5,
    private val windowMs: Long = 1_500,
    private val minStartKmh: Int = 30,
    private val cooldownMs: Long = 10_000,
) {
    private val history = ArrayDeque<Pair<Long, Int>>()
    private var lastEventAt = Long.MIN_VALUE / 2
    var lastEventAtOrNull: Long? = null
        private set

    /** 급제동이면 true */
    fun feed(at: Long, speedKmh: Int, brakeOn: Boolean): Boolean {
        history.addLast(at to speedKmh)
        while (history.first().first < at - windowMs) history.removeFirst()
        if (!brakeOn || at - lastEventAt < cooldownMs) return false
        val (t0, v0) = history.first()
        if (v0 < minStartKmh || at == t0) return false
        val decel = (v0 - speedKmh) / 3.6 / ((at - t0) / 1000.0)
        if (decel < minDecel) return false
        lastEventAt = at
        lastEventAtOrNull = at
        return true
    }
}

/**
 * 사고·고장 정차 감지: 급제동 뒤 30초 안에 멈춰 10초 넘게 서 있거나, 비상등을 켠 채 10초 넘게 서 있으면 정차.
 * 시속 20km 이상으로 5초 달리면 다시 출발로 본다.
 */
class StopDetector(
    private val holdMs: Long = 10_000,
    private val afterBrakeMs: Long = 30_000,
    private val resumeMs: Long = 5_000,
) {
    sealed interface Change
    data object NowStopped : Change
    data object NowResumed : Change

    var stopped = false
        private set
    private var stillSince: Long? = null
    private var movingSince: Long? = null

    fun feed(at: Long, speedKmh: Int, hazardOn: Boolean, lastHardBrakeAt: Long?): Change? {
        if (!stopped) {
            if (speedKmh > 1) { stillSince = null; return null }
            val since = stillSince ?: at.also { stillSince = it }
            val afterBrake = lastHardBrakeAt != null && since - lastHardBrakeAt in 0..afterBrakeMs
            if (at - since >= holdMs && (afterBrake || hazardOn)) {
                stopped = true
                movingSince = null
                return NowStopped
            }
            return null
        }
        if (speedKmh < 20) { movingSince = null; return null }
        val since = movingSince ?: at.also { movingSince = it }
        if (at - since >= resumeMs) {
            stopped = false
            stillSince = null
            return NowResumed
        }
        return null
    }
}

/**
 * 비상등 감사 인사 감지.
 * 한국 운전자는 끼어든 뒤 비상등 버튼을 눌러 2~3번 깜빡이고 끈다. 이 짧은 켜짐(0.8~4초)을
 * 달리는 중(시속 20km 이상), 깜빡이를 켠 지 20초 안에 했을 때만 "고마워"로 읽는다.
 * 4초 넘게 켜 두거나 서 있는 중이면 진짜 비상 상황이라 감사로 읽지 않는다.
 */
class HazardThanksDetector(
    private val minOnMs: Long = 800,
    private val maxOnMs: Long = 4_000,
    private val afterSignalMs: Long = 20_000,
    private val minSpeedKmh: Int = 20,
    private val cooldownMs: Long = 10_000,
) {
    private var onSince: Long? = null
    private var lastTurnSignalAt: Long? = null
    private var lastThanksAt = Long.MIN_VALUE / 2
    private var stillDuringOn = false

    /** 감사 인사로 읽혔으면 true (비상등을 끄는 순간 판정) */
    fun feed(at: Long, hazardOn: Boolean, turnSignalOn: Boolean, speedKmh: Int): Boolean {
        if (turnSignalOn) lastTurnSignalAt = at
        val since = onSince
        if (hazardOn) {
            if (since == null) { onSince = at; stillDuringOn = false }
            if (speedKmh < minSpeedKmh) stillDuringOn = true
            return false
        }
        if (since == null) return false
        onSince = null
        val onFor = at - since
        val signal = lastTurnSignalAt ?: return false
        val ok = onFor in minOnMs..maxOnMs &&
            !stillDuringOn &&
            since - signal in 0..afterSignalMs &&
            at - lastThanksAt >= cooldownMs
        if (ok) lastThanksAt = at
        return ok
    }
}
