package com.example.lifeos.ui.leads

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.lifeos.data.LeadEntity
import com.example.lifeos.data.LeadRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

val URGENCIES = listOf("low", "medium", "high")

/**
 * Owns Leads-screen state: the open-leads list (Room Flow, read-only from here), the
 * quick-add inline field, and the edit bottom sheet. Validation state lives here via
 * computed properties, rendered by the field widgets as isError/supportingText.
 */
class LeadsViewModel(private val repository: LeadRepository) : ViewModel() {

    val leads: StateFlow<List<LeadEntity>> = repository.openLeads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- quick add ----
    var showQuickAdd by mutableStateOf(false)
        private set
    var quickAddText by mutableStateOf("")
        private set
    val quickAddError: String?
        get() = if (showQuickAdd && quickAddText.isBlank()) "Lead text cannot be empty" else null

    fun openQuickAdd() {
        showQuickAdd = true
    }

    fun updateQuickAddText(value: String) {
        quickAddText = value
    }

    fun cancelQuickAdd() {
        showQuickAdd = false
        quickAddText = ""
    }

    fun submitQuickAdd() {
        val text = quickAddText.trim()
        if (text.isBlank()) return
        viewModelScope.launch { repository.addLead(text, "medium", null) }
        quickAddText = ""
        showQuickAdd = false
    }

    // ---- edit sheet ----
    var editingLead by mutableStateOf<LeadEntity?>(null)
        private set
    var editText by mutableStateOf("")
        private set
    var editUrgency by mutableStateOf("medium")
        private set
    var editCaseId by mutableStateOf("")
        private set
    val editTextError: String?
        get() = if (editingLead != null && editText.isBlank()) "Lead text cannot be empty" else null

    fun openEdit(lead: LeadEntity) {
        editingLead = lead
        editText = lead.text
        editUrgency = lead.urgency
        editCaseId = lead.caseId?.toString() ?: ""
    }

    fun updateEditText(value: String) { editText = value }
    fun updateEditUrgency(value: String) { editUrgency = value }
    fun updateEditCaseId(value: String) { editCaseId = value }

    fun closeEdit() {
        editingLead = null
    }

    fun saveEdit() {
        val lead = editingLead ?: return
        if (editText.isBlank()) return
        val caseId = editCaseId.trim().toLongOrNull()
        viewModelScope.launch {
            repository.updateLead(
                lead,
                mapOf(
                    "text" to editText.trim(),
                    "urgency" to editUrgency,
                    "case_id" to caseId,
                ),
            )
        }
        editingLead = null
    }

    fun markDone(lead: LeadEntity) {
        viewModelScope.launch { repository.markDone(lead) }
    }

    fun delete(lead: LeadEntity) {
        if (editingLead?.id == lead.id) editingLead = null
        viewModelScope.launch { repository.deleteLead(lead) }
    }
}

class LeadsViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return LeadsViewModel(LeadRepository(context.applicationContext)) as T
    }
}
