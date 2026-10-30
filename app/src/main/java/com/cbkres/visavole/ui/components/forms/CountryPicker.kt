package com.cbkres.visavole.ui.components.forms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.data.Country
import com.cbkres.visavole.domain.searchCountries
import com.cbkres.visavole.ui.CountryRow
import com.cbkres.visavole.ui.HazeBox
import com.cbkres.visavole.ui.hazeAlphas

/** Search + list of countries; tap toggles membership in [selected]. */
@Composable
internal fun CountryPicker(
    countries: Map<String, Country>,
    query: String,
    onQuery: (String) -> Unit,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    label: String,
) {
    val listState = rememberLazyListState()
    // Blank query shows the full alphabetical list; otherwise the shared ranked matching
    // (diacritic folding, word initials, typo tolerance) applies.
    val list = if (query.isBlank()) countries.values.sortedBy { it.name }
    else searchCountries(countries, query)
    LaunchedEffect(list, selected) {
        val selectedIndex = list.indexOfFirst { selected.contains(it.iso2) }
        if (selectedIndex < 0) return@LaunchedEffect
        // Scroll only when the selection is off-screen (e.g. opening in edit mode). Tapping an
        // already-visible country must not yank it to the top of the list.
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == selectedIndex }) listState.scrollToItem(selectedIndex)
    }
    Column {
        OutlinedTextField(
            value = query, onValueChange = onQuery,
            label = { Text(label) },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().height(180.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            val (pickerTop, pickerEnd) = listState.hazeAlphas()
            HazeBox(
                pickerTop,
                pickerEnd,
                MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(state = listState, modifier = Modifier.padding(4.dp)) {
                    items(list, key = { it.iso2 }) { entry ->
                        CountryRow(
                            name = entry.name,
                            onClick = { onToggle(entry.iso2) },
                            iso = entry.iso2,
                            selected = selected.contains(entry.iso2),
                        )
                    }
                }
            }
        }
    }
}