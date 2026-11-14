package com.botdue.nearby.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.botdue.nearby.core.NearbyClient
import com.botdue.nearby.core.Notice
import com.botdue.nearby.core.RoadPos
import com.botdue.nearby.core.SafetyChecklist

private val DANGER_ORDER = listOf(Notice.Kind.STOPPED_AHEAD, Notice.Kind.HAZARD_AHEAD, Notice.Kind.HAZARD_BEHIND)

/** 위험 알림이 먼저(급한 순), 나머지는 최근에 받은 순 */
private fun ordered(list: Collection<Notice>) =
    list.sortedWith(compareBy<Notice> { DANGER_ORDER.indexOf(it.kind).let { i -> if (i < 0) DANGER_ORDER.size else i } }.thenByDescending { it.seq })

private fun Notice.Kind.danger() = this == Notice.Kind.STOPPED_AHEAD || this == Notice.Kind.HAZARD_AHEAD || this == Notice.Kind.HAZARD_BEHIND

/**
 * 주행 화면 (세로 1칸). 도로 표지판처럼 읽힌다: 왼쪽은 앞길 램프, 오른쪽은 가장 급한 알림 하나.
 * 달리는 중에는 "거리 숫자 → 무엇 한 줄 → 말한 안내"만 보인다. 근거·범위·다시 알림·사라짐 표는
 * 기어 P이거나 알림을 눌렀을 때만 펼친다. 내 차가 멈추면 오른쪽이 행동요령으로, 차를 세우면 신고 도우미로 바뀐다.
 */
@Composable
fun DriveScreen(
    client: NearbyClient,
    version: Int,
    tunnel: IntRange?,
    clock: String,
    modifier: Modifier = Modifier,
    onCall: (String) -> Unit = {},
) {
    val s = client.last
    // version이 바뀔 때마다 알림 목록을 다시 읽는다 (NearbyClient는 Compose 상태가 아니다)
    val notices = remember(version) { ordered(client.notices.values) }
    var openedKey by remember { mutableStateOf<String?>(null) }
    Row(modifier.fillMaxSize().background(pal.bg)) {
        Box(
            Modifier
                .width(240.px)
                .fillMaxHeight()
                .background(pal.panel)
                .padding(start = 36.px, top = 40.px, bottom = 40.px)
        ) {
            if (s != null) RoadStrip(s.pos, notices, tunnel)
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 44.px, vertical = 40.px)
        ) {
            Row(Modifier.fillMaxWidth()) {
                T(
                    when {
                        s == null -> "출발 전"
                        s.parked -> "주차 중"
                        s.inTunnel -> "${s.pos.roadId} 터널 안"
                        else -> "${s.pos.roadId} 상행"
                    },
                    24.tx, Modifier.weight(1f), color = pal.sub,
                )
                T(if (s == null || s.parked) clock else "시속 ${s.speedKmh}km", 24.tx, color = pal.sub)
            }
            Spacer(Modifier.height(56.px))
            val list = client.checklist
            when {
                list != null -> ChecklistPanel(list, list.completed.toSet(), list.current)
                client.showReportGuide -> ReportPanel(client.reportGuide())
                client.showCallGuide -> CallPanel(onCall)
                client.question != null -> QuestionPanel(client.question!!)
                notices.isEmpty() -> Quiet()
                else -> {
                    val top = notices.first()
                    val open = s?.parked == true || openedKey == top.key
                    Column(Modifier.clickable { openedKey = if (openedKey == top.key) null else top.key }) {
                        BigNotice(top, s?.pos, open)
                    }
                    notices.drop(1).take(2).forEach { SmallNotice(it) }
                }
            }
            Spacer(Modifier.weight(1f))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(pal.cardBg, RoundedCornerShape(8.px))
                    .padding(horizontal = 30.px, vertical = 26.px)
            ) {
                T("말한 안내", 20.tx, color = pal.sub)
                Spacer(Modifier.height(8.px))
                // 문장 속 거리는 지금 남은 거리로 맞춘다 (위의 큰 숫자와 같게)
                T(client.caption() ?: "알릴 일이 생기면 한 번만, 짧게 말해요.", 28.tx, color = pal.cardFg, lineHeight = 1.5f)
            }
        }
    }
}

