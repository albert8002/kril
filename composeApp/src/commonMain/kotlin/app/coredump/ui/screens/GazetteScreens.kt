package app.coredump.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.core.RoundView
import app.coredump.core.Tier
import app.coredump.dailyNumber
import app.coredump.data.History
import app.coredump.game.AttemptStatus
import app.coredump.game.MatchSession
import app.coredump.game.MatchState
import app.coredump.game.Phase
import app.coredump.platform.Platform
import app.coredump.platform.shareText
import app.coredump.ui.C
import app.coredump.ui.LocalLook
import app.coredump.ui.Panel
import app.coredump.ui.RollingNumber
import app.coredump.ui.Spinner
import app.coredump.ui.TButton
import app.coredump.ui.TLink
import app.coredump.ui.TText
import app.coredump.ui.Th
import app.coredump.ui.group
import app.coredump.ui.rememberHaptic
import app.coredump.ui.tierColor
import kotlinx.coroutines.delay

// GAZETTE: a newspaper. Front-page menu with masthead, dateline, lead story and columns; each prompt is a
// "clue" card in serif italics; wrong answers are struck through in pencil; the verdict lands as a rubber
// stamp; results are printed as an EXTRA! edition with dotted leaders.

private val WEEKDAYS = listOf("SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY")
private val MONTHS = listOf("JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE", "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER")

/** UTC dateline, e.g. "FRIDAY, NOVEMBER 6, 2026" (civil-from-days algorithm, proleptic Gregorian). */
fun dateline(epochMs: Long): String {
    val days = epochMs.floorDiv(86_400_000L)
    val weekday = (days + 4).mod(7L).toInt()           // 1970-01-01 was a Thursday (index 4)
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097                                    // day of era [0, 146096]
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365   // year of era [0, 399]
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)              // day of year [0, 365]
    val mp = (5 * doy + 2) / 153                                   // month from March [0, 11]
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
    return "${WEEKDAYS[weekday]}, ${MONTHS[m - 1]} $d, $y"
}

/** Fades in like ink soaking into paper: alpha + a small drop, after [delayMs]. */
@Composable
private fun InkIn(delayMs: Long, content: @Composable () -> Unit) {
    val motion = !LocalLook.current.reduceMotion
    val p = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { delay(delayMs); p.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
    Box(Modifier.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 8f }) { content() }
}

@Composable
private fun Rule(thick: Dp = 1.dp, color: Color = C.amber) = Box(Modifier.fillMaxWidth().height(thick).background(color))

@Composable
private fun Kicker(text: String, color: Color = C.hot) =
    TText(text.uppercase(), size = 10.sp, color = color, weight = FontWeight.Bold, spacing = 2.sp)

@Composable
private fun DottedLeader(modifier: Modifier) {
    val c = C.line
    Canvas(modifier.height(12.dp)) {
        val r = 1.dp.toPx()
        var x = r
        while (x < size.width) { drawCircle(c, r, Offset(x, size.height - 3.dp.toPx())); x += 5.dp.toPx() }
    }
}

// ======================================================================= menu

