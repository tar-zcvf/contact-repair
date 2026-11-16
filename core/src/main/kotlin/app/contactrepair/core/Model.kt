package app.contactrepair.core

/** A named byte blob read from a user-chosen file. */
data class NamedBytes(val fileName: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is NamedBytes && other.fileName == fileName && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * fileName.hashCode() + bytes.contentHashCode()
}

/** Options that control how [repair] behaves. */
data class RepairOptions(
    val defaultRegion: String,
    val mergeDuplicates: Boolean,
    val fixShoutyNames: Boolean,
    val dropEmptyCards: Boolean,
    /** Optional typed calling code, e.g. "+90", "90", or a two-letter code like "TR".
     *  Empty string means the dropdown region alone. */
    val preferredCallingCode: String = "",
    /** When true, redact sensitive values (home addresses, phone extensions, SSN-like
     *  tokens in notes) before export. Off by default. */
    val redactSensitive: Boolean = false
)

/** Validity of a phone number as judged by libphonenumber. */
enum class PhoneValidity { VALID, POSSIBLE, INVALID, UNKNOWN }

/** A single phone number on a contact. */
data class Phone(
    val value: String,      // E.164 if parsed, otherwise original trimmed value
    val type: String,       // CELL | HOME | WORK | FAX | VOICE
    val extension: String?, // extension if libphonenumber returned one
    val validity: PhoneValidity = PhoneValidity.UNKNOWN,
    val typeLabel: String = "Unknown" // readable: Mobile, Landline, Toll-free, VOIP, Unknown
)

/** A single e-mail address on a contact. */
data class Email(
    val value: String,      // lowercased
    val type: String        // HOME | WORK | INTERNET
)

/** A postal address on a contact. */
data class Address(
    val street: String = "",
    val city: String = "",
    val region: String = "",
    val postal: String = "",
    val country: String = "",
    val formatted: String = "",
    val type: String = ""
) {
    /** A compact single-line rendering, used for display and change comparison. */
    fun short(): String =
        listOf(street, city, region, postal, country).filter { it.isNotBlank() }.joinToString(", ")
}

/** Confidence in a merge (how sure we are the cluster is one person). */
enum class MergeConfidence { HIGH, MEDIUM, LOW }

/** A single field change detected during repair, for the before/after diff. */
data class FieldChange(
    val label: String,   // Name, Phone, Email, Organization, Title, Birthday, Website, Address, Groups, Nickname, Photo
    val before: String,  // raw source value ("(empty)" if the field was empty)
    val after: String    // exported value
)

/** The model for one repaired contact. Mutable so the UI can override a few fields. */
data class RepairedContact(
    var fn: String = "",
    var given: String = "",
    var additional: String = "",
    var family: String = "",
    var prefix: String = "",
    var suffix: String = "",
    var phones: MutableList<Phone> = mutableListOf(),
    var emails: MutableList<Email> = mutableListOf(),
    var urls: MutableList<String> = mutableListOf(),
    var categories: MutableList<String> = mutableListOf(),
    var org: String = "",
    var title: String = "",
    var role: String = "",
    var nickname: String = "",
    var note: String = "",
    var bday: String? = null,
    var address: Address? = null,
    var photo: String? = null, // base64 (no data: prefix) or a http(s) URL
    val warnings: MutableList<String> = mutableListOf(),
    var fnSynthesized: Boolean = false,
    var nameKey: String = "",
    /** Raw, unmodified custom/unknown vCard property lines, preserved verbatim. */
    val customFields: MutableList<String> = mutableListOf(),
    /** Per-field before/after changes, populated by the repair engine. */
    val changes: MutableList<FieldChange> = mutableListOf(),
    /** Confidence when this contact is a merge of 2+ cards (HIGH/MEDIUM/LOW). */
    var mergeConfidence: MergeConfidence? = null,
    /** One [SourceSnapshot] per source card (one for a single card, one per
     *  merged-away source for a cluster). Used to build the original-details note. */
    val sources: MutableList<SourceSnapshot> = mutableListOf()
) {
    /** True when the card has no name, phone, email, org, or address. A synthesized
     *  FN ("Unknown") does not count as real content. */
    fun isEmptyCard(): Boolean =
        (fn.isBlank() || fnSynthesized) && given.isBlank() && family.isBlank() && additional.isBlank() &&
            phones.isEmpty() && emails.isEmpty() && org.isBlank() && address == null

    fun hasNameInfo(): Boolean =
        fn.isNotBlank() || given.isNotBlank() || family.isNotBlank() || additional.isNotBlank()
}

/** Raw source strings captured at parse time for one source card.
 *  Used to build the "Original details (before repair):" note block so the
 *  previous value of any changed field is preserved on the exported contact. */
class SourceSnapshot(
    var fileName: String = "",
    var fn: String = "",
    var prefix: String = "",
    var given: String = "",
    var additional: String = "",
    var family: String = "",
    var suffix: String = "",
    var nickname: String = "",
    var org: String = "",
    var title: String = "",
    var bday: String? = null,
    var address: Address? = null,
    var photoDropped: Boolean = false,
    val phones: MutableList<String> = mutableListOf(),
    val emails: MutableList<String> = mutableListOf(),
    val droppedEmails: MutableList<String> = mutableListOf(),
    val urls: MutableList<String> = mutableListOf(),
    val categories: MutableList<String> = mutableListOf()
)

/** A manual override from the review dialog. Null means the field was not changed. */
data class ManualEdit(
    val displayName: String?,
    val primaryPhone: String?,
    val primaryEmail: String?
)

/** Human-readable report produced alongside the repaired cards. */
data class RepairReport(
    val inputFiles: List<String> = emptyList(),
    val cardsRead: Int = 0,
    val emptyDropped: Int = 0,
    val merged: Int = 0,
    val phonesRewritten: Int = 0,
    val emailsRewritten: Int = 0,
    val photosDropped: Int = 0,
    val invalidPhones: Int = 0,
    val redacted: Int = 0,
    val warnings: List<Pair<String, String>> = emptyList() // (display name, message)
)

/** Result of a repair run. */
data class RepairResult(
    val cards: List<RepairedContact>,
    val report: RepairReport
)
