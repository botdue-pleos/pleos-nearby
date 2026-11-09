package com.botdue.nearby.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.botdue.nearby.core.Heading
import com.botdue.nearby.core.Notice
import com.botdue.nearby.core.RoadPos
import com.botdue.nearby.core.SEGMENT_M
import com.botdue.nearby.core.demo.MAIN

private const val AHEAD_CELLS = 15
private const val BEHIND_CELLS = 5
private const val PIXELS_PER_ROW = 3

/**
 * 앞길 램프: 아이오닉 파라메트릭 픽셀처럼 네모 칸을 세로로 쌓았다. 한 줄이 200m, 위가 앞쪽.
 * 앞 3km·뒤 1km. 다른 차의 위치는 그리지 않는다(서버도 보내지 않는다). 주의보·정차·합류 지점과 터널만 켠다.
 *
 * 새 알림이 오면 내 줄에서 그 지점까지 픽셀이 한 번 차례로 켜졌다가(거리를 눈으로 보여 주려고) 그 지점만 남는다.
 * 이 앱에서 움직이는 건 이것 하나다.
 */
@Composable
fun RoadStrip(me: RoadPos, notices: Collection<Notice>, tunnel: IntRange?, modifier: Modifier = Modifier) {
    val sign = if (me.heading == Heading.UP) 1 else -1
    // 줄 번호 k: 내 줄이 0, 앞으로 +1, 뒤로 -1
    val marks = mutableMapOf<Int, Notice.Kind>()
    for (n in notices) {
        val at = n.at ?: continue
        if (!at.sameLane(me)) continue
        marks[(at.segment - me.segment) * sign] = n.kind
    }
    val p = pal

    // 가장 최근에 받은 알림 지점까지 한 번 훑는다
    val newest = notices.filter { it.at?.sameLane(me) == true }.maxByOrNull { it.seq }
    val sweepTo = newest?.at?.let { ((it.segment - me.segment) * sign).coerceIn(-BEHIND_CELLS, AHEAD_CELLS - 1) } ?: 0
    val sweep = remember { Animatable(1f) }
    LaunchedEffect(newest?.key, newest?.seq) {
        if (newest == null) return@LaunchedEffect
        sweep.snapTo(0f)
        sweep.animateTo(1f, tween(durationMillis = 900, easing = LinearEasing))
    }

    fun colorOf(kind: Notice.Kind?): Color? = when (kind) {
        Notice.Kind.STOPPED_AHEAD, Notice.Kind.HAZARD_AHEAD, Notice.Kind.HAZARD_BEHIND -> p.danger
        Notice.Kind.MERGE -> p.accent
        else -> null
    }

    val rows = (AHEAD_CELLS - 1 downTo -BEHIND_CELLS).toList()
    Row(
        modifier
            .fillMaxHeight()
            .semantics { contentDescription = "앞길 램프: 앞 3킬로미터, 뒤 1킬로미터" },
    ) {
        Canvas(Modifier.width(118.px).fillMaxHeight()) {
            val rowH = size.height / rows.size
            val gap = rowH * 0.18f
            val cell = minOf(rowH - gap, (size.width - gap * (PIXELS_PER_ROW - 1)) / PIXELS_PER_ROW)
            val radius = CornerRadius(cell * 0.12f)
            val sweepColor = colorOf(newest?.kind) ?: p.accent
            rows.forEachIndexed { i, k ->
                val cellStart = (me.segment + sign * k) * SEGMENT_M
                val inTunnel = tunnel != null && me.roadId == MAIN && cellStart in tunnel
                var fill = when {
                    k == 0 -> p.me
                    else -> colorOf(marks[k]) ?: if (inTunnel) p.tunnel else p.cell
                }
                // 훑는 빛: 내 줄에서 목표 줄 쪽으로 지나가며 잠깐 켜진다
                val span = kotlin.math.abs(sweepTo)
                if (sweep.value < 1f && span > 0 && k != 0 && k * sweepTo > 0 && kotlin.math.abs(k) < span) {
                    val head = sweep.value * span
                    val behindHead = head - kotlin.math.abs(k)
                    if (behindHead in 0f..3f) fill = sweepColor.copy(alpha = 1f - behindHead / 3f)
                }
                val y = i * rowH + gap / 2
                repeat(PIXELS_PER_ROW) { c ->
                    val x = c * (cell + gap)
                    drawRoundRect(fill, Offset(x, y), Size(cell, cell), radius)
                    if (inTunnel && k != 0 && marks[k] == null) {
                        drawRoundRect(p.outline, Offset(x, y), Size(cell, cell), radius, style = Stroke(width = 1.5f))
                    }
                }
            }
        }
        Column(Modifier.fillMaxHeight().padding(start = 14.px)) {
            rows.forEach { k ->
                val cellStart = (me.segment + sign * k) * SEGMENT_M
                val inTunnel = tunnel != null && me.roadId == MAIN && cellStart in tunnel
                val kind = marks[k]
                val label = when {
                    k == 0 -> "나"
                    kind == Notice.Kind.STOPPED_AHEAD -> "멈춘 차"
                    kind == Notice.Kind.HAZARD_AHEAD -> "주의"
                    kind == Notice.Kind.HAZARD_BEHIND -> "뒤 주의"
                    kind == Notice.Kind.MERGE -> "합류"
                    k == 5 -> "1km"
                    k == 10 -> "2km"
                    k == AHEAD_CELLS - 1 -> "3km"
                    k == -BEHIND_CELLS -> "뒤 1km"
                    inTunnel && cellStart == tunnel!!.first -> "터널"
                    else -> null
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (label != null) {
                        T(label, 20.tx, color = if (kind != null || k == 0) p.fg else p.sub, weight = if (kind != null) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal)
                    }
                }
            }
        }
    }
}
