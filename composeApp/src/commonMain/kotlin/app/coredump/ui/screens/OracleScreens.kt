package app.coredump.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.core.RoundView
import app.coredump.data.History
import app.coredump.game.MatchSession
import app.coredump.game.MatchState
import app.coredump.game.Phase
import app.coredump.platform.shareText
import app.coredump.ui.C
import app.coredump.ui.LocalLook
import app.coredump.ui.Panel
import app.coredump.ui.RankBadge
import app.coredump.ui.RollingNumber
import app.coredump.ui.Spinner
import app.coredump.ui.Style
import app.coredump.ui.TButton
import app.coredump.ui.TLink
import app.coredump.ui.TText
import app.coredump.ui.TierBadge
import app.coredump.ui.box
import app.coredump.ui.group
import app.coredump.ui.rankColor
import app.coredump.ui.rememberHaptic
import app.coredump.ui.tierColor
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ORACLE: a tarot reading. The menu is a spread of cards that turn face-up one by one; each prompt is a card
// flipped over, a candle burns down as the timer, a constellation draws itself on each verdict, and the results
// are a three-card reading (past · present · future).

private fun cardName(it: Item): String = when (it.key) {
    "daily" -> "THE SUN"
    "solo" -> "THE HERMIT"
    "duel" -> "THE RIVALS"
    "party" -> "THE TOWER"
    "friends" -> "THE LOVERS"
    "stats" -> "THE WHEEL"
    "browse" -> "THE ARCHIVE"
    "theme" -> "THE MOON"
    "settings" -> "THE EMPEROR"
    "manual" -> "THE GUIDE"
    else -> it.label.uppercase()
}

/** Back of a card: fine diamond lattice and a star in the middle. */
@Composable
private fun CardBack(modifier: Modifier) {
    val gold = C.hot
    val panel = C.panel
    Box(modifier.box(Style.ORACLE, gold, lerp(panel, gold, 0.08f), 1.2.dp).drawBehind {
        val l = 9.dp.toPx()
        val t = 9.dp.toPx()
        val r = size.width - l
        val b = size.height - t
        val step = 14.dp.toPx()
        clipRect(l, t, r, b) {
            var x = l - (b - t)
            while (x < r) {
                drawLine(gold.copy(alpha = 0.18f), Offset(x, t), Offset(x + (b - t), b), 1f)
                drawLine(gold.copy(alpha = 0.18f), Offset(x + (b - t), t), Offset(x, b), 1f)
                x += step
            }
        }
        val c = center
        val s = minOf(size.width, size.height) * 0.12f
        val star = Path().apply {
            moveTo(c.x, c.y - s); quadraticTo(c.x, c.y, c.x + s, c.y); quadraticTo(c.x, c.y, c.x, c.y + s)
            quadraticTo(c.x, c.y, c.x - s, c.y); quadraticTo(c.x, c.y, c.x, c.y - s); close()
        }
        drawCircle(panel, s * 0.9f, c)
        drawPath(star, gold)
    })
}

