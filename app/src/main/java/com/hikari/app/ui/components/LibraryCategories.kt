package com.hikari.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hikari.app.data.LibraryCategory
import com.hikari.app.i18n.tr
import com.hikari.app.tv.tvToggle
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

    // Full height from the start. A partially-expanded sheet is a little over
    // half the screen, which is shorter than title + list + "Create a new
    // category" + the confirm button — the reported "the Add to library button
    // is hidden below the fold" (the sheet could be dragged up, but nothing
    // said so, and a drag on the list just scrolled the list).
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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
                    // Capped, not fixed: a two-category list must not push the
                    // buttons down the way a 300dp box would.
                    .heightIn(max = 300.dp),
            ) {
                items(displayCategories, key = { it.id }) { c ->
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
private fun DragGrip() {
    Column(
        Modifier.padding(start = 4.dp, end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(3) {
            Box(
                Modifier
                    .width(18.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}

private fun categoryDragTarget(
    ids: List<String>,
    centres: Map<String, Float>,
    tops: Map<String, Float>,
    from: Int,
    pointerY: Float,
): Int {
    if (from < 0 || from >= ids.size) return from
    val top = tops[ids[from]]
    val centre = centres[ids[from]]
    if (top != null && centre != null) {
        val rowH = (centre - top).coerceAtLeast(1f) * 2f
        if (from + 1 < ids.size) {
            val nextTop = tops[ids[from + 1]] ?: Float.MAX_VALUE
            if (pointerY > nextTop + rowH * 0.45f) return from + 1
        }
        if (from - 1 >= 0) {
            val prevTop = tops[ids[from - 1]] ?: Float.MIN_VALUE
            if (pointerY < prevTop + rowH * 0.55f) return from - 1
        }
    } else {
        ids.getOrNull(from + 1)?.let { next ->
            centres[next]?.let { if (pointerY > it) return from + 1 }
        }
        ids.getOrNull(from - 1)?.let { prev ->
            centres[prev]?.let { if (pointerY < it) return from - 1 }
        }
    }
    return from
}

private fun categoryAutoScrollStep(pointerY: Float, top: Float, bottom: Float, edge: Float): Float =
    when {
        bottom <= top -> 0f
        pointerY < top + edge -> -6f
        pointerY > bottom - edge -> 6f
        else -> 0f
    }

/** One category line in the picker: a checkbox, the name, and nothing else. */
@Composable
private fun CategoryToggleRow(name: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            // The whole line is the target — for a FINGER as well: a tap
            // anywhere on the row ticks or unticks the category. This is the
            // half that was missing: the row used to answer only the D-pad (see
            // [Modifier.tvToggle]) while its Checkbox was decorative
            // (`onCheckedChange = null`), so on a phone a tap on the row did
            // nothing at all and there was no way to untick what was ticked —
            // "clicking anything to tick, untick is unclickable".
            //
            // [Modifier.toggleable] is what gives a row the touch handler, the
            // pressed-state ripple AND the accessibility semantics a checkbox
            // row needs, in one modifier — the same thing Material's own
            // Checkbox is built on.
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            // …and this is the half that was already there: the D-pad presses
            // and left/right flips arrive in the PREVIEW pass, so a remote
            // cannot tick it twice through both modifiers.
            .tvToggle(checked, onValueChange = { onToggle() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
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
    onMove: (String, Int) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf("") }
    var liftedId by remember { mutableStateOf<String?>(null) }
    // Local order while dragging so moves don't jump the finger to a freshly
    // recomposed row halfway across the list (store writes used to fire on
    // every small drag, which made the list reorder too fast).
    var dragOrder by remember { mutableStateOf<List<LibraryCategory>?>(null) }
    val displayCategories = dragOrder ?: categories
    val listState = rememberLazyListState()
    val dragScope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val rowCentres = remember { HashMap<String, Float>() }
    val rowTops = remember { HashMap<String, Float>() }
    val dragY = remember { floatArrayOf(0f) }
    val lastMoveAt = remember { longArrayOf(0L) }
    val dragStartIndex = remember { intArrayOf(-1) }
    var categoryScrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val listBounds = remember { floatArrayOf(0f, 0f) }
    val autoScrollEdge = with(LocalDensity.current) { 72.dp.toPx() }

    // Opens fully expanded so the list, the new-category field and every row's
    // actions are on screen together (see CategoryPickerSheet).
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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
            if (!LocalHideHelp.current) {
            Text(
                tr("Organise your saved titles. Renaming keeps every title filed under it."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .heightIn(max = 300.dp)
                    .onGloballyPositioned { c ->
                        listBounds[0] = c.boundsInRoot().top
                        listBounds[1] = c.boundsInRoot().bottom
                    },
            ) {
                items(displayCategories, key = { it.id }) { c ->
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
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (liftedId == c.id) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else Color.Transparent,
                                )
                                .clickable {
                                    editingId = c.id
                                    editingText = c.name
                                }
                                .onGloballyPositioned { lc ->
                                    val b = lc.boundsInRoot()
                                    rowCentres[c.id] = b.center.y
                                    rowTops[c.id] = b.top
                                }
                                .pointerInput(c.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            liftedId = c.id
                                            dragY[0] = rowCentres[c.id] ?: 0f
                                            lastMoveAt[0] = 0L
                                            dragOrder = categories.toList()
                                            dragStartIndex[0] = categories.indexOfFirst { it.id == c.id }
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragY[0] += amount.y
                                            val order = dragOrder ?: return@detectDragGesturesAfterLongPress
                                            val from = order.indexOfFirst { it.id == c.id }
                                            if (from >= 0) {
                                                val now = System.currentTimeMillis()
                                                // ~220ms + half-row travel keeps the list
                                                // from leaping under the finger.
                                                if (now - lastMoveAt[0] >= 220L) {
                                                    val to = categoryDragTarget(
                                                        order.map { it.id },
                                                        rowCentres,
                                                        rowTops,
                                                        from,
                                                        dragY[0],
                                                    )
                                                    if (to != from && to in order.indices) {
                                                        lastMoveAt[0] = now
                                                        val mutable = order.toMutableList()
                                                        val item = mutable.removeAt(from)
                                                        mutable.add(to, item)
                                                        dragOrder = mutable
                                                        haptics.performHapticFeedback(
                                                            HapticFeedbackType.TextHandleMove
                                                        )
                                                    }
                                                }
                                            }
                                            val step = categoryAutoScrollStep(
                                                dragY[0],
                                                listBounds[0],
                                                listBounds[1],
                                                autoScrollEdge,
                                            )
                                            categoryScrollJob?.cancel()
                                            categoryScrollJob = if (step != 0f) {
                                                dragScope.launch { listState.scrollBy(step) }
                                            } else null
                                        },
                                        onDragEnd = {
                                            val order = dragOrder
                                            val start = dragStartIndex[0]
                                            val end = order?.indexOfFirst { it.id == c.id } ?: -1
                                            if (order != null && start >= 0 && end >= 0 && end != start) {
                                                onMove(c.id, end - start)
                                            }
                                            liftedId = null
                                            dragOrder = null
                                            dragStartIndex[0] = -1
                                            categoryScrollJob?.cancel()
                                            categoryScrollJob = null
                                        },
                                        onDragCancel = {
                                            liftedId = null
                                            dragOrder = null
                                            dragStartIndex[0] = -1
                                            categoryScrollJob?.cancel()
                                            categoryScrollJob = null
                                        },
                                    )
                                }
                                .padding(vertical = 14.dp, horizontal = 8.dp),
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
                            DragGrip()
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
