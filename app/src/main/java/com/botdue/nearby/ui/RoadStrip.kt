package com.botdue.nearby.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.botdue.nearby.core.Heading
import com.botdue.nearby.core.Notice
import com.botdue.nearby.core.RoadPos
import com.botdue.nearby.core.SEGMENT_M
import com.botdue.nearby.core.demo.MAIN

private const val AHEAD_CELLS = 15
private const val BEHIND_CELLS = 5

/**
 * 앞길 띠: 내 차 기준 앞 3km·뒤 1km를 200m 칸으로 세운다. 위가 앞쪽.
 * 다른 차의 위치는 그리지 않는다(서버도 보내지 않는다). 주의보·정차·합류 지점과 터널만 칠한다.
 */
@Composable
fun RoadStrip(me: RoadPos, notices: Collection<Notice>, tunnel: IntRange?, modifier: Modifier = Modifier) {
    val sign = if (me.heading == Heading.UP) 1 else -1
    // 칸 번호 k: 내 칸이 0, 앞으로 +1, 뒤로 -1
    val marks = mutableMapOf<Int, Notice.Kind>()
    for (n in notices) {
        val at = n.at ?: continue
        if (!at.sameLane(me)) continue
        marks[(at.segment - me.segment) * sign] = n.kind
    }
    val p = pal
    Column(
        modifier
            .fillMaxHeight()
            .semantics { contentDescription = "앞길 띠: 앞 3킬로미터, 뒤 1킬로미터" },
        verticalArrangement = Arrangement.spacedBy(5.px),
    ) {
        for (k in AHEAD_CELLS - 1 downTo -BEHIND_CELLS) {
            val cellStart = (me.segment + sign * k) * SEGMENT_M
            val inTunnel = tunnel != null && me.roadId == MAIN && cellStart in tunnel
            val kind = marks[k]
            val fill = when {
                k == 0 -> p.me
                kind == Notice.Kind.STOPPED_AHEAD || kind == Notice.Kind.HAZARD_AHEAD || kind == Notice.Kind.HAZARD_BEHIND -> p.danger
                kind == Notice.Kind.MERGE -> p.accent
                inTunnel -> p.tunnel
                else -> p.cell
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(96.px)
                        .fillMaxHeight()
                        .background(fill, RoundedCornerShape(6.px))
                        .then(if (inTunnel && k != 0 && kind == null) Modifier.border(BorderStroke(2.px, p.outline), RoundedCornerShape(6.px)) else Modifier)
                )
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
                if (label != null) {
                    T(
                        label, 20.tx, Modifier.padding(start = 14.px),
                        color = if (kind != null || k == 0) p.fg else p.sub,
                    )
                }
            }
        }
    }
}
