package com.example.lifeos.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.lifeos.LifeOsTheme
import com.example.lifeos.PcUnreachableBanner
import com.example.lifeos.data.LeadEntity
import com.example.lifeos.ui.fields.CheckboxField
import com.example.lifeos.ui.fields.CurrencyField
import com.example.lifeos.ui.fields.DatePickerField
import com.example.lifeos.ui.fields.LongTextField
import com.example.lifeos.ui.fields.ReadOnlyField
import com.example.lifeos.ui.fields.SelectChips
import com.example.lifeos.ui.fields.ValidatedTextField
import com.example.lifeos.ui.leads.LeadRow
import com.example.lifeos.ui.leads.QuickAddRow
import com.example.lifeos.ui.leads.URGENCIES

/**
 * Compose Preview Screenshot Tests -- the PC verification rig (no emulator). Render:
 * `gradlew updateDebugScreenshotTest` (record) / `validateDebugScreenshotTest` (check). The
 * plugin's scanner only finds @Preview methods INSIDE a class -- top-level fns are ignored.
 */
class LifeosScreenshots {

    private fun sampleLead(
        id: Long = 1L,
        text: String = "Pull the 221B doorbell-cam footage from the night of the disappearance",
        caseId: Long? = 1L,
        urgency: String = "medium",
        done: Boolean = false,
    ) = LeadEntity(
        id = id,
        text = text,
        caseId = caseId,
        urgency = urgency,
        done = done,
        createdAt = "2026-07-01T00:00:00Z",
        doneAt = if (done) "2026-07-02T00:00:00Z" else null,
        updatedAt = "2026-07-01T00:00:00Z",
        updatedBy = "phone",
        deletedAt = null,
        serverSeq = 12L,
    )

    // ---- field widgets ----

    @Preview(name = "validated-text-field-normal", showBackground = true, widthDp = 360)
    @Composable
    fun validatedTextFieldNormal() {
        LifeOsTheme {
            Surface {
                Column(Modifier.padding(16.dp)) {
                    ValidatedTextField(
                        value = "Ask Wiggins to trace the courier's e-bike serial number",
                        onValueChange = {},
                        label = "Lead text",
                        required = true,
                    )
                }
            }
        }
    }

    @Preview(name = "validated-text-field-error", showBackground = true, widthDp = 360)
    @Composable
    fun validatedTextFieldError() {
        LifeOsTheme {
            Surface {
                Column(Modifier.padding(16.dp)) {
                    ValidatedTextField(
                        value = "",
                        onValueChange = {},
                        label = "Lead text",
                        required = true,
                        errorText = "Lead text cannot be empty",
                    )
                }
            }
        }
    }

    @Preview(name = "long-text-field", showBackground = true, widthDp = 360)
    @Composable
    fun longTextField() {
        LifeOsTheme {
            Surface {
                Column(Modifier.padding(16.dp)) {
                    LongTextField(
                        value = "Get Mrs. Hudson's account of who came and went that week.",
                        onValueChange = {},
                        label = "Notes",
                    )
                }
            }
        }
    }

    @Preview(name = "select-chips", showBackground = true, widthDp = 360)
    @Composable
    fun selectChips() {
        LifeOsTheme {
            Surface {
                Column(Modifier.padding(16.dp)) {
                    SelectChips(
                        label = "Urgency",
                        options = URGENCIES,
                        selected = "medium",
                        onSelect = {},
                    )
                }
            }
        }
    }

    @Preview(name = "date-picker-field", showBackground = true, widthDp = 360)
    @Composable
    fun datePickerField() {
        LifeOsTheme {
            Surface {
                Column(Modifier.padding(16.dp)) {
                    DatePickerField(
                        label = "Expected by",
                        isoDate = "2026-07-15",
                        onDateChange = {},
                    )
                }
            }
        }
    }

    @Preview(name = "checkbox-field", showBackground = true, widthDp = 360)
    @Composable
    fun checkboxField() {
        LifeOsTheme {
            Surface {
                Column(Modifier.padding(16.dp)) {
                    CheckboxField(
                        label = "Done",
                        checked = true,
                        onCheckedChange = {},
                    )
                }
            }
        }
    }

