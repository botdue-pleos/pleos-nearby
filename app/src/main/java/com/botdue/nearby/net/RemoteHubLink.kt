package com.botdue.nearby.net

import android.os.Handler
import android.os.Looper
import com.botdue.nearby.core.Downlink
import com.botdue.nearby.core.NearbyJson
import com.botdue.nearby.core.Uplink
import com.botdue.nearby.core.demo.CarId
import com.botdue.nearby.core.demo.HubLink
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * 여러 대 시연: 이 기기의 차 한 대를 진짜 중계 서버에 붙인다.
 * 에뮬레이터 여러 대가 같은 서버(PC)에 붙으면 서로의 알림이 오간다.
 */
class RemoteHubLink(private val url: String, private val onChange: () -> Unit) : HubLink {
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient()
    private var car: CarId? = null
    var status = "연결 중"
        private set
    override var deliver: (CarId, Downlink) -> Unit = { _, _ -> }

    private val socket: WebSocket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = post { status = "서버 연결됨" }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { NearbyJson.decodeFromString(Downlink.serializer(), text) }.getOrNull() ?: return
            post { car?.let { deliver(it, msg) } }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = post { status = "서버 연결 안 됨" }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = post { status = "연결 끊김" }
    })

    private fun post(block: () -> Unit) { main.post { block(); onChange() } }

    override fun send(car: CarId, up: Uplink) {
        this.car = car
        socket.send(NearbyJson.encodeToString(Uplink.serializer(), up))
    }

    /** 서버 시계를 5분 앞당긴다 (서버를 NEARBY_DEMO=1로 켰을 때만) */
    fun jumpServer() {
        val base = url.replace("ws://", "http://").replace("wss://", "https://").substringBeforeLast("/nearby")
        Thread { runCatching { http.newCall(Request.Builder().url("$base/demo/jump").build()).execute().close() } }.start()
    }

    fun close() {
        socket.close(1000, null)
    }
}
