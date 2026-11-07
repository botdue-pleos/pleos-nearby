package com.botdue.nearby.core

import java.security.SecureRandom

/**
 * 10분마다 바뀌는 익명 ID. 고정 ID가 없어 서버도 한 차를 오래 따라갈 수 없다.
 * 멈춰 서서 뒤차에 알리는 동안에는 바꾸지 않는다(출발 알림을 같은 ID로 보내야 하므로).
 */
class AnonymousIds(
    private val rotateMs: Long = 10 * 60_000L,
    private val random: SecureRandom = SecureRandom(),
) {
    private var current: String = fresh()
    private var issuedAt: Long? = null
    var hold: Boolean = false

    fun current(now: Long): String {
        val since = issuedAt
        if (since == null) {
            issuedAt = now
        } else if (!hold && now - since >= rotateMs) {
            current = fresh()
            issuedAt = now
        }
        return current
    }

    private fun fresh(): String {
        val bytes = ByteArray(8).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
