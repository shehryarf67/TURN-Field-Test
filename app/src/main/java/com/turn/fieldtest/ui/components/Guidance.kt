package com.turn.fieldtest.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * Small shared building blocks that keep every screen's guidance consistent:
 * a numbered step header, a "?" help toggle, a collapsed "Advanced" section,
 * a friendly empty state and a confirm dialog for destructive actions.
 */

/** "Step N of M: title", one sentence of what to do, one of why, and an optional "?" with more detail. */
@Composable
fun StepHeader(
    step: Int,
    total: Int,
    title: String,
    what: String,
    why: String? = null,
    help: String? = null,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Step $step of $total", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (help != null) HelpButton(open) { open = !open }
        }
        Text(what, style = MaterialTheme.typography.bodyLarge)
        if (why != null) Text(why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (help != null) AnimatedVisibility(open) { HelpText(help) }
    }
}

/** A short line of guidance with a "?" that expands a longer explanation. */
@Composable
fun HelpTip(summary: String, details: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable(summary) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            HelpButton(open) { open = !open }
        }
        AnimatedVisibility(open) { HelpText(details) }
    }
}

@Composable
private fun HelpButton(open: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = if (open) "Hide help" else "Show help" }) {
        Icon(Icons.AutoMirrored.Outlined.HelpOutline, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun HelpText(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f), shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}

/** Rarely-used or technical options, collapsed by default. */
@Composable
fun AdvancedSection(
    modifier: Modifier = Modifier,
    title: String = "Advanced",
    subtitle: String? = null,
    initiallyOpen: Boolean = false,
    content: @Composable () -> Unit,
) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    OutlinedCard(modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        TextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) "Collapse" else "Expand")
        }
        AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }
}

/** What to show when a list is empty: what is missing and the next thing to do. */
@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Outlined.Inbox, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

/** Confirmation for actions that cannot be undone or that remove data. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}