@Composable
fun GazetteMenu(model: AppModel) {
    val items = menuItems(model).associateBy { it.key }
    val haptic = rememberHaptic()
    val now = Platform.epochMillis()
    val today = dailyNumber(now)
    val played = History.playedDaily(today)

    fun open(k: String) { haptic(true); items[k]?.action?.invoke() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            TText("VOL. I · No. $today", Modifier.weight(1f), size = 10.sp, color = C.dim, spacing = 1.sp)
            TText("FREE EDITION", size = 10.sp, color = C.dim, spacing = 1.sp)
        }
        Spacer(Modifier.height(4.dp))
        AppTitle(44.sp)
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            TText(dateline(now), Modifier.weight(1f), size = 10.sp, color = C.amber, spacing = 1.sp, maxLines = 1)
            TText("“ALL THE ANSWERS FIT TO GUESS”", size = 9.sp, color = C.dim, maxLines = 1)
        }
        Rule(2.dp)
        CrashBanner(model)

        // lead story: the daily run
        Spacer(Modifier.height(14.dp))
        InkIn(80) {
            Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { open("daily") }) {
                Kicker(if (played != null) "Today's puzzle · filed" else "Today's puzzle")
                Spacer(Modifier.height(4.dp))
                TText(
                    if (played != null) "Daily Run No. $today: You Scored ${played.points} Points"
                    else "Daily Run No. $today Is Out: Seven Prompts, One Chance",
                    size = 27.sp, weight = FontWeight.Bold, color = C.amber,
                )
                Spacer(Modifier.height(6.dp))
                TText("Everyone gets the same seven prompts today. The rarer your answer, the higher it scores — the obvious ones barely count.",
                    size = 14.sp, color = C.dim)
                Spacer(Modifier.height(10.dp))
                TButton(if (played != null) "Play it again" else "Read & play", { open("daily") }, Modifier.fillMaxWidth(), primary = true)
            }
        }
        Spacer(Modifier.height(16.dp))
        Rule()
        Spacer(Modifier.height(12.dp))

        // two columns of stories
        InkIn(220) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Column(Modifier.weight(1f)) {
                    Story("Solo", "Play a Solo Run", "Choose topics, the clock and how forgiving spelling is.") { open("solo") }
                    Spacer(Modifier.height(12.dp))
                    Story("1v1", "Challenge a Friend", "Same prompts, head to head, online or on Wi-Fi.") { open("duel") }
                }
                Box(Modifier.padding(horizontal = 12.dp).width(1.dp).fillMaxHeight().background(C.line))
                Column(Modifier.weight(1f)) {
                    Story("Records", "Your Season So Far", if (History.games == 0) "No runs on file yet." else "${History.games} runs · best ${History.best} points.") { open("stats") }
                    Spacer(Modifier.height(12.dp))
                    Story("Archive", "Every List on File", "Browse each list and all of its answers.") { open("browse") }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Rule(2.dp)
        Spacer(Modifier.height(6.dp))
        Kicker("Classifieds", C.amber)
        Spacer(Modifier.height(8.dp))
        InkIn(360) {
            Row(Modifier.fillMaxWidth()) {
                Ad("THEME", Th.pal.name.lowercase().replaceFirstChar { it.uppercase() }, Modifier.weight(1f)) { open("theme") }
                Spacer(Modifier.width(8.dp))
                Ad("SETTINGS", "Sources & defaults", Modifier.weight(1f)) { open("settings") }
                Spacer(Modifier.width(8.dp))
                Ad("HOW TO", "Rules of the game", Modifier.weight(1f)) { open("manual") }
            }
        }
        Spacer(Modifier.height(18.dp))
        Panel("Press room") { LibraryFooter(model) }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun Story(kicker: String, head: String, body: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth().clickable(src, null, onClick = onClick)) {
        Kicker(kicker)
        Spacer(Modifier.height(2.dp))
        TText(head, size = 17.sp, weight = FontWeight.Bold, color = C.amber)
        Spacer(Modifier.height(3.dp))
        TText(body, size = 12.sp, color = C.dim)
        TText("Continued →", Modifier.padding(top = 4.dp), size = 11.sp, color = C.hot, decoration = TextDecoration.Underline)
    }
}

