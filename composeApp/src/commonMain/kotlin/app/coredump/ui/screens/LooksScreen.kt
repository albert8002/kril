package app.coredump.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.core.Custom
import app.coredump.core.IconAnim
import app.coredump.core.Rank
import app.coredump.core.Tier
import app.coredump.data.SettingsStore
import app.coredump.platform.rememberImagePicker
import app.coredump.ui.C
import app.coredump.ui.ChipBox
import app.coredump.ui.CustomIcon
import app.coredump.ui.CustomLooks
import app.coredump.ui.Panel
import app.coredump.ui.RankBadge
import app.coredump.ui.TButton
import app.coredump.ui.TLink
import app.coredump.ui.TText
import app.coredump.ui.TermInput
import app.coredump.ui.Th
import app.coredump.ui.TierBadge
import kotlinx.coroutines.delay

/**
 * The player's own names, colours, icons and icon animations for the answer tiers and the end-of-game ranks.
 * Everything stays on this device; the other player in a 1v1 sees their own names.
 */
@Composable
fun LooksScreen(model: AppModel) {
    // one photo picker for the whole screen; [target] = the tier / rank waiting for its icon
    var target by remember { mutableStateOf<String?>(null) }
    // bumped by "reset all": editors start over with the default values
    var gen by remember { mutableStateOf(0) }
    val pick = rememberImagePicker(CustomLooks.ICON_PX) { png ->
        val k = target
        target = null
        when {
            k == null || png == null -> Unit
            CustomLooks.store(k, png) -> model.update { s -> s.copy(custom = s.custom + (k to (s.custom[k] ?: Custom()).copy(icon = true))) }
            else -> model.toast = Th.say("could not read that image", "Could not read that image")
        }
    }

    Frame(model, Th.say("ranks --edit", "Tiers & ranks")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            TText(
                Th.say("own names, colours and icons for answer tiers and end-of-game ranks. only on this device.",
                    "Give answer tiers and end-of-game ranks your own names, colours and icons. They stay on this device."),
                size = 12.sp, color = C.dim,
            )
            Spacer(Modifier.height(14.dp))
            TText(Th.say("ANSWER TIERS", "Answer tiers"), size = 13.sp, color = C.hot, weight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            for (t in Tier.validDescending.reversed()) key(t, gen) {
                LookEditor(model, Custom.keyOf(t), t.defaultLabel, "+${t.points} pts", { target = it; pick() }) {
                    TierBadge(t, 14.sp)
                }
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(6.dp))
            TText(Th.say("RANKS", "End-of-game ranks"), size = 13.sp, color = C.hot, weight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            for (r in Rank.entries) key(r, gen) {
                LookEditor(model, Custom.keyOf(r), r.defaultTitle, Th.say("avg ≥ ${r.minAvg} pts", "Average ${r.minAvg}+ points"),
                    { target = it; pick() }) {
                    RankBadge(r, size = 16.sp)
                }
                Spacer(Modifier.height(12.dp))
            }
            if (model.settings.custom.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                TLink(Th.say("reset everything to default", "Reset all to default"), {
                    for ((k, c) in model.settings.custom) if (c.icon) CustomLooks.remove(k)
                    model.update { it.copy(custom = emptyMap()) }
                    gen++
                }, color = C.blood)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Editor for one tier or rank: live preview, name, colour, icon and its animation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookEditor(
    model: AppModel,
    k: String,
    defaultName: String,
    note: String,
    onPickIcon: (String) -> Unit,
    preview: @Composable () -> Unit,
) {
    val c = model.settings.custom[k] ?: Custom()
    fun set(f: (Custom) -> Custom) = model.update { s ->
        val n = f(s.custom[k] ?: Custom())
        s.copy(custom = if (n.isDefault) s.custom - k else s.custom + (k to n))
    }
    Panel(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { preview() }
            TText(note, size = 10.sp, color = C.dim)
        }
        Spacer(Modifier.height(10.dp))

        // name: saved a moment after typing stops
        var name by remember(k) { mutableStateOf(c.name) }
        LaunchedEffect(name) {
            delay(400)
            if (name.trim() != (model.settings.custom[k]?.name ?: "")) set { it.copy(name = name.trim()) }
        }
        TermInput(name, { if (it.length <= SettingsStore.MAX_NAME) name = it }, Modifier.fillMaxWidth(),
            prompt = Th.say("name", "Name"), placeholder = defaultName, size = 14.sp)

        // colour
        Spacer(Modifier.height(10.dp))
        TText(Th.say("colour", "Colour"), size = 11.sp, color = C.dim)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Swatch(null, c.color == null) { set { it.copy(color = null) } }
            for (argb in CustomLooks.swatches) Swatch(Color(argb), c.color == argb) { set { it.copy(color = argb) } }
        }

        // icon + animation
        Spacer(Modifier.height(12.dp))
        TText(Th.say("icon", "Icon"), size = 11.sp, color = C.dim)
        Spacer(Modifier.height(6.dp))
        val has = c.icon && CustomLooks.icons[k] != null
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (has) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { CustomIcon(k, 34.dp) }
                Spacer(Modifier.width(10.dp))
            }
            TButton(if (has) Th.say("REPLACE", "Replace") else Th.say("UPLOAD ICON", "Upload icon"), { onPickIcon(k) },
                Modifier.weight(1f), textSize = 12.sp)
            if (has) {
                Spacer(Modifier.width(8.dp))
                TButton(Th.say("REMOVE", "Remove"), {
                    CustomLooks.remove(k)
                    set { it.copy(icon = false, anim = "NONE") }
                }, Modifier.weight(1f), textSize = 12.sp, color = C.blood)
            }
        }
        if (has) {
            Spacer(Modifier.height(10.dp))
            TText(Th.say("icon animation", "Icon animation"), size = 11.sp, color = C.dim)
            Spacer(Modifier.height(6.dp))
            val cur = IconAnim.byName(c.anim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (a in IconAnim.entries) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // each choice shows the animation running on the icon itself
                        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { CustomIcon(k, 22.dp, anim = a) }
                        ChipBox(a.label, a == cur, size = 11.sp) { set { it.copy(anim = a.name) } }
                    }
                }
            }
        }
        if (!c.isDefault) {
            Spacer(Modifier.height(8.dp))
            TLink(Th.say("reset", "Reset this one"), {
                if (c.icon) CustomLooks.remove(k)
                name = ""
                set { Custom() }
            }, color = C.dim, size = 11.sp)
        }
    }
}

/** One colour choice; [color] null = the theme's own colour. */
@Composable
private fun Swatch(color: Color?, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) C.hot else C.line
    Box(
        Modifier.size(30.dp).clip(CircleShape).border(if (selected) 3.dp else 1.dp, ring, CircleShape)
            .background(color ?: C.panel, CircleShape)
            .clickable(remember { MutableInteractionSource() }, null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (color == null) TText("×", size = 14.sp, color = C.dim, weight = FontWeight.Bold)
        else if (selected) TText("✓", size = 13.sp, color = if (color.luminanceOf() > 0.5f) Color.Black else Color.White, weight = FontWeight.Bold)
    }
}

private fun Color.luminanceOf(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
