package app.coredump.ui

import app.coredump.platform.SoundOut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Background music: the 7/4 theme ([Score]) in the active look's arrangement, looping under everything.
 * Rendered off the main thread the first time a look is used (about 30–40 s of audio), then kept.
 * Called from the main thread only.
 */
object Music {
    private var on = true
    private var volume = 0.5f
    private var style: Style? = null
    private val loops = LinkedHashMap<Style, ShortArray>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var rendering: Style? = null
    private var duckJob: Job? = null

    /** The song set to play (its key is the effects' key), null before the first [configure]. */
    val song: Style? get() = style

    /** Music level relative to the effects (the effects stay on top). */
    private fun gain() = volume * 0.45f

    fun configure(enabled: Boolean, vol: Float, theme: Style) {
        val changed = enabled != on || theme != style || kotlin.math.abs(vol - volume) > 0.001f
        on = enabled
        volume = vol.coerceIn(0f, 1f)
        style = theme
        if (changed) apply()
    }

    /**
     * Big effects (a rare answer, the final rank, lists fetched) sit on top: the music dips to 40 % for [seconds],
     * then comes back. Android glides between levels; iOS fades.
     */
    fun duck(seconds: Float) {
        val s = style ?: return
        val pcm = loops[s] ?: return
        if (!on || volume <= 0.01f) return
        runCatching { SoundOut.music(pcm, gain() * 0.4f) }
        duckJob?.cancel()
        duckJob = scope.launch {
            delay((seconds.coerceIn(0.2f, 4f) * 1000).toLong())
            withContext(Dispatchers.Main) {
                val now = style
                val p = if (now != null) loops[now] else null
                if (on && volume > 0.01f && p != null) runCatching { SoundOut.music(p, gain()) }
            }
        }
    }

    private fun apply() {
        val s = style
        if (!on || volume <= 0.01f || s == null) {
            job?.cancel(); rendering = null
            runCatching { SoundOut.music(null, 0f) }
            return
        }
        loops[s]?.let { pcm -> runCatching { SoundOut.music(pcm, gain()) }; return }
        if (rendering == s) return
        job?.cancel()
        rendering = s
        job = scope.launch {
            val pcm = runCatching { Score.render(s) { !isActive } }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@launch
            withContext(Dispatchers.Main) {
                if (rendering == s) rendering = null
                loops[s] = pcm
                // keep two arrangements at most (each is a few MB)
                while (loops.size > 2) loops.remove(loops.keys.first())
                if (style == s && on && volume > 0.01f) runCatching { SoundOut.music(pcm, gain()) }
            }
        }
    }
}
