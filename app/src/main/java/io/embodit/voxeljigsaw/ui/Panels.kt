package io.embodit.voxeljigsaw.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.LinearProgressIndicator
import com.pico.spatial.ui.design.ListItem
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.voxeljigsaw.BuildConfig
import io.embodit.voxeljigsaw.R
import io.embodit.voxeljigsaw.domain.Landmarks
import io.embodit.voxeljigsaw.ui.theme.JigColors
import kotlinx.coroutines.delay

private val GlassShape = RoundedCornerShape(24.dp)

/**
 * Glass panel root: fixed dp size (an attachment root must not wrap its content), Material.Regular,
 * redrawn every 0.5 s so a black backdrop frame on the emulator does not stick (ported from Sky Dome).
 */
@Composable
private fun Glass(w: Float, h: Float, padding: Int = 18, content: @Composable ColumnScope.() -> Unit) {
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            tick.intValue++
        }
    }
    Column(
        modifier = Modifier.size(w.dp, h.dp)
            .clip(GlassShape)
            .backgroundMaterial(enable = true, style = Material.Regular)
            .drawBehind { tick.intValue }
            .padding(padding.dp),
        content = content,
    )
}

/** An empty fixed-size root for a panel that is not in use (its holder is shrunk away by the scene). */
@Composable
private fun Empty(w: Float, h: Float) = Box(Modifier.size(w.dp, h.dp))

@Composable
private fun ink() = PicoTheme.colorScheme.labelPrimary

/**
 * Secondary text. labelSecondary measured 3.95–4.5:1 on the Regular glass (tools/contrast.py, build/ui-audit.md), under the
 * factory's 4.5:1 rule (profile D13), so small text uses the primary label colour; size and weight carry the hierarchy.
 */
@Composable
private fun sub() = PicoTheme.colorScheme.labelPrimary

@Composable
private fun Primary(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier, colors = ButtonDefaults.buttonColors(containerColor = JigColors.SeamGold, contentColor = JigColors.OnGold)) {
        // design-style: inherited-content-color Button
        Text(label, maxLines = 1)
    }
}

@Composable
private fun Secondary(label: String, modifier: Modifier = Modifier, small: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier, size = if (small) ButtonDefaults.Small else ButtonDefaults.Regular, colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillSecondary, contentColor = PicoTheme.colorScheme.labelPrimary)) {
        // design-style: inherited-content-color Button
        Text(label, maxLines = 1)
    }
}

@Composable
private fun RoundIcon(icon: Int, label: String, size: Int = 48, active: Boolean = false, onClick: () -> Unit) {
    val c = PicoTheme.colorScheme
    val tint = if (active) JigColors.OnGold else c.labelPrimary
    IconButton(
        onClick = onClick,
        size = IconButtonDefaults.iconButtonSize(size.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.iconButtonColors(containerColor = if (active) JigColors.SeamGold else c.fillSecondary, contentColor = tint),
    ) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size((size * 0.5f).dp), tint = tint)
    }
}

@Composable
private fun segColors() =
    SegmentControlDefaults.colors(
        itemBackgroundColor = Color.Transparent,
        itemContentColor = PicoTheme.colorScheme.labelPrimary,
        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
    )

