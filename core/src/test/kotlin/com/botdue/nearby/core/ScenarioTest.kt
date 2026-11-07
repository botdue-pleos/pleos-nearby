package com.botdue.nearby.core

import com.botdue.nearby.core.demo.CarId
import com.botdue.nearby.core.demo.DemoClock
import com.botdue.nearby.core.demo.LocalHubLink
import com.botdue.nearby.core.demo.ScenarioPlayer
import com.botdue.nearby.core.demo.Scenarios
import org.junit.Assert.assertTrue
import org.junit.Test

/** 시연 시나리오를 처음부터 끝까지 돌려 차마다 기대한 말을 들었는지 확인한다 */
class ScenarioTest {

    private fun play(make: () -> com.botdue.nearby.core.demo.Scenario, jump: Boolean = false): ScenarioPlayer {
        val scenario = make()
        val clock = DemoClock(scenario.startAt)
        val player = ScenarioPlayer(scenario, LocalHubLink(clock::now), clock = clock)
        player.runToEnd()
        if (jump) player.jump()
        return player
    }

    private fun ScenarioPlayer.heard(car: CarId) = clients.getValue(car).spoken.joinToString("\n")

    @Test
    fun `시연 1 - 합류 예고, 비상등 감사, 트렁크 알림`() {
        val p = play(Scenarios::merge)
        assertTrue(p.heard(CarId.B), p.heard(CarId.B).contains("1대 합류 예정"))
        assertTrue(p.heard(CarId.C), p.heard(CarId.C).contains("합류 예정"))
        assertTrue(p.heard(CarId.A), p.heard(CarId.A).contains("시속 90km 흐름"))
        assertTrue(p.heard(CarId.B), p.heard(CarId.B).contains("앞차가 고맙대요. 비상등으로"))
        assertTrue(p.heard(CarId.A), p.heard(CarId.A).contains("뒤차에 고맙다고 전했어요"))
        assertTrue(p.heard(CarId.A), p.heard(CarId.A).contains("트렁크가 열려 있대요"))
        assertTrue("이미 지나간 D는 합류 알림을 받지 않는다", !p.heard(CarId.D).contains("합류"))
    }

    @Test
    fun `시연 2 - 터널 안 주의보, 정차 행동요령, 터널 앞 재안내, 신고 도우미`() {
        val p = play(Scenarios::tunnel)
        val d = p.heard(CarId.D)
        assertTrue(d, d.contains("터널 안 위험 운전 제보"))
        assertTrue(d, d.contains("터널 안 멈춘 차"))
        assertTrue(d, d.contains("곧 터널이에요"))
        assertTrue(p.heard(CarId.A), p.heard(CarId.A).contains("뒤쪽에 위험 운전 제보"))
        val c = p.heard(CarId.C)
        listOf("터널 안에 멈춰 섰어요", "비상등 확인했어요", "트렁크 확인했어요", "비상구", "신고 확인했어요", "모두 마쳤어요")
            .forEach { assertTrue("$it\n$c", c.contains(it)) }
        assertTrue("비상등을 이미 켰으니 켜라고 말하지 않는다\n$c", !c.contains("비상등을 켜세요"))
        val b = p.clients.getValue(CarId.B)
        assertTrue(b.spoken.joinToString(), b.showReportGuide && b.memos.size == 1)
    }

    @Test
    fun `시연 2 - 5분 뒤 주의보가 사라지고 지도용 통계가 남는다`() {
        val scenario = Scenarios.tunnel()
        val clock = DemoClock(scenario.startAt)
        val link = LocalHubLink(clock::now)
        val player = ScenarioPlayer(scenario, link, clock = clock)
        player.runToEnd()
        player.jump()
        assertTrue(link.hub.board.active.isEmpty())
        assertTrue(link.hub.board.hotspots(min = 1).isNotEmpty())
    }
}
