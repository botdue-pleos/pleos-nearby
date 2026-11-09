package com.botdue.nearby.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.botdue.nearby.core.NearbyClient
import com.botdue.nearby.core.Notice
import com.botdue.nearby.core.SafetyChecklist

private val DANGER_ORDER = listOf(Notice.Kind.STOPPED_AHEAD, Notice.Kind.HAZARD_AHEAD, Notice.Kind.HAZARD_BEHIND)

/** 위험 알림이 먼저(급한 순), 나머지는 최근에 받은 순 */
private fun ordered(list: Collection<Notice>) =
    list.sortedWith(compareBy<Notice> { DANGER_ORDER.indexOf(it.kind).let { i -> if (i < 0) DANGER_ORDER.size else i } }.thenByDescending { it.seq })

private fun Notice.Kind.danger() = this == Notice.Kind.STOPPED_AHEAD || this == Notice.Kind.HAZARD_AHEAD || this == Notice.Kind.HAZARD_BEHIND

/**
 * 주행 화면 (세로 1칸). 왼쪽은 앞길 띠, 오른쪽은 지금 가장 급한 알림 하나와 나머지 목록.
 * 내 차가 멈춰 서면 오른쪽이 행동요령 확인판으로, 차를 세우면 신고 도우미로 바뀐다.
 */
@Composable
fun DriveScreen(
    client: NearbyClient,
    version: Int,
    tunnel: IntRange?,
    clock: String,
    modifier: Modifier = Modifier,
) {
    val s = client.last
    // version이 바뀔 때마다 알림 목록을 다시 읽는다 (NearbyClient는 Compose 상태가 아니다)
    val notices = remember(version) { ordered(client.notices.values) }
    Column(
        modifier
            .fillMaxSize()
            .background(pal.bg)
            .padding(horizontal = 48.px, vertical = 40.px)
    ) {
        Header("니어바이", clock)
        Spacer(Modifier.height(10.px))
        T(
            when {
                s == null -> "출발 전"
                s.parked -> "주차 중"
                else -> "${s.pos.roadId} · 시속 ${s.speedKmh}km${if (s.inTunnel) " · 터널 안" else ""}"
            },
            24.tx, color = pal.sub,
        )
        Spacer(Modifier.height(28.px))
        Row(Modifier.weight(1f)) {
            if (s != null) RoadStrip(s.pos, notices, tunnel, Modifier.width(250.px))
            Spacer(Modifier.width(32.px))
            Column(Modifier.weight(1f)) {
                val list = client.checklist
                when {
                    list != null -> ChecklistPanel(list, list.completed.toSet(), list.current)
                    client.showReportGuide -> ReportPanel(client.reportGuide())
                    notices.isEmpty() -> Quiet()
                    else -> {
                        BigNotice(notices.first())
                        Spacer(Modifier.height(20.px))
                        notices.drop(1).take(3).forEach { SmallNotice(it); Spacer(Modifier.height(12.px)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.px))
        Divider()
        Spacer(Modifier.height(14.px))
        T("말한 내용", 20.tx, color = pal.sub)
        T(client.spoken.lastOrNull() ?: "알릴 일이 생기면 한 번만, 짧게 말해요.", 24.tx, lineHeight = 1.35f)
    }
}

@Composable
private fun Quiet() {
    Column {
        T("앞길 조용해요", 44.tx, weight = FontWeight.Bold)
        Spacer(Modifier.height(14.px))
        T("근처 플레오스 차와 익명으로 연결돼 있어요. 차량 번호·차종·색상은 주고받지 않아요.", 26.tx, color = pal.sub)
    }
}

/**
 * 지금 가장 급한 알림. 색으로 크게 칠하지 않고, 램프와 같은 픽셀 세 칸으로만 종류를 보여 준다
 * (강조는 왼쪽 램프 한 곳에 모은다).
 */
@Composable
private fun BigNotice(n: Notice) {
    val lit = when {
        n.kind.danger() -> pal.danger
        n.kind == Notice.Kind.MERGE || n.kind == Notice.Kind.FLOW -> pal.accent
        else -> pal.me
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(pal.cardBg, RoundedCornerShape(10.px))
            .padding(32.px)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            repeat(3) {
                Box(Modifier.padding(end = 6.px).size(22.px).background(lit, RoundedCornerShape(3.px)))
            }
            T(kindLabel(n.kind), 22.tx, Modifier.padding(start = 10.px), weight = FontWeight.SemiBold, color = pal.sub)
        }
        Spacer(Modifier.height(18.px))
        T(n.title, 46.tx, weight = FontWeight.ExtraBold, color = pal.cardFg, lineHeight = 1.2f)
        Spacer(Modifier.height(14.px))
        T(n.detail, 26.tx, color = pal.cardFg)
    }
}

@Composable
private fun SmallNotice(n: Notice) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(18.px)
                .background(if (n.kind.danger()) pal.danger else if (n.kind == Notice.Kind.MERGE) pal.accent else pal.me, RoundedCornerShape(3.px))
        )
        T(n.title, 24.tx, Modifier.padding(start = 14.px))
    }
}

