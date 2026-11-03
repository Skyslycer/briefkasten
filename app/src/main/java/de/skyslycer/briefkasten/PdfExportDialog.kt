package de.skyslycer.briefkasten

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PdfExportDialog(period: ExportPeriod, count: Int, close: () -> Unit, export: (PdfImages, Boolean, ExportDestination) -> Unit) {
    var images by rememberSaveable { mutableStateOf(PdfImages.BOTH) }
    var separate by rememberSaveable { mutableStateOf(false) }
    ExportDialog(title = "Export PDF", period = period.label, count = count, enabled = count > 0,
        close = close, export = { export(images, separate, it) }) {
        Text("Include images", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PdfImages.entries.forEachIndexed { index, choice ->
                SegmentedButton(selected = choice == images, onClick = { images = choice }, icon = {},
                    shape = SegmentedButtonDefaults.itemShape(index, PdfImages.entries.size)) { Text(choice.label) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("One PDF per envelope", Modifier.weight(1f).padding(end = 12.dp))
            Switch(checked = separate, onCheckedChange = { separate = it })
        }
    }
}
