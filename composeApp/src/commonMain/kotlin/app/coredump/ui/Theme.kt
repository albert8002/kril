package app.coredump.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.core.Tier
import app.coredump.res.Res
import app.coredump.res.dejavu_mono
import app.coredump.res.dejavu_mono_bold
import app.coredump.res.inter_bold
import app.coredump.res.inter_regular
import app.coredump.res.inter_semibold
import app.coredump.res.lora_bold
import app.coredump.res.lora_regular
import app.coredump.res.pixel_dump
import app.coredump.res.poppins_bold
import app.coredump.res.poppins_bold_italic
import app.coredump.res.poppins_regular
import org.jetbrains.compose.resources.Font

/**
 * A theme = a [Style] (font, shapes, effects, layout feel) + a [Palette] (colours).
 * Every palette works with every style; each style lists the palettes made for it.
 */
enum class Style(
    val label: String,
    val blurb: String,
    /** Corner radius of panels, buttons and fields. */
    val corner: Dp,
    /** Text glow / bloom allowed. */
    val glows: Boolean,
) {
    TERMINAL("TERMINAL", "CRT hacker shell: scanlines, glitches, typed commands", 0.dp, true),
    PAPER("GAZETTE", "newspaper front page: headlines, clue cards, rubber stamps", 2.dp, false),
    NEON("ARCADE", "coin-op cabinet: attract mode, HUD, high-score table", 16.dp, true),
    CLEAN("QUIZ SHOW", "bright game-show app: big buttons, progress dots, confetti", 14.dp, false),
    PIXEL("QUEST", "handheld RPG: dialogue box, ▶ cursor, boss battle, level ups", 0.dp, false),
    COMIC("COMIC", "punk collage: slanted panels, ransom-note letters, starbursts", 0.dp, false);

    companion object {
        fun byName(n: String): Style = entries.firstOrNull { it.name == n } ?: TERMINAL
    }
}

/**
 * Colour roles (names kept from the original amber terminal):
 * bg: screen. panel: card fill. line: borders / faint text. ghost: empty bar cells.
 * dim: secondary text. amber: main text. hot: accent. white: peak emphasis (rarest tier, flashes).
 * blood: errors / danger. bloodDim: faint error fill.
 */
class Palette(
    val id: String,
    val name: String,
    val bg: Color, val panel: Color, val line: Color, val ghost: Color,
    val dim: Color, val amber: Color, val hot: Color, val white: Color,
    val blood: Color, val bloodDim: Color,
) {
    val dark: Boolean get() = bg.luminance() < 0.4f
}

private fun pal(id: String, name: String, vararg c: Long): Palette {
    require(c.size == 10)
    val k = c.map { Color(it) }
    return Palette(id, name, k[0], k[1], k[2], k[3], k[4], k[5], k[6], k[7], k[8], k[9])
}

