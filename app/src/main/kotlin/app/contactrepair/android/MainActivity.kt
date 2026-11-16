package app.contactrepair.android

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.contactrepair.core.ManualEdit
import app.contactrepair.core.NamedBytes
import app.contactrepair.core.RepairOptions
import app.contactrepair.core.Writers
import app.contactrepair.core.repair
import app.contactrepair.core.RepairEngine
import app.contactrepair.core.Regions
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val scope = MainScope()

    private lateinit var prefs: SharedPreferences

    // --- SAF pickers ---
    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            for (uri in uris) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Provider refused — the one-shot read is sufficient.
                }
            }
            // Persist recent files (persistable grants already taken).
            persistRecent(uris)
            uiState.value = uiState.value.copy(
                selectedUris = uris,
                recentUris = uris,
                screen = Screen.Repair
            )
        }
    }

    private val createDoc = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) saveZip(uri)
    }

    private val shareDoc = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            saveZip(uri) { shareUri(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.let {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it, false)
        }

        prefs = getSharedPreferences("contact_repair", Context.MODE_PRIVATE)

        val savedCountry = prefs.getString("country", null)
        val defaultCountry = savedCountry
            ?: Locale.getDefault().country
            ?.takeIf { it.length == 2 }
            ?: "US"
        val defaultCallingCode = Regions.regionCatalog(Locale.US)
            .firstOrNull { it.regionCode == defaultCountry }
            ?.callingCode
            .orEmpty()
        val savedCallingCode = prefs.getString("calling_code", null)
        val initialCallingCode = savedCallingCode ?: defaultCallingCode

        val initialRecent = loadRecent()

        uiState.value = uiState.value.copy(
            country = defaultCountry,
            callingCode = initialCallingCode,
            recentUris = initialRecent
        )

        setContent {
            ContactRepairTheme {
                var showExplanation by remember { mutableStateOf(false) }
                var showWhyDialog by remember { mutableStateOf(false) }

                AppRoot(
                    state = uiState.value,
                    onPick = { showExplanation = true },
                    onPickRecent = { uris ->
                        uiState.value = uiState.value.copy(selectedUris = uris)
                    },
                    onClearRecent = {
                        prefs.edit().remove("recent_uris").apply()
                        uiState.value = uiState.value.copy(recentUris = emptyList())
                    },
                    onCountryChange = { c ->
                        prefs.edit().putString("country", c).apply()
                        uiState.value = uiState.value.copy(country = c)
                    },
                    onCallingCodeChange = { cc ->
                        prefs.edit().putString("calling_code", cc).apply()
                        val s = uiState.value
                        uiState.value = s.copy(callingCode = cc)
                        val ctx = Regions.resolveParseContext(s.country, cc)
                        if (!ctx.customUnknown && ctx.callingCodeDigits != null) {
                            uiState.value = uiState.value.copy(country = ctx.regionCode)
                        }
                    },
                    onOptionToggle = { key, value ->
                        val s = uiState.value
                        uiState.value = when (key) {
                            "merge" -> s.copy(merge = value)
                            "shouty" -> s.copy(shouty = value)
                            "dropEmpty" -> s.copy(dropEmpty = value)
                            "redact" -> s.copy(redact = value)
                            else -> s
                        }
                    },
                    onFormatToggle = { key ->
                        val s = uiState.value
                        val set = s.outputFormats.toMutableSet()
                        if (set.contains(key)) set.remove(key) else set.add(key)
                        uiState.value = s.copy(outputFormats = set)
                    },
                    onRepair = { doRepair() },
                    onSave = { createDoc.launch("contacts-repaired.zip") },
                    onShare = { shareDoc.launch("contacts-repaired.zip") },
                    onExclude = { idx ->
                        val s = uiState.value
                        val set = s.excluded.toMutableSet()
                        if (set.contains(idx)) set.remove(idx) else set.add(idx)
                        uiState.value = s.copy(excluded = set)
                    },
                    onExcludeMany = { idxs ->
                        val s = uiState.value
                        uiState.value = s.copy(excluded = s.excluded.union(idxs))
                    },
                    onIncludeMany = { idxs ->
                        val s = uiState.value
                        uiState.value = s.copy(excluded = s.excluded.subtract(idxs))
                    },
                    onEdit = { idx ->
                        uiState.value = uiState.value.copy(editingIndex = idx)
                    },
                    onSearchChange = { q ->
                        uiState.value = uiState.value.copy(searchQuery = q)
                    },
                    onSortChange = { m ->
                        uiState.value = uiState.value.copy(sortMode = m)
                    },
                    onFilterChange = { m ->
                        uiState.value = uiState.value.copy(filterMode = m)
                    },
                    onEditSave = { idx, fn, phone, email ->
                        val s = uiState.value
                        val cards = s.result?.cards ?: emptyList()
                        if (idx < cards.size) {
                            val options = RepairOptions(
                                defaultRegion = s.country,
                                mergeDuplicates = s.merge,
                                fixShoutyNames = s.shouty,
                                dropEmptyCards = s.dropEmpty,
                                preferredCallingCode = s.callingCode,
                                redactSensitive = s.redact
                            )
                            val edit = ManualEdit(
                                displayName = if (fn.isNotBlank()) fn else null,
                                primaryPhone = if (phone.isNotBlank()) phone else null,
                                primaryEmail = if (email.isNotBlank()) email else null
                            )
                            val updated = cards.mapIndexed { i, c ->
                                if (i == idx) RepairEngine.applyManualEdit(c, edit, options) else c
                            }
                            uiState.value = s.copy(
                                result = s.result?.copy(cards = updated) ?: null,
                                editingIndex = null
                            )
                        }
                    },
                    onEditCancel = {
                        uiState.value = uiState.value.copy(editingIndex = null)
                    },
                    onBackToRepair = {
                        uiState.value = uiState.value.copy(screen = Screen.Repair)
                    },
                    onWhyPermission = { showWhyDialog = true }
                )

                if (showExplanation) {
                    ExplanationDialog(
                        onDismiss = { showExplanation = false },
                        onConfirm = {
                            showExplanation = false
                            pickFiles.launch(arrayOf(
                                "text/vcard", "text/x-vcard", "text/csv",
                                "text/comma-separated-values", "text/plain",
                                "application/zip", "application/octet-stream"
                            ))
                        },
                        prefs = prefs
                    )
                }

                if (showWhyDialog) {
                    AlertDialog(
                        onDismissRequest = { showWhyDialog = false },
                        title = { Text("Why is no permission listed?") },
                        text = { Text(EXPLANATION_BODY) },
                        confirmButton = {
                            TextButton(onClick = { showWhyDialog = false }) { Text("Close") }
                        }
                    )
                }
            }
        }
    }

    // --- Recent files (persistable URIs from last session) ---

    private fun persistRecent(uris: List<Uri>) {
        prefs.edit().putString("recent_uris", uris.joinToString("|") { it.toString() }).apply()
    }

    private fun loadRecent(): List<Uri> =
        prefs.getString("recent_uris", "")
            .orEmpty()
            .split("|")
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }

    private fun doRepair() {
        val s = uiState.value
        val uris = s.selectedUris
        if (uris.isEmpty()) return
        uiState.value = s.copy(
            busy = true,
            error = null,
            progressText = "Reading ${uris.size} file(s)…"
        )
        scope.launch {
            try {
                val files = withContext(Dispatchers.Default) {
                    uris.mapIndexed { i, uri ->
                        uiState.value = uiState.value.copy(
                            progressText = "Reading file ${i + 1} of ${uris.size}…"
                        )
                        NamedBytes(uri.lastPathSegment ?: "file", readUri(uri))
                    }
                }
                val options = RepairOptions(
                    defaultRegion = s.country,
                    mergeDuplicates = s.merge,
                    fixShoutyNames = s.shouty,
                    dropEmptyCards = s.dropEmpty,
                    preferredCallingCode = s.callingCode,
                    redactSensitive = s.redact
                )
                uiState.value = uiState.value.copy(progressText = "Repairing…")
                val result = withContext(Dispatchers.Default) {
                    repair(files, options)
                }
                uiState.value = uiState.value.copy(
                    busy = false,
                    progressText = null,
                    result = result,
                    screen = Screen.Review,
                    excluded = emptySet(),
                    editingIndex = null,
                    searchQuery = "",
                    sortMode = SortMode.Name,
                    filterMode = FilterMode.All
                )
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    busy = false,
                    progressText = null,
                    error = "Repair failed: ${e.message}",
                    screen = Screen.Repair
                )
            }
        }
    }

    private fun readUri(uri: Uri): ByteArray {
        return contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Cannot read $uri")
    }

    private fun saveZip(uri: Uri, afterSave: (Uri) -> Unit = {}) {
        val s = uiState.value
        val result = s.result ?: return
        scope.launch {
            try {
                val cards = result.cards.filterIndexed { i, _ -> i !in s.excluded }
                val options = RepairOptions(
                    s.country, s.merge, s.shouty, s.dropEmpty, s.callingCode, s.redact
                )
                val vcf = Writers.writeVCard(cards)
                val csv = Writers.writeCsv(cards)
                val report = Writers.writeReport(result, options)

                val bos = ByteArrayOutputStream()
                ZipOutputStream(bos).use { zos ->
                    zos.putNextEntry(ZipEntry("contacts.vcf"))
                    zos.write(vcf.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("google-contacts.csv"))
                    zos.write(csv.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("repair-report.txt"))
                    zos.write(report.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()

                    // Optional extra formats.
                    if ("vcf4" in s.outputFormats) {
                        zos.putNextEntry(ZipEntry("contacts-vcf4.vcf"))
                        zos.write(Writers.writeVCard4(cards).toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    }
                    if ("json" in s.outputFormats) {
                        zos.putNextEntry(ZipEntry("contacts.json"))
                        zos.write(Writers.writeJson(cards).toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    }
                    if ("perContact" in s.outputFormats) {
                        for ((name, text) in Writers.writeVcfPerContact(cards)) {
                            zos.putNextEntry(ZipEntry("per-contact/$name"))
                            zos.write(text.toByteArray(Charsets.UTF_8))
                            zos.closeEntry()
                        }
                    }
                }

                contentResolver.openOutputStream(uri)?.use { it.write(bos.toByteArray()) }
                    ?: throw IOException("Cannot write $uri")

                uiState.value = s.copy(screen = Screen.Saved, savedUri = uri)
                afterSave(uri)
            } catch (e: Exception) {
                uiState.value = s.copy(error = "Save failed: ${e.message}")
            }
        }
    }

    private fun shareUri(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Share repaired contacts"))
        } catch (e: Exception) {
            uiState.value = uiState.value.copy(error = "Share failed: ${e.message}")
        }
    }

    companion object {
        const val EXPLANATION_BODY =
            "Contact Repair can only read files you pick. It cannot see the rest of your phone. " +
            "Android does not list this access under App permissions, so App info shows no permission. " +
            "The next screen is the system file picker. Picking a file is the permission. You can cancel."
    }
}

@androidx.compose.runtime.Composable
private fun ExplanationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    prefs: SharedPreferences
) {
    var dontShow by remember {
        mutableStateOf(prefs.getBoolean("no_explain", false))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose files") },
        text = {
            Column {
                Text(MainActivity.EXPLANATION_BODY)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = dontShow, onCheckedChange = { dontShow = it })
                    Text("Don't show this explanation again")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                prefs.edit().putBoolean("no_explain", dontShow).apply()
                onConfirm()
            }) { Text("Choose files") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
