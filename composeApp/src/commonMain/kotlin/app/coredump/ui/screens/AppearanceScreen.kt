package app.coredump.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.core.Tier
import app.coredump.ui.C
import app.coredump.ui.Choice
import app.coredump.ui.Palette
import app.coredump.ui.Palettes
import app.coredump.ui.Panel
import app.coredump.ui.Style
import app.coredump.ui.TButton
import app.coredump.ui.TText
import app.coredump.ui.Th
import app.coredump.ui.TierBadge
import app.coredump.ui.Toggle
import app.coredump.ui.box
import app.coredump.ui.drawBox
import app.coredump.ui.rememberHaptic
import app.coredump.ui.styleFont

/** Pick a theme (style) and one of its palettes. The whole app re-themes immediately. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(model: AppModel) {
    val s = model.settings
    val haptic = rememberHaptic()
    Frame(model, Th.say("chtheme", "Theme")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Panel(Th.say("THEME", "Style")) {
                for (style in Style.entries) {
                    val on = style == Th.style
                    val pal = if (on) Th.pal else Palettes.of(style).first()
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp)
                            .box(Th.style, if (on) C.hot else C.line, if (on) C.hot.copy(alpha = 0.10f) else Color.Transparent,
                                strokeWidth = if (on) 2.dp else 1.dp)
                            .clickable(remember { MutableInteractionSource() }, null) {
                                haptic(true)
                                if (!on) model.setTheme(style, Palettes.of(style).first().id)
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ThemeThumb(style, pal)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            TText(style.label, size = 15.sp, weight = FontWeight.Bold, color = if (on) C.hot else C.amber)
                            TText(style.blurb, size = 11.sp, color = C.dim)
                            TText("${Palettes.of(style).size} colour schemes", size = 10.sp, color = C.dim)
                        }
                        if (on) TText("✓", size = 20.sp, color = C.hot, weight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Panel(Th.say("PALETTE", "Colours")) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (p in Palettes.of(Th.style)) {
                        val on = p.id == Th.pal.id
                        Column(
                            Modifier.width(94.dp).clickable(remember { MutableInteractionSource() }, null) {
                                haptic(false); model.setTheme(Th.style, p.id)
                            },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Swatch(p, on)
                            Spacer(Modifier.height(4.dp))
                            TText(p.name, size = 10.sp, color = if (on) C.hot else C.dim, maxLines = 1,
                                weight = if (on) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Panel(Th.say("PREVIEW", "Preview")) {
                TText(Th.say("> Name a dog breed", "Name a dog breed"), size = 18.sp, weight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TierBadge(Tier.HEISENBUG, 11.sp)
                    Spacer(Modifier.width(8.dp))
                    TierBadge(Tier.ZERO_DAY, 11.sp)
                    Spacer(Modifier.width(8.dp))
                    TierBadge(Tier.SEGFAULT, 11.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    TButton(Th.say("EXECUTE", "Submit"), {}, Modifier.weight(1f), primary = true, textSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    TButton(Th.say("ABORT", "Cancel"), {}, Modifier.weight(1f), textSize = 13.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Panel(Th.say("DISPLAY", "Display")) {
                TText("text size", size = 13.sp, color = C.dim)
                Spacer(Modifier.height(6.dp))
                Choice(listOf(0.9f, 1f, 1.15f, 1.3f), s.textScale, { "${(it * 100).toInt()}%" }) { v -> model.update { it.copy(textScale = v) } }
                Spacer(Modifier.height(8.dp))
                Toggle("reduce motion", s.reduceMotion, { v -> model.update { it.copy(reduceMotion = v) } },
                    "no shake, typing, scramble or rain")
                if (Th.style == Style.TERMINAL) {
                    Toggle("scanlines", s.scanlines, { v -> model.update { it.copy(scanlines = v) } })
                    Toggle("phosphor flicker", s.flicker, { v -> model.update { it.copy(flicker = v) } })
                }
                if (Th.style.glows) {
                    Spacer(Modifier.height(6.dp))
                    TText("glow", size = 13.sp, color = C.dim)
                    Spacer(Modifier.height(6.dp))
                    Choice(listOf(0f, 0.5f, 1f, 1.5f), s.glow, { if (it == 0f) "OFF" else "${(it * 100).toInt()}%" }) { v ->
                        model.update { it.copy(glow = v) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Small picture of a style in a palette: backdrop motif, "Aa" in the style's font, a button and a badge. */