object Palettes {
    val terminal = listOf(
        pal("amber", "AMBER P3", 0xFF0A0603, 0xFF120B04, 0xFF3A2806, 0xFF241804, 0xFF8C6100, 0xFFFFB000, 0xFFFFD58A, 0xFFFFF3D6, 0xFFFF4122, 0xFF5C1408),
        pal("green", "GREEN P1", 0xFF020A04, 0xFF06140A, 0xFF0F3A1A, 0xFF0A2410, 0xFF1F8A3C, 0xFF33FF66, 0xFFA8FFB8, 0xFFE6FFEA, 0xFFFF4F3A, 0xFF4A1610),
        pal("ice", "ICE", 0xFF02070B, 0xFF061019, 0xFF0E2E44, 0xFF0A1E2C, 0xFF2C7FA8, 0xFF4FD8FF, 0xFFB8F0FF, 0xFFEAFBFF, 0xFFFF5470, 0xFF4D1422),
        pal("magenta", "MAGENTA", 0xFF0A030A, 0xFF140614, 0xFF3D0F3D, 0xFF260A26, 0xFF9C2E9C, 0xFFFF4FFF, 0xFFFFB3FF, 0xFFFFEFFF, 0xFFFF5A3D, 0xFF4A1A10),
        pal("red", "RED ALERT", 0xFF0B0303, 0xFF160606, 0xFF401010, 0xFF2A0A0A, 0xFF9E2A2A, 0xFFFF3B3B, 0xFFFFB0A0, 0xFFFFE9E4, 0xFFFFD23A, 0xFF4A3A0A),
        pal("white", "WHITE P4", 0xFF08090B, 0xFF111317, 0xFF2F343C, 0xFF1B1F25, 0xFF8A93A0, 0xFFE6EBF2, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFF5555, 0xFF4A1515),
        pal("toxic", "TOXIC", 0xFF060800, 0xFF0E1203, 0xFF2E3A06, 0xFF1C2404, 0xFF7D9A12, 0xFFC6FF1A, 0xFFECFF9E, 0xFFF8FFE0, 0xFFFF4E2E, 0xFF4D1A0E),
    )
    val paper = listOf(
        pal("newsprint", "NEWSPRINT", 0xFFF3EFE4, 0xFFEAE4D4, 0xFFB9AF98, 0xFFDCD4C1, 0xFF6B6454, 0xFF1B1914, 0xFF1F4E99, 0xFF7A1FA2, 0xFFB3261E, 0xFFF0D3CC),
        pal("sepia", "SEPIA", 0xFFEFE2C8, 0xFFE6D6B5, 0xFFB39A6E, 0xFFD9C49C, 0xFF7A6040, 0xFF3B2A17, 0xFF8B3A0E, 0xFF4B2C7A, 0xFFA3201B, 0xFFEBC9B8),
        pal("salmon", "SALMON", 0xFFFCEBDD, 0xFFF5DECB, 0xFFD2B49A, 0xFFEBD2BE, 0xFF7D6858, 0xFF2B2523, 0xFF0D5C91, 0xFF5B2A86, 0xFFB42318, 0xFFF6D3C9),
        pal("ledger", "LEDGER", 0xFFE9F0E1, 0xFFDDE7D2, 0xFFA9BC97, 0xFFCCDBBD, 0xFF586A4B, 0xFF1D2B17, 0xFF2F6B1F, 0xFF8A5A00, 0xFFA12B22, 0xFFEBD0C8),
        pal("carbon", "CARBON COPY", 0xFFFAFAF7, 0xFFF0F1EE, 0xFFB8C0CC, 0xFFDFE3E8, 0xFF5A6880, 0xFF15306B, 0xFFC2185B, 0xFF0B7A75, 0xFFC62828, 0xFFF4D2D2),
        pal("night", "NIGHT EDITION", 0xFF1E1D1A, 0xFF26241F, 0xFF4A463C, 0xFF302D27, 0xFF9A9384, 0xFFECE6D6, 0xFFE0A030, 0xFFF2F2F2, 0xFFFF6B57, 0xFF4A2420),
    )
    val neon = listOf(
        pal("synthwave", "SYNTHWAVE", 0xFF12001F, 0xFF1C0533, 0xFF4A1A7A, 0xFF2A0A48, 0xFF9B6BD1, 0xFF2DE2E6, 0xFFFF2E97, 0xFFFFF26B, 0xFFFF3D5A, 0xFF4A0F2A),
        pal("outrun", "OUTRUN", 0xFF140A1E, 0xFF1F0F2E, 0xFF5C2A4A, 0xFF2E1530, 0xFFC46B8A, 0xFFFFB347, 0xFFFF4F8B, 0xFF7DF9FF, 0xFFFF3B3B, 0xFF4A1515),
        pal("cyber", "CYBER YELLOW", 0xFF0A0A0F, 0xFF14141C, 0xFF3A3A1A, 0xFF222212, 0xFF8F8A2E, 0xFFFCEE09, 0xFF00F0FF, 0xFFFF2A6D, 0xFFFF5A1F, 0xFF4A1E0A),
        pal("vapor", "VAPORWAVE", 0xFF1A1033, 0xFF241848, 0xFF5A4A8A, 0xFF2E2258, 0xFFA99BE0, 0xFFFF9DE2, 0xFF8BE9FD, 0xFFFFF3B0, 0xFFFF6B8B, 0xFF4A1A2E),
        pal("acid", "ACID", 0xFF050805, 0xFF0B120B, 0xFF1E3A1E, 0xFF102010, 0xFF3FA84A, 0xFF39FF14, 0xFFFF00FF, 0xFFF5FF5A, 0xFFFF3355, 0xFF4A0F1A),
        pal("laser", "ICE LASER", 0xFF040A18, 0xFF0A1430, 0xFF1A3A6E, 0xFF0F2248, 0xFF5A8BD6, 0xFF7DF9FF, 0xFFB967FF, 0xFFFFFFFF, 0xFFFF4D6D, 0xFF3A1028),
    )
    val clean = listOf(
        pal("light", "LIGHT", 0xFFF6F7F9, 0xFFFFFFFF, 0xFFDDE1E7, 0xFFEDEFF3, 0xFF6B7280, 0xFF111827, 0xFF2563EB, 0xFF7C3AED, 0xFFDC2626, 0xFFFEE2E2),
        pal("dark", "DARK", 0xFF0F1115, 0xFF1A1D23, 0xFF2C313A, 0xFF22262D, 0xFF8B93A1, 0xFFF3F4F6, 0xFF60A5FA, 0xFFC084FC, 0xFFF87171, 0xFF3B1A1A),
        pal("mint", "MINT", 0xFFF1F8F5, 0xFFFFFFFF, 0xFFCFE5DB, 0xFFE3F1EA, 0xFF5B7A6C, 0xFF10291F, 0xFF0F9D74, 0xFF7C3AED, 0xFFD64545, 0xFFFADBDB),
        pal("rose", "ROSE", 0xFFFBF4F6, 0xFFFFFFFF, 0xFFEBD3DA, 0xFFF5E6EA, 0xFF8A6570, 0xFF2A151B, 0xFFD6336C, 0xFF6741D9, 0xFFC92A2A, 0xFFFFE3E3),
        pal("ocean", "OCEAN", 0xFF0B1620, 0xFF112231, 0xFF1F3A4F, 0xFF162C3D, 0xFF7FA3BD, 0xFFE6F1F8, 0xFF38BDF8, 0xFFFBBF24, 0xFFF87171, 0xFF3A1A1E),
        pal("sand", "SAND", 0xFFF5F1EA, 0xFFFFFDF8, 0xFFE2D9C9, 0xFFEEE7DA, 0xFF857761, 0xFF2B261E, 0xFFC2410C, 0xFF0E7490, 0xFFB91C1C, 0xFFFBE0D6),
    )
    val pixel = listOf(
        pal("dmg", "DMG GREEN", 0xFF9BBC0F, 0xFF8BAC0F, 0xFF306230, 0xFF8BAC0F, 0xFF306230, 0xFF0F380F, 0xFF0F380F, 0xFF0F380F, 0xFF0F380F, 0xFF306230),
        pal("pocket", "POCKET GREY", 0xFFC9CCB4, 0xFFB5B8A0, 0xFF5E6152, 0xFFB5B8A0, 0xFF4A4C40, 0xFF16170F, 0xFF16170F, 0xFF16170F, 0xFF16170F, 0xFF5E6152),
        pal("backlit", "BACKLIT", 0xFF7FE0C8, 0xFF62C9B0, 0xFF2E8A72, 0xFF62C9B0, 0xFF1E6A57, 0xFF0A3328, 0xFF0A3328, 0xFF0A3328, 0xFF0A3328, 0xFF2E8A72),
        pal("virtual", "RED VISOR", 0xFF000000, 0xFF140000, 0xFF550000, 0xFF2A0000, 0xFFAA0000, 0xFFFF1A1A, 0xFFFF6A6A, 0xFFFFB0B0, 0xFFFF1A1A, 0xFF550000),
        pal("color", "COLOR", 0xFFF8F8F8, 0xFFE8E8F0, 0xFF8888A8, 0xFFE0E0E8, 0xFF58587A, 0xFF181830, 0xFFE03030, 0xFF3050E0, 0xFFE03030, 0xFFF0B0B0),
        pal("banana", "BANANA", 0xFFF8E8A0, 0xFFF0D878, 0xFFB08820, 0xFFF0D878, 0xFF805810, 0xFF302008, 0xFF302008, 0xFF302008, 0xFF302008, 0xFFB08820),
    )

