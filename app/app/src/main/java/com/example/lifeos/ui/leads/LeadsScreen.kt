package com.example.lifeos.ui.leads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lifeos.data.LeadEntity
import com.example.lifeos.ui.fields.CheckboxField
import com.example.lifeos.ui.fields.SelectChips
import com.example.lifeos.ui.fields.ValidatedTextField

/**
 * Leads screen -- the reference casework dashboard's native Edit-tab domain. Open-lead list
 * from Room (stable `key = lead.id`); quick-add via FAB (1 tap opens the inline field, focus
 * auto-requests the keyboard); edit bottom sheet; done checkbox; delete. All writes go
 * through LeadsViewModel -> LeadRepository, offline-first via the outbox
 * (see data/LeadRepository.kt).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeadsScreen(viewModel: LeadsViewModel, modifier: Modifier = Modifier) {
    val leads by viewModel.leads.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (viewModel.showQuickAdd) {
                item(key = "quick-add-row") {
                    QuickAddRow(
                        text = viewModel.quickAddText,
                        onTextChange = viewModel::updateQuickAddText,
                        errorText = viewModel.quickAddError,
                        onSubmit = { viewModel.submitQuickAdd() },
                        onCancel = { viewModel.cancelQuickAdd() },
                    )
                }
            }

            items(leads, key = { it.id }) { lead ->
                LeadRow(
                    lead = lead,
                    onMarkDone = { viewModel.markDone(lead) },
                    onClick = { viewModel.openEdit(lead) },
                    onDelete = { viewModel.delete(lead) },
                )
            }
        }

        FloatingActionButton(
            onClick = { viewModel.openQuickAdd() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add lead")
        }
    }

    viewModel.editingLead?.let { EditLeadSheet(viewModel) }
}

/**
 * Stateless (screenshot-testable, per the PC verification rig): the caller wires this to
 * LeadsViewModel. `autoFocus` defaults on for real use (1-tap-to-keyboard quick add) but is
 * turned off for the screenshot-test preview so recording a static PNG doesn't fight the IME.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickAddRow(
    text: String,
    onTextChange: (String) -> Unit,
    errorText: String?,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    if (autoFocus) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        ValidatedTextField(
            value = text,
            onValueChange = onTextChange,
            label = "New lead",
            required = true,
            errorText = errorText,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                onSubmit()
                keyboardController?.hide()
            }),
        )
        IconButton(onClick = onCancel) {
            Icon(Icons.Default.Close, contentDescription = "Cancel quick add")
        }
    }
}

/** Already stateless -- internal (not private) so the screenshotTest source set can preview
 * it directly, per the PC verification rig. */
@Composable
internal fun LeadRow(
    lead: LeadEntity,
    onMarkDone: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    // Locally-created rows not yet synced (temp negative id) can't yet be updated/deleted
    // server-side -- the create mutation must land first (see LeadRepository doc comment).
    val pendingSync = lead.id < 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { if (!pendingSync) onClick() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckboxField(
                label = "",
                checked = lead.done,
                onCheckedChange = { onMarkDone() },
                enabled = !pendingSync,
                modifier = Modifier,
            )
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(text = lead.text, fontWeight = FontWeight.Medium)
                val subtitleParts = mutableListOf<String>()
                subtitleParts.add("${lead.urgency} urgency")
                lead.caseId?.let { subtitleParts.add("case #$it") }
                if (pendingSync) subtitleParts.add("syncing…")
                Text(
                    text = subtitleParts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { if (!pendingSync) onDelete() }, enabled = !pendingSync) {
                Icon(Icons.Default.Delete, contentDescription = "Delete lead")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditLeadSheet(viewModel: LeadsViewModel) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = { viewModel.closeEdit() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Edit lead", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            ValidatedTextField(
                value = viewModel.editText,
                onValueChange = viewModel::updateEditText,
                label = "Lead text",
                required = true,
                errorText = viewModel.editTextError,
                modifier = Modifier.fillMaxWidth(),
            )

            SelectChips(
                label = "Urgency",
                options = URGENCIES,
                selected = viewModel.editUrgency,
                onSelect = viewModel::updateEditUrgency,
            )

            ValidatedTextField(
                value = viewModel.editCaseId,
                onValueChange = viewModel::updateEditCaseId,
                label = "Case # (optional)",
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(4.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { viewModel.closeEdit() }) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { viewModel.saveEdit() },
                    enabled = viewModel.editTextError == null,
                ) { Text("Save") }
            }
        }
    }
}