@Composable
private fun Ad(head: String, body: String, modifier: Modifier, onClick: () -> Unit) {
    val ink = C.amber
    Column(
        modifier.drawBehind {
            drawRect(ink, style = Stroke(1.dp.toPx()))
            val i = 3.dp.toPx()
            drawRect(ink.copy(alpha = 0.5f), Offset(i, i), Size(size.width - 2 * i, size.height - 2 * i), style = Stroke(0.6.dp.toPx()))
        }.clickable(remember { MutableInteractionSource() }, null, onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TText(head, size = 12.sp, weight = FontWeight.Bold, color = C.amber, spacing = 1.sp, align = TextAlign.Center)
        TText(body, size = 10.sp, color = C.dim, align = TextAlign.Center, maxLines = 2)
    }
}

// ======================================================================= play

@Composable
fun GazettePlay(model: AppModel, session: MatchSession, st: MatchState, a: AnswerState) {
    val now by rememberNow(st.startedAt, isRunning(st))
    val left = timeLeft(st, now)
    Column(Modifier.fillMaxSize()) {
        PlayHeader(model, session, st)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Kicker("Clue ${st.round + 1} of ${st.total}", C.amber)
                Spacer(Modifier.weight(1f))
                TText("Score  ", size = 11.sp, color = C.dim)
                RollingNumber(st.myTotal, size = 15.sp, color = C.hot)
            }
            if (session.isMulti) {
                Row(Modifier.fillMaxWidth()) {
                    TText("vs. ${st.oppName ?: "?"}" + if (st.oppLocked && st.phase != Phase.REVEAL) " — has answered" else "",
                        Modifier.weight(1f), size = 11.sp, color = C.dim)
                    TText("${st.oppTotal}", size = 11.sp, color = C.dim)
                }
            }
            Spacer(Modifier.height(6.dp))
            // ink line timer
            if (st.seconds > 0) {
                val f = left / (st.seconds * 1000f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(2.dp).background(C.ghost)) {
                        Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).fillMaxHeight().background(if (left < 5000) C.blood else C.amber))
                    }
                    TText("  ${(left + 999) / 1000} sec.", size = 12.sp, color = if (left < 5000) C.blood else C.dim)
                }
            } else TText("No deadline today.", size = 11.sp, color = C.dim)
            Spacer(Modifier.height(14.dp))

            if (st.phase == Phase.WAITING) {
                Row(verticalAlignment = Alignment.CenterVertically) { Spinner(); TText("  ${st.message ?: "Waiting for the wire…"}", size = 13.sp, color = C.dim) }
            } else if (st.prompt.isNotEmpty()) {
                key(st.round) {
                    InkIn(0) {
                        Column(Modifier.fillMaxWidth()) {
                            Rule(2.dp); Spacer(Modifier.height(2.dp)); Rule(0.7.dp)
                            Spacer(Modifier.height(10.dp))
                            Kicker("${st.topic} · ${group(st.listSize)} answers on file", C.dim)
                            Spacer(Modifier.height(6.dp))
                            TText(st.prompt, size = 25.sp, weight = FontWeight.Bold, color = C.amber)
                            Spacer(Modifier.height(10.dp))
                            Rule(0.7.dp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            when (st.phase) {
                Phase.TYPING -> if (!st.paused) {
                    TText("Your answer", size = 11.sp, color = C.dim)
                    AnswerField(a, st, Modifier.fillMaxWidth().shakeX(a), size = 20.sp)
                    val s = suggestionOf(st)
                    if (s != null) {
                        Spacer(Modifier.height(8.dp))
                        TText("Perhaps you meant “$s”?", Modifier.clickable(remember { MutableInteractionSource() }, null) { a.send(s) },
                            size = 14.sp, color = C.hot, decoration = TextDecoration.Underline)
                    }
                    if (st.attempts.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Pencil(st)
                    }
                    Spacer(Modifier.height(14.dp))
                    TButton("Submit answer", { a.send() }, Modifier.fillMaxWidth(), enabled = a.text.isNotBlank(), primary = true)
                    Spacer(Modifier.height(8.dp))
                    TText("Wrong guesses cost nothing but time. Rare answers make the front page.", size = 11.sp, color = C.dim)
                }
                Phase.LOCKED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(C.hot); TText("  Filed. Waiting for the other correspondent…", size = 13.sp, color = C.dim)
                }
                Phase.REVEAL -> st.reveal?.let { v ->
                    Box(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Kicker("Our verdict", C.dim)
                            Spacer(Modifier.height(4.dp))
                            TText(headline(v.me), size = 28.sp, weight = FontWeight.Bold, color = tierColor(v.me.tier))
                            Spacer(Modifier.height(4.dp))
                            TText(tierLine(v.me.tier) + if (v.me.points > 0) " — ${v.me.points} points." else ".", size = 14.sp, color = C.dim)
                            if (v.me.tier == Tier.SEGFAULT) TText("You wrote: “${v.me.input}”", size = 12.sp, color = C.dim)
                        }
                        Stamp(v.me.tier, v.index, Modifier.align(Alignment.TopEnd).padding(top = 4.dp))
                    }
                    RevealExtras(v, session.isMulti)
                    Spacer(Modifier.height(10.dp))
                    DiscardAction(model, session, v.index)
                }
                Phase.ERROR -> Panel("Stop the presses", color = C.blood, titleColor = C.blood) { TText(st.message ?: "unknown fault", size = 14.sp, color = C.blood) }
                else -> Unit
            }
        }
        PlayActions(model, session, st, Modifier.padding(16.dp))
    }
}

/** Misses written in pencil and struck through. */
@Composable
private fun Pencil(st: MatchState) {
    Column {
        TText("Struck out:", size = 11.sp, color = C.dim)
        TText(
            st.attempts.joinToString("   ") { if (it.status == AttemptStatus.VERIFYING) "${it.text} (checking…)" else it.text },
            size = 15.sp, color = C.dim.copy(alpha = 0.85f), decoration = TextDecoration.LineThrough,
        )
        if (st.verifying) Row(verticalAlignment = Alignment.CenterVertically) { Spinner(C.dim, 11.sp); TText("  checking the records…", size = 11.sp, color = C.dim) }
    }
}

/** Rubber stamp: slams down from 1.8× with a tilt, double border, slightly faded ink. */
@Composable
private fun Stamp(tier: Tier, round: Int, modifier: Modifier = Modifier) {
    val motion = !LocalLook.current.reduceMotion
    val p = remember(round) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(round) { delay(350); p.animateTo(1f, tween(260, easing = FastOutLinearInEasing)) }
    val c = if (tier.isValid) (if (tier.heat >= 4) C.hot else C.amber) else C.blood
    val word = when {
        tier == Tier.TIMEOUT -> "LATE"
        !tier.isValid -> "REJECTED"
        tier.heat >= 5 -> "EXCLUSIVE"
        else -> "ACCEPTED"
    }
    TText(
        word,
        modifier.graphicsLayer {
            val s = 1.8f - 0.8f * p.value
            scaleX = s; scaleY = s
            rotationZ = -8f
            alpha = 0.85f * p.value
        }.drawBehind {
            val w = 2.5.dp.toPx()
            drawRect(c, style = Stroke(w))
            val i = 4.dp.toPx()
            drawRect(c, Offset(i, i), Size(size.width - 2 * i, size.height - 2 * i), style = Stroke(1.dp.toPx()))
        }.padding(horizontal = 12.dp, vertical = 8.dp),
        size = 15.sp, color = c, weight = FontWeight.Bold, spacing = 3.sp,
    )
}

