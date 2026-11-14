package com.botdue.nearby.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyHubTest {
    private var now = 1_000_000L
    private val hub = NearbyHub({ now })
    private fun main(m: Int) = RoadPos("본선", m, Heading.UP)

    @Test
    fun `한 대의 신호만으로는 주의보가 생기지 않는다`() {
        hub.receive(Presence("d", main(4000), 90))
        val out = hub.receive(VoiceReport("x", main(6000))) + hub.receive(BrakeSignal("x", main(6010)))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `서로 다른 두 차 신호가 30초 안이면 주의보, 뒤 2km엔 전방 주의 앞 1km엔 후방 주의`() {
        hub.receive(Presence("behind", main(4400), 90))
        hub.receive(Presence("ahead", main(6700), 90))
        hub.receive(Presence("far", main(3000), 90))
        hub.receive(BrakeSignal("b", main(6150)))
        now += 20_000
        val out = hub.receive(VoiceReport("c", main(6250)))
        val alerts = out.associate { it.to to it.msg as HazardAlert }
        assertEquals(Side.AHEAD, alerts["behind"]!!.side)
        assertEquals(Side.BEHIND, alerts["ahead"]!!.side)
        assertNull(alerts["far"])
    }

    @Test
    fun `30초가 지난 신호는 함께 세지 않는다`() {
        hub.receive(BrakeSignal("b", main(6150)))
        now += 31_000
        assertNull(hub.board.add("c", main(6150), now, false))
    }

    @Test
    fun `터널 안 주의보는 뒤 3km까지 알린다`() {
        hub.receive(Presence("d", main(3500), 90))
        hub.receive(BrakeSignal("b", main(6300), inTunnel = true))
        val out = hub.receive(BrakeSignal("c", main(6320), inTunnel = true))
        assertTrue((out.single().msg as HazardAlert).inTunnel)
    }

    @Test
    fun `주의보는 5분 뒤 사라지고 통계만 남는다`() {
        hub.receive(Presence("d", main(5000), 90))
        hub.receive(BrakeSignal("b", main(6300)))
        hub.receive(BrakeSignal("c", main(6300)))
        now += 5 * 60_000L
        hub.receive(Presence("d", main(5000), 90))
        val out = hub.tick()
        assertTrue(out.single().msg is AlertCleared)
        assertTrue(hub.board.active.isEmpty())
        assertTrue(hub.board.hotspots(min = 1).isNotEmpty())
        assertTrue("통계 5건 미만은 지도에 안 나온다", hub.board.hotspots().isEmpty())
    }

    @Test
    fun `주의보가 떠 있는 동안 범위에 새로 들어온 차도 알림을 받는다`() {
        hub.receive(BrakeSignal("b", main(6300)))
        hub.receive(BrakeSignal("c", main(6300)))
        now += 60_000
        val out = hub.receive(Presence("late", main(5000), 90))
        assertEquals(Side.AHEAD, (out.single().msg as HazardAlert).side)
        assertTrue("같은 주의보는 두 번 보내지 않는다", hub.receive(Presence("late", main(5100), 90)).isEmpty())
    }

    @Test
    fun `합류 예고 - 본선 차는 합류 대수를, 합류할 차는 본선 속도를 받는다`() {
        val join = main(5000)
        hub.receive(Presence("main", main(4700), 90))
        hub.receive(Presence("passed", main(5600), 90))
        val out = hub.receive(Presence("ramp", RoadPos("진입로", 100, Heading.UP), 40, MergePlan(join, 30)))
        val byCar = out.groupBy({ it.to }, { it.msg })
        assertEquals(1, (byCar["main"]!!.single() as MergeAhead).count)
        assertNull("이미 지나간 차는 받지 않는다", byCar["passed"])
        assertEquals(90, (byCar["ramp"]!!.single() as MainlineFlow).avgSpeedKmh)
        assertTrue("같은 내용은 다시 보내지 않는다", hub.receive(Presence("ramp", RoadPos("진입로", 110, Heading.UP), 40, MergePlan(join, 29))).isEmpty())
    }

    @Test
    fun `감사는 바로 뒤 150m 안 차 하나에만, 없으면 못 전했다고 알려 준다`() {
        hub.receive(Presence("near", main(4900), 90))
        hub.receive(Presence("far", main(4700), 90))
        val out = hub.receive(Thanks("me", main(5000), ThanksVia.HAZARD_BLINK))
        assertEquals(listOf("near", "me"), out.map { it.to })
        assertEquals(SendResult(SendKind.THANKS, true), out.last().msg)

        val none = hub.receive(Thanks("lonely", main(9000), ThanksVia.VOICE))
        assertEquals(SendResult(SendKind.THANKS, false), none.single().msg)
    }

    @Test
    fun `문제 알림은 바로 앞 100m 안 차 하나에만`() {
        hub.receive(Presence("front", main(5060), 90))
        hub.receive(Presence("back", main(4950), 90))
        val out = hub.receive(ProblemNotice("me", main(5000), Problem.FUEL_DOOR))
        assertEquals("front", out.first().to)
        assertEquals(ProblemSpotted(Problem.FUEL_DOOR), out.first().msg)
    }

    @Test
    fun `정차 알림은 뒤차에 가고, 출발하면 지워진다`() {
        hub.receive(Presence("behind", main(5000), 90))
        val out = hub.receive(Stopped("s", main(6300)))
        val alert = out.single().msg as StoppedAlert
        val cleared = hub.receive(Resumed("s"))
        assertEquals(AlertCleared(alert.alertId), cleared.single().msg)
    }

    @Test
    fun `지나간 뒤차가 "있어"라고 답하면 멈춘 차 신호가 끊겨도 정차 알림을 10분 더 이어 둔다`() {
        hub.receive(Presence("behind", main(5000), 90))
        val alert = hub.receive(Stopped("s", main(6300))).single().msg as StoppedAlert
        now += 9 * 60_000L
        hub.receive(Presence("behind", main(6500), 60))
        assertTrue(hub.receive(StopCheck("behind", alert.alertId, stillThere = true)).isEmpty())
        now += 9 * 60_000L
        hub.receive(Presence("behind", main(9000), 90))
        assertTrue("답한 지 9분: 아직 남아 있다", hub.tick().isEmpty())
        now += 2 * 60_000L
        hub.receive(Presence("behind", main(9000), 90))
        assertEquals(AlertCleared(alert.alertId), hub.tick().single().msg)
    }

    @Test
    fun `"없어" 두 표면 지우지만, 멈춘 차가 아직 신호를 보내거나 알림을 안 받은 차의 표는 세지 않는다`() {
        hub.receive(Presence("d1", main(5000), 90))
        hub.receive(Presence("d2", main(4800), 90))
        hub.receive(Presence("s", main(6300), 0))
        val alert = hub.receive(Stopped("s", main(6300))).first().msg as StoppedAlert
        hub.receive(Presence("stranger", main(9000), 90))
        assertTrue(hub.receive(StopCheck("d1", alert.alertId, false)).isEmpty())
        assertTrue("알림을 받지 않은 차의 표", hub.receive(StopCheck("stranger", alert.alertId, false)).isEmpty())
        assertTrue("멈춘 차가 방금 신호를 보냈다", hub.receive(StopCheck("d2", alert.alertId, false)).isEmpty())
        now += 11_000
        hub.receive(Presence("d1", main(7000), 90))
        val out = hub.receive(StopCheck("d2", alert.alertId, false))
        assertEquals(setOf("d1", "d2"), out.map { it.to }.toSet())
        assertTrue(out.all { it.msg == AlertCleared(alert.alertId) })
    }

    @Test
    fun `"있어"가 오면 그때까지의 "없어" 표는 버린다`() {
        listOf("d1", "d2", "d3").forEach { hub.receive(Presence(it, main(5000), 90)) }
        val alert = hub.receive(Stopped("s", main(6300))).first().msg as StoppedAlert
        now += 11_000
        hub.receive(StopCheck("d1", alert.alertId, false))
        hub.receive(StopCheck("d2", alert.alertId, true))
        assertTrue(hub.receive(StopCheck("d3", alert.alertId, false)).isEmpty())
    }

    @Test
    fun `통신 형식에는 차량 번호·차종·색상·자유 텍스트 칸이 없다`() {
        val samples: List<Any> = listOf(
            Presence("id", main(1), 1, MergePlan(main(2), 3)), BrakeSignal("id", main(1)), VoiceReport("id", main(1)),
            Stopped("id", main(1)), StopCheck("id", "s1", true), Thanks("id", main(1), ThanksVia.VOICE), ProblemNotice("id", main(1), Problem.TRUNK),
        )
        val json = samples.joinToString { NearbyJson.encodeToString(Uplink.serializer(), it as Uplink) }
        listOf("plate", "model", "color", "text", "message").forEach { assertFalse(it, json.contains("\"$it\"")) }
        val down = NearbyJson.encodeToString(Downlink.serializer(), HazardAlert("h1", main(6300), Side.AHEAD, true))
        assertFalse("다른 차에게 가는 말에는 익명 ID도 없다", down.contains("\"id\""))
    }

    @Test
    fun `서버는 정확한 위치 대신 200m 구간 가운데만 내보낸다`() {
        hub.receive(Presence("d", main(5000), 90))
        hub.receive(BrakeSignal("b", main(6213)))
        val out = hub.receive(BrakeSignal("c", main(6255)))
        assertEquals(6300, (out.single().msg as HazardAlert).at.meter)
    }
}
