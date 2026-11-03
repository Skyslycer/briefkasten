package de.skyslycer.briefkasten

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognizedTextSheet(app: BriefkastenApp, letter: Letter, cutout: Boolean, close: () -> Unit) {
    var text by remember(letter.id, cutout) { mutableStateOf<EnvelopeReadings?>(null) }
    var failed by remember(letter.id, cutout) { mutableStateOf(false) }
    var attempt by remember(letter.id, cutout) { mutableIntStateOf(0) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(letter.id, cutout, attempt) {
        text = null
        failed = false
        try { text = app.groups.recognizedText(letter, cutout) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    var copied by remember(letter.id, cutout) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(2000); copied = false } }
    val displayed = text?.displayText.orEmpty()
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = 24.dp)) {
            Text(if (cutout) "Cutout text" else "Full image text", style = MaterialTheme.typography.headlineSmall)
            Text("Recognition may contain mistakes. Long-press to select text, or copy the whole reading below.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
            Surface(Modifier.weight(1f).fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                when {
                    failed -> Column(Modifier.padding(20.dp)) {
                        Text("Couldn't read this envelope. Try again.")
                        TextButton(onClick = { attempt++ }) { Text("Retry") }
                    }
                    text == null -> Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("Reading envelope…")
                    }
                    displayed.isBlank() -> Text("No text was detected in this envelope.", Modifier.padding(20.dp))
                    else -> {
                        BasicTextField(
                            value = displayed,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = close) { Text("Done") }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(enabled = text != null && displayed.isNotBlank(),
                    onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(if (cutout) "Cutout text" else "Full image text", displayed))); copied = true } }) { Text(if (copied) "Copied" else "Copy text") }
            }
        }
    }
}
