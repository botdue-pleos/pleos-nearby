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
 * 비상등 감사 인사 감지. 원칙: 감사를 놓치는 것은 괜찮지만, 진짜 비상을 감사로 잘못 읽지 않는다.
 * 한국 운전자는 끼어든 뒤 비상등 버튼을 눌러 2~3번 깜빡이고 끈다. 이 짧은 켜짐만 "고마워"로 읽는다.
 *
 * | 조건 | 달리는 중 (시속 20km 이상) | 정체·정차 (켠 동안 한 번이라도 20km 미만) |
 * | 끼어듦 | 깜빡이를 켰다 끈 뒤 20초 안에 비상등을 켬 | 같음 |
 * | 켠 시간 | 0.8~4초 | 0.8~3초 |
 * | 급제동 | 켜기 전 30초 안·켠 동안 없음 | 같음 |
 * | 기어 | P 아님 | 같음 |
 * | 다시 켬 | 끈 뒤 10초 안에 다시 켜면 그 켜짐은 비상 | 끈 뒤 10초를 지켜보고, 그사이 다시 켜면 취소 |
 *
 * 달리는 중에는 끄는 순간 바로 판정한다(뒤차가 금방 멀어지므로). 정체 중에는 앞이 막혀 멈춘 뒤 비상등을
 * 켜는 일이 흔해서, 끄고 10초 동안 다시 켜지 않는 걸 확인한 뒤에야 감사로 보낸다.
 */
class HazardThanksDetector(
    private val minOnMs: Long = 800,
    private val maxOnMs: Long = 4_000,
    private val maxOnSlowMs: Long = 3_000,
    private val afterSignalMs: Long = 20_000,
    private val minSpeedKmh: Int = 20,
    private val noBrakeBeforeMs: Long = 30_000,
    private val relightMs: Long = 10_000,
    private val cooldownMs: Long = 10_000,
) {
    private var onSince: Long? = null
    private var lastTurnSignalAt: Long? = null
    private var lastThanksAt = Long.MIN_VALUE / 2
    private var lastOffAt: Long? = null
    private var slowDuringOn = false
    private var parkedDuringOn = false
    /** 이번 켜짐이 직전에 끈 지 10초 안에 다시 켠 것인가 (그러면 비상) */
    private var relit = false
    /** 정체 중 감사 후보: 끈 시각. 10초 동안 다시 켜지 않으면 감사로 보낸다 */
    private var pendingSince: Long? = null

    /**
     * 감사 인사로 읽혔으면 true. 달리는 중이면 비상등을 끄는 순간, 정체 중이면 끈 뒤 10초가 지난 순간.
     * [lastHardBrakeAt]: [HardBrakeDetector]가 마지막으로 급제동을 잡은 시각, [parked]: 기어 P
     */
    fun feed(
        at: Long,
        hazardOn: Boolean,
        turnSignalOn: Boolean,
        speedKmh: Int,
        lastHardBrakeAt: Long? = null,
        parked: Boolean = false,
    ): Boolean {
        if (turnSignalOn) lastTurnSignalAt = at
        val since = onSince
        if (hazardOn) {
            if (since == null) {
                onSince = at
                // 끈 뒤 곧 다시 켰다 → 감사 후보도 취소하고 이번 켜짐도 비상으로 본다
                relit = lastOffAt?.let { at - it < relightMs } ?: false
                pendingSince = null
                slowDuringOn = false
                parkedDuringOn = false
            }
            if (speedKmh < minSpeedKmh) slowDuringOn = true
            if (parked) parkedDuringOn = true
            return false
        }
        if (since == null) {
            val pending = pendingSince ?: return false
            // 지켜보는 동안 급제동하거나 P로 바꾸면 감사가 아니다
            if (parked || (lastHardBrakeAt != null && lastHardBrakeAt >= pending)) { pendingSince = null; return false }
            if (at - pending < relightMs) return false
            pendingSince = null
            lastThanksAt = at
            return true
        }
        onSince = null
        lastOffAt = at
        val onFor = at - since
        val signal = lastTurnSignalAt ?: return false
        val braked = lastHardBrakeAt != null && lastHardBrakeAt >= since - noBrakeBeforeMs
        val ok = onFor >= minOnMs &&
            onFor <= (if (slowDuringOn) maxOnSlowMs else maxOnMs) &&
            !relit && !braked && !parkedDuringOn && !parked &&
            since - signal in 0..afterSignalMs &&
            at - lastThanksAt >= cooldownMs
        if (!ok) return false
        if (slowDuringOn) {
            pendingSince = at
            return false
        }
        lastThanksAt = at
        return true
    }
}