/** Line-art symbol for a menu card. */
@Composable
private fun CardGlyph(key: String, modifier: Modifier) {
    val g = C.hot
    val cut = C.panel
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f
        val sw = 1.5.dp.toPx()
        val st = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(dx: Float, dy: Float) = Offset(c.x + dx * r, c.y + dy * r)
        when (key) {
            "daily" -> {
                drawCircle(g, r * 0.38f, c, style = st)
                for (i in 0 until 12) {
                    val a = i * PI.toFloat() / 6f
                    drawLine(g, p(cos(a) * 0.52f, sin(a) * 0.52f), p(cos(a) * (if (i % 2 == 0) 0.94f else 0.74f), sin(a) * (if (i % 2 == 0) 0.94f else 0.74f)), sw, StrokeCap.Round)
                }
            }
            "solo" -> {
                val body = Path().apply { moveTo(p(-0.32f, -0.2f).x, p(0f, -0.2f).y); lineTo(p(0.32f, 0f).x, p(0f, -0.2f).y)
                    lineTo(p(0.32f, 0f).x, p(0f, 0.6f).y); lineTo(p(-0.32f, 0f).x, p(0f, 0.6f).y); close() }
                drawPath(body, g, style = st)
                drawLine(g, p(-0.42f, -0.2f), p(0f, -0.55f), sw, StrokeCap.Round)
                drawLine(g, p(0.42f, -0.2f), p(0f, -0.55f), sw, StrokeCap.Round)
                drawCircle(g, r * 0.12f, p(0f, -0.72f), style = st)
                drawCircle(g, r * 0.1f, p(0f, 0.2f))
            }
            "party" -> {
                val tower = Path().apply { moveTo(p(-0.3f, 0.8f).x, p(0f, 0.8f).y); lineTo(p(-0.22f, -0.4f).x, p(0f, -0.4f).y)
                    lineTo(p(0.22f, -0.4f).x, p(0f, -0.4f).y); lineTo(p(0.3f, 0.8f).x, p(0f, 0.8f).y); close() }
                drawPath(tower, g, style = st)
                val bolt = Path().apply { moveTo(p(0.5f, -0.9f).x, p(0f, -0.9f).y); lineTo(p(0.1f, -0.5f).x, p(0f, -0.5f).y)
                    lineTo(p(0.3f, -0.45f).x, p(0f, -0.45f).y); lineTo(p(-0.1f, -0.05f).x, p(0f, -0.05f).y) }
                drawPath(bolt, g, style = st)
                drawCircle(g, r * 0.08f, p(0f, 0.2f))
            }
            "duel" -> {
                drawLine(g, p(-0.7f, 0.7f), p(0.7f, -0.7f), sw, StrokeCap.Round)
                drawLine(g, p(0.7f, 0.7f), p(-0.7f, -0.7f), sw, StrokeCap.Round)
                drawLine(g, p(-0.62f, 0.32f), p(-0.32f, 0.62f), sw, StrokeCap.Round)
                drawLine(g, p(0.62f, 0.32f), p(0.32f, 0.62f), sw, StrokeCap.Round)
            }
            "stats" -> {
                drawCircle(g, r * 0.8f, c, style = st)
                drawCircle(g, r * 0.18f, c, style = st)
                for (i in 0 until 8) {
                    val a = i * PI.toFloat() / 4f
                    drawLine(g, p(cos(a) * 0.18f, sin(a) * 0.18f), p(cos(a) * 0.8f, sin(a) * 0.8f), sw)
                }
            }
            "browse" -> {
                val left = Path().apply { moveTo(c.x, p(0f, -0.4f).y); lineTo(p(-0.8f, 0f).x, p(0f, -0.55f).y); lineTo(p(-0.8f, 0f).x, p(0f, 0.45f).y)
                    lineTo(c.x, p(0f, 0.6f).y); close() }
                val right = Path().apply { moveTo(c.x, p(0f, -0.4f).y); lineTo(p(0.8f, 0f).x, p(0f, -0.55f).y); lineTo(p(0.8f, 0f).x, p(0f, 0.45f).y)
                    lineTo(c.x, p(0f, 0.6f).y); close() }
                drawPath(left, g, style = st)
                drawPath(right, g, style = st)
            }
            "theme" -> {
                drawCircle(g, r * 0.62f, c)
                drawCircle(cut, r * 0.56f, p(0.3f, -0.12f))
            }
            "settings" -> {
                val crown = Path().apply {
                    moveTo(p(-0.7f, 0.4f).x, p(0f, 0.4f).y); lineTo(p(-0.7f, 0f).x, p(0f, -0.4f).y); lineTo(p(-0.35f, 0f).x, p(0f, 0f).y)
                    lineTo(c.x, p(0f, -0.6f).y); lineTo(p(0.35f, 0f).x, p(0f, 0f).y); lineTo(p(0.7f, 0f).x, p(0f, -0.4f).y)
                    lineTo(p(0.7f, 0f).x, p(0f, 0.4f).y); close()
                }
                drawPath(crown, g, style = st)
                drawLine(g, p(-0.7f, 0.58f), p(0.7f, 0.58f), sw, StrokeCap.Round)
            }
            else -> {
                val eye = Path().apply {
                    moveTo(p(-0.8f, 0f).x, c.y); quadraticTo(c.x, p(0f, -0.75f).y, p(0.8f, 0f).x, c.y)
                    quadraticTo(c.x, p(0f, 0.75f).y, p(-0.8f, 0f).x, c.y); close()
                }
                drawPath(eye, g, style = st)
                drawCircle(g, r * 0.2f, c)
            }
        }
    }
}