@Composable
private fun Segments(labels: List<String>, selected: Int, itemWidth: Int, onPick: (Int) -> Unit) {
    SegmentControl(modifier = Modifier.width((itemWidth * labels.size + 8).dp).height(40.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        labels.forEachIndexed { i, l ->
            SegmentItem(
                selected = i == selected,
                onClick = { onPick(i) },
                modifier = Modifier.width(itemWidth.dp).fillMaxHeight(),
                textStyle = PicoTheme.typography.labelMedium,
                colors = segColors(),
                title = { Text(text = l, maxLines = 1, color = ink()) },
            )
        }
    }
}

/** A 48 dp round key with its name under it (factory B19: buttons >= 48 dp, icon keys carry words). */
@Composable
private fun Labeled(icon: Int, label: String, active: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        RoundIcon(icon, label, 48, active, onClick)
        Spacer(Modifier.height(2.dp))
        Text(label, style = PicoTheme.typography.labelSmall, color = sub(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Mute key on the main panels (factory default D15: a mute switch you can see, not only in settings; remembered). */
@Composable
private fun MuteKey(s: UiState, g: GameController) {
    val t = s.strings
    Labeled(if (s.sound) R.drawable.ic_sound else R.drawable.ic_mute, if (s.sound) t.mute else t.unmute) { g.setSound(!s.sound) }
}

// ---------------------------------------------------------------------------------------------
// home panel: loading, home, settings, confirm, load error

@Composable
fun HomePanel(s: UiState, g: GameController) {
    val shown = s.screen == Screen.HOME || s.screen == Screen.LOADING || s.screen == Screen.ERROR || (s.screen == Screen.PLAY && s.overlay == Overlay.SETTINGS)
    if (!shown) return Empty(PanelSizes.HOME_W, PanelSizes.HOME_H)
    val t = s.strings
    Glass(PanelSizes.HOME_W, PanelSizes.homeHeight(s)) {
        when {
            s.screen == Screen.LOADING -> {
                Spacer(Modifier.height(60.dp))
                Text(t.loading, style = PicoTheme.typography.titleMedium, color = ink(), modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(progress = { s.loading }, modifier = Modifier.fillMaxWidth().padding(horizontal = 80.dp))
            }
            s.screen == Screen.ERROR -> ErrorBody(s, g)
            s.overlay == Overlay.SETTINGS -> SettingsBody(s, g)
            s.overlay == Overlay.CONFIRM -> ConfirmBody(s, g)
            else -> HomeBody(s, g)
        }
    }
}

@Composable
private fun ColumnScope.HomeBody(s: UiState, g: GameController) {
    val t = s.strings
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (id in Landmarks.IDS) LandmarkCard(s, g, id, Modifier.weight(1f))
    }
    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Segments(Landmarks.COUNTS.map { t.pieces(it) }, Landmarks.COUNTS.indexOf(s.count), 84) { g.selectCount(Landmarks.COUNTS[it]) }
        Spacer(Modifier.weight(1f))
        val saved = s.saves.containsKey(s.key)
        if (saved) Secondary(t.restart, small = true) { g.askRestart() }
        Primary(if (saved) t.resume else t.start, Modifier.width(120.dp)) { g.startOrResume() }
        MuteKey(s, g)
        Labeled(R.drawable.ic_gear, t.settings) { g.openSettings() }
    }
}

@Composable
private fun LandmarkCard(s: UiState, g: GameController, id: String, modifier: Modifier) {
    val t = g.book.of(id, s.lang)
    val selected = id == s.landmark
    val c = PicoTheme.colorScheme
    Column(
        modifier = modifier.height(150.dp).clip(RoundedCornerShape(16.dp))
            .border(if (selected) 2.dp else 0.dp, if (selected) c.interaction else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable { g.selectLandmark(id) }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(78.dp)) {
            Image(painterResource(thumbOf(id)), contentDescription = t.name, modifier = Modifier.fillMaxWidth().height(78.dp))
            s.saves["$id-${s.count}"]?.let { placed ->
                Text("$placed / ${s.count}", style = PicoTheme.typography.labelSmall, color = JigColors.OnGold, modifier = Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(8.dp)).drawBehind { drawRect(JigColors.SeamGold) }.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        Text(t.short, style = PicoTheme.typography.titleSmall, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.place, style = PicoTheme.typography.labelSmall, color = sub(), maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in Landmarks.COUNTS) {
                val done = s.records["$id-$n"]?.completed == true
                // done shows as "25 ✓" in the primary label colour: jade / tertiary on the light glass were under 4.5:1
                Text(if (done) "$n ✓" else "$n", style = PicoTheme.typography.labelSmall, color = ink(), fontWeight = if (done) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

private fun thumbOf(id: String) = when (id) {
    "bigben" -> R.drawable.thumb_bigben
    "taj" -> R.drawable.thumb_taj
    else -> R.drawable.thumb_qinian
}

@Composable
private fun ColumnScope.ConfirmBody(s: UiState, g: GameController) {
    val t = s.strings
    val name = g.book.of(s.landmark, s.lang).short
    Spacer(Modifier.weight(1f))
    Text(t.confirm(name, s.count), style = PicoTheme.typography.bodyMedium, color = ink(), modifier = Modifier.align(Alignment.CenterHorizontally))
    Spacer(Modifier.height(16.dp))
    Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Secondary(t.cancel, Modifier.width(120.dp)) { g.closeOverlay() }
        Primary(t.ok, Modifier.width(120.dp)) { g.confirmRestart() }
    }
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.SettingsBody(s: UiState, g: GameController) {
    val t = s.strings
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(R.drawable.ic_back, t.back) { g.closeOverlay() }
        Spacer(Modifier.width(10.dp))
        Text(t.settings, style = PicoTheme.typography.titleMedium, color = ink())
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t.language, style = PicoTheme.typography.bodyMedium, color = ink())
        Spacer(Modifier.weight(1f))
        val opts = listOf("follow", "zh", "en")
        Segments(listOf(t.follow, "中文", "English"), opts.indexOf(s.langSetting).coerceAtLeast(0), 92) { g.setLanguage(opts[it]) }
    }
    ListItem(
        headlineContent = { Text(t.easyMode, maxLines = 1, color = ink()) },
        supportingContent = { Text(t.easyNote, maxLines = 1, style = PicoTheme.typography.labelSmall, color = sub()) },
        trailingContent = { Switch(checked = s.easy, onCheckedChange = { g.setEasy(it) }) },
    )
    ListItem(headlineContent = { Text(t.sound, maxLines = 1, color = ink()) }, trailingContent = { Switch(checked = s.sound, onCheckedChange = { g.setSound(it) }) })
    ListItem(
        headlineContent = { Text(t.wrist, maxLines = 1, color = ink()) },
        supportingContent = { Text(t.wristNote, maxLines = 1, style = PicoTheme.typography.labelSmall, color = sub()) },
        trailingContent = { Switch(checked = s.wrist, onCheckedChange = { g.setWrist(it) }) },
    )
    Spacer(Modifier.height(4.dp))
    Text(t.aboutText(BuildConfig.VERSION_NAME), style = PicoTheme.typography.labelSmall, color = sub(), maxLines = 2, modifier = Modifier.padding(horizontal = 8.dp))
}

@Composable
private fun ColumnScope.ErrorBody(s: UiState, g: GameController) {
    val t = s.strings
    Spacer(Modifier.weight(1f))
    Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_warn), contentDescription = null, modifier = Modifier.size(24.dp), tint = PicoTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Text(t.errTitle, style = PicoTheme.typography.titleMedium, color = ink())
    }
    if (s.saveLost) Text(t.errSave, style = PicoTheme.typography.bodyMedium, color = ink(), modifier = Modifier.align(Alignment.CenterHorizontally))
    Text(s.errorReason ?: "", style = PicoTheme.typography.labelSmall, color = sub(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterHorizontally))
    Spacer(Modifier.height(14.dp))
    Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Primary(t.retry, Modifier.width(120.dp)) { g.retry() }
        Secondary(t.homeShort, Modifier.width(120.dp)) { g.goHome() }
    }
    Spacer(Modifier.weight(1f))
}

// ---------------------------------------------------------------------------------------------
// top bar: progress, hint / peek / more, message line, menu and confirm strip

@Composable
fun TopBar(s: UiState, g: GameController) {
    if (s.screen != Screen.PLAY || s.overlay == Overlay.SETTINGS) return Empty(PanelSizes.TOP_W, PanelSizes.TOP_H)
    val t = s.strings
    val name = g.book.of(s.landmark, s.lang).short
    Glass(PanelSizes.TOP_W, PanelSizes.topHeight(s), padding = 12) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = PicoTheme.typography.titleMedium, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Text("${s.placed} / ${s.total}", style = PicoTheme.typography.labelMedium, color = sub())
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { if (s.total == 0) 0f else s.placed / s.total.toFloat() }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_stick), contentDescription = null, modifier = Modifier.size(12.dp), tint = sub())
                    Spacer(Modifier.width(4.dp))
                    Text(t.stickTip, style = PicoTheme.typography.labelSmall, color = sub(), maxLines = 1)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Labeled(R.drawable.ic_hint, t.hint, active = s.hintOn) { g.hint() }
                    if (s.hints > 0) Text("${s.hints}", style = PicoTheme.typography.labelSmall, color = JigColors.OnGold, modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).drawBehind { drawRect(JigColors.SeamGold) }.padding(horizontal = 4.dp))
                }
                Labeled(R.drawable.ic_peek, t.peek, active = s.peekOn) { g.peek() }
                MuteKey(s, g)
                Labeled(R.drawable.ic_more, t.more, active = s.overlay == Overlay.MENU) { g.openMenu() }
            }
        }
        when (s.overlay) {
            Overlay.MENU -> {
                Spacer(Modifier.height(4.dp))
                ListItem(headlineContent = { Text(t.restart, color = ink(), maxLines = 1) }, modifier = Modifier.clickable { g.askRestart() })
                ListItem(headlineContent = { Text(t.home, color = ink(), maxLines = 1) }, modifier = Modifier.clickable { g.goHome() })
                ListItem(headlineContent = { Text(t.settings, color = ink(), maxLines = 1) }, modifier = Modifier.clickable { g.openSettings() })
            }
            Overlay.CONFIRM -> {
                Spacer(Modifier.height(10.dp))
                Text(t.confirm(name, s.count), style = PicoTheme.typography.bodyMedium, color = ink(), maxLines = 2)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Secondary(t.cancel, small = true) { g.closeOverlay() }
                    Primary(t.ok) { g.confirmRestart() }
                }
            }
            else -> if (s.message == null) StepLine(s, g) else s.message.let { m ->
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { g.dismissMessage() }.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val icon = when (m.kind) { MessageKind.FACT -> R.drawable.ic_hint; MessageKind.WRONG -> R.drawable.ic_turn_y; MessageKind.TEACH -> R.drawable.ic_peek }
                    val tint = when (m.kind) { MessageKind.FACT -> JigColors.SeamGold; MessageKind.WRONG -> PicoTheme.colorScheme.alert; MessageKind.TEACH -> sub() }
                    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), tint = tint)
                    Spacer(Modifier.width(8.dp))
                    Text(m.text, style = PicoTheme.typography.bodyMedium, color = ink(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * The step line under the progress bar: which step this is and what to do with the marked piece, with the exact
 * turn keys when it still faces the wrong way. Tapping it is the hint key (user feedback 2026-10-23).
 */
@Composable
private fun ColumnScope.StepLine(s: UiState, g: GameController) {
    val t = s.strings
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).drawBehind { drawRect(JigColors.SeamGold.copy(alpha = 0.16f)) }.clickable { g.hint() }.padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(t.step(s.step.coerceAtLeast(1), s.total), style = PicoTheme.typography.labelMedium, color = ink(), maxLines = 1)
        Spacer(Modifier.width(8.dp))
        val keys = s.stepKeys
        when {
            !s.easy && !s.hintOn -> Text(t.stepAsk, style = PicoTheme.typography.labelMedium, color = sub(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            s.easy -> Text(t.stepEasy, style = PicoTheme.typography.labelMedium, color = sub(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            keys == null -> Text(t.stepReady, style = PicoTheme.typography.labelMedium, color = sub(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            else -> {
                Text(t.stepTurn, style = PicoTheme.typography.labelMedium, color = sub(), maxLines = 1)
                Spacer(Modifier.width(4.dp))
                keys.forEach { tip ->
                    Icon(painterResource(if (tip) R.drawable.ic_flip else R.drawable.ic_turn_y), contentDescription = if (tip) t.tipX else t.turnY, modifier = Modifier.size(16.dp), tint = JigColors.SeamGold)
                }
                Spacer(Modifier.width(4.dp))
                Text(t.stepThenDrag, style = PicoTheme.typography.labelMedium, color = sub(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// turn keys above the selected piece

@Composable
fun TurnKeys(s: UiState, g: GameController) {
    if (s.screen != Screen.PLAY || s.selected == null) return Empty(PanelSizes.KEYS_W, PanelSizes.KEYS_H)
    val t = s.strings
    Row(
        Modifier.size(PanelSizes.KEYS_W.dp, PanelSizes.KEYS_H.dp).clip(RoundedCornerShape(24.dp)).backgroundMaterial(enable = true, style = Material.Regular),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Labeled(R.drawable.ic_turn_y, t.turnYShort) { g.turn(false) }
        Labeled(R.drawable.ic_flip, t.tipXShort) { g.turn(true) }
    }
}

// ---------------------------------------------------------------------------------------------
// landmark card (complete) and the display pill

@Composable
fun LandmarkCardPanel(s: UiState, g: GameController) {
    if (s.screen != Screen.COMPLETE || !s.cardShown) return Empty(PanelSizes.CARD_W, PanelSizes.CARD_H)
    val t = s.strings
    val lm = g.book.of(s.landmark, s.lang)
    Glass(PanelSizes.CARD_W, PanelSizes.CARD_H, padding = 16) {
        Text(lm.name, style = PicoTheme.typography.titleLarge, color = ink(), maxLines = 2)
        Text(lm.place, style = PicoTheme.typography.labelMedium, color = sub())
        Spacer(Modifier.height(6.dp))
        Text("${t.built} ${lm.built} · ${t.height} ${lm.height}", style = PicoTheme.typography.labelSmall, color = sub(), maxLines = 2)
        Text(lm.scale, style = PicoTheme.typography.labelSmall, color = sub(), maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Text(lm.story, style = PicoTheme.typography.bodySmall, color = ink(), maxLines = 4)
        Spacer(Modifier.height(8.dp))
        Text(t.seen, style = PicoTheme.typography.labelMedium, color = sub())
        lm.facts.forEach { f -> Text("· $f", style = PicoTheme.typography.labelSmall, color = ink(), maxLines = 2) }
        Spacer(Modifier.weight(1f))
        Text(t.time(Strings.clock(s.elapsedSec), s.hints), style = PicoTheme.typography.labelMedium, color = sub())
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Secondary(t.display, Modifier.weight(1f), small = true) { g.showDisplay() }
            Primary(t.next, Modifier.weight(1f)) { g.nextLandmark() }
        }
    }
}

@Composable
fun DisplayPill(s: UiState, g: GameController) {
    if (s.screen != Screen.DISPLAY) return Empty(PanelSizes.PILL_W, PanelSizes.PILL_H)
    val t = s.strings
    val lm = g.book.of(s.landmark, s.lang)
    Row(
        Modifier.size(PanelSizes.PILL_W.dp, PanelSizes.PILL_H.dp).clip(RoundedCornerShape(24.dp)).backgroundMaterial(enable = true, style = Material.Regular).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!s.pillOpen) {
            Text("${lm.short} ✓", style = PicoTheme.typography.labelMedium, color = ink(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
            RoundIcon(R.drawable.ic_more, t.more) { g.togglePill() }
        } else {
            Secondary(t.intro, Modifier.weight(1f), small = true) { g.showCard() }
            Spacer(Modifier.width(6.dp))
            Secondary(t.homeShort, Modifier.weight(1f), small = true) { g.goHome() }
            RoundIcon(R.drawable.ic_close, t.back) { g.togglePill() }
        }
    }
}
