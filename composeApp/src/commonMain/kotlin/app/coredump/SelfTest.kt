package app.coredump

import app.coredump.core.Judge
import app.coredump.game.Phase
import app.coredump.platform.CrashLog
import app.coredump.ui.Style
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Automated walk-through used by CI on an emulator / simulator (never in normal use):
 * every theme → menu, a 3-prompt solo run with wrong / misspelled / right answers (incl. online checks),
 * summary, appearance, browse, config; then an index stress test and one collection batch.
 * Prints "SELFTEST SHOT <name>" before pausing, so the test script can take a screenshot.
 * Enabled by an intent extra (Android) or launch argument (iOS).
 */
object SelfTest {
    var enabled = false

    private fun log(s: String) = println("SELFTEST $s")

    private suspend fun shot(name: String) {
        log("SHOT $name")
        delay(1800)
    }

    private fun typo(s: String): String = if (s.length < 6) s else s.substring(0, 2) + s.substring(3)

    private val plan = listOf(
        Style.TERMINAL to "amber", Style.PAPER to "newsprint", Style.NEON to "synthwave",
        Style.CLEAN to "light", Style.PIXEL to "dmg", Style.COMIC to "rebel",
    )
    private val extra = listOf(
        Style.TERMINAL to "green", Style.PAPER to "night", Style.NEON to "outrun", Style.CLEAN to "dark", Style.PIXEL to "color", Style.COMIC to "pop",
    )

    suspend fun run(model: AppModel) {
        try {
            log("START")
            var waited = 0
            while ((!model.bank.state.value.ready || model.screen == Screen.Boot) && waited < 90_000) { delay(250); waited += 250 }
            log("READY prompts=${model.bank.state.value.prompts} answers=${model.bank.state.value.answers}")
            model.update { it.copy(rounds = 3, seconds = 40) }

            for ((style, pal) in plan) {
                model.setTheme(style, pal)
                model.home(); delay(1200)
                shot("menu_${style.name.lowercase()}")
                model.startSolo(); delay(1500)
                val s = (model.screen as? Screen.Play)?.session
                if (s == null) { log("no session"); continue }
                for (round in 0 until 3) {
                    delay(900)
                    val p = s.promptAt(round) ?: break
                    s.submit("asdfgh")                                 // key-mashing -> penalty
                    s.submit("zzqx nothing ${Random.nextInt(999)}")   // miss -> online check
                    delay(700)
                    if (round == 1) {
                        s.skip()
                    } else {
                        val e = p.entries.firstOrNull { it.name.length >= 7 } ?: p.entries[Random.nextInt(p.entries.size)]
                        if (round == 0) shot("play_${style.name.lowercase()}")
                        s.submit(typo(e.name))
                        delay(500)
                        if (s.state.value.confirm != null) {
                            if (round == 0) shot("confirm_${style.name.lowercase()}")
                            s.confirm(true)
                        }
                        if (s.state.value.phase == Phase.TYPING) s.submit(e.name)
                    }
                    var w = 0
                    while (s.state.value.phase == Phase.TYPING && w < 8000) { delay(200); w += 200 }
                    delay(1300)
                    if (round == 0) { delay(300); shot("reveal_${style.name.lowercase()}") }
                    s.next()
                }
                delay(2500)
                shot("summary_${style.name.lowercase()}")
                model.go(Screen.Appearance); delay(1200)
                shot("appearance_${style.name.lowercase()}")
                model.go(Screen.Browse); delay(1000)
                model.go(Screen.Config); delay(1000)
                shot("config_${style.name.lowercase()}")
            }
            for ((style, pal) in extra) {
                model.setTheme(style, pal)
                model.home(); delay(1200)
                shot("menu_${style.name.lowercase()}_$pal")
            }
            model.setTheme(Style.TERMINAL, "amber")
            model.home(); delay(600)
            model.startDaily(); delay(2000)
            shot("daily_terminal")
            (model.screen as? Screen.Play)?.session?.let { model.abortRun(it) }

            // stress: lookups across many lists (index cache must keep memory flat)
            val all = model.bank.all()
            if (all.isNotEmpty()) {
                repeat(600) { i ->
                    val p = all[Random.nextInt(all.size)]
                    if (p.entries.isNotEmpty()) {
                        val e = p.entries[Random.nextInt(p.entries.size)]
                        Judge.judge(p, typo(e.name), 2)
                        p.index.suggest(e.name + "x")
                    }
                    if (i % 100 == 0) { log("stress $i"); delay(20) }
                }
            }
            log("stress done")

            // one small collection batch (network)
            model.bank.requestMore(4)
            var w = 0
            delay(1000)
            while (model.bank.state.value.collect.running && w < 150_000) { delay(1000); w += 1000 }
            val c = model.bank.state.value.collect
            log("COLLECT added=${c.added} library=${c.library} msg=${c.message}")

            // discard: gone from the bank, back after undo (built-in list, then a collected one if any)
            for (id in listOfNotNull(model.bank.all().firstOrNull { it.source == app.coredump.core.SourceKind.BUNDLED }?.id,
                    model.bank.all().firstOrNull { it.id.startsWith("wx.") }?.id)) {
                model.discard(id); delay(1500)
                val gone = model.bank.all().none { it.id == id } && id in app.coredump.data.Discards.ids
                model.undoDiscard(id); delay(1500)
                val back = model.bank.all().any { it.id == id } && id !in app.coredump.data.Discards.ids
                log("DISCARD $id gone=$gone back=$back")
                if (!gone || !back) throw IllegalStateException("discard check failed for $id")
            }
            model.home(); delay(800)
            shot("menu_after_collect")
            log("DONE ok")
        } catch (e: Throwable) {
            CrashLog.note("selftest", e)
            log("FAIL ${e::class.simpleName}: ${e.message}")
            log("DONE fail")
        }
    }
}