@Composable
private fun Quiet() {
    T("앞길 조용해요", 56.tx, weight = FontWeight.ExtraBold, lineHeight = 1.2f)
    Spacer(Modifier.height(20.px))
    T("근처 플레오스 차와 익명으로 이어져 있어요. 차량 번호와 차종, 색상은 주고받지 않아요.", 28.tx, color = pal.sub, lineHeight = 1.55f)
}

/** 거리 표기: 1km 넘으면 소수 한 자리 km, 아니면 100m 단위 */
private fun distanceParts(m: Int): Pair<String, String> =
    if (m >= 1000) "%.1f".format(m / 1000.0) to "km 앞" else "${(m / 100 * 100).coerceAtLeast(100)}" to "m 앞"

private fun Notice.count(): Int? = Regex("([0-9]+)대").find(title)?.groupValues?.get(1)?.toIntOrNull()

/** 큰 글씨 한 줄: 무슨 일인지 */
private fun Notice.what(): String = when (kind) {
    Notice.Kind.HAZARD_AHEAD -> (if (inTunnel) "터널 안 " else "") + "위험 운전 제보"
    Notice.Kind.STOPPED_AHEAD -> (if (inTunnel) "터널 안 " else "") + "멈춘 차"
    Notice.Kind.MERGE -> "진입로에서 ${count() ?: 1}대 합류 예정"
    else -> title
}

/** 근거 표: 판정 규칙을 운전자가 읽는 말로 */
private fun Notice.facts(): List<Pair<String, String>> = when (kind) {
    Notice.Kind.HAZARD_AHEAD -> listOf(
        "근거" to "서로 다른 차 2대의 신호, 30초 안",
        "범위" to if (inTunnel) "터널이라 뒤 3km까지" else "뒤 2km까지",
        "다시 알림" to if (inTunnel) "터널 1km 앞에서" else "없음",
        "사라짐" to "5분 뒤",
    )
    Notice.Kind.STOPPED_AHEAD -> listOf(
        "근거" to "급제동 뒤 멈춰 선 차",
        "범위" to if (inTunnel) "터널이라 뒤 3km까지" else "뒤 2km까지",
        "사라짐" to "그 차가 다시 출발하면",
    )
    Notice.Kind.HAZARD_BEHIND -> listOf(
        "근거" to "서로 다른 차 2대의 신호, 30초 안",
        "범위" to "앞 1km까지",
        "사라짐" to "5분 뒤",
    )
    Notice.Kind.MERGE -> listOf(
        "근거" to "진입로 차의 내비 경로",
        "언제" to "60초 안 합류",
        "판단" to "참고만 하세요",
    )
    else -> emptyList()
}

/** [open]이 false(달리는 중)면 표를 숨기고 그만큼 거리 숫자를 키운다 */
@Composable
private fun ColumnScope.BigNotice(n: Notice, me: RoadPos?, open: Boolean) {
    val d = if (me != null && n.at != null) me.distanceTo(n.at!!) else null
    val showDistance = d != null && d > 0 && n.kind in setOf(Notice.Kind.HAZARD_AHEAD, Notice.Kind.STOPPED_AHEAD, Notice.Kind.MERGE)
    val tone = when {
        n.kind.danger() -> pal.danger
        n.kind == Notice.Kind.MERGE || n.kind == Notice.Kind.FLOW -> pal.accent
        else -> pal.fg
    }
    if (showDistance) {
        val (num, unit) = distanceParts(d!!)
        Row(verticalAlignment = Alignment.Bottom) {
            T(num, if (open) 104.tx else 176.tx, weight = FontWeight.ExtraBold, lineHeight = 1f)
            T(unit, if (open) 40.tx else 52.tx, Modifier.padding(start = 12.px, bottom = if (open) 10.px else 18.px), weight = FontWeight.Bold)
        }
        Spacer(Modifier.height(if (open) 16.px else 24.px))
        T(n.what(), if (open) 42.tx else 50.tx, weight = FontWeight.ExtraBold, color = tone, lineHeight = 1.3f)
    } else {
        T(n.what(), 52.tx, weight = FontWeight.ExtraBold, color = if (n.kind.danger()) tone else pal.fg, lineHeight = 1.25f)
        Spacer(Modifier.height(16.px))
        T(n.detail, 28.tx, color = pal.sub, lineHeight = 1.5f)
    }
    val facts = if (open) n.facts() else emptyList()
    if (facts.isNotEmpty()) {
        Spacer(Modifier.height(40.px))
        facts.forEachIndexed { i, (k, v) ->
            Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 22.px)) {
                T(k, 26.tx, Modifier.width(150.px), color = pal.sub)
                T(v, 26.tx, Modifier.weight(1f))
            }
            if (i == facts.lastIndex) Divider()
        }
    }
    Spacer(Modifier.height(24.px))
}

