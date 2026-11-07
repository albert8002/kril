package app.coredump.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import app.coredump.core.Custom
import app.coredump.core.IconAnim
import app.coredump.core.Names
import app.coredump.core.Rank
import app.coredump.core.Tier
import app.coredump.platform.Platform
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The player's own colours and icons for tiers and ranks (names live in [Names]).
 * Icons are small PNGs (longest side ≤ [ICON_PX] px) kept as base64 text files, decoded once into [icons].
 */
object CustomLooks {
    const val ICON_PX = 192

    /** Bumped on every change: colour and icon readers depend on it, so badges redraw at once. */
    private val version = mutableIntStateOf(0)
    val icons = mutableStateMapOf<String, ImageBitmap>()

    /** Colours offered in the editor (ARGB). */
    val swatches: List<Long> = listOf(
        0xFFE53935, 0xFFFF7043, 0xFFFFB300, 0xFFFDD835, 0xFF7CB342, 0xFF00A86B, 0xFF26C6DA, 0xFF1E88E5,
        0xFF3949AB, 0xFF8E24AA, 0xFFD81B60, 0xFFFF4FD8, 0xFF8D6E63, 0xFF90A4AE, 0xFFFFFFFF, 0xFF212121,
    )

    private fun fileOf(key: String) = "icon_" + key.replace(':', '_') + ".b64"

    /** After the settings changed: redraw, load icons not decoded yet, drop removed ones. */
    fun changed(custom: Map<String, Custom>) {
        for (k in icons.keys.toList()) if (custom[k]?.icon != true) icons.remove(k)
        for ((k, c) in custom) if (c.icon && k !in icons) read(k)?.let { icons[k] = it }
        version.intValue++
    }

    /** Own colour of [key] (null = theme default). Reading it inside composition redraws on change. */
    fun color(key: String): Color? {
        version.intValue
        return Names.custom[key]?.color?.let { Color(it) }
    }

    fun argb(c: Color): Long = c.toArgb().toLong() and 0xFFFFFFFFL

    @OptIn(ExperimentalResourceApi::class)
    private fun decode(png: ByteArray): ImageBitmap? = runCatching { png.decodeToImageBitmap() }.getOrNull()

    private fun read(key: String): ImageBitmap? =
        Platform.readText(fileOf(key))?.let { B64.decode(it) }?.let { decode(it) }

    /** Stores an icon for [key]; false when the bytes are not a readable image. */
    fun store(key: String, png: ByteArray): Boolean {
        val bmp = decode(png) ?: return false
        runCatching { Platform.writeText(fileOf(key), B64.encode(png)) }.onFailure { return false }
        icons[key] = bmp
        version.intValue++
        return true
    }

    fun remove(key: String) {
        runCatching { Platform.delete(fileOf(key)) }
        icons.remove(key)
        version.intValue++
    }

    /** Gradient for a custom colour: dark, base, light. */
    fun gradient(c: Color): List<Color> = listOf(lerp(c, Color.Black, 0.45f), c, lerp(c, Color.White, 0.55f))

    /** The colour as text on dark / light panels. */
    fun onDark(c: Color): Color = lerp(c, Color.White, 0.3f)
    fun onLight(c: Color): Color = lerp(c, Color.Black, 0.35f)
}

fun customKey(t: Tier) = Custom.keyOf(t)
fun customKey(r: Rank) = Custom.keyOf(r)

/**
 * The player's icon for [key] (tier or rank), [size] square, with the chosen micro-animation.
 * Draws nothing when there is no icon. [animated] = false for long lists.
 */
@Composable
fun CustomIcon(key: String, side: Dp, modifier: Modifier = Modifier, animated: Boolean = true, anim: IconAnim? = null) {
    val bmp = CustomLooks.icons[key] ?: return
    val a = anim ?: IconAnim.byName(Names.custom[key]?.anim)
    val motion = animated && a != IconAnim.NONE && !LocalLook.current.reduceMotion
    val t: State<Float> = if (motion) {
        rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(periodOf(a), easing = LinearEasing), RepeatMode.Restart))
    } else remember { mutableFloatStateOf(0f) }
    Image(bmp, null, modifier.size(side).graphicsLayer {
        val p = t.value
        val w = 2f * PI.toFloat() * p
        val px = side.toPx()
        when (a) {
            IconAnim.NONE -> {}
            IconAnim.PULSE -> { val s = 1f + 0.12f * sin(w); scaleX = s; scaleY = s }
            IconAnim.BOUNCE -> { translationY = -abs(sin(w)) * px * 0.22f; scaleY = 1f - 0.08f * cos(w * 2f).coerceAtLeast(0f) }
            IconAnim.SPIN -> { rotationZ = 360f * p }
            IconAnim.WOBBLE -> { rotationZ = 14f * sin(w) }
            IconAnim.FLOAT -> { translationY = sin(w) * px * 0.12f }
            IconAnim.BLINK -> { alpha = 0.3f + 0.7f * (0.5f + 0.5f * cos(w)) }
            // a short shake, then rest
            IconAnim.SHAKE -> { translationX = if (p < 0.3f) sin(p / 0.3f * 6f * PI.toFloat()) * px * 0.08f else 0f }
            IconAnim.FLIP -> { rotationY = 360f * p; cameraDistance = 10f * density }
        }
    })
}

private fun periodOf(a: IconAnim): Int = when (a) {
    IconAnim.PULSE -> 1400
    IconAnim.BOUNCE -> 1100
    IconAnim.SPIN -> 2600
    IconAnim.WOBBLE -> 1300
    IconAnim.FLOAT -> 2400
    IconAnim.BLINK -> 1800
    IconAnim.SHAKE -> 1700
    IconAnim.FLIP -> 2800
    IconAnim.NONE -> 1000
}

/** Small base64 codec (standard alphabet, with padding) for icon files. */
object B64 {
    private const val ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val rev = IntArray(128) { -1 }.also { r -> ABC.forEachIndexed { i, c -> r[c.code] = i } }

    fun encode(b: ByteArray): String {
        val sb = StringBuilder((b.size + 2) / 3 * 4)
        var i = 0
        while (i < b.size) {
            val n = b.size - i
            val x = ((b[i].toInt() and 0xFF) shl 16) or
                ((if (n > 1) b[i + 1].toInt() and 0xFF else 0) shl 8) or
                (if (n > 2) b[i + 2].toInt() and 0xFF else 0)
            sb.append(ABC[(x shr 18) and 63]).append(ABC[(x shr 12) and 63])
            sb.append(if (n > 1) ABC[(x shr 6) and 63] else '=').append(if (n > 2) ABC[x and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray? {
        val clean = s.filter { !it.isWhitespace() }
        if (clean.length % 4 != 0) return null
        val pad = clean.takeLast(2).count { it == '=' }
        val out = ByteArray(clean.length / 4 * 3 - pad)
        var o = 0
        var i = 0
        while (i < clean.length) {
            var x = 0
            for (k in 0 until 4) {
                val c = clean[i + k]
                val v = if (c == '=') 0 else if (c.code < 128) rev[c.code] else -1
                if (v < 0) return null
                x = (x shl 6) or v
            }
            for (k in 0 until 3) if (o < out.size && (k < 3 - (if (i + 4 == clean.length) pad else 0))) out[o++] = (x shr (16 - 8 * k)).toByte()
            i += 4
        }
        return out
    }
}
