package com.botdue.nearby

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.botdue.nearby.core.HazardBoard
import com.botdue.nearby.core.demo.CarId
import com.botdue.nearby.core.demo.DemoClock
import com.botdue.nearby.core.demo.LocalHubLink
import com.botdue.nearby.core.demo.ScenarioPlayer
import com.botdue.nearby.core.demo.Scenarios
import com.botdue.nearby.core.demo.demoHotspotSeed
import com.botdue.nearby.net.RemoteHubLink
import com.botdue.nearby.ui.Chip
import com.botdue.nearby.ui.DayPalette
import com.botdue.nearby.ui.DriveScreen
import com.botdue.nearby.ui.LocalFont
import com.botdue.nearby.ui.LocalPalette
import com.botdue.nearby.ui.MapScreen
import com.botdue.nearby.ui.NightPalette
import com.botdue.nearby.ui.T
import com.botdue.nearby.ui.astaSans
import com.botdue.nearby.ui.pal
import com.botdue.nearby.ui.px
import com.botdue.nearby.ui.tx
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import com.botdue.nearby.ui.FitWindow

class MainActivity : ComponentActivity() {
    private var start by mutableStateOf(StartAt())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        start = startOf(intent)
        // 새 시작 값이 오면 시연 한 판을 처음부터 다시 만든다
        setContent { FitWindow { key(start) { NearbyApp(start) } } }
    }

    /** 이미 떠 있을 때 am start를 다시 부르면 여기로 온다. 캡처 옵션(clean 등)을 새로 적용한다 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        start = startOf(intent)
    }

    // 화면 캡처·영상용: adb shell am start ... --ei scenario 1 --es car D --ei seek 30 --ez map false
    private fun startOf(intent: Intent) = StartAt(
        scenario = intent.getIntExtra("scenario", 0).coerceIn(0, Scenarios.all.lastIndex),
        car = intent.getStringExtra("car")?.let { runCatching { CarId.valueOf(it) }.getOrNull() } ?: CarId.B,
        seekSec = intent.getIntExtra("seek", 0),
        jump = intent.getBooleanExtra("jump", false),
        map = intent.getBooleanExtra("map", false),
        clean = intent.getBooleanExtra("clean", false),
        play = intent.getBooleanExtra("play", false),
    )
}

data class StartAt(
    val scenario: Int = 0,
    val car: CarId = CarId.B,
    val seekSec: Int = 0,
    val jump: Boolean = false,
    val map: Boolean = false,
    /** 시연 조작판을 숨긴다 (차 안 목업용 화면 캡처) */
    val clean: Boolean = false,
    /** 열자마자 재생 (조작판 없이 영상 녹화) */
    val play: Boolean = false,
)

/** 시연 한 판: 재생기 + (여러 대 시연이면) 서버 연결 */
private class Session(val player: ScenarioPlayer, val remote: RemoteHubLink?, val board: HazardBoard)

private fun newSession(index: Int, remoteCar: CarId?, onChange: () -> Unit): Session {
    val scenario = Scenarios.all[index]()
    if (remoteCar != null) {
        val link = RemoteHubLink(BuildConfig.HUB_URL, onChange)
        // 지도 화면은 서버 통계 대신 시연 데이터를 보여 준다
        val board = HazardBoard().apply { seedStats(demoHotspotSeed()) }
        return Session(ScenarioPlayer(scenario, link, setOf(remoteCar)), link, board)
    }
    val clock = DemoClock(scenario.startAt)
    val link = LocalHubLink(clock::now)
    link.hub.board.seedStats(demoHotspotSeed())
    return Session(ScenarioPlayer(scenario, link, clock = clock), null, link.hub.board)
}

