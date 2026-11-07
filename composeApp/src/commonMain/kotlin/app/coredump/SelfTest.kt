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

    /** First word (4+ letters) of a multi-word answer that is not an answer by itself: should get "did you mean" choices. */
    private fun wordProbe(p: app.coredump.core.Prompt): String? = p.entries.asSequence()
        .filter { it.name.trim().contains(' ') }
        .map { it.name.trim().substringBefore(' ').filter { c -> c.isLetter() } }
        .filter { it.length >= 4 }
        .firstOrNull { !Judge.judge(p, it, 2).tier.isValid }

    private const val ICON_STAR = "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAABbklEQVR42u1bQRKDMAgsDH/y/8e+Kr3YjtNaJ0GEjcDJm2HZ3QCjj0dFRWi01lrk+zl7ATi8+k8KZUExILr6qSXwiUAZcObqY3lAEAvKBDPTH48BATLgzNUvD4AEwFkGnJn+uBJwZAFnrn6ZoAcA6uo7yUDQV1m97yEilcZInRSappf+o2/BopkMywKkb6bwLjpLS5H8LgN+5HAHNvxJ/hCAW0hiZfKRQdLsjYym6sN9wHS+0Jn8UCM0DQgDyQ93gvAgDCavaoVhQVAk322C8NekMvlTAEDcEB3X3OUAhIFwourm47C7Lxglb7oPcAPBMPnaCE0JgPGmiDMsPksCBcDdADD0Ac6s/5IABADBk6Ug7OtUk+XqA2e7QnHX/84E936O8BJBmuDUbIAHYGBu92aDwK6qethg4ANymf4ttjVbNlwkC0FcU3nKQpCXFR4mKahV72bDSR8giy83PBKHO0P0316oZ6moqKioqJgoXrcTBskTfQ3FAAAAAElFTkSuQmCC"
    private const val ICON_RING = "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAABh0lEQVR42u1b2xHDIAzDui7TWTpvZ+k46Vfv+tGkPGxhEuk7B5YwxDFKKYIgCMJ1YczJtm3bqgMzs1MI0EJ6hhiWlTRLDGMTvz//j/N68ISwaPI1hHsF8RDBosh7EK8RYlQEW4F4pBBYjfzePL0Hr3mQZxGvyYbWTMDK5H/N35oJ8E7FDCKECfCtbhbyv+JpyQJkqO5mVqNYcd97ngdYdd97xYezpX5r/Dbj4Dv62ImY46g2uM0sWo6eYW01sFa/hvzI872vRWRZ+QgRwitBBoloERB5+nsF7zHOHh+c4d0/Ejeypj5rK6BcHBJAAkgACSABsvXpmLUIstTks2oRRF4+eq2axzh7fJC5Zc0ow5G5b8/4BkFt2ng2KBjkU7bEPqQYPUG3y9FV7gR6L0yRxa01y1OEjH06Zt8APSpmFaHHK4BsxkW2nQ69g2bLgtrXnmshlEWEkTgwmlqzRRj1CNmKLjFPqxw8DxlWNnj6BOUULfIKF7nFi/4XKPpjpFz0nyFBEIRL4w0H09ZeNGJgFQAAAABJRU5ErkJggg=="

    private fun typo(s: String): String = if (s.length < 6) s else s.substring(0, 2) + s.substring(3)

    private val plan = listOf(
        Style.TERMINAL to "amber", Style.PAPER to "newsprint", Style.NEON to "synthwave",
        Style.CLEAN to "light", Style.PIXEL to "dmg", Style.COMIC to "rebel",
        Style.ABYSS to "trench", Style.METRO to "board", Style.JACKPOT to "felt",
        Style.CHAT to "daylight", Style.DESKTOP to "classic", Style.ORACLE to "midnight",
    )
    private val extra = listOf(
        Style.TERMINAL to "green", Style.PAPER to "night", Style.NEON to "outrun", Style.CLEAN to "dark", Style.PIXEL to "color", Style.COMIC to "pop",
        Style.ABYSS to "jelly", Style.METRO to "platform", Style.JACKPOT to "velvet", Style.CHAT to "midnight", Style.DESKTOP to "storm",
        Style.ORACLE to "parchment",
    )

    suspend fun run(model: AppModel) {
        try {
            log("START")
            var waited = 0
            while ((!model.bank.state.value.ready || model.screen == Screen.Boot) && waited < 90_000) { delay(250); waited += 250 }
            log("READY prompts=${model.bank.state.value.prompts} answers=${model.bank.state.value.answers}")
            model.update { it.copy(rounds = 3, seconds = 40) }

            // own tier / rank looks: names, colours and animated icons (visible in every theme's shots)
            val star = app.coredump.ui.B64.decode(ICON_STAR)
            val ring = app.coredump.ui.B64.decode(ICON_RING)
            val okStar = star != null && app.coredump.ui.CustomLooks.store("T:HELLO_WORLD", star)
            val okRing = ring != null && app.coredump.ui.CustomLooks.store("R:INTERN", ring)
            model.update {
                it.copy(custom = mapOf(
                    "T:HELLO_WORLD" to app.coredump.core.Custom(name = "BASIC", color = 0xFFE53935, icon = okStar, anim = "SPIN"),
                    "T:ZERO_DAY" to app.coredump.core.Custom(name = "GOAT", color = 0xFF00A86B),
                    "R:INTERN" to app.coredump.core.Custom(name = "ROOKIE", icon = okRing, anim = "PULSE"),
                ))
            }
            log("CUSTOM star=$okStar ring=$okRing label=${app.coredump.core.Tier.HELLO_WORLD.label} rank=${app.coredump.core.Rank.INTERN.title}")

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
                        if (round == 0) {
                            // one word of a longer answer: the "did you mean" choices list the answers containing it
                            wordProbe(p)?.let { w ->
                                s.submit(w); delay(450)
                                log("SUGGEST '$w' -> ${s.state.value.attempts.lastOrNull { it.suggestions.isNotEmpty() }?.suggestions}")
                            }
                            shot("play_${style.name.lowercase()}")
                            // the theme's typing / sending touches around the answer field
                            repeat(3) { model.fx.key(); delay(70) }
                            model.fx.sent(); delay(200)
                            shot("touch_${style.name.lowercase()}")
                            // the screenshot is taken a moment after the log line: keep the touches coming meanwhile
                            repeat(12) { model.fx.key(); if (it % 3 == 0) model.fx.sent(); delay(110) }
                        }
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
                    if (round == 0) {
                        delay(300); shot("reveal_${style.name.lowercase()}")
                        val v = s.state.value.reveal
                        log("ANSWERS ${style.name} groups=${v?.all?.size} names=${v?.all?.sumOf { it.names.size }} list=${v?.listSize}")
                        app.coredump.ui.screens.RevealProbe.ping++; delay(900)
                        shot("answers_${style.name.lowercase()}")
                    }
                    s.next()
                }
                delay(2500)
                shot("summary_${style.name.lowercase()}")
                model.go(Screen.Appearance); delay(1200)
                shot("appearance_${style.name.lowercase()}")
                model.go(Screen.Browse); delay(1000)
                model.go(Screen.Config); delay(1000)
                shot("config_${style.name.lowercase()}")
                model.go(Screen.Fetch); delay(1000)
                shot("fetch_${style.name.lowercase()}")
                model.go(Screen.Looks); delay(1200)
                shot("looks_${style.name.lowercase()}")
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

            // answer search on the device (real network): an alias, a typo-free unlisted member, a non-member
            for ((want, input) in listOf("Name a dog breed" to "Labrador", "Name a dog breed" to "Kai Ken", "Name a dog breed" to "banana",
                    "Name a cheese" to "Tete de Moine", "Name a cheese" to "toaster")) {
                val p = model.bank.all().firstOrNull { it.text == want } ?: continue
                val t0 = kotlin.time.TimeSource.Monotonic.markNow()
                val local = Judge.judge(p, input, 2)
                val out = if (local.tier.isValid) "LOCAL(${local.entry?.name})" else when (val r = model.bank.verify(p, input)) {
                    is app.coredump.data.AnswerCheck.Outcome.Listed -> "LISTED(${r.name})"
                    is app.coredump.data.AnswerCheck.Outcome.Found -> "FOUND(${r.label}, ${r.links})"
                    app.coredump.data.AnswerCheck.Outcome.Missing -> "MISSING"
                    app.coredump.data.AnswerCheck.Outcome.Unreachable -> "UNREACHABLE"
                }
                log("VERIFY '${p.text}' '$input' -> $out (${t0.elapsedNow().inWholeMilliseconds}ms)")
            }

            // one small collection batch for chosen categories (network), with the fetch screen open
            model.setTheme(Style.TERMINAL, "amber")
            model.go(Screen.Fetch); delay(600)
            model.bank.requestMore(4, setOf("TV", "GAMING"))
            var w = 0
            delay(2500)
            shot("fetch_running")
            while (model.bank.state.value.collect.running && w < 150_000) { delay(1000); w += 1000 }
            val c = model.bank.state.value.collect
            log("COLLECT added=${c.added} library=${c.library} failed=${c.failed} topics=${c.topics} msg=${c.message}")
            delay(800)
            shot("fetch_done")

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
