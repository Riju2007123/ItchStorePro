package io.itchstore.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: AppVM) {
    var sheet by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val active = vm.query.isNotBlank() || vm.searchFilters.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        TagSearchBar(vm, onFilters = { sheet = true }, onSearch = { vm.runSearch(); focus.clearFocus() })
        if (!active) FilterPicker(vm, Modifier.fillMaxSize())
        else GameGrid(vm.search, { vm.selected = it })
    }
    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            FilterPicker(vm, Modifier.fillMaxWidth().height(520.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagSearchBar(vm: AppVM, onFilters: () -> Unit, onSearch: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search, "Search",
                Modifier.padding(end = 8.dp).clickable { onSearch() }
            )
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                vm.searchFilters.forEach { f ->
                    InputChip(
                        selected = true,
                        onClick = { vm.toggleFilter(f) },
                        label = { Text(f.label, fontSize = 13.sp) },
                        trailingIcon = {
                            Icon(Icons.Default.Close, "Remove ${f.label}", Modifier.size(16.dp))
                        }
                    )
                }
                BasicTextField(
                    value = vm.draft,
                    onValueChange = { vm.updateDraft(it) },
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.widthIn(min = 110.dp).height(32.dp),
                    decorationBox = { inner ->
                        Box(Modifier.height(32.dp), contentAlignment = Alignment.CenterStart) {
                            if (vm.draft.isEmpty() && vm.searchFilters.isEmpty()) {
                                Text("Search games or add tags", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            inner()
                        }
                    }
                )
            }
            if (vm.draft.isNotEmpty() || vm.searchFilters.isNotEmpty()) {
                IconButton({ vm.clearSearch() }) { Icon(Icons.Default.Clear, "Clear all") }
            }
            IconButton(onFilters) { Icon(Icons.Default.List, "Filters") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChipFlow(list: List<Filter>, selected: Set<String>, onPick: (Filter) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEach { f ->
            FilterChip(selected = f.slug in selected, onClick = { onPick(f) }, label = { Text(f.label) })
        }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun FilterPicker(vm: AppVM, modifier: Modifier = Modifier) {
    var tagQ by remember { mutableStateOf("") }
    var askAdult by remember { mutableStateOf(false) }
    val selected = vm.searchFilters.map { it.slug }.toSet()
    val shown = remember(tagQ, vm.allTags) {
        vm.allTags.filter { tagQ.isBlank() || it.label.contains(tagQ.trim(), ignoreCase = true) }.take(90)
    }

    LazyColumn(
        modifier, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { SectionTitle("Price") }
        item { ChipFlow(PRICE_FILTERS, selected) { vm.toggleFilter(it) } }
        item { SectionTitle("Platform") }
        item { ChipFlow(PLATFORM_FILTERS, selected) { vm.toggleFilter(it) } }
        item { SectionTitle("Genre") }
        item { ChipFlow(GENRE_FILTERS, selected) { vm.toggleFilter(it) } }
        item { SectionTitle("Tags (${vm.allTags.size})") }
        item {
            OutlinedTextField(
                value = tagQ, onValueChange = { tagQ = it },
                placeholder = { Text("Find a tag") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item { ChipFlow(shown, selected) { vm.toggleFilter(it) } }
        if (shown.size >= 90) item {
            Text("Showing the first 90. Type to narrow down.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { SectionTitle("Adult content (18+)") }
        item {
            ChipFlow(ADULT_FILTERS, selected) { f ->
                if (f.slug in selected || vm.adultOk) vm.toggleFilter(f) else askAdult = true
            }
        }
    }

    if (askAdult) {
        AlertDialog(
            onDismissRequest = { askAdult = false },
            title = { Text("Adult content") },
            text = { Text("This shows games marked for adults. Continue only if you're 18 or older.") },
            confirmButton = {
                TextButton({
                    vm.confirmAdult(); askAdult = false
                    vm.toggleFilter(ADULT_FILTERS[0])
                }) { Text("I'm 18+") }
            },
            dismissButton = { TextButton({ askAdult = false }) { Text("Cancel") } }
        )
    }
}
