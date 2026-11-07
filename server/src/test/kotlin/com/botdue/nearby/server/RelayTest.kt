package com.botdue.nearby.server

import com.botdue.nearby.core.Downlink
import com.botdue.nearby.core.Heading
import com.botdue.nearby.core.NearbyJson
import com.botdue.nearby.core.Presence
import com.botdue.nearby.core.RoadPos
import com.botdue.nearby.core.Thanks
import com.botdue.nearby.core.ThanksReceived
import com.botdue.nearby.core.ThanksVia
import com.botdue.nearby.core.Uplink
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** 두 차가 서로 다른 WebSocket 연결로 붙어도 감사 인사가 뒤차 연결로만 가는지 */
class RelayTest {

    @Test
    fun `앞차의 감사 인사가 뒤차 연결로 간다`() = testApplication {
        application { nearby() }
        val client = createClient { install(WebSockets) }
        val front = client.webSocketSession("/nearby")
        val back = client.webSocketSession("/nearby")
        fun json(u: Uplink) = Frame.Text(NearbyJson.encodeToString(Uplink.serializer(), u))
        val road = { m: Int -> RoadPos("본선", m, Heading.UP) }

        back.send(json(Presence("back", road(4900), 90)))
        front.send(json(Presence("front", road(5000), 90)))
        front.send(json(Thanks("front", road(5000), ThanksVia.HAZARD_BLINK)))

        val got = withTimeout(5_000) { (back.incoming.receive() as Frame.Text).readText() }
        assertEquals(ThanksReceived(ThanksVia.HAZARD_BLINK), NearbyJson.decodeFromString(Downlink.serializer(), got))
    }
}