/** Crescent moon (gold disc with a bite taken out in the background colour). */
@Composable
private fun Crescent(modifier: Modifier) {
    val gold = C.hot
    val bg = C.bg
    Canvas(modifier) {
        val r = size.minDimension / 2f
        drawCircle(gold, r, center)
        drawCircle(bg, r * 0.86f, Offset(center.x + r * 0.42f, center.y - r * 0.18f))
    }
}

// ======================================================================= menu

@Composable
fun OracleMenu(model: AppModel) {
    val items = menuItems(model)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(30.dp))
        Crescent(Modifier.size(34.dp))
        Spacer(Modifier.height(10.dp))
        AppTitle(40.sp)
        Spacer(Modifier.height(6.dp))
        TText("Draw a card. Name what others won't.", size = 13.sp, color = C.dim, align = TextAlign.Center)
        TText(if (History.games == 0) "No readings yet." else "Best reading ${History.best} · ${History.games} readings", size = 11.sp, color = C.dim)
        CrashBanner(model)
        Spacer(Modifier.height(22.dp))
        items.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                pair.forEachIndexed { j, it -> TarotCard(row * 2 + j, it, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
        }
        Spacer(Modifier.height(6.dp))
        Panel("The library") { LibraryFooter(model, sources = false) }
        Spacer(Modifier.height(30.dp))
    }
}