@Composable
private fun ThemeThumb(style: Style, p: Palette) {
    val font = styleFont(style)
    Box(
        Modifier.size(112.dp, 78.dp).clip(RoundedCornerShape(8.dp)).drawBehind {
            drawRect(p.bg)
            when (style) {
                Style.TERMINAL -> {
                    var y = 0f
                    while (y < size.height) { drawRect(Color.Black.copy(alpha = 0.3f), Offset(0f, y), Size(size.width, 1f)); y += 3f }
                }
                Style.PAPER -> {
                    drawRect(p.amber, Offset(8f, 8f), Size(size.width - 16f, 3f))
                    drawRect(p.amber, Offset(8f, 14f), Size(size.width - 16f, 1f))
                }
                Style.NEON -> {
                    val hz = size.height * 0.62f
                    for (i in 0 until 6) {
                        val z = i / 6f
                        drawRect(p.hot.copy(alpha = 0.5f), Offset(0f, hz + (size.height - hz) * z * z), Size(size.width, 1f))
                    }
                    for (i in -4..4) drawLine(p.hot.copy(alpha = 0.4f), Offset(size.width / 2, hz), Offset(size.width / 2 + i * size.width / 5, size.height), 1f)
                }
                Style.CLEAN -> drawRoundRect(p.panel, Offset(8f, 10f), Size(size.width - 16f, size.height - 20f),
                    androidx.compose.ui.geometry.CornerRadius(14f))
                Style.PIXEL -> {
                    var x = 0f
                    while (x < size.width) { drawRect(p.line.copy(alpha = 0.12f), Offset(x, 0f), Size(1f, size.height)); x += 4f }
                }
                Style.COMIC -> {
                    // halftone corner + one slashed band
                    for (row in 0 until 5) for (col in 0 until 7) {
                        val r = (5f - row) * 0.9f - col * 0.35f
                        if (r > 0.4f) drawCircle(p.hot.copy(alpha = 0.55f), r, Offset(size.width - col * 9f - 6f, row * 8f + 5f))
                    }
                    val band = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, size.height * 0.78f); lineTo(size.width, size.height * 0.58f)
                        lineTo(size.width, size.height * 0.70f); lineTo(0f, size.height * 0.90f); close()
                    }
                    drawPath(band, p.white.copy(alpha = 0.35f))
                }
            }
        },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
            BasicText("Aa", style = TextStyle(color = p.amber, fontFamily = font, fontSize = 20.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp, 14.dp).drawBehind { drawBox(style, p.hot, p.hot, 1.dp.toPx(), pill = style == Style.NEON || style == Style.CLEAN) })
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(22.dp, 14.dp).drawBehind { drawBox(style, p.white, Color.Transparent, 1.dp.toPx(), pill = style == Style.NEON || style == Style.CLEAN) })
            }
        }
    }
}

@Composable
private fun Swatch(p: Palette, on: Boolean) {
    Box(
        Modifier.size(94.dp, 56.dp).clip(RoundedCornerShape(10.dp)).drawBehind {
            drawRect(p.bg)
            val w = size.width / 4f
            val h = size.height * 0.38f
            val y = size.height - h - 8f
            listOf(p.amber, p.hot, p.white, p.blood).forEachIndexed { i, c ->
                drawRoundRect(c, Offset(8f + i * (w - 4f), y), Size(w - 10f, h), androidx.compose.ui.geometry.CornerRadius(4f))
            }
            drawRect(p.panel, Offset(8f, 8f), Size(size.width - 16f, size.height * 0.28f))
            if (on) drawRoundRect(p.hot, style = androidx.compose.ui.graphics.drawscope.Stroke(5f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(20f))
            else drawRoundRect(p.line, style = androidx.compose.ui.graphics.drawscope.Stroke(2f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(20f))
        },
    )
}
