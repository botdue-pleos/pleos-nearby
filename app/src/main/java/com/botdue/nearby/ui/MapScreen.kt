package com.botdue.nearby.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.botdue.nearby.core.HazardBoard
import com.botdue.nearby.core.SEGMENT_M

/**
 * 위험 지점 지도: 평일 시간대 × 도로 구간. 같은 칸에 5건 이상 쌓인 곳만 칠한다.
 * 누가 언제 제보했는지는 없고, 칸별 건수만 있다.
 */
@Composable
fun MapScreen(hotspots: Map<HazardBoard.StatKey, Int>, tunnel: IntRange?, onBack: () -> Unit) {
    val hours = 7..21
    val segs = 36 downTo 26
    val sum = hotspots.entries.filter { it.key.dayOfWeek <= 5 }
        .groupBy({ it.key.segment to it.key.hour }, { it.value })
        .mapValues { it.value.sum() }
    val max = (sum.values.maxOrNull() ?: 1).coerceAtLeast(1)
    Column(
        Modifier
            .fillMaxSize()
            .background(pal.bg)
            .padding(horizontal = 48.px, vertical = 40.px)
    ) {
        Header("위험 지점 지도", "본선 상행 · 평일 · 시")
        Spacer(Modifier.height(16.px))
        T("5건 이상 쌓인 칸만 보여요. 차량·제보자 정보 없이 칸별 건수만 남아요. (시연 데이터)", 22.tx, color = pal.sub)
        Spacer(Modifier.height(24.px))
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(140.px).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.px)) {
                segs.forEach { seg ->
                    val m = seg * SEGMENT_M
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        T("${"%.1f".format(m / 1000.0)}km${if (tunnel != null && m in tunnel) " 터널" else ""}", 18.tx, color = pal.sub)
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.px)) {
                segs.forEach { seg ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.px)) {
                        hours.forEach { h ->
                            val v = sum[seg to h] ?: 0
                            val a = if (v == 0) 0f else 0.25f + 0.75f * v / max
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(if (v == 0) pal.cell else pal.danger.copy(alpha = a), RoundedCornerShape(4.px))
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(start = 140.px, top = 8.px)) {
            hours.forEach { h ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (h % 3 == 0) T("$h", 18.tx, color = pal.sub)
                }
            }
        }
        Spacer(Modifier.height(24.px))
        T("확장: 이 지도를 지자체·경찰의 단속·시설 개선 위치 자료로", 22.tx, color = pal.sub)
        Spacer(Modifier.height(20.px))
        OutlineButton("주행 화면으로", Modifier.width(360.px), onBack)
    }
}