// ======================================================================= summary

@Composable
fun GazetteSummary(model: AppModel, session: MatchSession) {
    val st by session.state.collectAsState()
    val t = totalsOf(st)
    val motion = !LocalLook.current.reduceMotion
    val wiggle = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (!motion) return@LaunchedEffect
        delay(300)
        repeat(3) { wiggle.animateTo(-3f, tween(70)); wiggle.animateTo(3f, tween(70)) }
        wiggle.animateTo(0f, tween(70))
    }
    val opp = st.oppName
    Frame(model, "Results", onBack = { model.home() }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(14.dp))
            TText("EXTRA! EXTRA!", Modifier.graphicsLayer { rotationZ = wiggle.value }, size = 15.sp, color = C.blood,
                weight = FontWeight.Bold, spacing = 3.sp)
            Spacer(Modifier.height(4.dp))
            InkIn(150) {
                TText(
                    when {
                        session.isMulti && opp != null && t.my > st.oppTotal -> "${st.myName} Beats $opp, ${t.my} to ${st.oppTotal}"
                        session.isMulti && opp != null && t.my < st.oppTotal -> "$opp Edges Out ${st.myName}, ${st.oppTotal} to ${t.my}"
                        session.isMulti && opp != null -> "Dead Heat: ${st.myName} and $opp Tie on ${t.my}"
                        st.daily != null -> "Daily Run No. ${st.daily}: Local Player Scores ${t.my} Points"
                        else -> "Local Player Scores ${t.my} Points"
                    },
                    size = 29.sp, weight = FontWeight.Bold, color = C.amber,
                )
            }
            Spacer(Modifier.height(6.dp))
            InkIn(300) {
                TText("Rated “${t.rank.title.lowercase()}” by the editors: ${t.rank.line}", size = 14.sp, color = C.dim)
            }
            Spacer(Modifier.height(6.dp))
            TText("By our correspondent · ${t.count} clues · ${t.valid} accepted · average ${oneDecimal(t.avg)}", size = 11.sp, color = C.dim)
            Spacer(Modifier.height(12.dp))
            Rule(2.dp)
            Spacer(Modifier.height(8.dp))
            Kicker("The results", C.amber)
            Spacer(Modifier.height(6.dp))
            for (r in st.rounds) GazetteRow(model, r)
            Spacer(Modifier.height(14.dp))
            Rule()
            Spacer(Modifier.height(8.dp))
            Kicker("Box score", C.amber)
            Spacer(Modifier.height(4.dp))
            for (tier in Tier.entries.reversed()) {
                val n = st.rounds.count { it.me.tier == tier }
                if (n == 0) continue
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    TText(tier.label, size = 12.sp, color = tierColor(tier))
                    DottedLeader(Modifier.weight(1f).padding(horizontal = 6.dp))
                    TText("$n", size = 12.sp, color = C.amber, weight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(10.dp))
            TLink("Send this edition to a friend", { shareText(shareCard(st, t.rank)) }, color = C.hot)
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            if (!session.isMulti) {
                TButton("Print another", { model.replay(session) }, Modifier.weight(1f), primary = true)
                Spacer(Modifier.width(10.dp))
            }
            TButton("Front page", { model.home() }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun GazetteRow(model: AppModel, r: RoundView) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { open = !open }.padding(vertical = 6.dp)) {
        TText("${r.index + 1}. ${r.prompt}", size = 12.sp, color = C.dim, maxLines = 2)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            TText(answerText(r.me.tier, r.me.canonical, r.me.input), size = 15.sp, color = tierColor(r.me.tier), weight = FontWeight.Bold, maxLines = 1)
            DottedLeader(Modifier.weight(1f).padding(horizontal = 6.dp))
            TText(if (r.me.points > 0) "+${r.me.points}" else "—", size = 14.sp, color = C.amber, weight = FontWeight.Bold)
        }
        r.opp?.let { o ->
            TText("${o.player}: ${answerText(o.tier, o.canonical, o.input)} (+${o.points})", size = 11.sp, color = C.dim, maxLines = 1)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { RoundDetails(model, r) }
    }
}