    val comic = listOf(
        pal("rebel", "REBEL RED", 0xFF0C0C0C, 0xFF1A1A1A, 0xFFF2F2F2, 0xFF2A2A2A, 0xFFB8B8B8, 0xFFF7F7F7, 0xFFE3101E, 0xFFFFD400, 0xFFE3101E, 0xFF4A0A0E),
        pal("velvet", "VELVET BLUE", 0xFF070B1A, 0xFF101833, 0xFFE8EEFF, 0xFF1A2448, 0xFF9AA8D6, 0xFFF4F7FF, 0xFF2F6BFF, 0xFFFFC94A, 0xFFFF4D5E, 0xFF3A1020),
        pal("mustard", "MUSTARD", 0xFF111111, 0xFF1C1C1C, 0xFFFFF4C2, 0xFF2B2B2B, 0xFFC9BE8A, 0xFFFFF8DC, 0xFFFFC21A, 0xFFFF4F2E, 0xFFFF4F2E, 0xFF4A1A0A),
        pal("toxin", "TOXIN", 0xFF0A0F0A, 0xFF132013, 0xFFEFFFEF, 0xFF1C2C1C, 0xFFA2C7A2, 0xFFF4FFF4, 0xFF27E36B, 0xFFFF2E9A, 0xFFFF3B3B, 0xFF4A0F1A),
        pal("royal", "ROYAL", 0xFF120A1A, 0xFF1F1230, 0xFFF5ECFF, 0xFF2A1A40, 0xFFB9A2D6, 0xFFFBF6FF, 0xFF9B3BFF, 0xFFFFC94A, 0xFFFF4D6D, 0xFF3A1028),
        pal("pop", "POP ART", 0xFFFFF4E0, 0xFFFFFFFF, 0xFF111111, 0xFFF1E3C8, 0xFF5A5040, 0xFF111111, 0xFFE3101E, 0xFF1F5BFF, 0xFFE3101E, 0xFFFFD9D9),
    )

