package com.hikari.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hikari.app.data.LibraryCategory
import com.hikari.app.i18n.tr
import kotlinx.coroutines.launch

/**
 * "Which categories should this go in?" — the sheet behind Add to library and
 * behind Move to.
 *
 * The picks are a SET, not a radio group: a title can be both a Series and an
 * Action, which is the whole reason the Library has categories rather than
 * folders. A new category can be invented from inside the sheet, and it is
 * picked as soon as it is created, so the user never has to re-open the sheet to
 * finish what they were doing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    title: String,
    subtitle: String,
    categories: List<LibraryCategory>,
    selected: Set<String>,
    confirmLabel: String,
    onConfirm: (Set<String>) -> Unit,
    /** Creates the category and returns it, so the sheet can tick what the user
     *  just invented instead of leaving it un-ticked (or, worse, relying on the
     *  caller to file it and then overwriting that with [onConfirm]). */
    onCreateCategory: suspend (String) -> LibraryCategory,
    onDismiss: () -> Unit,
    removeLabel: String = "",
    onRemove: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    // Deliberately NOT keyed on `selected`: the sheet is the authority on what
    // is ticked while it is open, and re-keying would silently drop a tick the
    // user just made as soon as anything else wrote a filing.
    var picked by remember { mutableStateOf(selected) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .height(if (categories.size > 6) 320.dp else 300.dp),
            ) {
                items(categories, key = { it.id }) { c ->
                    CategoryToggleRow(
                        name = c.name,
                        checked = c.id in picked,
                        onToggle = {
                            picked = if (c.id in picked) picked - c.id else picked + c.id
                        },
                    )
                }
            }
            if (creating) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text(tr("New category")) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = newName.isNotBlank(),
                        onClick = {
                            val name = newName.trim()
                            newName = ""
                            creating = false
                            scope.launch {
                                val created = onCreateCategory(name)
                                picked = picked + created.id
                            }
                        },
                    ) {
                        Text(tr("Add"))
                    }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { creating = true }
                        .padding(horizontal = 4.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        tr("Create a new category"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (onRemove != null) {
                TextButton(
                    onClick = onRemove,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                ) {
                    Text(
                        removeLabel.ifBlank { tr("Remove from library") },
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Surface(
                onClick = { onConfirm(picked) },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            ) {
                Box(Modifier.padding(vertical = 15.dp), contentAlignment = Alignment.Center) {
                    Text(
                        confirmLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One category line in the picker: a checkbox, the name, and nothing else. */
@Composable
private fun CategoryToggleRow(name: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Manage the categories themselves: rename one, delete one, add one. Opened
 * from the Library header, so the list the user is looking at is the list they
 * are editing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManagerSheet(
    categories: List<LibraryCategory>,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                tr("Library categories"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                tr("Organise your saved titles. Renaming keeps every title filed under it."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            LazyColumn(Modifier.height(300.dp)) {
                items(categories, key = { it.id }) { c ->
                    if (editingId == c.id) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = editingText,
                                onValueChange = { editingText = it },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                enabled = editingText.isNotBlank(),
                                onClick = {
                                    onRename(c.id, editingText.trim())
                                    editingId = null
                                },
                            ) {
                                Icon(
                                    Icons.Filled.Done,
                                    contentDescription = tr("Save"),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(onClick = { editingId = null }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = tr("Cancel"),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editingId = c.id
                                    editingText = c.name
                                }
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                c.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (c.builtIn) {
                                Text(
                                    tr("Built-in"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            }
                            IconButton(onClick = { onDelete(c.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = tr("Delete"),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text(tr("New category")) },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        onCreate(newName.trim())
                        newName = ""
                    },
                ) {
                    Text(tr("Add"))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