/** A menu card: dealt face down, turns face up after a short delay; lifts a little while pressed. */
@Composable
private fun TarotCard(index: Int, item: Item, modifier: Modifier) {
    val motion = !LocalLook.current.reduceMotion
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptic = rememberHaptic()
    val flip = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { delay(110L * index + 160L); flip.animateTo(1f, tween(560, easing = FastOutSlowInEasing)) }
    val lift by animateFloatAsState(if (pressed) 1f else 0f, tween(120))
    Box(
        modifier.aspectRatio(0.64f).graphicsLayer {
            rotationY = (1f - flip.value) * 180f
            cameraDistance = 14f * density
            translationY = -lift * 4.dp.toPx()
        }.clickable(src, null) { haptic(true); item.action() },
    ) {
        if (flip.value < 0.5f) {
            CardBack(Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.fillMaxSize().box(Style.ORACLE, C.hot, C.panel, 1.2.dp, glow = index == 0).padding(horizontal = 10.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween,
            ) {
                TText(roman(index + 1), size = 12.sp, color = C.hot, weight = FontWeight.Bold, spacing = 2.sp)
                CardGlyph(item.key, Modifier.size(52.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TText(cardName(item), size = 12.sp, weight = FontWeight.Bold, spacing = 1.5.sp, align = TextAlign.Center, maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    TText(if (item.key == "daily") dailyLabel(item.label, "Daily reading") else item.hint, size = 9.sp, color = C.dim,
                        align = TextAlign.Center, maxLines = 2)
                }
            }
        }
    }
}

// ======================================================================= play

@Composable
fun OraclePlay(model: AppModel, session: MatchSession, st: MatchState, a: AnswerState) {
    val now by rememberNow(st.startedAt, isRunning(st))
    val left = timeLeft(st, now)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TText("CARD ${roman(st.round + 1)} OF ${roman(st.total)}", size = 11.sp, color = C.dim, weight = FontWeight.Bold, spacing = 2.sp)
            Spacer(Modifier.weight(1f))
            TText("✦ ", size = 13.sp, color = C.hot)
            RollingNumber(st.myTotal, size = 16.sp, color = C.hot)
            PauseQuit(model, session, st, color = C.hot)
        }
        // the run's sky: stars join up as points come in, then it flashes on to the next constellation
        app.coredump.ui.ConstellationStrip(st.myTotal, Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 18.dp))
        Box(Modifier.padding(horizontal = 18.dp)) { PeerLine(session, st) }
        if (st.seconds > 0) Candle(left, st.seconds, isRunning(st))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
            if (st.phase == Phase.WAITING) {
                Row(verticalAlignment = Alignment.CenterVertically) { Spinner(C.hot); TText("  ${st.message ?: "Shuffling the deck…"}", size = 13.sp, color = C.dim) }
            } else if (st.prompt.isNotEmpty()) {
                key(st.round) { PromptCard(st) }
            }
            Spacer(Modifier.height(18.dp))
            when (st.phase) {
                Phase.TYPING -> if (!st.paused) {
                    AnswerField(a, st, Modifier.fillMaxWidth().shakeX(a), size = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    SuggestionChip(st, a)
                    Spacer(Modifier.height(8.dp))
                    AttemptChips(st)
                    Spacer(Modifier.height(14.dp))
                    TButton("Offer your answer", { a.send() }, Modifier.fillMaxWidth(), enabled = a.text.isNotBlank(), primary = true, textSize = 15.sp)
                    Spacer(Modifier.height(10.dp))
                    TText("The rarer the answer, the brighter the omen.", Modifier.fillMaxWidth(), size = 11.sp, color = C.dim, align = TextAlign.Center)
                }
                Phase.LOCKED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(C.hot); TText("  Your card is sealed. Waiting for your rival…", size = 13.sp, color = C.dim)
                }
                Phase.REVEAL -> st.reveal?.let { v ->
                    OracleVerdict(v)
                    RevealExtras(v, session.isMulti)
                }
                Phase.ERROR -> Panel("THE SPIRITS ARE SILENT", color = C.blood, titleColor = C.blood) {
                    TText(st.message ?: "unknown fault", size = 14.sp, color = C.blood)
                }
                else -> Unit
            }
        }
        PlayActions(model, session, st, Modifier.padding(16.dp))
    }
}

/** Timer as a burning wick: the line shortens, a small flame flickers at its end (red in the last 5 s). */
@Composable
private fun Candle(leftMs: Long, seconds: Int, running: Boolean) {
    val f = (leftMs / (seconds * 1000f)).coerceIn(0f, 1f)
    val low = leftMs in 1..5000
    val motion = !LocalLook.current.reduceMotion
    val t = rememberInfiniteTransition()
    val flick by t.animateFloat(0f, 1f, infiniteRepeatable(tween(620, easing = LinearEasing), RepeatMode.Reverse))
    val gold = C.hot
    val line = C.line
    val blood = C.blood
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.weight(1f).height(20.dp)) {
            val y = size.height * 0.75f
            val c = if (low) blood else gold
            drawLine(line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            val x = size.width * f
            drawLine(c, Offset(0f, y), Offset(x, y), 2.dp.toPx())
            if (f > 0f) {
                val fl = if (running && motion) flick else 0.5f
                val h = (7f + 3f * fl).dp.toPx()
                val wd = (3.2f + 0.6f * fl).dp.toPx()
                val flame = Path().apply {
                    moveTo(x, y - h); quadraticTo(x + wd, y - h * 0.3f, x, y); quadraticTo(x - wd, y - h * 0.3f, x, y - h); close()
                }
                drawCircle(c.copy(alpha = 0.16f + 0.1f * fl), 9.dp.toPx(), Offset(x, y - h * 0.45f))
                drawPath(flame, c)
            }
        }
        TText("  ${(leftMs + 999) / 1000}s", Modifier.width(48.dp), size = 12.sp, color = if (low) C.blood else C.dim)
    }
}