    fun of(style: Style): List<Palette> = when (style) {
        Style.TERMINAL -> terminal
        Style.PAPER -> paper
        Style.NEON -> neon
        Style.CLEAN -> clean
        Style.PIXEL -> pixel
        Style.COMIC -> comic
    }

    fun find(style: Style, id: String): Palette = of(style).firstOrNull { it.id == id } ?: of(style).first()
}

/** The active theme. Snapshot state: changing it re-colours and re-styles every screen at once. */
object Th {
    var style by mutableStateOf(Style.TERMINAL)
    var pal by mutableStateOf(Palettes.terminal.first())

    fun set(style: Style, paletteId: String) {
        this.style = style
        this.pal = Palettes.find(style, paletteId)
    }

    val terminal: Boolean get() = style == Style.TERMINAL
    /** Hacker jargon (vim ~/.rc, ls -la …) only in the terminal theme; plain words elsewhere. */
    fun say(terminal: String, plain: String): String = if (style == Style.TERMINAL) terminal else plain
}

/** Colour roles of the active palette (read inside composition: recomposes on theme change). */
object C {
    val bg: Color get() = Th.pal.bg
    val panel: Color get() = Th.pal.panel
    val line: Color get() = Th.pal.line
    val ghost: Color get() = Th.pal.ghost
    val dim: Color get() = Th.pal.dim
    val amber: Color get() = Th.pal.amber
    val hot: Color get() = Th.pal.hot
    val white: Color get() = Th.pal.white
    val blood: Color get() = Th.pal.blood
    val bloodDim: Color get() = Th.pal.bloodDim
}

/** Readable text colour on top of [fill]. */
fun onColor(fill: Color): Color = if (fill.luminance() > 0.45f) Color(0xFF111111) else Color(0xFFFAFAFA)

/**
 * Each tier has its own colours (bronze, teal, silver, gold, pink, galaxy), the same in every theme.
 * gradient: badge fill, darkest first. onDark / onLight: text colour of the tier on dark / light backgrounds.
 */
class TierLook(val gradient: List<Color>, val onDark: Color, val onLight: Color)

