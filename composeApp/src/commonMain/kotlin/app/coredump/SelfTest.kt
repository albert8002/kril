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

    /** Answers for me whenever I hold the core (a real entry nobody said yet), so the game moves on. */
    private fun meltAnswer(model: AppModel, st: app.coredump.game.MeltState): String? {
        val p = model.bank.all().firstOrNull { it.text == st.prompt } ?: return null
        val said = st.used.map { it.name }.toSet()
        return p.entries.firstOrNull { it.name !in said && it.name.length >= 4 }?.name
    }

    /**
     * Sigils: every look (both palette sets) draws a code; its pixels are read back and decoded as they are, and again
     * through a simulated phone camera (turned, tilted, shrunk, blurred, noisy, uneven light). Shots of the first set.
     */
    private suspend fun sigilChecks(model: AppModel) {
        model.home(); delay(400)
        val probe = app.coredump.ui.SigilProbe
        probe.armed = true
        val rnd = Random(5)
        var clean = 0; var cam = 0; var n = 0
        for ((i, look) in (plan + extra).withIndex()) {
            model.setTheme(look.first, look.second)
            val want = app.coredump.net.ShortCode.Decoded(app.coredump.net.JoinTicket.newRoom(rnd), rnd.nextInt(4), i % 2 == 1)
            probe.show = want
            delay(1100)
            val bmp = runCatching { probe.layer?.toImageBitmap() }.getOrNull()
            if (bmp == null || bmp.width < 50) { log("SIGIL ${look.first}/${look.second} no capture"); continue }
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h)
            bmp.readPixels(px)
            val lum = ByteArray(w * h) { k ->
                val c = px[k]
                val a = (c ushr 24) and 0xFF
                val l = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                ((l * a + 128 * (255 - a)) / 255).toByte()
            }
            n++
            val reader = app.coredump.net.SigilReader()
            val got = reader.read(lum, w, h)
            val ok1 = got == want
            if (ok1) clean++
            val (cl, cw, ch) = cameraView(lum, w, h, i)
            val reader2 = app.coredump.net.SigilReader()
            val got2 = reader2.read(cl, cw, ch)
            val ok2 = got2 == want
            if (ok2) cam++
            log("SIGIL ${look.first}/${look.second} ${w}x$h clean=${if (ok1) "ok" else "FAIL(${reader.trace})"} " +
                "camera=${if (ok2) "ok" else "FAIL(${reader2.trace})"}")
            if (i < plan.size) shot("sigil_${look.first.name.lowercase()}")
        }
        probe.show = null
        probe.armed = false
        log("SIGIL SUMMARY clean=$clean/$n camera=$cam/$n")
        model.setTheme(Style.TERMINAL, "amber")
        model.home(); delay(400)
    }

    /** What a phone camera might make of [src]: 480×360, turned, tilted, ~60 % of the height, blurred, noisy, glare. */
    private fun cameraView(src: ByteArray, w: Int, h: Int, seed: Int): Triple<ByteArray, Int, Int> {
        val r = Random(seed * 7 + 1)
        val ow = 480; val oh = 360
        val scale = 0.62 * oh / kotlin.math.min(w, h)
        val rot = r.nextDouble() * 2 * kotlin.math.PI
        val sx = 0.82 + 0.15 * r.nextDouble()
        val cr = kotlin.math.cos(rot); val sr = kotlin.math.sin(rot)
        val img = FloatArray(ow * oh)
        for (y in 0 until oh) for (x in 0 until ow) {
            val dx = x - ow / 2.0; val dy = y - oh / 2.0
            val rx = (cr * dx + sr * dy) / (scale * sx); val ry = (-sr * dx + cr * dy) / scale
            val fx = w / 2.0 + rx; val fy = h / 2.0 + ry
            img[y * ow + x] = if (fx < 0 || fy < 0 || fx >= w - 1 || fy >= h - 1) 128f else {
                val x0 = fx.toInt(); val y0 = fy.toInt(); val ax = (fx - x0).toFloat(); val ay = (fy - y0).toFloat()
                fun p(xx: Int, yy: Int) = (src[yy * w + xx].toInt() and 0xFF).toFloat()
                (p(x0, y0) * (1 - ax) + p(x0 + 1, y0) * ax) * (1 - ay) + (p(x0, y0 + 1) * (1 - ax) + p(x0 + 1, y0 + 1) * ax) * ay
            }
        }
        var cur = img
        repeat(2) {
            val tmp = FloatArray(ow * oh); val out = FloatArray(ow * oh)
            for (y in 0 until oh) for (x in 0 until ow) {
                var sum = 0f; var c = 0
                for (d in -1..1) { val xx = x + d; if (xx in 0 until ow) { sum += cur[y * ow + xx]; c++ } }
                tmp[y * ow + x] = sum / c
            }
            for (y in 0 until oh) for (x in 0 until ow) {
                var sum = 0f; var c = 0
                for (d in -1..1) { val yy = y + d; if (yy in 0 until oh) { sum += tmp[yy * ow + x]; c++ } }
                out[y * ow + x] = sum / c
            }
            cur = out
        }
        val glare = if (r.nextBoolean()) 25f else -25f
        val out = ByteArray(ow * oh) { k ->
            val x = k % ow
            val v = 128 + (cur[k] - 128) * 0.75f + glare * x / ow + (r.nextFloat() * 2 - 1) * 10f
            v.toInt().coerceIn(0, 255).toByte()
        }
        return Triple(out, ow, oh)
    }

    /** MELTDOWN: the setup, a game against bots (with shots in a few looks), then a party over the real relay. */
    private suspend fun meltdownChecks(model: AppModel) {
        for (style in listOf(Style.TERMINAL, Style.ABYSS, Style.COMIC, Style.ORACLE)) {
            model.setTheme(style, plan.first { it.first == style }.second)
            model.home(); delay(400)
            model.go(Screen.Meltdown); delay(1300)
            if (style == Style.TERMINAL) shot("meltdown_setup_terminal")
            model.startMeltBots(5, 1); delay(4500)
            val s = (model.screen as? Screen.Melt)?.session ?: run { log("MELT no session"); return }
            log("MELT ${style.name} phase=${s.state.value.phase} players=${s.state.value.players.size} prompt='${s.state.value.prompt}'")
            shot("meltdown_${style.name.lowercase()}")
            if (style == Style.TERMINAL) {
                // play on: answer whenever I hold the core, until a blast or 40 s
                var t = 0
                var answered = 0
                var blasts = 0
                var lastEvn = -1
                while (t < 40_000) {
                    val st = s.state.value
                    if (st.evn != lastEvn) { lastEvn = st.evn; if (st.event.isNotEmpty()) log("MELT event ${st.eventKind}: ${st.event}"); if (st.eventKind == "blast") blasts++ }
                    if (st.phase == app.coredump.game.MeltPhase.TURN && st.holder == s.myId) {
                        meltAnswer(model, st)?.let { s.submit(it); answered++ }
                    }
                    if (blasts > 0 && t > 20_000) break
                    if (st.phase == app.coredump.game.MeltPhase.OVER) break
                    delay(700); t += 700
                    if (t == 7_000) shot("meltdown_terminal_turns")
                }
                log("MELT played answered=$answered blasts=$blasts heat=${s.state.value.heat} round=${s.state.value.round}")
                shot("meltdown_terminal_late")
            }
            model.leaveMelt(s); delay(500)
        }

        // a party over the real relay: this device hosts, a second (headless) seat joins it with the code
        model.setTheme(Style.METRO, plan.first { it.first == Style.METRO }.second)
        model.home(); delay(300)
        model.go(Screen.Meltdown); delay(600)
        val host = model.openPartyHost()
        var w = 0
        while (!host.lobby.value.open && w < 30_000) { delay(250); w += 250 }
        log("PARTY host open=${host.lobby.value.open} status=${host.lobby.value.status}")
        val guest = app.coredump.game.PartyGuest(model.scope, "GUESTBOT", host.room, model.settings.roomBroker)
        guest.connect()
        w = 0
        while (host.lobby.value.players.size < 2 && w < 30_000) { delay(250); w += 250 }
        log("PARTY seated=${host.lobby.value.players.map { it.name }} guest=${guest.lobby.value.status}")
        if (host.lobby.value.players.size >= 2) {
            model.startParty(1, 0); delay(5000)
            val hs = (model.screen as? Screen.Melt)?.session
            log("PARTY host phase=${hs?.state?.value?.phase} guest phase=${guest.state.value.phase} guest players=${guest.state.value.players.size} prompt='${guest.state.value.prompt}'")
            shot("party_metro")
            // the guest answers when it holds the core; its state must follow the host's
            var t = 0
            var gAnswers = 0
            while (t < 20_000) {
                val gs = guest.state.value
                if (gs.phase == app.coredump.game.MeltPhase.TURN && gs.holder == guest.myId) { meltAnswer(model, gs)?.let { guest.submit(it); gAnswers++ } }
                val me = hs?.state?.value
                if (hs != null && me != null && me.phase == app.coredump.game.MeltPhase.TURN && me.holder == hs.myId) { meltAnswer(model, me)?.let { hs.submit(it) } }
                delay(800); t += 800
            }
            log("PARTY after guestAnswers=$gAnswers hostSeq=${hs?.state?.value?.seq} guestSeq=${guest.state.value.seq} used=${guest.state.value.used.size} event='${guest.state.value.event}'")
            guest.leave()
            if (hs != null) model.leaveMelt(hs)
        } else {
            guest.leave()
        }
        model.closeParty()
        model.home(); delay(500)
    }

    /**
     * ORACLE: a run that finishes a constellation and flies to the next (started just before the end of the first
     * one), slowed down, with shots before, during and after the flight.
     */
    private suspend fun oracleFlight(model: AppModel) {
        model.setTheme(Style.ORACLE, plan.first { it.first == Style.ORACLE }.second)
        model.home(); delay(600)
        app.coredump.ui.RunProbe.bias = (app.coredump.ui.Sky.complete(0) - 30f).toInt()
        app.coredump.ui.RunProbe.slow = 3f
        model.startSolo(); delay(1500)
        val s = (model.screen as? Screen.Play)?.session
        val e = s?.promptAt(0)?.entries?.lastOrNull()
        if (s != null && e != null) {
            delay(900)
            s.submit(e.name)
            log("RUN oracle flight from=${app.coredump.ui.RunProbe.bias} points=${s.state.value.reveal?.me?.points}")
            for ((name, wait) in listOf("oracle_fly_1" to 2200L, "oracle_fly_2" to 1500L, "oracle_fly_3" to 1100L, "oracle_fly_4" to 1300L, "oracle_fly_5" to 2200L)) {
                delay(wait)
                log("SHOT $name")
            }
            delay(3500)
        }
        app.coredump.ui.RunProbe.bias = 0
        app.coredump.ui.RunProbe.slow = 1f
        s?.let { model.abortRun(it) }
    }

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

            // score runs at a third of the speed, so the screenshot catches one in flight
            app.coredump.ui.RunProbe.slow = 3f
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
                            model.fx.sent(); delay(200)
                            shot("touch_${style.name.lowercase()}")
                            // the screenshot is taken a moment after the log line: keep the touches coming meanwhile
                            repeat(4) { model.fx.sent(); delay(330) }
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
                    if (round == 0) {
                        // the score run in flight (or arriving), then the reveal once it has gone
                        delay(400)
                        log("RUN ${style.name} points=${s.state.value.reveal?.me?.points}")
                        shot("run_${style.name.lowercase()}")
                        delay(4600)
                    } else delay(1300)
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
            app.coredump.ui.RunProbe.slow = 1f
            for ((style, pal) in extra) {
                model.setTheme(style, pal)
                model.home(); delay(1200)
                shot("menu_${style.name.lowercase()}_$pal")
            }
            // ADHD-fy: every look's menu with it on, and one score run with it on
            model.update { it.copy(hyper = Style.entries.map { st -> st.name }.toSet()) }
            for ((style, pal) in plan) {
                model.setTheme(style, pal)
                model.home(); delay(1500)
                shot("hyper_menu_${style.name.lowercase()}")
            }
            model.setTheme(Style.ABYSS, plan.first { it.first == Style.ABYSS }.second)
            model.startSolo(); delay(1500)
            (model.screen as? Screen.Play)?.session?.let { s ->
                delay(900)
                val p = s.promptAt(0)
                val e = p?.entries?.lastOrNull()
                if (e != null) {
                    s.submit(e.name); delay(700)
                    log("RUN hyper points=${s.state.value.reveal?.me?.points}")
                    shot("hyper_run_abyss")
                    delay(3000)
                }
                model.abortRun(s)
            }
            model.update { it.copy(hyper = emptySet()) }
            oracleFlight(model)
            model.setTheme(Style.TERMINAL, "amber")
            model.home(); delay(600)
            model.startDaily(); delay(2000)
            shot("daily_terminal")
            (model.screen as? Screen.Play)?.session?.let { model.abortRun(it) }

            // quit pop-ups in a few looks
            for (style in listOf(Style.PAPER, Style.CHAT, Style.DESKTOP, Style.METRO)) {
                model.setTheme(style, plan.first { it.first == style }.second)
                model.home(); delay(500)
                model.startSolo(); delay(1500)
                (model.screen as? Screen.Play)?.session?.let { s ->
                    s.pause(); model.confirmQuit = true; delay(900)
                    shot("quit_${style.name.lowercase()}")
                    model.abortRun(s)
                }
            }

            // friends: add one, then the FRIENDS tab of the 1v1 screen
            model.setTheme(Style.TERMINAL, "amber")
            model.addFriend("ALEX", "K7Q2-M9XA")
            app.coredump.ui.screens.LobbyProbe.tab = 2
            model.home(); delay(400)
            model.go(Screen.Lobby); delay(1500)
            shot("friends_terminal")
            app.coredump.ui.screens.LobbyProbe.tab = 0
            model.back(); delay(600)

            meltdownChecks(model)
            sigilChecks(model)

            // invites over the real relay: one to my own inbox from a made-up code; pop-up, decline, the sender hears it
            model.setTheme(Style.PAPER, plan.first { it.first == Style.PAPER }.second)
            model.home(); delay(500)
            val inviter = app.coredump.game.Inviter(model.scope, "ME", model.myRoom(), "TESTBOT", app.coredump.net.JoinTicket.newRoom(),
                model.settings.roomBroker, "duel")
            inviter.send()
            var iw = 0
            while (model.invites.isEmpty() && iw < 30_000) { delay(250); iw += 250 }
            log("INVITE popup=${model.invites.size} status=${inviter.status.value} after ${iw}ms")
            delay(900)
            shot("invite_popup_paper")
            model.invites.firstOrNull()?.let { model.declineInvite(it) }
            iw = 0
            while (inviter.status.value != app.coredump.game.Inviter.Status.DECLINED && iw < 12_000) { delay(250); iw += 250 }
            log("INVITE answer status=${inviter.status.value} after ${iw}ms")
            inviter.cancel()

            // friend request by code only: pop-up, accept, the sender gets my name back
            model.setTheme(Style.CHAT, plan.first { it.first == Style.CHAT }.second)
            model.home(); delay(400)
            val fakeCode = app.coredump.net.JoinTicket.newRoom()
            val req = app.coredump.game.Inviter(model.scope, "ME", model.myRoom(), "TESTFRIEND", fakeCode, model.settings.roomBroker,
                "friend", kind = "friend", myElo = 1111, myStreak = 3)
            req.send()
            iw = 0
            while (model.friendPops.isEmpty() && iw < 30_000) { delay(250); iw += 250 }
            log("FRIEND popup=${model.friendPops.size} requests=${model.settings.requests.size} after ${iw}ms")
            delay(900)
            shot("friend_request_chat")
            model.acceptFriend(fakeCode)
            iw = 0
            while (req.status.value != app.coredump.game.Inviter.Status.ACCEPTED && iw < 12_000) { delay(250); iw += 250 }
            log("FRIEND answer status=${req.status.value} name='${req.replyName}' friends=${model.settings.friends.map { it.name + ":" + it.elo }}")
            req.cancel()
            app.coredump.ui.screens.LobbyProbe.tab = 2
            model.go(Screen.Lobby); delay(1500)
            shot("friends_chat")
            model.back(); delay(500)

            // ranked: two searchers on the real relay find each other (one hosts, one joins)
            var m1: app.coredump.game.RankedMatch? = null
            var m2: app.coredump.game.RankedMatch? = null
            val mmA = app.coredump.game.Matchmaker(model.scope, "TEST-A", app.coredump.net.JoinTicket.newRoom(), 0, 1000) { m1 = it }
            val mmB = app.coredump.game.Matchmaker(model.scope, "TEST-B", app.coredump.net.JoinTicket.newRoom(), 0, 1040) { m2 = it }
            mmA.start(); mmB.start()
            iw = 0
            while ((m1 == null || m2 == null) && iw < 40_000) { delay(500); iw += 500 }
            log("RANKED a=${m1?.let { "${if (it.host) "host" else "guest"} vs ${it.oppName}" }} b=${m2?.let { "${if (it.host) "host" else "guest"} vs ${it.oppName}" }} after ${iw}ms")
            mmA.cancel(); mmB.cancel()
            app.coredump.ui.screens.LobbyProbe.tab = 3
            model.go(Screen.Lobby); delay(1500)
            shot("ranked_chat")
            model.back(); delay(500)

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
                    is app.coredump.data.AnswerCheck.Outcome.Found -> "FOUND(${r.label}, ${r.links}) " +
                        (r.fame?.let { f -> "${app.coredump.core.FameScore.tier(f, p.topic)} · ${app.coredump.core.FameScore.note(f, p.topic)}" } ?: "no fame")
                    app.coredump.data.AnswerCheck.Outcome.Missing -> "MISSING"
                    is app.coredump.data.AnswerCheck.Outcome.Near -> "NEAR(${r.names})"
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
            // the window was rebuilt (e.g. a configuration change while starting): the new screen starts the test
            // again, so this run just ends quietly
            if (e is kotlinx.coroutines.CancellationException) { log("RESTART ${e::class.simpleName}"); throw e }
            CrashLog.note("selftest", e)
            log("FAIL ${e::class.simpleName}: ${e.message}")
            log("DONE fail")
        }
    }
}