/** The prompt card: dealt face down, then turned over. */
@Composable
private fun PromptCard(st: MatchState) {
    val motion = !LocalLook.current.reduceMotion
    val rot = remember { Animatable(if (motion) 180f else 0f) }
    LaunchedEffect(Unit) { delay(150); rot.animateTo(0f, tween(820, easing = FastOutSlowInEasing)) }
    Box(Modifier.fillMaxWidth().heightIn(min = 190.dp).graphicsLayer { rotationY = rot.value; cameraDistance = 16f * density }) {
        if (rot.value > 90f) {
            CardBack(Modifier.fillMaxWidth().height(190.dp))
        } else {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 190.dp).box(Style.ORACLE, C.hot, C.panel, 1.2.dp, glow = true).padding(horizontal = 22.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                TText(roman(st.round + 1), size = 13.sp, color = C.hot, weight = FontWeight.Bold, spacing = 3.sp)
                Spacer(Modifier.height(6.dp))
                TText(st.topic.uppercase(), size = 10.sp, color = C.dim, spacing = 3.sp)
                Spacer(Modifier.height(14.dp))
                TText(st.prompt, size = 21.sp, weight = FontWeight.Bold, align = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                TText("${group(st.listSize)} answers foretold", size = 11.sp, color = C.dim)
            }
        }
    }
}

@Composable
private fun OracleVerdict(v: RoundView) {
    val me = v.me
    val c = tierColor(me.tier)
    val motion = !LocalLook.current.reduceMotion
    val draw = remember(v.index) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(v.index) { draw.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
    Column(Modifier.fillMaxWidth().box(Style.ORACLE, c.copy(alpha = 0.7f), C.panel, 1.dp).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Constellation(me.tier.heat, v.index, draw.value, c, Modifier.fillMaxWidth().height(54.dp))
        Spacer(Modifier.height(8.dp))
        TText(tierLine(me.tier).uppercase(), size = 11.sp, color = C.hot, weight = FontWeight.Bold, spacing = 2.5.sp, align = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        TText(headline(me), size = 26.sp, weight = FontWeight.Bold, color = c, align = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TierBadge(me.tier, 11.sp)
            Spacer(Modifier.width(10.dp))
            TText(signed(me.points), size = 18.sp, weight = FontWeight.Bold, color = C.hot)
        }
    }
}

/** heat + 2 stars joined one by one as [p] goes 0 → 1. */
@Composable
private fun Constellation(heat: Int, seed: Int, p: Float, color: Color, modifier: Modifier) {
    val pts = remember(seed, heat) {
        val r = Random(seed * 7 + heat)
        List(heat + 2) { Offset(0.1f + 0.8f * it / (heat + 1).toFloat(), 0.2f + 0.6f * r.nextFloat()) }
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val n = pts.size
        val shown = p * (n - 1)
        for (i in 0 until n - 1) {
            val f = (shown - i).coerceIn(0f, 1f)
            if (f <= 0f) break
            val a = Offset(pts[i].x * w, pts[i].y * h)
            val b = Offset(pts[i + 1].x * w, pts[i + 1].y * h)
            drawLine(color.copy(alpha = 0.5f), a, Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f), 1.dp.toPx())
        }
        for (i in 0 until n) {
            if (i > shown + 0.01f) break
            val o = Offset(pts[i].x * w, pts[i].y * h)
            drawCircle(color.copy(alpha = 0.22f), 5.dp.toPx(), o)
            drawCircle(color, 2.dp.toPx(), o)
        }
    }
}

// ======================================================================= summary

@Composable
fun OracleSummary(model: AppModel, session: MatchSession) {
    val st by session.state.collectAsState()
    val t = totalsOf(st)
    val best = st.rounds.maxByOrNull { it.me.points }
    Frame(model, "Your reading", onBack = { model.home() }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            TText(if (st.daily != null) "THE DAILY READING · #${st.daily}" else "A READING OF ${roman(t.count.coerceAtLeast(1))} CARDS",
                size = 10.sp, color = C.dim, spacing = 3.sp, align = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SpreadCard(0, "THE PAST", Modifier.weight(1f)) {
                    if (best != null) {
                        TText(answerText(best.me.tier, best.me.canonical, best.me.input), size = 13.sp, weight = FontWeight.Bold,
                            color = tierColor(best.me.tier), align = TextAlign.Center, maxLines = 3)
                        Spacer(Modifier.height(4.dp))
                        TText(best.me.tier.label, size = 8.sp, color = tierColor(best.me.tier), align = TextAlign.Center, maxLines = 2)
                    }
                }
                SpreadCard(1, "THE PRESENT", Modifier.weight(1f)) {
                    app.coredump.ui.CustomIcon(app.coredump.ui.customKey(t.rank), 30.dp)
                    TText(t.rank.title, size = 13.sp, weight = FontWeight.Bold, color = rankColor(t.rank), align = TextAlign.Center, maxLines = 3)
                }
                SpreadCard(2, "THE FUTURE", Modifier.weight(1f)) {
                    RollingNumber(t.my, size = 22.sp, color = C.hot, durationMs = 1800)
                    TText("points", size = 9.sp, color = C.dim)
                }
            }
            Spacer(Modifier.height(18.dp))
            RankBadge(t.rank, size = 18.sp)
            Spacer(Modifier.height(8.dp))
            TText(t.rank.line, size = 13.sp, color = C.dim, align = TextAlign.Center)
            TText("Average ${oneDecimal(t.avg)} per card", size = 11.sp, color = C.dim, align = TextAlign.Center)
            val opp = st.oppName
            if (session.isMulti && opp != null) {
                Spacer(Modifier.height(12.dp))
                val o = st.oppTotal
                TText(when { t.my > o -> "The stars favour you · ${t.my}–$o"; t.my < o -> "The stars favour $opp · $o–${t.my}"; else -> "The stars are undecided · ${t.my}–$o" },
                    size = 15.sp, weight = FontWeight.Bold, color = if (t.my >= o) C.hot else C.blood, align = TextAlign.Center)
            }
            Spacer(Modifier.height(18.dp))
            Panel("The cards") { st.rounds.forEach { OracleRow(model, it) } }
            Spacer(Modifier.height(10.dp))
            TLink("Share the reading", { shareText(shareCard(st, t.rank)) }, color = C.hot)
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            if (!session.isMulti) {
                TButton("Draw again", { model.replay(session) }, Modifier.weight(1f), primary = true)
                Spacer(Modifier.width(12.dp))
            }
            TButton("Leave", { model.home() }, Modifier.weight(1f))
        }
    }
}

