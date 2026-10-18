@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kar.reader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kar.reader.core.designsystem.BookCover3D
import com.kar.reader.core.designsystem.ConfirmDialog
import com.kar.reader.core.designsystem.GlassCard
import com.kar.reader.core.designsystem.GlossyButton
import com.kar.reader.core.designsystem.KarTopBar
import com.kar.reader.core.domain.RuleField
import com.kar.reader.core.domain.RuleOp
import com.kar.reader.core.domain.SmartQuery
import com.kar.reader.core.domain.SmartRule
import com.kar.reader.data.db.displayTitle
import com.kar.reader.data.repo.LibraryItem
import com.kar.reader.data.repo.LibraryRepository
import com.kar.reader.data.repo.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RuleDraft(val field: RuleField, val op: RuleOp, val value: String)

@HiltViewModel
class CollectionEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val library: LibraryRepository,
) : ViewModel() {
    val collectionId: String? = savedState.get<String>("id")

    val name = MutableStateFlow("Unfinished PDFs this month")
    val matchAll = MutableStateFlow(true)
    val rules = MutableStateFlow(
        listOf(
            RuleDraft(RuleField.FORMAT, RuleOp.IS, "PDF"),
            RuleDraft(RuleField.ADDED_DAYS, RuleOp.WITHIN_DAYS, "30"),
            RuleDraft(RuleField.PROGRESS, RuleOp.LESS_THAN, "100"),
        ),
    )

    val items: StateFlow<List<LibraryItem>> = profiles.currentId.flatMapLatest { library.observeLibrary(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (collectionId != null) viewModelScope.launch {
            library.getCollection(collectionId)?.let { c ->
                name.value = c.name
                SmartQuery.decode(c.smartQuery)?.let { q ->
                    matchAll.value = q.matchAll
                    rules.value = q.rules.map { RuleDraft(it.field, it.op, it.value) }
                } ?: run { rules.value = emptyList() }
            }
        }
    }

    fun query() = SmartQuery(rules.value.map { SmartRule(it.field, it.op, it.value) }, matchAll.value)

    fun setRule(index: Int, rule: RuleDraft) = rules.update { list -> list.toMutableList().also { it[index] = rule } }
    fun addRule() = rules.update { it + RuleDraft(RuleField.TAG, RuleOp.IS, "") }
    fun removeRule(index: Int) = rules.update { list -> list.filterIndexed { i, _ -> i != index } }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        library.saveCollection(profiles.currentIdNow(), collectionId, name.value, query())
        onDone()
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        collectionId?.let { library.deleteCollection(it) }
        onDone()
    }
}

@Composable
fun CollectionEditorScreen(onBack: () -> Unit, vm: CollectionEditorViewModel = hiltViewModel()) {
    val name by vm.name.collectAsStateWithLifecycle()
    val matchAll by vm.matchAll.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }
    val query = SmartQuery(rules.map { SmartRule(it.field, it.op, it.value) }, matchAll)
    val matches = items.filter { query.matches(it.facts(), now) }

    Column(Modifier.fillMaxSize()) {
        KarTopBar(
            title = if (vm.collectionId == null) "New smart collection" else "Edit smart collection",
            onBack = onBack, backIcon = Icons.AutoMirrored.Filled.ArrowBack,
            actions = {
                if (vm.collectionId != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete collection") }
            },
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(130.dp),
            contentPadding = PaddingValues(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                GlassCard(Modifier.fillMaxWidth()) {
                    OutlinedTextField(name, { vm.name.value = it }, label = { Text("Collection name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Match", modifier = Modifier.padding(end = 8.dp))
                        FilterChip(selected = matchAll, onClick = { vm.matchAll.value = true }, label = { Text("all rules") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = !matchAll, onClick = { vm.matchAll.value = false }, label = { Text("any rule") })
                    }
                    rules.forEachIndexed { index, rule ->
                        Spacer(Modifier.height(10.dp))
                        RuleRow(rule, onChange = { vm.setRule(index, it) }, onRemove = { vm.removeRule(index) })
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = vm::addRule) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add rule") }
                        Spacer(Modifier.weight(1f))
                        GlossyButton("Save collection", onClick = { vm.save(onBack) }, enabled = name.isNotBlank())
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("${matches.size} book${if (matches.size == 1) "" else "s"} match · updates automatically", fontWeight = FontWeight.SemiBold)
            }
            items(matches, key = { it.book.id }) { item ->
                Column {
                    BookCover3D(item.book.displayTitle, item.book.format, item.coverFile, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(item.book.displayTitle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${item.progressPercent}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete this collection?", "Only the collection is removed. The books stay in your library.", "Delete",
            onConfirm = { vm.delete(onBack) }, onDismiss = { confirmDelete = false }, destructive = true,
        )
    }
}

@Composable
private fun RuleRow(rule: RuleDraft, onChange: (RuleDraft) -> Unit, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Picker(rule.field.label, RuleField.entries.map { it to it.label }, Modifier.weight(1.2f)) { f ->
            val op = SmartQuery.opsFor(f).first()
            onChange(RuleDraft(f, op, defaultValue(f)))
        }
        Picker(rule.op.label, SmartQuery.opsFor(rule.field).map { it to it.label }, Modifier.weight(1f)) { onChange(rule.copy(op = it)) }
        when (rule.field) {
            RuleField.FORMAT -> Picker(rule.value, listOf("PDF" to "PDF", "EPUB" to "EPUB"), Modifier.weight(1.2f)) { onChange(rule.copy(value = it)) }
            RuleField.FAVORITE -> Picker(if (rule.value == "yes") "Yes" else "No", listOf("yes" to "Yes", "no" to "No"), Modifier.weight(1.2f)) { onChange(rule.copy(value = it)) }
            else -> OutlinedTextField(
                rule.value, { onChange(rule.copy(value = it)) }, singleLine = true, modifier = Modifier.weight(1.2f),
                suffix = { Text(suffix(rule.field)) },
            )
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Remove rule") }
    }
}

private fun defaultValue(f: RuleField) = when (f) {
    RuleField.FORMAT -> "PDF"
    RuleField.FAVORITE -> "yes"
    RuleField.ADDED_DAYS, RuleField.OPENED_DAYS -> "30"
    RuleField.PROGRESS -> "50"
    RuleField.HIGHLIGHTS, RuleField.NOTES -> "0"
    RuleField.RATING -> "4"
    RuleField.TAG, RuleField.AUTHOR -> ""
}

private fun suffix(f: RuleField) = when (f) {
    RuleField.ADDED_DAYS, RuleField.OPENED_DAYS -> "days"
    RuleField.PROGRESS -> "%"
    RuleField.RATING -> "stars"
    else -> ""
}

@Composable
private fun <T> Picker(label: String, options: List<Pair<T, String>>, modifier: Modifier, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("$label ▾", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onPick(value); open = false }) }
        }
    }
}
