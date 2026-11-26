package com.bitchat.android.ui.mosh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MoshGrid design tokens from the design canvas (brand board, "Signal" direction). Colours that
 * change with light and dark theme come from MaterialTheme; these are the fixed brand accents.
 */
object MoshColors {
    val Acid = Color(0xFFC6FF3D)
    val Cyan = Color(0xFF3DF2FF)
    val Pink = Color(0xFFFF5CDB)
    val Red = Color(0xFFE8222B)
    val Uv = Color(0xFFA98BFF)
    val Amber = Color(0xFFFFC266)
    /** Avatar colours, picked per friend from their id so a friend keeps one colour. */
    val Avatars = listOf(Pink, Cyan, Uv, Acid, Amber)
}

object MoshShapes {
    val Tile = RoundedCornerShape(24.dp)
    val Card = RoundedCornerShape(18.dp)
    val Pill = RoundedCornerShape(50)
}

/** A friend's colour, the same everywhere: avatar, compass blip and Find arrow. */
fun avatarColor(key: String): Color = MoshColors.Avatars[Math.floorMod(key.hashCode(), MoshColors.Avatars.size)]

/** Round avatar: the friend's photo if they sent one, else initials coloured per friend. [key] is "me", a peer ID, a Noise key or a contact chat id. */
@Composable
fun MoshAvatar(name: String, key: String, size: Dp = 50.dp) {
    val photos by com.bitchat.android.mosh.ProfilePhotos.images.collectAsState()
    val photo = remember(key, photos) {
        photos[key] ?: if (photos.isEmpty() || key == "me") null
            else com.bitchat.android.mosh.MoshRuntime.friendFor(key)?.noiseKeyHex?.let { photos[it] }
    }
    if (photo != null) {
        androidx.compose.foundation.Image(photo, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape).background(avatarColor(key)))
        return
    }
    val initials = avatarInitials(name)
    Box(Modifier.size(size).clip(CircleShape).background(avatarColor(key)), contentAlignment = Alignment.Center) {
        Text(initials, color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.3f).sp)
    }
}

/**
 * Up to two initials: the first letter or digit of the first two words that have one. Emoji and
 * symbols are skipped, so a name like "Jo 🎧" gives "J" instead of half an emoji.
 */
internal fun avatarInitials(name: String): String = name.trim().split(Regex("\\s+"))
    .mapNotNull { w -> w.codePoints().filter { Character.isLetterOrDigit(it) }.findFirst().orElse(-1).takeIf { it >= 0 } }
    .take(2).joinToString("") { String(Character.toChars(it)).uppercase() }.ifEmpty { "?" }

/** Big rounded tile: a small grey label over a bold value (Network, Find). */
@Composable
fun MoshTile(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.clip(MoshShapes.Tile).background(MaterialTheme.colorScheme.surface).heightIn(min = 96.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** Screen title row: big bold title, optional actions on the right. */
@Composable
fun MoshTitle(title: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Row(modifier.padding(start = 22.dp, end = 16.dp, top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        actions()
    }
}

/** Setting row with a switch on the right; the whole row toggles and reads as a switch. */
@Composable
fun MoshSwitchRow(title: String, subtitle: String?, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** Setting row that does something when tapped: optional icon, title and one grey line. */
@Composable
fun MoshActionRow(title: String, subtitle: String?, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

class MoshNavItem(val label: String, val icon: ImageVector)

/** Floating pill tab bar from the design: icon over label; the selected tab is ink with an acid icon. */
@Composable
fun MoshBottomNav(items: List<MoshNavItem>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.navigationBarsPadding().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 16.dp).fillMaxWidth()
            .height(72.dp).clip(RoundedCornerShape(36.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, item ->
            val on = i == selected
            Column(
                Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp))
                    .selectable(selected = on, role = Role.Tab) { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Icon(item.icon, contentDescription = null, modifier = Modifier.size(24.dp),
                    tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