@Composable
fun NearbyApp(start: StartAt = StartAt()) {
    val palette = if (isSystemInDarkTheme()) NightPalette else DayPalette
    val context = LocalContext.current
    val assets = context.assets
    val font = remember { astaSans(assets) }

    var scenarioIndex by remember { mutableIntStateOf(start.scenario) }
    var car by remember { mutableStateOf(start.car) }
    var remote by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(start.play) }
    var showMap by remember { mutableStateOf(start.map) }
    var version by remember { mutableIntStateOf(0) }
    var session by remember {
        mutableStateOf(newSession(start.scenario, null) { version++ }.also { s ->
            while (s.player.t < start.seekSec * 1000L && !s.player.finished) s.player.step()
            if (start.jump) s.player.jump()
        })
    }

    fun restart() {
        session.remote?.close()
        session = newSession(scenarioIndex, if (remote) car else null) { version++ }
        playing = false
        version++
    }

    DisposableEffect(Unit) { onDispose { session.remote?.close() } }

    LaunchedEffect(session, playing) {
        while (playing && !session.player.finished) {
            delay(500)
            session.player.step()
            version++
        }
        if (session.player.finished) playing = false
    }

    BackHandler(enabled = showMap) { showMap = false }

    CompositionLocalProvider(LocalPalette provides palette, LocalFont provides font) {
        val player = session.player
        val shownCar = if (car in player.cars) car else player.cars.first()
        val client = player.clients.getValue(shownCar)
        val clock = Instant.ofEpochMilli(player.now).atZone(ZoneId.of("Asia/Seoul")).let { "%d:%02d".format(it.hour, it.minute) }

        Column(Modifier.fillMaxSize().background(pal.bg)) {
            if (showMap) {
                Column(Modifier.weight(1f)) { MapScreen(session.board.hotspots(), player.scenario.tunnel) { showMap = false } }
            } else {
                // 재생기 상태는 Compose 상태가 아니라서, 바뀐 횟수(version)를 함께 넘겨 다시 그리게 한다
                DriveScreen(client, version, player.scenario.tunnel, clock, Modifier.weight(1f)) { number ->
                    // 전화 앱을 열기만 한다 (자동으로 걸지 않는다). 전화 앱이 없는 에뮬레이터에서는 아무 일도 없다
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            if (!start.clean) DemoBar(
                scenarioIndex = scenarioIndex,
                onScenario = { scenarioIndex = it; restart() },
                car = shownCar,
                onCar = { car = it; if (remote) restart() },
                roles = player.scenario.roles,
                playing = playing,
                finished = player.finished,
                onPlay = { if (player.finished) restart() else playing = !playing },
                onRestart = { restart() },
                canJump = player.scenario.canJump && player.finished,
                onJump = {
                    session.remote?.jumpServer()
                    player.jump()
                    version++
                },
                onMap = { showMap = !showMap },
                remote = remote,
                remoteStatus = session.remote?.status,
                onRemote = { remote = !remote; restart() },
                onVoice = { client.onVoice(it); version++ },
                progress = "${player.t / 1000}초 / ${player.scenario.durationMs / 1000}초",
            )
        }
    }
}

/**
 * 시연 조작판 (실제 차량 화면에는 없다).
 * 시나리오·보는 차를 고르고, 재생하고, 말하기 버튼으로 음성 인식(STT) 대신 문장을 넣는다.
 */
@Composable
private fun DemoBar(
    scenarioIndex: Int,
    onScenario: (Int) -> Unit,
    car: CarId,
    onCar: (CarId) -> Unit,
    roles: Map<CarId, String>,
    playing: Boolean,
    finished: Boolean,
    onPlay: () -> Unit,
    onRestart: () -> Unit,
    canJump: Boolean,
    onJump: () -> Unit,
    onMap: () -> Unit,
    remote: Boolean,
    remoteStatus: String?,
    onRemote: () -> Unit,
    onVoice: (String) -> Unit,
    progress: String,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(pal.cardBg)
            .padding(horizontal = 32.px, vertical = 20.px),
        verticalArrangement = Arrangement.spacedBy(12.px),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.px), verticalAlignment = Alignment.CenterVertically) {
            Scenarios.all.indices.forEach { i ->
                Chip(if (i == 0) "시연 1 합류" else "시연 2 터널", i == scenarioIndex) { onScenario(i) }
            }
            Chip(if (playing) "멈춤" else if (finished) "다시 재생" else "재생", playing, onClick = onPlay)
            Chip("처음부터", onClick = onRestart)
            if (canJump) Chip("5분 뒤로", onClick = onJump)
            Chip("위험 지점 지도", onClick = onMap)
            Chip(if (remote) "여러 대 시연 켬" else "한 대 시연", remote, onClick = onRemote)
            T(progress + (remoteStatus?.let { " · $it" } ?: ""), 20.tx, color = pal.sub)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.px)) {
            CarId.entries.forEach { c -> Chip("차 $c · ${roles[c]}", c == car) { onCar(c) } }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.px), verticalAlignment = Alignment.CenterVertically) {
            T("말하기", 20.tx, color = pal.sub)
            listOf("고마워", "방금 위험한 차 있었어", "신고해줘", "앞차 주유구 열렸어", "신고했어", "있어", "없어", "응", "아니").forEach { Chip("\"$it\"", onClick = { onVoice(it) }) }
        }
    }
}
