package app.coredump.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.game.MatchSession
import app.coredump.game.MatchState
import app.coredump.game.Phase
import app.coredump.ui.C
import app.coredump.ui.TText
import app.coredump.ui.rememberHaptic

// Small helpers shared by the newer archetypes (ABYSS, METRO, JACKPOT, CHAT, DESKTOP, ORACLE).

/** The clock can be stopped right now (solo, while typing). */
fun canPauseNow(st: MatchState): Boolean = st.canPause && st.phase == Phase.TYPING && !st.paused

/** Pause while typing; otherwise ask to quit the run. */
fun pauseOrQuit(model: AppModel, session: MatchSession, st: MatchState) {
    if (canPauseNow(st)) session.pause() else { session.pause(); model.confirmQuit = true }
}

/** "‖" while the run can be paused, else "✕"; tapping it does [pauseOrQuit]. */
@Composable
fun PauseQuit(model: AppModel, session: MatchSession, st: MatchState, color: Color = C.amber, size: TextUnit = 18.sp,
              pause: String = "‖", quit: String = "✕") {
    val haptic = rememberHaptic()
    TText(if (canPauseNow(st)) pause else quit,
        Modifier.clickable(remember { MutableInteractionSource() }, null) { haptic(false); pauseOrQuit(model, session, st) }.padding(10.dp),
        size = size, color = color, weight = FontWeight.Bold)
}

/** Roman numeral for 1..3999 (ORACLE card numbers). */
fun roman(n: Int): String {
    if (n <= 0) return "0"
    val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
    val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
    var x = n.coerceAtMost(3999)
    val sb = StringBuilder()
    for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
    return sb.toString()
}

/** Daily item label rebuilt with another word for "daily run" (keeps "#N" and "✓"). */
fun dailyLabel(label: String, word: String): String {
    val tail = label.substringAfter('#', "")
    return if (tail.isEmpty()) word else "$word #$tail"
}
