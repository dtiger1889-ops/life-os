package com.example.lifeos.ui.fields

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Multi-line free text (notes / context). Part of the field-widget library. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LongTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    errorText: String? = null,
    minLines: Int = 3,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        isError = errorText != null,
        supportingText = errorText?.let { { Text(it) } },
        singleLine = false,
        minLines = minLines,
    )
}