/** One card of the three-card spread; they turn over one after another. */
@Composable
private fun SpreadCard(i: Int, title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val motion = !LocalLook.current.reduceMotion
    val flip = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { delay(300L + 280L * i); flip.animateTo(1f, tween(620, easing = FastOutSlowInEasing)) }
    Box(modifier.aspectRatio(0.62f).graphicsLayer { rotationY = (1f - flip.value) * 180f; cameraDistance = 14f * density }) {
        if (flip.value < 0.5f) {
            CardBack(Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize().box(Style.ORACLE, C.hot, C.panel, 1.dp).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                TText(roman(i + 1), size = 10.sp, color = C.hot, weight = FontWeight.Bold, spacing = 2.sp)
                Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
                TText(title, size = 9.sp, color = C.dim, weight = FontWeight.Bold, spacing = 1.5.sp, align = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

@Composable
private fun OracleRow(model: AppModel, r: RoundView) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { open = !open }.padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TText(roman(r.index + 1), Modifier.width(44.dp), size = 12.sp, color = C.hot, weight = FontWeight.Bold)
            Column(Modifier.weight(1f)) {
                TText(r.prompt, size = 10.sp, color = C.dim, maxLines = 2)
                TText(answerText(r.me.tier, r.me.canonical, r.me.input), size = 14.sp, weight = FontWeight.Bold, color = tierColor(r.me.tier), maxLines = 1)
            }
            TText(signed(r.me.points), size = 13.sp, weight = FontWeight.Bold)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { RoundDetails(model, r) }
    }
}
