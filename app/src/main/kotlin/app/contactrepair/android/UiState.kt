package app.contactrepair.android

import app.contactrepair.core.RepairResult
import androidx.compose.runtime.mutableStateOf
import android.net.Uri

enum class Screen { Repair, Review, Saved }

/** Review-screen sort mode. */
enum class SortMode { Name, Warnings, Source }

/** Review-screen filter mode. */
enum class FilterMode { All, HasWarnings, Unnamed }

data class UiState(
    val screen: Screen = Screen.Repair,
    val selectedUris: List<Uri> = emptyList(),
    val country: String = "US",
    val callingCode: String = "",
    val merge: Boolean = true,
    val shouty: Boolean = true,
    val dropEmpty: Boolean = true,
    val redact: Boolean = false,
    /** Output format toggles: "vcf4", "json", "perContact". */
    val outputFormats: Set<String> = emptySet(),
    val result: RepairResult? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val excluded: Set<Int> = emptySet(),
    val editingIndex: Int? = null,
    // Review screen state
    val searchQuery: String = "",
    val sortMode: SortMode = SortMode.Name,
    val filterMode: FilterMode = FilterMode.All,
    // Saved screen
    val savedUri: Uri? = null,
    // Progress
    val progressText: String? = null,
    // Recent files (persistable URIs from last session)
    val recentUris: List<Uri> = emptyList()
)

internal val uiState = mutableStateOf(UiState())
