package com.turn.fieldtest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.turn.fieldtest.ui.model.EventSeverity
import com.turn.fieldtest.ui.theme.TurnAmber
import com.turn.fieldtest.ui.theme.TurnMint
import com.turn.fieldtest.ui.theme.TurnRed

@Composable
internal fun PageHeader(
    eyebrow: String,
    title: String,
    description: String,
    compact: Boolean,
    action: (@Composable () -> Unit)? = null
) {
    if (compact || action == null) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            HeaderCopy(eyebrow, title, description)
            if (action != null) {
                Spacer(Modifier.height(6.dp))
                action()
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) { HeaderCopy(eyebrow, title, description) }
            action()
        }
    }
}

@Composable
private fun HeaderCopy(eyebrow: String, title: String, description: String) {
    Text(
        eyebrow.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing * 1.3
    )
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
    Text(
        description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun SectionCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                trailing?.invoke()
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
internal fun AdaptiveColumns(
    modifier: Modifier = Modifier,
    breakpoint: Dp = 690.dp,
    primaryWeight: Float = 1f,
    secondaryWeight: Float = 1f,
    gap: Dp = 14.dp,
    primary: @Composable () -> Unit,
    secondary: @Composable () -> Unit
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= breakpoint) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Box(Modifier.weight(primaryWeight)) { primary() }
                Box(Modifier.weight(secondaryWeight)) { secondary() }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
                primary()
                secondary()
            }
        }
    }
}

@Composable
internal fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    OutlinedCard(
        modifier = modifier,
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(value, style = MaterialTheme.typography.titleLarge, color = accent, fontWeight = FontWeight.Black)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun StatusPill(
    text: String,
    severity: EventSeverity = EventSeverity.INFO,
    modifier: Modifier = Modifier
) {
    val color = when (severity) {
        EventSeverity.INFO -> MaterialTheme.colorScheme.primary
        EventSeverity.GOOD -> TurnMint
        EventSeverity.WARNING -> TurnAmber
        EventSeverity.ERROR -> TurnRed
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(color))
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
internal fun LabelValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            modifier = Modifier.weight(1.35f)
        )
    }
}

@Composable
internal fun TableShell(
    headers: List<Pair<String, Dp>>,
    rows: @Composable () -> Unit
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
    ) {
        Row(
            Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            headers.forEach { (text, width) ->
                Text(
                    text.uppercase(),
                    modifier = Modifier.width(width),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        rows()
    }
}

@Composable
internal fun DataRow(
    cells: List<Pair<String, Dp>>,
    tint: Color? = null
) {
    Row(
        Modifier
            .background(tint?.copy(alpha = 0.06f) ?: Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        cells.forEachIndexed { index, (text, width) ->
            Text(
                text,
                modifier = Modifier.width(width),
                style = MaterialTheme.typography.bodySmall,
                color = if (index == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (index == 0) FontWeight.Medium else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
}

@Composable
internal fun BleDisabledBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f))
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text("BLE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.secondary)
        }
        Column(Modifier.weight(1f)) {
            Text("BLE not configured — Wi-Fi + PDR active", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(
                "No BLE permissions requested and no scans started.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal val PagePadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp)
