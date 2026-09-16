package com.example.lifeos.ui.fields

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import java.math.BigDecimal

/**
 * Currency amount field, part of the field-widget library. The raw string stays in the
 * caller's ViewModel so the user can type freely ("12", "12.5", "12."); [parseCurrency] is
 * the single validation entry point every caller shares -- mirrors ValidatedTextField's
 * isError/supportingText contract.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    errorText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(if (required) "$label *" else label) },
        modifier = modifier,
        isError = errorText != null,
        supportingText = errorText?.let { { Text(it) } },
        singleLine = true,
        leadingIcon = { Text("$") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

/** Parses a currency string to a BigDecimal; null on anything unparseable (blank, non-numeric).
 * Shared by every ViewModel that owns a CurrencyField's validation state. */
fun parseCurrency(raw: String): BigDecimal? =
    raw.trim().takeIf { it.isNotEmpty() }?.let { runCatching { BigDecimal(it) }.getOrNull() }