    @Preview(name = "read-only-field", showBackground = true, widthDp = 360)
    @Composable
    fun readOnlyField() {
        LifeOsTheme {
            Surface {
                Column(Modifier.padding(16.dp)) {
                    ReadOnlyField(label = "Open leads", value = "3")
                }
            }
        }
    }

    @Preview(name = "currency-field-normal", showBackground = true, widthDp = 360)
    @Composable
    fun currencyFieldNormal() {
        LifeOsTheme {
            Surface {
                Column(Modifier.padding(16.dp)) {
                    CurrencyField(value = "42.50", onValueChange = {}, label = "Retainer", required = true)
                }
            }
        }
    }

    @Preview(name = "currency-field-error", showBackground = true, widthDp = 360)
    @Composable
    fun currencyFieldError() {
        LifeOsTheme {
            Surface {
                Column(Modifier.padding(16.dp)) {
                    CurrencyField(
                        value = "not a number",
                        onValueChange = {},
                        label = "Retainer",
                        required = true,
                        errorText = "Enter a valid amount",
                    )
                }
            }
        }
    }

    // ---- PcUnreachableBanner (screen-entry reachability probe) ----

    @Preview(name = "pc-unreachable-banner", showBackground = true, widthDp = 360)
    @Composable
    fun pcUnreachableBanner() {
        LifeOsTheme {
            Surface {
                PcUnreachableBanner()
            }
        }
    }

    // ---- LeadRow states ----

    @Preview(name = "lead-row-normal", showBackground = true, widthDp = 360)
    @Composable
    fun leadRowNormal() {
        LifeOsTheme {
            Surface {
                LeadRow(
                    lead = sampleLead(
                        text = "Cross-reference contactless payment terminals near Marylebone Road",
                        urgency = "medium",
                    ),
                    onMarkDone = {},
                    onClick = {},
                    onDelete = {},
                )
            }
        }
    }

    @Preview(name = "lead-row-long-text", showBackground = true, widthDp = 360)
    @Composable
    fun leadRowLongText() {
        LifeOsTheme {
            Surface {
                LeadRow(
                    lead = sampleLead(
                        text = "Request the group chat's server-side timestamps (metadata only, " +
                            "not content) from the private client whose name has been withheld",
                        urgency = "high",
                        caseId = 3L,
                    ),
                    onMarkDone = {},
                    onClick = {},
                    onDelete = {},
                )
            }
        }
    }

    @Preview(name = "lead-row-done", showBackground = true, widthDp = 360)
    @Composable
    fun leadRowDone() {
        LifeOsTheme {
            Surface {
                LeadRow(
                    lead = sampleLead(text = "Interview the night porter at the Diogenes Club annexe", done = true),
                    onMarkDone = {},
                    onClick = {},
                    onDelete = {},
                )
            }
        }
    }

    @Preview(name = "lead-row-pending-sync", showBackground = true, widthDp = 360)
    @Composable
    fun leadRowPendingSync() {
        LifeOsTheme {
            Surface {
                LeadRow(
                    lead = sampleLead(id = -1L, text = "New lead not yet synced", caseId = null),
                    onMarkDone = {},
                    onClick = {},
                    onDelete = {},
                )
            }
        }
    }

    // ---- QuickAddRow states ----

    @Preview(name = "quick-add-row-empty-error", showBackground = true, widthDp = 360)
    @Composable
    fun quickAddRowEmptyError() {
        LifeOsTheme {
            Surface {
                QuickAddRow(
                    text = "",
                    onTextChange = {},
                    errorText = "Lead text cannot be empty",
                    onSubmit = {},
                    onCancel = {},
                    autoFocus = false,
                )
            }
        }
    }

    @Preview(name = "quick-add-row-filled", showBackground = true, widthDp = 360)
    @Composable
    fun quickAddRowFilled() {
        LifeOsTheme {
            Surface {
                QuickAddRow(
                    text = "Interview the night porter at the Diogenes Club annexe",
                    onTextChange = {},
                    errorText = null,
                    onSubmit = {},
                    onCancel = {},
                    autoFocus = false,
                )
            }
        }
    }
}