private val tierLooks: Map<Tier, TierLook> = mapOf(
    Tier.SKIPPED to TierLook(listOf(Color(0xFF4B5563), Color(0xFF9CA3AF)), Color(0xFF9CA3AF), Color(0xFF6B7280)),
    Tier.TIMEOUT to TierLook(listOf(Color(0xFF4B5563), Color(0xFF9CA3AF)), Color(0xFF9CA3AF), Color(0xFF6B7280)),
    Tier.SEGFAULT to TierLook(listOf(Color(0xFF7F1D1D), Color(0xFFEF4444)), Color(0xFFFF6B6B), Color(0xFFB91C1C)),
    Tier.HELLO_WORLD to TierLook(listOf(Color(0xFF7A3F14), Color(0xFFC57A3E), Color(0xFFF2C28A)), Color(0xFFE6A56A), Color(0xFF8A4A18)),
    Tier.COPYPASTA to TierLook(listOf(Color(0xFF0E5F54), Color(0xFF34B3A0), Color(0xFFA6F0E1)), Color(0xFF6FE0CD), Color(0xFF0E6E61)),
    Tier.LEGACY to TierLook(listOf(Color(0xFF3B5470), Color(0xFF94AECB), Color(0xFFE6F0FA)), Color(0xFFB4CCE4), Color(0xFF3B5876)),
    Tier.MEMORY_LEAK to TierLook(listOf(Color(0xFF8A5A00), Color(0xFFE3A51A), Color(0xFFFFE89A)), Color(0xFFFFD34D), Color(0xFF946300)),
    Tier.HEISENBUG to TierLook(listOf(Color(0xFF6A1B9A), Color(0xFFE0218A), Color(0xFFFF9AD5)), Color(0xFFFF6CC0), Color(0xFFB0136F)),
    Tier.ZERO_DAY to TierLook(listOf(Color(0xFF231463), Color(0xFF5B3BFF), Color(0xFF00C8FF), Color(0xFFFF4FD8)), Color(0xFF8FE9FF), Color(0xFF4B2BB5)),
)

fun tierLook(t: Tier): TierLook = tierLooks.getValue(t)

/** Text colour of a tier on the current background (the monochrome QUEST palettes keep their own ink). */
fun tierColor(t: Tier): Color {
    if (Th.style == Style.PIXEL) return when (t) {
        Tier.TIMEOUT, Tier.SEGFAULT, Tier.SKIPPED -> C.blood
        Tier.HELLO_WORLD, Tier.COPYPASTA -> C.dim
        else -> C.amber
    }
    if (t == Tier.SEGFAULT) return C.blood
    val l = tierLook(t)
    return if (Th.pal.dark) l.onDark else l.onLight
}

/** Colours of the run ranks, lowest to highest (2–4 stops each). */
fun rankGradient(r: app.coredump.core.Rank): List<Color> = when (r.ordinal) {
    0 -> listOf(Color(0xFF6B7280), Color(0xFFB8C0CC))
    1 -> listOf(Color(0xFF7A3F14), Color(0xFFD08A4E), Color(0xFFF2C28A))
    2 -> listOf(Color(0xFF0E6B5E), Color(0xFF3CB8A3), Color(0xFFA6F0E1))
    3 -> listOf(Color(0xFF3B5470), Color(0xFF9DB6CF), Color(0xFFF1F6FB))
    4 -> listOf(Color(0xFF8A5A00), Color(0xFFE3A51A), Color(0xFFFFE89A))
    5 -> listOf(Color(0xFF6A1B9A), Color(0xFFE0218A), Color(0xFFFF9AD5))
    else -> listOf(Color(0xFF2B1A6B), Color(0xFF5B3BFF), Color(0xFF00D4FF), Color(0xFFFF4FD8))
}

/** Global screen effects, triggered from anywhere, animated by ThemedScreen. */
@Stable
class Fx {
    var glitchTick by mutableIntStateOf(0)
    var shakeTick by mutableIntStateOf(0)
    var flashTick by mutableIntStateOf(0)
    var flashColor by mutableStateOf(Color.White)
    var shakePower by mutableStateOf(1f)

    fun glitch() { glitchTick++ }
    fun shake(power: Float = 1f) { shakePower = power; shakeTick++ }
    fun flash(color: Color) { flashColor = color; flashTick++ }
}

/** Visual options from settings. glow: multiplier 0..1.5 (dimensionless). */
data class Look(
    val glow: Float = 1f,
    val scanlines: Boolean = true,
    val flicker: Boolean = true,
    val reduceMotion: Boolean = false,
    val haptics: Boolean = true,
)

/** Font of the active style (name kept from the mono-only days). */
val LocalMono = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }
/** Display font for big titles (italic variants etc.). */
val LocalDisplay = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }
val LocalLook = staticCompositionLocalOf { Look() }
val LocalFx = staticCompositionLocalOf { Fx() }