@Composable
private fun SmallNotice(n: Notice) {
    Row(Modifier.padding(top = 14.px), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(18.px)
                .background(if (n.kind.danger()) pal.danger else if (n.kind == Notice.Kind.MERGE) pal.accent else pal.me, RoundedCornerShape(2.px))
        )
        T(n.title, 24.tx, Modifier.padding(start = 14.px), color = pal.sub)
    }
}

/** 2차 사고 행동요령: 차량 신호로 확인한 단계에 체크 */
@Composable
private fun ChecklistPanel(list: SafetyChecklist, completed: Set<SafetyChecklist.Step>, current: SafetyChecklist.Step?) {
    T("뒤차에 알렸어요", 52.tx, weight = FontWeight.ExtraBold, lineHeight = 1.2f)
    Spacer(Modifier.height(14.px))
    T(if (list.inTunnel) "터널 안에 멈춰 섰어요. 할 일을 차가 신호로 확인해요." else "멈춰 섰어요. 할 일을 차가 신호로 확인해요.", 28.tx, color = pal.sub, lineHeight = 1.5f)
    Spacer(Modifier.height(36.px))
    SafetyChecklist.Step.entries.forEachIndexed { i, step ->
        val done = step in completed
        val now = current == step
        Divider()
        Row(Modifier.fillMaxWidth().padding(vertical = 22.px), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.px)
                    .background(if (done) pal.me else pal.cell, RoundedCornerShape(3.px))
            )
            Column(Modifier.padding(start = 22.px).weight(1f)) {
                T(list.title(step), 30.tx, weight = if (now) FontWeight.ExtraBold else FontWeight.Normal, color = if (done) pal.sub else pal.fg)
                T(
                    when (step) {
                        SafetyChecklist.Step.HAZARD -> "비상등 신호"
                        SafetyChecklist.Step.TRUNK -> "트렁크 열림 신호"
                        SafetyChecklist.Step.EVACUATE -> "운전석 문 열림 신호"
                        SafetyChecklist.Step.REPORT -> "\"신고했어\"라는 말"
                    } + if (done) "로 확인했어요" else "로 확인해요",
                    22.tx, color = pal.sub,
                )
            }
            if (now) T("지금", 24.tx, weight = FontWeight.Bold, color = pal.danger)
        }
        if (i == SafetyChecklist.Step.entries.lastIndex) Divider()
    }
}

/** 멈춘 차 옆을 지나간 뒤 묻는 말. 답은 말로 한다 */
@Composable
private fun QuestionPanel(q: Pair<String, String>) {
    T(q.first, 52.tx, weight = FontWeight.ExtraBold, lineHeight = 1.25f)
    Spacer(Modifier.height(20.px))
    T(q.second, 28.tx, color = pal.sub, lineHeight = 1.5f)
}

/** "신고할까요?"에 "응"이라고 답했을 때: 번호를 누르면 전화 앱이 열린다 (자동으로 걸지 않는다) */
@Composable
private fun CallPanel(onCall: (String) -> Unit) {
    T("신고 번호", 52.tx, weight = FontWeight.ExtraBold, lineHeight = 1.2f)
    Spacer(Modifier.height(14.px))
    T("누르면 전화 앱이 열려요.", 28.tx, color = pal.sub, lineHeight = 1.5f)
    Spacer(Modifier.height(36.px))
    OutlineButton("112  경찰", Modifier.fillMaxWidth()) { onCall("112") }
    Spacer(Modifier.height(18.px))
    OutlineButton("1588-2504  한국도로공사", Modifier.fillMaxWidth()) { onCall("15882504") }
}

@Composable
private fun ReportPanel(lines: List<String>) {
    T("기록해 둔 순간", 52.tx, weight = FontWeight.ExtraBold, lineHeight = 1.2f)
    Spacer(Modifier.height(30.px))
    Column(verticalArrangement = Arrangement.spacedBy(18.px)) {
        lines.forEachIndexed { i, line ->
            T(line, if (i == 0) 32.tx else 26.tx, weight = if (i == 0) FontWeight.Bold else FontWeight.Normal, color = if (i == 0) pal.fg else pal.sub, lineHeight = 1.5f)
        }
    }
}
