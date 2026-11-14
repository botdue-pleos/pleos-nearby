package com.botdue.nearby.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorsTest {

    @Test
    fun `시속 90에서 1초 만에 40으로 줄이면 급제동`() {
        val d = HardBrakeDetector()
        assertFalse(d.feed(0, 90, false))
        assertFalse(d.feed(500, 90, false))
        assertTrue(d.feed(1500, 40, true))
    }

    @Test
    fun `천천히 줄이거나 브레이크를 안 밟으면 급제동 아님`() {
        val d = HardBrakeDetector()
        var v = 90
        var t = 0L
        repeat(10) { assertFalse(d.feed(t, v, true)); t += 500; v -= 3 }
        val e = HardBrakeDetector()
        e.feed(0, 90, false)
        assertFalse(e.feed(1000, 40, false))
    }

    @Test
    fun `급제동 뒤 10초 서 있으면 정차, 다시 달리면 출발`() {
        val s = StopDetector()
        assertEquals(null, s.feed(0, 0, false, lastHardBrakeAt = -1000))
        assertEquals(null, s.feed(9_500, 0, false, -1000))
        assertEquals(StopDetector.NowStopped, s.feed(10_000, 0, false, -1000))
        assertEquals(null, s.feed(11_000, 30, false, -1000))
        assertEquals(StopDetector.NowResumed, s.feed(16_000, 30, false, -1000))
    }

    @Test
    fun `급제동도 비상등도 없이 서 있는 건 신호 대기라 정차 아님`() {
        val s = StopDetector()
        for (t in 0..60_000L step 500) assertEquals(null, s.feed(t, 0, false, null))
    }

    @Test
    fun `깜빡이로 끼어든 뒤 비상등 1점5초는 감사 인사`() {
        val h = HazardThanksDetector()
        assertFalse(h.feed(0, false, true, 70))
        assertFalse(h.feed(3_000, true, false, 70))
        assertTrue(h.feed(4_500, false, false, 70))
    }

    @Test
    fun `오래 켠 비상등, 서 있을 때 켠 비상등, 깜빡이 없던 비상등은 감사 아님`() {
        val long = HazardThanksDetector()
        long.feed(0, false, true, 70); long.feed(1_000, true, false, 70)
        assertFalse(long.feed(9_000, false, false, 70))

        val still = HazardThanksDetector()
        still.feed(0, false, true, 0); still.feed(1_000, true, false, 0)
        assertFalse("서 있을 때는 끄는 순간 바로 감사로 보내지 않는다", still.feed(2_500, false, false, 0))

        val noSignal = HazardThanksDetector()
        noSignal.feed(0, true, false, 70)
        assertFalse(noSignal.feed(1_500, false, false, 70))
    }

    /** 정체 구간: 깜빡이로 끼어들고 멈춘 뒤 비상등을 [onMs] 켰다 끈다. 끈 뒤 [after]까지 0.5초씩 넣고 감사로 읽힌 횟수 */
    private fun jam(
        onMs: Long = 1_500,
        brakeAt: Long? = null,
        parked: Boolean = false,
        relightAfterOff: Long? = null,
        after: Long = 12_000,
    ): Int {
        val h = HazardThanksDetector()
        var thanks = 0
        h.feed(0, false, true, 8)
        h.feed(2_000, false, false, 3)
        val on = 4_000L
        var t = on
        while (t < on + onMs) { if (h.feed(t, true, false, 0, brakeAt, parked)) thanks++; t += 500 }
        val off = on + onMs
        t = off
        while (t <= off + after) {
            val relit = relightAfterOff != null && t - off in relightAfterOff until relightAfterOff + 2_000
            if (h.feed(t, relit, false, 0, brakeAt, parked)) thanks++
            t += 500
        }
        return thanks
    }

    @Test
    fun `정체 중 끼어든 뒤 멈춰서 비상등 1점5초는 끄고 10초 지켜본 뒤 감사`() {
        val h = HazardThanksDetector()
        h.feed(0, false, true, 8)
        h.feed(2_000, false, false, 0)
        h.feed(4_000, true, false, 0)
        assertFalse(h.feed(5_500, false, false, 0))
        assertFalse("끈 뒤 10초가 안 됐다", h.feed(15_000, false, false, 0))
        assertTrue(h.feed(15_500, false, false, 0))
        assertEquals(1, jam())
    }

    @Test
    fun `급제동 직후 켠 비상등은 감사가 아니다 (달리는 중에도)`() {
        assertEquals(0, jam(brakeAt = -10_000))
        val fast = HazardThanksDetector()
        fast.feed(0, false, true, 70)
        fast.feed(3_000, true, false, 70, lastHardBrakeAt = 1_000)
        assertFalse(fast.feed(4_500, false, false, 70, lastHardBrakeAt = 1_000))
        assertEquals("30초가 지난 급제동은 상관없다", 1, jam(brakeAt = -40_000))
    }

    @Test
    fun `정체 중에는 3초 넘게 켜면 비상, 달리는 중에도 4초 넘으면 비상`() {
        assertEquals(1, jam(onMs = 3_000))
        assertEquals(0, jam(onMs = 3_500))
        val fast = HazardThanksDetector()
        fast.feed(0, false, true, 70); fast.feed(1_000, true, false, 70)
        assertFalse(fast.feed(5_500, false, false, 70))
    }

    @Test
    fun `끈 뒤 10초 안에 다시 켜면 감사를 취소하고 비상으로 본다`() {
        assertEquals(0, jam(relightAfterOff = 5_000, after = 30_000))
        val relit = HazardThanksDetector()
        relit.feed(0, false, true, 70)
        relit.feed(2_000, true, false, 70)
        assertTrue("달리는 중에는 끄는 순간 판정", relit.feed(3_500, false, false, 70))
        relit.feed(6_000, true, false, 70)
        assertFalse("끈 지 2.5초 만에 다시 켰다", relit.feed(7_500, false, false, 70))
    }

    @Test
    fun `기어 P에서 켠 비상등은 감사가 아니다`() {
        assertEquals(0, jam(parked = true))
    }

    @Test
    fun `행동요령은 이미 한 단계를 건너뛰고 남은 것만 말한다`() {
        val c = SafetyChecklist(inTunnel = false)
        c.start(hazardOn = false, trunkOpen = false, doorOpen = false)
        val said = c.update(hazardOn = true, trunkOpen = true, doorOpen = false)
        assertEquals(SafetyChecklist.Step.EVACUATE, c.current)
        assertTrue(said.last().contains("가드레일"))
        assertTrue(c.update(true, true, false).isEmpty())
        c.update(true, true, true)
        c.update(true, true, true, reported = true)
        assertEquals(null, c.current)
    }

    @Test
    fun `터널에서는 비상구 쪽으로 대피하라고 안내`() {
        val c = SafetyChecklist(inTunnel = true)
        c.update(true, true, false)
        assertTrue(c.prompt().single().contains("비상구"))
    }

    @Test
    fun `음성 의도 읽기`() {
        val r = KeywordIntentReader
        assertEquals(Intent.SayThanks, r.read("고마워"))
        assertEquals(Intent.ReportHazard, r.read("방금 위험한 차 있었어"))
        assertEquals(Intent.MemoForReport, r.read("신고해줘"))
        assertEquals(Intent.Reported, r.read("신고했어"))
        assertEquals(Intent.TellProblem(Problem.FUEL_DOOR), r.read("앞차 주유구 열렸어"))
        assertEquals("위험 제보가 \"있었어\"보다 먼저", Intent.ReportHazard, r.read("방금 위험한 차 있었어"))
        assertEquals(Intent.StillThere(true), r.read("있어"))
        assertEquals(Intent.StillThere(true), r.read("아직 서 있었어"))
        assertEquals(Intent.StillThere(false), r.read("없어"))
        assertEquals(Intent.Answer(true), r.read("응"))
        assertEquals(Intent.Answer(false), r.read("아니"))
    }

    @Test
    fun `음성 제보는 1분에 한 번, 하루 10번까지`() {
        val g = ReportGuard()
        assertTrue(g.allow(0))
        assertFalse(g.allow(30_000))
        var t = 60_000L
        repeat(9) { assertTrue(g.allow(t)); t += 60_000 }
        assertFalse(g.allow(t))
    }
}
