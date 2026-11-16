package app.contactrepair.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.contactrepair.core.RepairedContact
import app.contactrepair.core.RegionOption
import app.contactrepair.core.Regions
import app.contactrepair.core.PhoneValidity
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    state: UiState,
    onPick: () -> Unit,
    onPickRecent: (List<android.net.Uri>) -> Unit,
    onClearRecent: () -> Unit,
    onCountryChange: (String) -> Unit,
    onCallingCodeChange: (String) -> Unit,
    onOptionToggle: (String, Boolean) -> Unit,
    onFormatToggle: (String) -> Unit,
    onRepair: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onExclude: (Int) -> Unit,
    onExcludeMany: (Set<Int>) -> Unit,
    onIncludeMany: (Set<Int>) -> Unit,
    onEdit: (Int) -> Unit,
    onSearchChange: (String) -> Unit,
    onSortChange: (SortMode) -> Unit,
    onFilterChange: (FilterMode) -> Unit,
    onEditSave: (Int, String, String, String) -> Unit,
    onEditCancel: () -> Unit,
    onBackToRepair: () -> Unit,
    onWhyPermission: () -> Unit
) {
    when (state.screen) {
        Screen.Repair -> RepairScreen(
            state, onPick, onPickRecent, onClearRecent, onCountryChange,
            onCallingCodeChange, onOptionToggle, onFormatToggle, onRepair, onWhyPermission
        )
        Screen.Review -> ReviewScreen(
            state, onExclude, onExcludeMany, onIncludeMany, onEdit,
            onSearchChange, onSortChange, onFilterChange, onEditSave, onEditCancel,
            onBackToRepair, onSave, onShare
        )
        Screen.Saved -> SavedScreen(state.savedUri, onBackToRepair, onShare)
    }
}

