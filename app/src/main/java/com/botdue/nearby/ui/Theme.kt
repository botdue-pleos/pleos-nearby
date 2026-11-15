package com.botdue.nearby.ui

import android.content.res.AssetManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pleos 공식 서체 Asta Sans. 가변 글꼴 하나를 굵기별로 불러 쓴다.
 * res/font로 넣으면 굵기(wght) 축이 적용되지 않아 assets에서 읽는다.
 */
@OptIn(ExperimentalTextApi::class)
fun astaSans(assets: AssetManager): FontFamily = FontFamily(
    listOf(FontWeight.Normal, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold).map { w ->
        Font(
            path = "fonts/AstaSans.ttf",
            assetManager = assets,
            weight = w,
            variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)),
        )
    }
)

val LocalFont = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

/**
 * 현대차 CI 색 기반 팔레트. 야간은 Hyundai Blue 바탕, 주간은 Light Sand 바탕.
 * 알림 색은 셋만 쓴다: 위험(Active Red), 합류·정보(Active Blue), 인사(Sand / Gold)
 */
data class Palette(
    val bg: Color,
    val fg: Color,
    val sub: Color,
    val line: Color,
    val cell: Color,
    val tunnel: Color,
    val me: Color,
    val primaryBg: Color,
    val primaryFg: Color,
    val outline: Color,
    val cardBg: Color,
    val cardFg: Color,
    val accent: Color,
    val danger: Color,
    val dangerFg: Color,
    val warm: Color,
    /** 앞길 램프 기둥 바탕 */
    val panel: Color,
)

/*
 * 주간: 밝은 도로 톤. 도로 표지판처럼 흰 바탕 위 진한 글자, 내 차는 Hyundai Blue, 위험은 붉은 픽셀.
 * 야간: 같은 구조를 아스팔트 회색으로 (밤에 밝은 화면이 눈부시지 않게).
 */
val DayPalette = Palette(
    bg = Color(0xFFEEF0F2), fg = Color(0xFF1C2430), sub = Color(0xFF5B6672), line = Color(0xFFCDD3D9),
    cell = Color(0xFFCBD0D6), tunnel = Color(0xFFB5BBC3), me = Color(0xFF002C5F),
    primaryBg = Color(0xFF002C5F), primaryFg = Color(0xFFFFFFFF), outline = Color(0xFF9AA3AD),
    cardBg = Color(0xFFFFFFFF), cardFg = Color(0xFF1C2430), accent = Color(0xFF00789C),
    danger = Color(0xFFC42A0E), dangerFg = Color(0xFFFFFFFF), warm = Color(0xFF002C5F),
    panel = Color(0xFFE2E5E9),
)

val NightPalette = Palette(
    bg = Color(0xFF15181C), fg = Color(0xFFE8E4DA), sub = Color(0xFF8C939C), line = Color(0xFF2A3037),
    cell = Color(0xFF2A3037), tunnel = Color(0xFF1C2025), me = Color(0xFFE8E4DA),
    primaryBg = Color(0xFFE8E4DA), primaryFg = Color(0xFF15181C), outline = Color(0xFF4A5058),
    cardBg = Color(0xFF1F2328), cardFg = Color(0xFFE8E4DA), accent = Color(0xFF3FC2E0),
    danger = Color(0xFFF0553A), dangerFg = Color(0xFFFFFFFF), warm = Color(0xFFE8E4DA),
    panel = Color(0xFF1B1E22),
)

val LocalPalette = staticCompositionLocalOf { DayPalette }

val pal: Palette
    @Composable get() = LocalPalette.current

/**
 * 시안(920x1330 px 창 기준) 수치를 그대로 옮기기 위한 변환.
 * Pleos 샘플 글자 체계(headline 38, body 20, 최소 18sp)에 맞춰 1.5로 나누고, 글자는 18sp 아래로 내리지 않는다.
 */
val Int.px: Dp get() = (this / 1.5f).dp
val Int.tx: TextUnit get() = maxOf(this / 1.5f, 18f).sp

/**
 * 설계 기준 창(세로 1칸 613×887dp, 가로 2칸 1247×887dp — 920×1330·1870×1330px, 240dpi)을 실제 앱 창 크기에 맞춰 통째로 비례 확대한다.
 * Pleos Connect v2.1.0 에뮬레이터(2560×1440, 160dpi)의 세로 1칸 창은 약 841×1210dp라, 이대로 두면 화면이 작게 그려지고 아래가 빈다.
 */
@Composable
fun FitWindow(content: @Composable () -> Unit) {
    val c = LocalConfiguration.current
    val w = c.screenWidthDp.toFloat()
    val h = c.screenHeightDp.toFloat()
    val k = (if (w < h) minOf(w / 613f, h / 887f) else minOf(w / 1247f, h / 887f)).coerceIn(0.5f, 3f)
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density * k, d.fontScale)) { content() }
}