@Composable
fun styleFont(style: Style): FontFamily = when (style) {
    Style.TERMINAL -> FontFamily(Font(Res.font.dejavu_mono, FontWeight.Normal), Font(Res.font.dejavu_mono_bold, FontWeight.Bold))
    Style.PAPER -> FontFamily(Font(Res.font.lora_regular, FontWeight.Normal), Font(Res.font.lora_bold, FontWeight.Bold))
    Style.NEON -> FontFamily(Font(Res.font.poppins_regular, FontWeight.Normal), Font(Res.font.poppins_bold, FontWeight.Bold))
    Style.CLEAN -> FontFamily(
        Font(Res.font.inter_regular, FontWeight.Normal), Font(Res.font.inter_semibold, FontWeight.SemiBold),
        Font(Res.font.inter_bold, FontWeight.Bold),
    )
    Style.PIXEL -> FontFamily(Font(Res.font.pixel_dump, FontWeight.Normal), Font(Res.font.pixel_dump, FontWeight.Bold))
    Style.COMIC -> FontFamily(
        Font(Res.font.inter_semibold, FontWeight.Normal), Font(Res.font.inter_bold, FontWeight.Bold),
        Font(Res.font.inter_bold, FontWeight.SemiBold),
    )
}

@Composable
fun displayFont(style: Style): FontFamily = when (style) {
    Style.NEON, Style.COMIC -> FontFamily(Font(Res.font.poppins_bold_italic, FontWeight.Bold, FontStyle.Italic))
    else -> styleFont(style)
}

/** Fonts mixed letter by letter for COMIC's ransom-note titles. */
@Composable
fun ransomFonts(): List<FontFamily> = listOf(
    FontFamily(Font(Res.font.poppins_bold_italic, FontWeight.Bold, FontStyle.Italic)),
    FontFamily(Font(Res.font.lora_bold, FontWeight.Bold)),
    FontFamily(Font(Res.font.dejavu_mono_bold, FontWeight.Bold)),
    FontFamily(Font(Res.font.inter_bold, FontWeight.Bold)),
)

/** Pixel font has one weight; mono/serif look best a touch smaller than the sans fonts. */
private fun sizeFor(style: Style, size: TextUnit): TextUnit = when (style) {
    Style.PIXEL -> size * 1.08f
    Style.CLEAN, Style.NEON, Style.COMIC -> size * 0.98f
    else -> size
}

@Composable
fun termStyle(
    size: TextUnit = 14.sp,
    color: Color = C.amber,
    weight: FontWeight = FontWeight.Normal,
    glow: Float = 1f,
    align: TextAlign = TextAlign.Start,
    spacing: TextUnit = 0.sp,
    decoration: TextDecoration? = null,
): TextStyle {
    val style = Th.style
    val g = if (style.glows) LocalLook.current.glow * glow * (if (style == Style.NEON) 1.3f else 1f) else 0f
    return TextStyle(
        color = color,
        fontSize = sizeFor(style, size),
        fontFamily = LocalMono.current,
        fontWeight = if (style == Style.PIXEL) FontWeight.Normal else weight,
        textAlign = align,
        letterSpacing = spacing,
        textDecoration = decoration,
        shadow = when {
            g > 0.01f -> Shadow(color.copy(alpha = 0.6f), Offset.Zero, 12f * g)
            style == Style.PIXEL && weight == FontWeight.Bold && size.value >= 20f -> Shadow(C.line, Offset(3f, 3f), 0f)
            else -> null
        },
    )
}

/** Themed text (glow in TERMINAL / NEON). */
@Composable
fun TText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    color: Color = C.amber,
    weight: FontWeight = FontWeight.Normal,
    glow: Float = 1f,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    spacing: TextUnit = 0.sp,
    decoration: TextDecoration? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = termStyle(size, color, weight, glow, align, spacing, decoration),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun TText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    color: Color = C.amber,
    glow: Float = 1f,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = termStyle(size, color, FontWeight.Normal, glow),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

fun group(n: Int): String {
    val s = n.toString()
    val sb = StringBuilder()
    for ((i, c) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0 && c != '-') sb.append(',')
        sb.append(c)
    }
    return sb.toString()
}
