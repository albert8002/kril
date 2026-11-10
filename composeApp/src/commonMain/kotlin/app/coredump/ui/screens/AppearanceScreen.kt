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
                Toggle("score runs", s.scoreRuns, { v -> model.update { it.copy(scoreRuns = v) } },
                    "points travel: speed up, count, slow down")
                val look = Th.style.name
                Toggle("ADHD-fy ${Th.style.label.lowercase()}", look in s.hyper, { v ->
                    model.update { it.copy(hyper = if (v) it.hyper + look else it.hyper - look) }
                }, "more motion, more colour, flourishes everywhere (this look only)")
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
                Style.ABYSS -> {
                    drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(p.line.copy(alpha = 0.7f), p.bg, Color.Black)))
                    val r = kotlin.random.Random(5)
                    repeat(26) { drawCircle(p.hot.copy(alpha = 0.25f + 0.5f * r.nextFloat()), 1.2f + r.nextFloat() * 1.5f, Offset(r.nextFloat() * size.width, r.nextFloat() * size.height)) }
                    drawCircle(p.hot.copy(alpha = 0.4f), 16f, Offset(size.width * 0.78f, size.height * 0.62f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                    drawCircle(p.hot.copy(alpha = 0.2f), 28f, Offset(size.width * 0.78f, size.height * 0.62f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                }
                Style.METRO -> {
                    drawRect(p.hot, Offset.Zero, Size(size.width, 5f))
                    for (i in 0 until 4) {
                        val x = size.width * 0.42f + i * 15f
                        drawRoundRect(p.panel, Offset(x, 14f), Size(13f, 18f), androidx.compose.ui.geometry.CornerRadius(2f))
                        drawRect(p.bg, Offset(x, 22.5f), Size(13f, 1f))
                    }
                    drawLine(p.hot, Offset(8f, size.height - 12f), Offset(size.width - 8f, size.height - 12f), 4f)
                    for (i in 0 until 5) drawCircle(p.amber, 4f, Offset(8f + i * (size.width - 16f) / 4f, size.height - 12f))
                }
                Style.JACKPOT -> {
                    var x = 6f
                    var i = 0
                    while (x < size.width) {
                        drawCircle(if (i % 2 == 0) p.white else p.line.copy(alpha = 0.5f), 2.2f, Offset(x, 5f))
                        drawCircle(if (i % 2 == 1) p.white else p.line.copy(alpha = 0.5f), 2.2f, Offset(x, size.height - 5f))
                        x += 10f; i++
                    }
                }
                Style.CHAT -> {
                    drawRoundRect(p.panel, Offset(size.width * 0.45f, 10f), Size(size.width * 0.48f, 18f), androidx.compose.ui.geometry.CornerRadius(9f))
                    drawRoundRect(p.hot, Offset(size.width * 0.52f, 34f), Size(size.width * 0.41f, 18f), androidx.compose.ui.geometry.CornerRadius(9f))
                }
                Style.DESKTOP -> {
                    val l = size.width * 0.44f
                    drawRect(p.panel, Offset(l, 8f), Size(size.width - l - 6f, size.height - 22f))
                    drawRect(p.hot, Offset(l + 2f, 10f), Size(size.width - l - 10f, 7f))
                    drawRect(p.panel, Offset(0f, size.height - 10f), Size(size.width, 10f))
                    drawRect(p.white, Offset(0f, size.height - 10f), Size(size.width, 1f))
                }
                Style.ORACLE -> {
                    val r = kotlin.random.Random(9)
                    repeat(22) { drawCircle(p.amber.copy(alpha = 0.2f + 0.5f * r.nextFloat()), 0.8f + r.nextFloat(), Offset(r.nextFloat() * size.width, r.nextFloat() * size.height)) }
                    val cw = 30f
                    val ch = 46f
                    val x = size.width - cw - 10f
                    drawRect(p.panel, Offset(x, 14f), Size(cw, ch))
                    drawRect(p.hot, Offset(x, 14f), Size(cw, ch), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                    drawRect(p.hot.copy(alpha = 0.5f), Offset(x + 3f, 17f), Size(cw - 6f, ch - 6f), style = androidx.compose.ui.graphics.drawscope.Stroke(0.8f))
                    drawCircle(p.hot, 5f, Offset(x + cw / 2f, 14f + ch / 2f))
                }
            }
        },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
            BasicText("Aa", style = TextStyle(color = p.amber, fontFamily = font, fontSize = 20.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pill = style == Style.NEON || style == Style.CLEAN || style == Style.ABYSS || style == Style.CHAT
                // DESKTOP draws its bevel from the active palette, so the thumbnail uses plain blocks there
                if (style == Style.DESKTOP) {
                    Box(Modifier.size(38.dp, 14.dp).drawBehind { drawRect(p.panel); drawRect(p.white, Offset.Zero, Size(size.width, 1.dp.toPx())); drawRect(Color.Black, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) })
                } else {
                    Box(Modifier.size(38.dp, 14.dp).drawBehind { drawBox(style, p.hot, p.hot, 1.dp.toPx(), pill = pill) })
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(22.dp, 14.dp).drawBehind { drawBox(style, p.white, Color.Transparent, 1.dp.toPx(), pill = pill) })
                }
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