// ---------------------------------------------------------------------
// Repair screen
// ---------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepairScreen(
    s: UiState,
    onPick: () -> Unit,
    onPickRecent: (List<android.net.Uri>) -> Unit,
    onClearRecent: () -> Unit,
    onCountryChange: (String) -> Unit,
    onCallingCodeChange: (String) -> Unit,
    onOptionToggle: (String, Boolean) -> Unit,
    onFormatToggle: (String) -> Unit,
    onRepair: () -> Unit,
    onWhyPermission: () -> Unit
) {
    val catalog = remember { Regions.regionCatalog(Locale.getDefault()) }

    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(catalog, searchQuery) {
        if (searchQuery.isBlank()) catalog else Regions.filterRegions(catalog, searchQuery)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("Contact Repair", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your files stay on this phone — nothing is uploaded. Choosing a file is what grants access to it.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))

        Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Text("Choose contact files")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onWhyPermission) {
            Text("Why is no permission listed?")
        }
        if (s.selectedUris.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("${s.selectedUris.size} file(s) selected", style = MaterialTheme.typography.bodySmall)
        }

        // Recent files (persistable grants from last session).
        if (s.recentUris.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onPickRecent(s.recentUris) }) {
                    Text("Use last files (${s.recentUris.size})")
                }
                TextButton(onClick = onClearRecent) {
                    Text("Clear")
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search country") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered) { opt ->
                    RegionRow(
                        option = opt,
                        selected = opt.regionCode == s.country,
                        onClick = {
                            onCountryChange(opt.regionCode)
                            if (opt.callingCode.isNotEmpty()) onCallingCodeChange(opt.callingCode)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = s.callingCode,
            onValueChange = { onCallingCodeChange(it) },
            label = { Text("Calling code") },
            placeholder = { Text("+90") },
            supportingText = {
                Text("Optional. Type +90, 90, or a two-letter code such as TR. Used only when a number has no country code. Overrides the country above.")
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))

        SwitchRow("Merge duplicates", s.merge, "merge", onOptionToggle)
        Spacer(Modifier.height(8.dp))
        SwitchRow("Fix shouty names", s.shouty, "shouty", onOptionToggle)
        Spacer(Modifier.height(8.dp))
        SwitchRow("Drop empty cards", s.dropEmpty, "dropEmpty", onOptionToggle)
        Spacer(Modifier.height(8.dp))
        SwitchRow("Redact sensitive (home address, extensions, SSN)", s.redact, "redact", onOptionToggle)

        Spacer(Modifier.height(24.dp))

        Text("Export formats (added to the zip)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        FormatCheckbox("vCard 4.0 (.vcf)", "vcf4", s.outputFormats, onFormatToggle)
        FormatCheckbox("JSON (.json)", "json", s.outputFormats, onFormatToggle)
        FormatCheckbox("One .vcf per contact", "perContact", s.outputFormats, onFormatToggle)

        Spacer(Modifier.height(24.dp))

        Button(onClick = onRepair, enabled = s.selectedUris.isNotEmpty() && !s.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Repair")
        }

        if (s.busy) {
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Text(s.progressText ?: "Repairing…")
            }
        }

        s.error?.let {
            Spacer(Modifier.height(16.dp))
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Accepted: .vcf, .vcard, .csv, .txt, Outlook/Apple CSV, .tab (and zips containing them).",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun FormatCheckbox(label: String, key: String, selected: Set<String>, onToggle: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Checkbox(checked = key in selected, onCheckedChange = { onToggle(key) })
    }
}

@Composable
fun RegionRow(
    option: RegionOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = option.displayName, style = MaterialTheme.typography.bodyLarge)
            if (option.callingCode.isNotEmpty()) {
                Text(
                    text = option.callingCode,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, key: String, onChange: (String, Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = { onChange(key, it) })
    }
}

// ---------------------------------------------------------------------
// Review screen — search, sort, filter, bulk actions, diff
// ---------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    s: UiState,
    onExclude: (Int) -> Unit,
    onExcludeMany: (Set<Int>) -> Unit,
    onIncludeMany: (Set<Int>) -> Unit,
    onEdit: (Int) -> Unit,
    onSearchChange: (String) -> Unit,
    onSortChange: (SortMode) -> Unit,
    onFilterChange: (FilterMode) -> Unit,
    onEditSave: (Int, String, String, String) -> Unit,
    onEditCancel: () -> Unit,
    onBackToRepair: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    val result = s.result ?: run {
        Column(Modifier.padding(24.dp)) {
            Text("No repaired contacts.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = onBackToRepair) { Text("Back") }
        }
        return
    }
    val r = result.report

    // Apply search + filter + sort to the visible list (original indices preserved).
    val visible: List<Pair<Int, RepairedContact>> = remember(result, s.searchQuery, s.filterMode, s.sortMode) {
        val q = s.searchQuery.trim().lowercase(Locale.ROOT)
        val base = result.cards.mapIndexed { i, c -> i to c }
            .filter { (i, c) ->
                when (s.filterMode) {
                    FilterMode.All -> true
                    FilterMode.HasWarnings -> c.warnings.isNotEmpty()
                    FilterMode.Unnamed -> !c.hasNameInfo()
                }
            }
            .filter { (_, c) ->
                if (q.isEmpty()) true
                else c.fn.lowercase(Locale.ROOT).contains(q) ||
                    c.phones.any { it.value.contains(q) } ||
                    c.emails.any { it.value.contains(q) }
            }
        when (s.sortMode) {
            SortMode.Name -> base.sortedBy { (_, c) -> c.fn.lowercase(Locale.ROOT) }
            SortMode.Warnings -> base.sortedByDescending { (_, c) -> c.warnings.size }
            SortMode.Source -> base.sortedBy { (i, _) -> i }
        }
    }

    val editIndex = s.editingIndex
    val warningIdx = visible.map { it.first }.filter { idx -> result.cards[idx].warnings.isNotEmpty() }.toSet()
    val unnamedIdx = visible.map { it.first }.filter { idx -> !result.cards[idx].hasNameInfo() }.toSet()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Review", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBackToRepair) { Text("Back") }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                CountRow("Cards read", r.cardsRead)
                CountRow("Merged", r.merged)
                CountRow("Phones rewritten", r.phonesRewritten)
                CountRow("Invalid phones", r.invalidPhones)
                CountRow("Warnings", r.warnings.size)
                if (r.redacted > 0) CountRow("Redacted fields", r.redacted)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Search
        OutlinedTextField(
            value = s.searchQuery,
            onValueChange = onSearchChange,
            label = { Text("Search name, phone, or email") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        // Filter chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("All", s.filterMode == FilterMode.All) { onFilterChange(FilterMode.All) }
            FilterChip("Warnings", s.filterMode == FilterMode.HasWarnings) { onFilterChange(FilterMode.HasWarnings) }
            FilterChip("Unnamed", s.filterMode == FilterMode.Unnamed) { onFilterChange(FilterMode.Unnamed) }
        }
        Spacer(Modifier.height(8.dp))

        // Sort
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sort:", style = MaterialTheme.typography.bodyMedium)
            FilterChip("Name", s.sortMode == SortMode.Name) { onSortChange(SortMode.Name) }
            FilterChip("Warnings", s.sortMode == SortMode.Warnings) { onSortChange(SortMode.Warnings) }
            FilterChip("Source", s.sortMode == SortMode.Source) { onSortChange(SortMode.Source) }
        }
        Spacer(Modifier.height(12.dp))

        // Bulk actions
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onExcludeMany(warningIdx) }) { Text("Exclude all with warnings") }
            TextButton(onClick = { onIncludeMany(unnamedIdx) }) { Text("Include all unnamed") }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(visible, key = { _, item -> item.first }) { _, (idx, c) ->
                ContactRow(
                    contact = c,
                    excluded = idx in s.excluded,
                    onClick = { onEdit(idx) },
                    onToggleExclude = { onExclude(idx) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text("Save repaired contacts")
        }
        Text(
            "${result.cards.size - s.excluded.size} of ${result.cards.size} contacts will be exported.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (editIndex != null && editIndex < result.cards.size) {
        EditContactDialog(
            contact = result.cards[editIndex],
            onDismiss = onEditCancel,
            onConfirm = { fn, phone, email -> onEditSave(editIndex, fn, phone, email) }
        )
    }
}

@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(onClick = onClick, label = { Text(label) }, selected = selected)
}

