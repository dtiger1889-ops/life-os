package com.example.lifeos.ui.fields

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Single-select chip row for an enum-validated field (urgency/status/...). Part of the
 * field-widget library -- every value in `options` is by construction valid, so this widget
 * can never produce an out-of-enum value (the validation is structural here, not a runtime
 * check).
 *
 * Horizontally scrollable: a plain Row fits short option lists (2-3 items) fine, but a
 * longer options list would render its last chips off the edge of a bottom sheet with no
 * way to reach them -- `horizontalScroll` costs nothing for the short lists and fixes the
 * long ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SelectChips(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionLabel: (T) -> String = { it.toString() },
) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(optionLabel(option)) },
                )
            }
        }
    }
}
