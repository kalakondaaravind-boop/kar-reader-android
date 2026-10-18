@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.kar.reader.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kar.reader.core.designsystem.BookCover3D
import com.kar.reader.core.designsystem.ConfirmDialog
import com.kar.reader.core.designsystem.EmptyState
import com.kar.reader.core.designsystem.GlassCard
import com.kar.reader.core.designsystem.GlossyButton
import com.kar.reader.core.designsystem.GlossyKind
import com.kar.reader.core.designsystem.GlossyProgress
import com.kar.reader.core.designsystem.KarColors
import com.kar.reader.core.designsystem.RatingBar
import com.kar.reader.core.designsystem.SectionLabel
import com.kar.reader.data.db.displayAuthor
import com.kar.reader.data.db.displayTitle
import com.kar.reader.data.files.ImportProblem
import com.kar.reader.data.prefs.LibrarySort
import com.kar.reader.data.repo.LibraryItem
import com.kar.reader.ui.common.Format

@Composable
fun LibraryScreen(
    activity: FragmentActivity,
    onOpenBook: (String) -> Unit,
    onScan: () -> Unit,
    onCalibre: () -> Unit,
    onEditCollection: (String?) -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()
    var importMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var detailsFor by remember { mutableStateOf<LibraryItem?>(null) }
    var binFor by remember { mutableStateOf<LibraryItem?>(null) }
    var collectionsFor by remember { mutableStateOf<LibraryItem?>(null) }
    var newCollection by rememberSaveable { mutableStateOf(false) }
    var lockError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.stage(uris) }
    val pickTypes = arrayOf("application/pdf", "application/epub+zip", "application/zip", "application/x-zip-compressed", "application/octet-stream")

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionLabel("KAR READER")
                    Text("My Library", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { vm.setGrid(!state.grid) }) {
                    Icon(if (state.grid) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView, contentDescription = if (state.grid) "Show as list" else "Show as grid")
                }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort") }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        LibrarySort.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(s.label + if (s == state.sort) "  ✓" else "") }, onClick = { vm.setSort(s); sortMenu = false })
                        }
                    }
                }
                Box {
                    GlossyButton("Import", onClick = { importMenu = true }, icon = Icons.Filled.Add)
                    DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Files or ZIP archive") }, leadingIcon = { Icon(Icons.Filled.FileOpen, null) },
                            onClick = { importMenu = false; picker.launch(pickTypes) },
                        )
                        DropdownMenuItem(text = { Text("Scan paper pages") }, leadingIcon = { Icon(Icons.Filled.CameraAlt, null) }, onClick = { importMenu = false; onScan() })
                        DropdownMenuItem(text = { Text("Calibre library") }, leadingIcon = { Icon(Icons.Outlined.CollectionsBookmark, null) }, onClick = { importMenu = false; onCalibre() })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search title, author, tag or series") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                shape = RoundedCornerShape(24.dp),
            )
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LibraryFilter.entries, key = { it.name }) { f ->
                    FilterChip(
                        selected = state.filter == f,
                        onClick = {
                            if (f == LibraryFilter.PRIVATE && !state.privateUnlocked) vm.lock.unlockPrivate(activity) { lockError = it }
                            vm.setFilter(f)
                        },
                        label = { Text(if (f == LibraryFilter.ALL) "All · ${state.totalCount}" else f.label) },
                        leadingIcon = if (f == LibraryFilter.PRIVATE) ({ Icon(if (state.privateUnlocked) Icons.Filled.LockOpen else Icons.Filled.Lock, null, Modifier.size(16.dp)) }) else null,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                items(state.collections, key = { it.collection.id }) { chip ->
                    FilterChip(
                        selected = state.selectedCollection == chip.collection.id,
                        onClick = { vm.selectCollection(chip.collection.id) },
                        label = { Text("${chip.collection.name} · ${chip.count}") },
                        leadingIcon = if (chip.smart) ({ Icon(Icons.Filled.AutoAwesome, null, Modifier.size(16.dp)) }) else null,
                        trailingIcon = if (state.selectedCollection == chip.collection.id && chip.smart) ({
                            Icon(Icons.Filled.Edit, "Edit rules", Modifier.size(16.dp).combinedClickable(onClick = { onEditCollection(chip.collection.id) }))
                        }) else null,
                    )
                }
                item {
                    TextButton(onClick = { newCollection = true }) { Text("+ Collection") }
                }
                item {
                    TextButton(onClick = { onEditCollection(null) }) { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Smart collection") }
                }
            }
            lockError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        }

        when {
            !state.loaded -> Unit
            state.items.isEmpty() -> EmptyState(
                title = if (state.totalCount == 0) "No books yet" else "Nothing matches",
                message = if (state.totalCount == 0) "Import PDF and EPUB files or a ZIP of books. They're copied into the app, so they stay available offline. Your originals aren't changed."
                else "Try another search, filter or collection.",
                action = if (state.totalCount == 0) ({ GlossyButton("Import files", onClick = { picker.launch(pickTypes) }, icon = Icons.Filled.Add) }) else null,
            )
            state.grid -> LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                contentPadding = PaddingValues(20.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.items, key = { it.book.id }) { item ->
                    BookGridItem(
                        item, onOpen = { onOpenBook(item.book.id) },
                        menu = { BookMenu(item, vm, { detailsFor = item }, { binFor = item }, { collectionsFor = item }) },
                    )
                }
            }
            else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                items(state.items, key = { it.book.id }) { item ->
                    BookListItem(
                        item, onOpen = { onOpenBook(item.book.id) },
                        menu = { BookMenu(item, vm, { detailsFor = item }, { binFor = item }, { collectionsFor = item }) },
                    )
                }
            }
        }
    }

    ImportDialogs(importState, vm)

    detailsFor?.let { item -> BookDetailsSheet(item, onDismiss = { detailsFor = null }, onSave = { t, a, y, p, r, tags -> vm.saveDetails(item, t, a, y, p, r, tags); detailsFor = null }) }

    binFor?.let { item ->
        ConfirmDialog(
            title = "Move to recycle bin?",
            message = "“${item.book.displayTitle}” and its notes, highlights and progress stay in the recycle bin for 30 days, where you can restore them. Your original file outside the app is not affected.",
            confirmText = "Move to bin",
            onConfirm = { vm.moveToBin(item) },
            onDismiss = { binFor = null },
            destructive = true,
        )
    }

    collectionsFor?.let { item -> CollectionPicker(item, state.collections.filter { !it.smart }, vm, onDismiss = { collectionsFor = null }) }

    if (newCollection) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newCollection = false },
            title = { Text("New collection") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)) },
            confirmButton = { TextButton(onClick = { vm.createCollection(name); newCollection = false }, enabled = name.isNotBlank()) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newCollection = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BookGridItem(item: LibraryItem, onOpen: () -> Unit, menu: @Composable () -> Unit) {
    Column(Modifier.combinedClickable(onClick = onOpen)) {
        Box {
            BookCover3D(item.book.displayTitle, item.book.format, item.coverFile, Modifier.fillMaxWidth())
            Row(Modifier.align(Alignment.TopEnd)) {
                if (item.isPrivate) Icon(Icons.Filled.Lock, "Private", tint = KarColors.Gold, modifier = Modifier.padding(6.dp).size(18.dp))
                if (item.fileMissing) Icon(Icons.Filled.Warning, "File missing", tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(6.dp).size(18.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(item.book.displayTitle, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.book.displayAuthor ?: item.book.format, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            menu()
        }
        GlossyProgress(item.progressPercent / 100f, Modifier.fillMaxWidth().padding(top = 4.dp), height = 6.dp)
    }
}

@Composable
private fun BookListItem(item: LibraryItem, onOpen: () -> Unit, menu: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BookCover3D(item.book.displayTitle, item.book.format, item.coverFile, Modifier.width(52.dp), tilt = false)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.book.displayTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(item.book.displayAuthor, item.book.format, Format.bytes(item.book.sizeBytes), if (item.highlightCount > 0) "${item.highlightCount} highlights" else null).joinToString(" · "),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (item.tags.isNotEmpty()) Text(item.tags.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
                GlossyProgress(item.progressPercent / 100f, Modifier.fillMaxWidth().padding(top = 6.dp), height = 5.dp)
            }
            if (item.favorite) Icon(Icons.Filled.Favorite, "Favorite", tint = KarColors.Gold, modifier = Modifier.padding(start = 8.dp).size(18.dp))
            if (item.isPrivate) Icon(Icons.Filled.Lock, "Private", tint = KarColors.Gold, modifier = Modifier.padding(start = 8.dp).size(18.dp))
            menu()
        }
    }
}

@Composable
private fun BookMenu(item: LibraryItem, vm: LibraryViewModel, onDetails: () -> Unit, onBin: () -> Unit, onCollections: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, contentDescription = "Book options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (item.favorite) "Remove from favorites" else "Add to favorites") },
                leadingIcon = { Icon(if (item.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null) },
                onClick = { vm.setFavorite(item, !item.favorite); open = false },
            )
            DropdownMenuItem(text = { Text("Edit details") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { onDetails(); open = false })
            DropdownMenuItem(text = { Text("Collections…") }, leadingIcon = { Icon(Icons.Outlined.CollectionsBookmark, null) }, onClick = { onCollections(); open = false })
            DropdownMenuItem(
                text = { Text(if (item.isPrivate) "Remove from private collection" else "Move to private collection") },
                leadingIcon = { Icon(if (item.isPrivate) Icons.Filled.LockOpen else Icons.Filled.Lock, null) },
                onClick = { vm.setPrivate(item, !item.isPrivate); open = false },
            )
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Move to recycle bin") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { onBin(); open = false })
        }
    }
}

@Composable
private fun CollectionPicker(item: LibraryItem, collections: List<CollectionChip>, vm: LibraryViewModel, onDismiss: () -> Unit) {
    var membership by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(item.book.id) { membership = vm.collectionMembership(item.book.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Collections") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (collections.isEmpty()) Text("No collections yet. Use “+ Collection” in the library to make one.")
                collections.forEach { chip ->
                    val member = chip.collection.id in membership
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Checkbox(checked = member, onCheckedChange = {
                            vm.toggleCollection(chip.collection.id, item, member)
                            membership = if (member) membership - chip.collection.id else membership + chip.collection.id
                        })
                        Text(chip.collection.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun BookDetailsSheet(item: LibraryItem, onDismiss: () -> Unit, onSave: (String, String, String, String, Int, String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember { mutableStateOf(item.book.displayTitle) }
    var author by remember { mutableStateOf(item.book.displayAuthor.orEmpty()) }
    var year by remember { mutableStateOf(item.book.year.orEmpty()) }
    var publisher by remember { mutableStateOf(item.book.publisher.orEmpty()) }
    var rating by remember { mutableStateOf(item.book.rating) }
    var tags by remember { mutableStateOf(item.tags.joinToString(", ")) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Book details", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Your changes are stored in KAR READER only; the document file is never modified. Original: ${item.book.title}${item.book.author?.let { " · $it" } ?: ""}",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(author, { author = it }, label = { Text("Author") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(year, { year = it.take(4) }, label = { Text("Year") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(publisher, { publisher = it }, label = { Text("Publisher") }, modifier = Modifier.weight(2f), singleLine = true)
            }
            OutlinedTextField(tags, { tags = it }, label = { Text("Tags (comma separated)") }, modifier = Modifier.fillMaxWidth())
            Text("Your rating", fontWeight = FontWeight.SemiBold)
            RatingBar(rating, onRate = { rating = it })
            Text(
                "${item.book.format} · ${Format.bytes(item.book.sizeBytes)}${item.book.pageCount?.let { if (item.book.format == "PDF") " · $it pages" else " · $it chapters" } ?: ""} · added ${Format.relative(item.book.addedAt)}",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GlossyButton("Save", onClick = { onSave(title, author, year, publisher, rating, tags) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ImportDialogs(state: ImportUiState, vm: LibraryViewModel) {
    when (state) {
        ImportUiState.Idle -> Unit
        is ImportUiState.Working -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Importing") },
            text = { Column { Text(state.message); Spacer(Modifier.height(16.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::cancelImport) { Text("Cancel") } },
        )
        is ImportUiState.Preview -> {
            val batch = state.batch
            AlertDialog(
                onDismissRequest = vm::cancelImport,
                title = { Text(if (batch.containsArchive) batch.archiveName ?: "ZIP archive" else "Ready to import") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Found ${batch.documents.size} book${if (batch.documents.size == 1) "" else "s"}, ${batch.newDocuments.size} of them new." +
                                if (batch.containsArchive) " Checked offline. Your ZIP file is left untouched." else "",
                        )
                        batch.documents.forEach { d ->
                            Text("${d.format.name} · ${d.title}" + if (d.duplicateOf != null) "  (already in library)" else "", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val rejected = batch.problems.filterIsInstance<ImportProblem.Rejected>()
                        val skipped = batch.problems.filterIsInstance<ImportProblem.Skipped>()
                        if (rejected.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Blocked or unreadable", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                            rejected.forEach { Text("${it.name}: ${it.reason}", fontSize = 13.sp) }
                        }
                        if (skipped.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Skipped", fontWeight = FontWeight.SemiBold)
                            skipped.forEach { Text("${it.name}: ${it.reason}", fontSize = 13.sp) }
                        }
                    }
                },
                confirmButton = {
                    GlossyButton(
                        "Import ${batch.newDocuments.size}", onClick = { vm.confirmImport(batch) },
                        enabled = batch.newDocuments.isNotEmpty(), compact = true,
                    )
                },
                dismissButton = { TextButton(onClick = vm::cancelImport) { Text("Cancel") } },
            )
        }
        is ImportUiState.Done -> AlertDialog(
            onDismissRequest = vm::dismissImportResult,
            title = { Text(if (state.imported > 0) "Import finished" else "Nothing imported") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${state.imported} added · ${state.duplicates} already in your library")
                    state.failed.forEach { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
                    state.skipped.forEach { Text(it, fontSize = 13.sp) }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissImportResult) { Text("OK") } },
        )
    }
}