private fun kindLabel(k: Notice.Kind) = when (k) {
    Notice.Kind.STOPPED_AHEAD -> "전방 정차"
    Notice.Kind.HAZARD_AHEAD -> "앞길 주의보"
    Notice.Kind.HAZARD_BEHIND -> "후방 주의"
    Notice.Kind.MERGE -> "합류 예고"
    Notice.Kind.FLOW -> "합류 준비"
    Notice.Kind.PROBLEM -> "근처 차가 알려 줌"
    Notice.Kind.THANKS -> "감사 인사"
    Notice.Kind.INFO -> "알림"
}

/** 2차 사고 행동요령: 차량 신호로 확인한 단계에 체크 */
@Composable
private fun ChecklistPanel(list: SafetyChecklist, completed: Set<SafetyChecklist.Step>, current: SafetyChecklist.Step?) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { Box(Modifier.padding(end = 6.px).size(22.px).background(pal.danger, RoundedCornerShape(3.px))) }
            T(if (list.inTunnel) "터널 안 정차" else "사고·고장 정차", 22.tx, Modifier.padding(start = 10.px), weight = FontWeight.SemiBold, color = pal.sub)
        }
        Spacer(Modifier.height(8.px))
        T("뒤차에 알렸어요", 40.tx, weight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.px))
        T("할 일을 차가 신호로 확인해요", 24.tx, color = pal.sub)
        Spacer(Modifier.height(24.px))
        SafetyChecklist.Step.entries.forEach { step ->
            val done = step in completed
            val now = current == step
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (now) pal.cardBg else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(14.px))
                    .padding(horizontal = 20.px, vertical = 18.px),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(34.px)
                        .background(if (done) pal.accent else pal.cell, RoundedCornerShape(8.px)),
                    contentAlignment = Alignment.Center,
                ) { if (done) T("✓", 20.tx, weight = FontWeight.Bold, color = pal.primaryFg) }
                Column(Modifier.padding(start = 18.px)) {
                    T(list.title(step), 30.tx, weight = if (now) FontWeight.Bold else FontWeight.Normal, color = if (done) pal.sub else pal.fg)
                    T(
                        when (step) {
                            SafetyChecklist.Step.HAZARD -> "비상등 신호"
                            SafetyChecklist.Step.TRUNK -> "트렁크 열림 신호"
                            SafetyChecklist.Step.EVACUATE -> "운전석 문 열림 신호"
                            SafetyChecklist.Step.REPORT -> "\"신고했어\" 음성"
                        } + if (done) " 확인" else "로 확인",
                        20.tx, color = pal.sub,
                    )
                }
            }
            Spacer(Modifier.height(8.px))
        }
    }
}

@Composable
private fun ReportPanel(lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(14.px)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { Box(Modifier.padding(end = 6.px).size(22.px).background(pal.me, RoundedCornerShape(3.px))) }
            T("신고 도우미", 22.tx, Modifier.padding(start = 10.px), weight = FontWeight.SemiBold, color = pal.sub)
        }
        T("기록해 둔 순간", 40.tx, weight = FontWeight.ExtraBold)
        lines.forEach { T(it, 26.tx) }
    }
}
