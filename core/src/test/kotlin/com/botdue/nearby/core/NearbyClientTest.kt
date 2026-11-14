package com.botdue.nearby.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 차 한 대(뒤차)가 정차 알림을 받고, 다가가고, 지나간 뒤 답하는 흐름 */
class NearbyClientTest {
    private val sent = mutableListOf<Uplink>()
    private val client = NearbyClient(send = { sent += it })
    private val stopAt = RoadPos("본선", 6300, Heading.UP)
    private var t = 0L

    /** [from]m에서 [to]m까지 [kmh]로 1초씩 달린다 */
    private fun drive(from: Int, to: Int, kmh: Int) {
        var m = from
        while (m <= to) {
            client.onSample(VehicleSample(t, RoadPos("본선", m, Heading.UP), kmh))
            t += 1_000
            m += (kmh / 3.6).toInt().coerceAtLeast(1)
        }
    }

    private fun heard() = client.spoken.joinToString("\n")

    private fun approachAndPass(passKmh: Int = 40) {
        drive(4000, 5000, 90)
        client.onDownlink(StoppedAlert("s1", stopAt, inTunnel = false))
        drive(5000, 6000, 90)
        drive(6000, 6480, passKmh)
    }

    @Test
    fun `멈춘 차 500m 앞에서 한 번 더 말하고, 지나간 뒤 한 번 묻는다`() {
        approachAndPass()
        val h = heard()
        assertEquals(h, 1, client.spoken.count { it == "앞에 멈춘 차가 있어요. 속도를 줄이고 비켜 가세요." })
        assertEquals(h, 1, client.spoken.count { it.startsWith("방금 지나간 곳에 아직 차가 서 있었나요?") })
        assertNotNull(client.question)
        listOf("멈추세요", "세우세요", "정차하세요").forEach { assertFalse("앱은 멈추라고 하지 않는다: $it\n$h", h.contains(it)) }
    }

    @Test
    fun `"있어"라고 답하면 서버에 알리고 신고를 묻고, "응"이면 신고 번호를 띄운다`() {
        approachAndPass()
        client.onVoice("있어")
        assertEquals(StopCheck(sent.last().id, "s1", true), sent.last())
        assertTrue(client.spoken.last() == "신고할까요?")
        client.onVoice("응")
        assertTrue(heard(), client.spoken.last().contains("112") && client.spoken.last().contains("1588-2504"))
        assertTrue(client.showCallGuide)
        assertNull(client.question)
        drive(6500, 8000, 90)
        assertFalse("1분 뒤에는 내린다", client.showCallGuide)
    }

    @Test
    fun `"없어"라고 답하면 없음을 보내고 신고는 묻지 않는다`() {
        approachAndPass()
        client.onVoice("없어")
        assertEquals(false, (sent.last() as StopCheck).stillThere)
        assertNull(client.question)
        assertFalse(client.showCallGuide)
    }

    @Test
    fun `답이 없으면 지나갈 때 크게 느려졌을 때만 "있음"으로 보낸다`() {
        approachAndPass(passKmh = 20)
        drive(6490, 6800, 20)
        assertEquals(true, (sent.filterIsInstance<StopCheck>().single()).stillThere)
        assertNull(client.question)

        val fast = mutableListOf<Uplink>()
        val other = NearbyClient(send = { fast += it })
        var m = 5000
        other.onSample(VehicleSample(0, RoadPos("본선", m, Heading.UP), 90))
        other.onDownlink(StoppedAlert("s1", stopAt, inTunnel = false))
        var at = 0L
        while (m < 7500) { at += 1_000; m += 25; other.onSample(VehicleSample(at, RoadPos("본선", m, Heading.UP), 90)) }
        assertTrue("느려지지 않았으면 아무것도 보내지 않는다", fast.none { it is StopCheck })
    }

    @Test
    fun `묻지 않았는데 "있어"만 들으면 못 알아들었다고 한다`() {
        drive(4000, 4100, 90)
        client.onVoice("있어")
        assertEquals("잘 못 알아들었어요.", client.spoken.last())
        assertTrue(sent.none { it is StopCheck })
    }

    @Test
    fun `자막의 거리는 지금 남은 거리와 같다`() {
        drive(4000, 4300, 90)
        client.onDownlink(StoppedAlert("s1", stopAt, inTunnel = false))
        assertTrue(client.caption()!!.startsWith("2킬로미터 앞"))
        drive(4300, 5200, 90)
        val d = client.last!!.pos.distanceTo(stopAt)!!
        assertTrue(client.caption()!!, client.caption()!!.startsWith(NearbyClient.distanceText(d) + " 앞"))
    }

    @Test
    fun `감사를 받으면 지난 합류 예고는 내린다`() {
        drive(4000, 4100, 90)
        client.onDownlink(MergeAhead(RoadPos("본선", 5000, Heading.UP), 1))
        assertNotNull(client.notices["merge"])
        client.onDownlink(ThanksReceived(ThanksVia.HAZARD_BLINK))
        assertNull(client.notices["merge"])
        assertNotNull(client.notices["thanks"])
    }

    @Test
    fun `신고 메모는 이 차의 앱 안에만 적는다고 말한다`() {
        drive(4000, 4100, 90)
        client.onVoice("신고해줘")
        assertTrue(client.spoken.last().contains("이 차의 앱 안에만"))
        assertTrue(client.reportGuide().last().contains("이 차의 앱 안에만"))
    }
}