@Composable
fun CountRow(label: String, value: Int) {
    Row(modifier = Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ContactRow(
    contact: RepairedContact,
    excluded: Boolean,
    onClick: () -> Unit,
    onToggleExclude: () -> Unit
) {
    val hasWarning = contact.warnings.isNotEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = contact.fn.ifBlank { "(unnamed)" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                if (hasWarning) {
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.size(8.dp).background(MaterialTheme.colorScheme.error))
                }
                contact.mergeConfidence?.let { conf ->
                    Spacer(Modifier.width(8.dp))
                    AssistChip(onClick = {}, label = { Text("merged: ${conf.name.lowercase()}") })
                }
            }
            val phone = contact.phones.firstOrNull()?.value
            val phoneLabel = contact.phones.firstOrNull()?.typeLabel
            val email = contact.emails.firstOrNull()?.value
            val line1 = listOfNotNull(phone, email).joinToString("  ·  ")
            if (line1.isNotEmpty()) {
                Text(text = line1, style = MaterialTheme.typography.bodySmall)
            }
            if (!phoneLabel.isNullOrEmpty() && phone != null) {
                Text(
                    text = phoneLabel + if (contact.phones.first().validity == PhoneValidity.INVALID) " (check)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (contact.changes.isNotEmpty()) {
                Text(
                    text = "${contact.changes.size} field(s) changed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Checkbox(checked = !excluded, onCheckedChange = { onToggleExclude() }, modifier = Modifier.size(48.dp))
    }
}

// ---------------------------------------------------------------------
// Edit dialog — with before/after diff
// ---------------------------------------------------------------------

@Composable
fun EditContactDialog(
    contact: RepairedContact,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var fn by remember { mutableStateOf(contact.fn) }
    var phone by remember { mutableStateOf(contact.phones.firstOrNull()?.value ?: "") }
    var email by remember { mutableStateOf(contact.emails.firstOrNull()?.value ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit contact") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Phone labels + validity.
                if (contact.phones.isNotEmpty()) {
                    Text("Phones", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    contact.phones.take(4).forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.value, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(8.dp))
                            Text(p.typeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (p.validity == PhoneValidity.INVALID) {
                                Spacer(Modifier.width(8.dp))
                                Text("(invalid)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                OutlinedTextField(value = fn, onValueChange = { fn = it }, label = { Text("Display name") })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Primary phone") })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Primary email") })

                // Before/after diff.
                if (contact.changes.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("Changes made by repair", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    contact.changes.take(10).forEach { ch ->
                        Text(
                            text = "${ch.label}: ${ch.before} → ${ch.after}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (contact.changes.size > 10) {
                        Text("… and ${contact.changes.size - 10} more", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(fn, phone, email) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ---------------------------------------------------------------------
// Saved screen
// ---------------------------------------------------------------------

@Composable
fun SavedScreen(savedUri: android.net.Uri?, onBackToRepair: () -> Unit, onShare: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("Saved!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            "Your repaired contacts zip has been created. Nothing was uploaded by this app.",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(24.dp))
        Text("Import into Google Contacts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        listOf(
            "1. Open Google Contacts (contacts.google.com) or the Contacts app.",
            "2. Choose Import, then select the .vcf file inside the zip.",
            "3. On a phone, unzip first and pick contacts.vcf.",
            "4. Review and confirm the import."
        ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(24.dp))
        if (savedUri != null) {
            Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Text("Share this zip")
            }
            Spacer(Modifier.height(12.dp))
        }
        Button(onClick = onBackToRepair, modifier = Modifier.fillMaxWidth()) {
            Text("Repair more files")
        }
    }
}
