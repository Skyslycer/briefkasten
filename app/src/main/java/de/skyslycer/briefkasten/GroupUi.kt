package de.skyslycer.briefkasten

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import kotlinx.coroutines.launch
import java.util.UUID

private data class TagColor(val name: String, val background: Color, val foreground: Color)
private val tagColors = listOf(
    TagColor("Sage", Color(0xFFB5D1AE), Color(0xFF35513B)),
    TagColor("Moss", Color(0xFFCBD18F), Color(0xFF505732)),
    TagColor("Clay", Color(0xFFE5B39B), Color(0xFF794C3A)),
    TagColor("Sand", Color(0xFFE8CD88), Color(0xFF70592B)),
    TagColor("Slate", Color(0xFFAFCBD7), Color(0xFF3E5961))
)

@Composable fun GroupTag(group: LetterGroup?, modifier: Modifier = Modifier) {
    val palette = group?.let { tagColors[it.color.coerceIn(tagColors.indices)] }
        ?: TagColor("No group", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = palette.background, contentColor = palette.foreground) {
        Text(group?.name ?: "No group", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable fun GroupSettings(app: BriefkastenApp) {
    val groups by app.database.archive().observeGroups().collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<LetterGroup?>(null) }
    var open by remember { mutableStateOf(false) }
    Text("Groups", style = MaterialTheme.typography.bodyLarge)
    Text("Match a name anywhere in a letter. The most specific match wins.", Modifier.padding(top = 8.dp, bottom = 12.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    groups.forEach { group ->
        Surface(onClick = { editing = group; open = true }, color = Color.Transparent, shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(end = 16.dp)) { GroupTag(group) }
                Text("Edit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    FilledTonalButton(onClick = { editing = null; open = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp),
        shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primary, .20f),
            contentColor = MaterialTheme.colorScheme.onSurface)) { Text("Add group", fontWeight = androidx.compose.ui.text.font.FontWeight.Normal) }
    if (open) GroupEditor(app, editing, groups) { open = false }
}

@Composable private fun GroupEditor(app: BriefkastenApp, existing: LetterGroup?, groups: List<LetterGroup>, close: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var color by remember { mutableIntStateOf(existing?.color ?: 0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clean = name.trim().replace(Regex("\\s+"), " ")
    val duplicate = groups.any { it.id != existing?.id && normalizedGroupText(it.name) == normalizedGroupText(clean) }
    fun save(remove: Boolean = false) {
        busy = true
        scope.launch {
            try {
                app.database.withTransaction {
                    val dao = app.database.archive()
                    if (remove && existing != null) dao.deleteGroup(existing.id)
                    else {
                        check(dao.groups().none { it.id != existing?.id && normalizedGroupText(it.name) == normalizedGroupText(clean) })
                        dao.saveGroup(LetterGroup(existing?.id ?: UUID.randomUUID().toString(), clean, color))
                    }
                }
                app.reindexGroups()
                close()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = "Couldn't save this group. Please try again." }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(if (existing == null) "New group" else "Edit group") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { if (it.length <= 40) name = it }, label = { Text("Group name") },
                    singleLine = true, enabled = !busy, isError = duplicate,
                    supportingText = { Text(if (duplicate) "This name already exists." else "Matches this text, ignoring case.") })
                Spacer(Modifier.height(16.dp))
                Text(tagColors[color].name, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    tagColors.forEachIndexed { index, palette ->
                        IconToggleButton(modifier = Modifier.semantics { contentDescription = palette.name }, checked = color == index, enabled = !busy, onCheckedChange = { color = index }) {
                            Box(Modifier.size(36.dp).background(palette.background, CircleShape), contentAlignment = Alignment.Center) {
                                Text(if (color == index) "✓" else "", color = palette.foreground,
                                    style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                GroupTag(LetterGroup("preview", clean.ifEmpty { "Group name" }, color))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (existing != null) TextButton(enabled = !busy, onClick = { save(remove = true) }) {
                    Text("Delete group", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = close) { Text("Cancel") }
                TextButton(enabled = !busy && clean.isNotEmpty() && !duplicate, onClick = { save() }) { Text("Save") }
            }
        })
}
