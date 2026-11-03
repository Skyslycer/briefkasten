package de.skyslycer.briefkasten

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun ExportDialog(title: String, period: String, count: Int, enabled: Boolean,
    close: () -> Unit, export: (ExportDestination) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = close) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(period, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$count ${if (count == 1) "envelope" else "envelopes"}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        TextButton(onClick = close, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                            Text("Cancel", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                        }
                    }
                    OutlinedButton(enabled = enabled, onClick = { export(ExportDestination.SHARE) },
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Share") }
                    Button(enabled = enabled, onClick = { export(ExportDestination.SAVE) },
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Save") }
                }
            }
        }
    }
}

@Composable
fun ZipExportDialog(period: ExportPeriod, count: Int, total: Int, selectPeriod: (ExportPeriod) -> Unit,
    close: () -> Unit, export: (Boolean, PdfImages, ExportDestination) -> Unit) {
    var backup by rememberSaveable { mutableStateOf(false) }
    val previousPeriod by rememberSaveable { mutableStateOf(period) }
    var images by rememberSaveable { mutableStateOf(PdfImages.ORIGINALS) }
    val selectedCount = if (backup) total else count
    ExportDialog(title = "Export ZIP", period = if (backup) "All time" else period.label,
        count = selectedCount, enabled = backup || selectedCount > 0, close = close,
        export = { export(backup, images, it) }) {
        Text("Include images", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().alpha(if (backup) 0.45f else 1f)) {
            PdfImages.entries.forEachIndexed { index, choice ->
                SegmentedButton(selected = !backup && choice == images, enabled = !backup,
                    colors = SegmentedButtonDefaults.colors(
                        disabledActiveContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        disabledActiveContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        disabledActiveBorderColor = MaterialTheme.colorScheme.outline,
                        disabledInactiveContainerColor = MaterialTheme.colorScheme.surface,
                        disabledInactiveContentColor = MaterialTheme.colorScheme.onSurface,
                        disabledInactiveBorderColor = MaterialTheme.colorScheme.outline),
                    onClick = { images = choice }, icon = {},
                    shape = SegmentedButtonDefaults.itemShape(index, PdfImages.entries.size)) { Text(choice.label) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Backup", Modifier.weight(1f).padding(end = 12.dp))
            Switch(checked = backup, onCheckedChange = { backup = it; selectPeriod(if (it) ExportPeriod.ALL else previousPeriod) })
        }
    }
}
