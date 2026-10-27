package com.drift.airmouse.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.drift.airmouse.core.AppPrefs
import com.drift.airmouse.ui.BackEdgeButton
import com.drift.airmouse.ui.BrandFont
import com.drift.airmouse.ui.ListScreen
import com.drift.airmouse.ui.Look
import com.drift.airmouse.ui.Looks
import com.drift.airmouse.ui.themeArt

/** Pick a look; each row shows a live preview of its art. Applies instantly. */
@Composable
fun ThemeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs.get(context) }
    val current by prefs.theme.collectAsState()

    ListScreen(edgeButton = { BackEdgeButton(onBack) }) {
        item { ListHeader { Text("Themes") } }
        Looks.forEach { look ->
            item {
                ThemeRow(look, selected = look.id == current, onPick = { prefs.setTheme(look.id) })
            }
        }
    }
}

@Composable
private fun ThemeRow(look: Look, selected: Boolean, onPick: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(2.dp, if (selected) look.primary else Color.Transparent, shape)
            .clickable(role = Role.RadioButton, onClickLabel = look.label, onClick = onPick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).themeArt(look),
            contentAlignment = Alignment.Center,
        ) {
            Text("D", fontFamily = BrandFont, fontSize = 16.sp, color = look.primary)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(look.label, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Text(
                look.tagline,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
