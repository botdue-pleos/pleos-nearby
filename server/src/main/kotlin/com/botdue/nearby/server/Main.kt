package com.botdue.nearby.server

import com.botdue.nearby.core.Downlink
import com.botdue.nearby.core.NearbyHub
import com.botdue.nearby.core.NearbyJson
import com.botdue.nearby.core.Uplink
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * 니어바이 중계 서버.
 * 차는 WebSocket(/nearby)으로 JSON 한 줄씩 보낸다. 판단은 core의 [NearbyHub]가 하고,
 * 이 파일은 "어느 익명 ID가 어느 연결에서 왔는지"만 기억해 결과를 돌려보낸다.
 * 연결이 끊기면 그 연결의 익명 ID도 함께 잊는다. 로그에는 메시지 종류만 남기고 위치는 남기지 않는다.
 *
 * 시연용으로 "/demo/jump"를 부르면 서버 시계를 5분 앞당긴다(주의보 소멸 시연). NEARBY_DEMO=1일 때만 켜진다.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) { nearby(demo = System.getenv("NEARBY_DEMO") == "1") }.start(wait = true)
}

class Relay(clock: () -> Long) {
    val hub = NearbyHub(clock)
    private val lock = Mutex()
    private val sessions = ConcurrentHashMap<String, DefaultWebSocketSession>()

    suspend fun onText(session: DefaultWebSocketSession, text: String, owned: MutableSet<String>) {
        val up = runCatching { NearbyJson.decodeFromString(Uplink.serializer(), text) }.getOrNull() ?: return
        sessions[up.id] = session
        owned += up.id
        val out = lock.withLock { hub.receive(up) }
        log.debug("in {}", up::class.simpleName)
        deliver(out)
    }

    suspend fun tick() = deliver(lock.withLock { hub.tick() })

    fun forget(ids: Set<String>) = ids.forEach { sessions.remove(it) }

    private suspend fun deliver(list: List<com.botdue.nearby.core.Delivery>) {
        for (d in list) {
            val s = sessions[d.to] ?: continue
            runCatching { s.send(Frame.Text(NearbyJson.encodeToString(Downlink.serializer(), d.msg))) }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(Relay::class.java)
    }
}

fun Application.nearby(demo: Boolean = false, clock: () -> Long = System::currentTimeMillis) {
    var offset = 0L
    val relay = Relay { clock() + offset }
    install(WebSockets)
    launch {
        while (true) {
            delay(1_000)
            relay.tick()
        }
    }
    routing {
        get("/") { call.respondText("pleos-nearby relay") }
        if (demo) get("/demo/jump") {
            offset += 5 * 60_000L
            relay.tick()
            call.respondText("+5분")
        }
        webSocket("/nearby") {
            val owned = mutableSetOf<String>()
            try {
                for (frame in incoming) if (frame is Frame.Text) relay.onText(this, frame.readText(), owned)
            } finally {
                relay.forget(owned)
            }
        }
    }
}
