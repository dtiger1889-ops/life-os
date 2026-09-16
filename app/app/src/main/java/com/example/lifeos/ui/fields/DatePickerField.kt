package com.example.lifeos.ui.fields

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Nullable ISO-date ("yyyy-MM-dd") field backed by Material3's DatePicker. Part of the
 * field-widget library. The text field itself stays read-only (typing a date by hand is
 * exactly the parse-validation surface this widget exists to remove); a transparent
 * clickable overlay opens the dialog since a read-only OutlinedTextField otherwise swallows
 * taps into its own (disabled) text cursor handling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(
    label: String,
    isoDate: String?,
    onDateChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = isoDate ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text("Not set") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                Row {
                    if (isoDate != null) {
                        IconButton(onClick = { onDateChange(null) }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear $label")
                        }
                    }
                    IconButton(onClick = { showPicker = true }) {
                        Icon(Icons.Default.DateRange, contentDescription = "Pick $label")
                    }
                }
            },
        )
        // Transparent tap-catcher over the whole field so tapping anywhere (not just the
        // trailing icon) opens the picker, matching normal date-field expectations.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showPicker = true },
        ) {}
    }

    if (showPicker) {
        val initialMillis = isoDate?.let {
            runCatching {
                LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }.getOrNull()
        }
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) {
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onDateChange(date.toString())
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}
