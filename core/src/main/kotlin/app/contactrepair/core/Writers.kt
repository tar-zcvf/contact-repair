package app.contactrepair.core

import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

/** Writers for the export files. Pure Kotlin/Java.
 *
 *  Required (in the output zip): vCard 3.0 (.vcf), Google Contacts CSV (.csv),
 *  repair-report.txt. Optional extras the user can enable: vCard 4.0 (.vcf4),
 *  JSON (.json), and one .vcf file per contact.
 */
object Writers {

    private val UTF8 = StandardCharsets.UTF_8

    // ---------------------------------------------------------------------
    // vCard 3.0
    // ---------------------------------------------------------------------

    fun writeVCard(cards: List<RepairedContact>): String {
        val sb = StringBuilder()
        for (c in cards) writeCard30(sb, c)
        return sb.toString()
    }

    private fun writeCard30(sb: StringBuilder, c: RepairedContact) {
        fun append(line: String) {
            sb.append(line.trimEnd(' ')).append("\r\n")
        }

        append("BEGIN:VCARD")
        append("VERSION:3.0")
        append(textProp("FN", c.fn.ifBlank { "Unknown" }))

        val nParts = listOf(c.family, c.given, c.additional, c.prefix, c.suffix)
        if (nParts.any { it.isNotBlank() }) append(textProp("N", nParts.joinToString(";")))

        for (p in c.phones) {
            val ext = p.extension
            val line = if (ext.isNullOrBlank()) "TEL;TYPE=${p.type}:${p.value}"
            else "TEL;TYPE=${p.type}:${p.value};ext=$ext"
            append(fold(line))
        }
        for (e in c.emails) append(textProp("EMAIL", e.value, listOf("TYPE" to e.type)))

        c.address?.let { a ->
            val type = if (a.type.isBlank()) "HOME" else a.type
            val value = listOf("", "", a.street, a.city, a.region, a.postal, a.country).joinToString(";")
            append(textProp("ADR", value, listOf("TYPE" to type)))
        }

        if (c.org.isNotBlank()) append(textProp("ORG", c.org))
        if (c.title.isNotBlank()) append(textProp("TITLE", c.title))
        if (c.nickname.isNotBlank()) append(textProp("NICKNAME", c.nickname))
        c.bday?.let { append("BDAY:${it.replace("-", "")}") }
        for (u in c.urls) if (u.isNotBlank()) append("URL:$u")
        if (c.categories.isNotEmpty()) append(textProp("CATEGORIES", c.categories.joinToString(",")))
        if (c.note.isNotBlank()) append(textProp("NOTE", c.note))

        // Preserve custom/unknown properties verbatim so no data is lost.
        for (cf in c.customFields) append(fold(cf))

        c.photo?.let { photo ->
            if (photo.startsWith("http://") || photo.startsWith("https://")) {
                append("PHOTO;VALUE=URI:$photo")
            } else {
                append("PHOTO;ENCODING=b:${photo.replace(Regex("\\s"), "")}")
            }
        }

        append("UID:${uid(c)}")
        append("END:VCARD")
    }

    // ---------------------------------------------------------------------
    // vCard 4.0 (UTF-8, lowercase type parameters, ISO BDAY)
    // ---------------------------------------------------------------------

    fun writeVCard4(cards: List<RepairedContact>): String {
        val sb = StringBuilder()
        for (c in cards) writeCard40(sb, c)
        return sb.toString()
    }

    private fun writeCard40(sb: StringBuilder, c: RepairedContact) {
        fun append(line: String) { sb.append(line.trimEnd(' ')).append("\r\n") }

        append("BEGIN:VCARD")
        append("VERSION:4.0")
        append(textProp4("FN", c.fn.ifBlank { "Unknown" }))

        val nParts = listOf(c.family, c.given, c.additional, c.prefix, c.suffix)
        if (nParts.any { it.isNotBlank() }) append(textProp4("N", nParts.joinToString(";")))

        for (p in c.phones) {
            val ext = p.extension
            val line = if (ext.isNullOrBlank()) "TEL;type=${v4Type(p.type)}:${p.value}"
            else "TEL;type=${v4Type(p.type)}:${p.value};ext=$ext"
            append(fold(line))
        }
        for (e in c.emails) append(textProp4("EMAIL", e.value, listOf("type" to v4Type(e.type))))

        c.address?.let { a ->
            val value = listOf("", "", a.street, a.city, a.region, a.postal, a.country).joinToString(";")
            append(textProp4("ADR", value, listOf("type" to (if (a.type.isBlank()) "home" else v4Type(a.type)))))
        }

        if (c.org.isNotBlank()) append(textProp4("ORG", c.org))
        if (c.title.isNotBlank()) append(textProp4("TITLE", c.title))
        if (c.nickname.isNotBlank()) append(textProp4("NICKNAME", c.nickname))
        c.bday?.let { append("BDAY:$it") } // ISO YYYY-MM-DD in 4.0
        for (u in c.urls) if (u.isNotBlank()) append("URL:$u")
        if (c.categories.isNotEmpty()) append(textProp4("CATEGORIES", c.categories.joinToString(";")))
        if (c.note.isNotBlank()) append(textProp4("NOTE", c.note))

        for (cf in c.customFields) append(fold(cf))

        c.photo?.let { photo ->
            if (photo.startsWith("http://") || photo.startsWith("https://")) {
                append("PHOTO;type=image/jpeg;value=uri:$photo")
            } else {
                append("PHOTO;type=image/jpeg:${photo.replace(Regex("\\s"), "")}")
            }
        }

        append("UID:${uid(c)}")
        append("END:VCARD")
    }

    /** vCard 4.0 uses lowercase, hyphenated type tokens. */
    private fun v4Type(t: String): String = when (t.uppercase(Locale.ROOT)) {
        "CELL" -> "cell"
        "HOME" -> "home"
        "WORK" -> "work"
        "FAX" -> "fax"
        "VOICE" -> "voice"
        "INTERNET" -> "internet"
        else -> t.lowercase(Locale.ROOT).ifEmpty { "voice" }
    }

    private fun textProp4(name: String, value: String, extra: List<Pair<String, String>> = emptyList()): String {
        val escaped = escapeText(value)
        val paramStr = if (extra.isEmpty()) "" else ";" + extra.joinToString(";") { "${it.first}=${it.second}" }
        return fold("$name$paramStr:$escaped")
    }

    fun uid(c: RepairedContact): String {
        val phones = c.phones.map { it.value }.sorted()
        val emails = c.emails.map { it.value }.sorted()
        val seed = buildString {
            append("contact:")
            append(phones.joinToString(","))
            append("|")
            append(emails.joinToString(","))
            append("|")
            append(c.nameKey)
            if (phones.isEmpty() && emails.isEmpty()) append("|").append(c.fn)
        }
        return uuidV3(seed)
    }

    private val NS_DNS = byteArrayOf(
        0x6b.toByte(), 0xa7.toByte(), 0xb8.toByte(), 0x11.toByte(),
        0x9d.toByte(), 0xad.toByte(), 0x11.toByte(), 0xd1.toByte(),
        0x80.toByte(), 0xb4.toByte(), 0x00.toByte(), 0xc0.toByte(),
        0x4f.toByte(), 0xd4.toByte(), 0x30.toByte(), 0xc8.toByte()
    )

    private fun uuidV3(name: String): String {
        val combined = NS_DNS + name.toByteArray(UTF8)
        return UUID.nameUUIDFromBytes(combined).toString()
    }

    fun escapeText(s: String): String =
        s.replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    private fun textProp(name: String, value: String, extra: List<Pair<String, String>> = emptyList()): String {
        val escaped = escapeText(value)
        val params = extra.toMutableList()
        if (value.any { it.code > 0x7f }) params.add("CHARSET" to "UTF-8")
        val paramStr = if (params.isEmpty()) "" else ";" + params.joinToString(";") { "${it.first}=${it.second}" }
        return fold("$name$paramStr:$escaped")
    }

    fun fold(line: String): String {
        if (line.toByteArray(UTF8).size <= 75) return line
        val out = StringBuilder()
        var lineOctets = 0
        var limit = 75
        for (ch in line) {
            val chBytes = ch.toString().toByteArray(UTF8).size
            if (lineOctets + chBytes > limit) {
                out.append("\r\n ")
                lineOctets = 1 + chBytes
                limit = 74
                out.append(ch)
            } else {
                out.append(ch)
                lineOctets += chBytes
            }
        }
        return out.toString()
    }

    // ---------------------------------------------------------------------
    // Google Contacts CSV
    // ---------------------------------------------------------------------

    val CSV_HEADER = listOf(
        "Name", "Given Name", "Additional Name", "Family Name", "Name Prefix", "Name Suffix",
        "Nickname", "Birthday", "Notes", "Group Membership",
        "Organization 1 - Name", "Organization 1 - Title", "Website 1 - Value",
        "E-mail 1 - Type", "E-mail 1 - Value",
        "E-mail 2 - Type", "E-mail 2 - Value",
        "E-mail 3 - Type", "E-mail 3 - Value",
        "Phone 1 - Type", "Phone 1 - Value",
        "Phone 2 - Type", "Phone 2 - Value",
        "Phone 3 - Type", "Phone 3 - Value",
        "Phone 4 - Type", "Phone 4 - Value",
        "Address 1 - Type", "Address 1 - Formatted", "Address 1 - Street", "Address 1 - City",
        "Address 1 - Region", "Address 1 - Postal Code", "Address 1 - Country"
    )

    fun writeCsv(cards: List<RepairedContact>): String {
        val sb = StringBuilder()
        sb.append(csvLine(CSV_HEADER)).append("\r\n")
        for (c in cards) sb.append(csvLine(csvRow(c))).append("\r\n")
        return sb.toString()
    }

    private fun csvRow(c: RepairedContact): List<String> {
        val notes = StringBuilder()
        if (c.note.isNotBlank()) notes.append(c.note)

        val emails = c.emails
        val phones = c.phones
        val urls = c.urls

        val extra = StringBuilder()
        if (emails.size > 3) {
            extra.append("\nAdditional emails:\n")
            emails.drop(3).forEach { extra.append(it.value).append("\n") }
        }
        if (phones.size > 4) {
            extra.append("\nAdditional phones:\n")
            phones.drop(4).forEach { extra.append(csvPhoneValue(it)).append("\n") }
        }
        if (urls.size > 1) {
            extra.append("\nAdditional websites:\n")
            urls.drop(1).forEach { extra.append(it).append("\n") }
        }
        val conf = c.mergeConfidence
        if (conf != null) {
            extra.append("\nMerged (confidence ").append(conf.name.lowercase()).append(")\n")
        }
        if (extra.isNotEmpty()) {
            if (notes.isNotEmpty()) notes.append("\n")
            notes.append(extra.toString().trimEnd('\n'))
        }

        val row = ArrayList<String>(CSV_HEADER.size)
        row.add(c.fn)
        row.add(c.given)
        row.add(c.additional)
        row.add(c.family)
        row.add(c.prefix)
        row.add(c.suffix)
        row.add(c.nickname)
        row.add(c.bday.orEmpty())
        row.add(notes.toString())
        row.add(c.categories.joinToString(" ::: "))
        row.add(c.org)
        row.add(c.title)
        row.add(urls.firstOrNull().orEmpty())
        repeat(3) { i ->
            row.add(if (i < emails.size) emailTypeLabel(emails[i].type) else "")
            row.add(if (i < emails.size) emails[i].value else "")
        }
        repeat(4) { i ->
            row.add(if (i < phones.size) phoneTypeLabel(phones[i].type) else "")
            row.add(if (i < phones.size) csvPhoneValue(phones[i]) else "")
        }
        val a = c.address
        row.add(a?.type.orEmpty())
        row.add(a?.formatted.orEmpty())
        row.add(a?.street.orEmpty())
        row.add(a?.city.orEmpty())
        row.add(a?.region.orEmpty())
        row.add(a?.postal.orEmpty())
        row.add(a?.country.orEmpty())
        return row
    }

    private fun csvPhoneValue(p: Phone): String =
        if (p.extension.isNullOrBlank()) p.value else "${p.value} ext ${p.extension}"

    private fun phoneTypeLabel(t: String): String = when (t.uppercase(Locale.ROOT)) {
        "CELL" -> "Mobile"
        "HOME" -> "Home"
        "WORK" -> "Work"
        "FAX" -> "Fax"
        else -> "Other"
    }

    private fun emailTypeLabel(t: String): String = when (t.uppercase(Locale.ROOT)) {
        "HOME" -> "Home"
        "WORK" -> "Work"
        else -> "Other"
    }

    private fun csvLine(fields: List<String>): String = fields.joinToString(",") { csvQuote(it) }

    private fun csvQuote(s: String): String {
        val needs = s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')
        return if (needs) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    // ---------------------------------------------------------------------
    // JSON
    // ---------------------------------------------------------------------

    fun writeJson(cards: List<RepairedContact>): String {
        val sb = StringBuilder()
        sb.append("[\n")
        for ((i, c) in cards.withIndex()) {
            sb.append(contactJson(c)).append(if (i < cards.size - 1) "," else "").append("\n")
        }
        sb.append("]\n")
        return sb.toString()
    }

    private fun contactJson(c: RepairedContact): String {
        val conf = c.mergeConfidence
        fun arr(items: List<String>, inner: (String) -> String = { "\"${jsonEscape(it)}\"" }): String =
            items.joinToString(", ") { inner(it) }

        val phonesJson = c.phones.joinToString(", ") { p ->
            "{ \"value\": \"${jsonEscape(p.value)}\", \"type\": \"${jsonEscape(p.type)}\", " +
                "\"label\": \"${jsonEscape(p.typeLabel)}\", \"validity\": \"${p.validity.name}\", " +
                "\"extension\": ${p.extension?.let { "\"${jsonEscape(it)}\"" } ?: "null"} }"
        }
        val emailsJson = c.emails.joinToString(", ") { e ->
            "{ \"value\": \"${jsonEscape(e.value)}\", \"type\": \"${jsonEscape(e.type)}\" }"
        }
        val a = c.address
        val addrJson = if (a == null) "null" else
            "{ \"street\": \"${jsonEscape(a.street)}\", \"city\": \"${jsonEscape(a.city)}\", " +
                "\"region\": \"${jsonEscape(a.region)}\", \"postal\": \"${jsonEscape(a.postal)}\", " +
                "\"country\": \"${jsonEscape(a.country)}\", \"type\": \"${jsonEscape(a.type)}\" }"

        val changesJson = c.changes.joinToString(", ") { ch ->
            "{ \"field\": \"${jsonEscape(ch.label)}\", \"before\": \"${jsonEscape(ch.before)}\", " +
                "\"after\": \"${jsonEscape(ch.after)}\" }"
        }

        return "{\n" +
            "  \"fn\": \"${jsonEscape(c.fn)}\",\n" +
            "  \"given\": \"${jsonEscape(c.given)}\",\n" +
            "  \"additional\": \"${jsonEscape(c.additional)}\",\n" +
            "  \"family\": \"${jsonEscape(c.family)}\",\n" +
            "  \"prefix\": \"${jsonEscape(c.prefix)}\",\n" +
            "  \"suffix\": \"${jsonEscape(c.suffix)}\",\n" +
            "  \"nickname\": \"${jsonEscape(c.nickname)}\",\n" +
            "  \"org\": \"${jsonEscape(c.org)}\",\n" +
            "  \"title\": \"${jsonEscape(c.title)}\",\n" +
            "  \"role\": \"${jsonEscape(c.role)}\",\n" +
            "  \"phones\": [ $phonesJson ],\n" +
            "  \"emails\": [ $emailsJson ],\n" +
            "  \"urls\": [ ${arr(c.urls)} ],\n" +
            "  \"categories\": [ ${arr(c.categories)} ],\n" +
            "  \"bday\": ${c.bday?.let { "\"$it\"" } ?: "null"},\n" +
            "  \"address\": $addrJson,\n" +
            "  \"photo\": ${c.photo?.let { "\"${jsonEscape(it)}\"" } ?: "null"},\n" +
            "  \"note\": \"${jsonEscape(c.note)}\",\n" +
            "  \"warnings\": [ ${arr(c.warnings)} ],\n" +
            "  \"mergeConfidence\": ${conf?.let { "\"${it.name}\"" } ?: "null"},\n" +
            "  \"changes\": [ $changesJson ],\n" +
            "  \"customFields\": [ ${arr(c.customFields)} ]\n" +
            "}"
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    // ---------------------------------------------------------------------
    // One .vcf file per contact
    // ---------------------------------------------------------------------

    /** Returns a map of safe file name -> single-contact vCard 3.0 text. */
    fun writeVcfPerContact(cards: List<RepairedContact>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (c in cards) {
            val base = c.fn.ifBlank { c.nameKey }.ifBlank { "contact" }
            val safe = base.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifBlank { "contact" }
            val sb = StringBuilder()
            writeCard30(sb, c)
            out["$safe.vcf"] = sb.toString()
        }
        return out
    }

    // ---------------------------------------------------------------------
    // repair-report.txt
    // ---------------------------------------------------------------------

    fun writeReport(r: RepairResult, options: RepairOptions): String {
        val sb = StringBuilder()
        fun line(s: String = "") { sb.append(s).append("\n") }

        line("Contact Repair — report")
        line("=".repeat(40))
        line()
        line("Input files:")
        if (r.report.inputFiles.isEmpty()) line("  (none)")
        for (f in r.report.inputFiles) line("  - $f")
        line()
        line("Options:")
        line("  default region:        ${options.defaultRegion}")
        line("  preferred calling code: ${options.preferredCallingCode.ifBlank { "(none)" }}")
        line("  merge duplicates:      ${options.mergeDuplicates}")
        line("  fix shouty names:      ${options.fixShoutyNames}")
        line("  drop empty cards:      ${options.dropEmptyCards}")
        line("  redact sensitive:      ${options.redactSensitive}")
        line()
        line("Counts:")
        line("  cards read:         ${r.report.cardsRead}")
        line("  empty dropped:      ${r.report.emptyDropped}")
        line("  merged:             ${r.report.merged}")
        line("  phones rewritten:   ${r.report.phonesRewritten}")
        line("  emails rewritten:   ${r.report.emailsRewritten}")
        line("  invalid phones:     ${r.report.invalidPhones}")
        line("  photos dropped:     ${r.report.photosDropped}")
        line("  redacted fields:    ${r.report.redacted}")
        line("  warnings:           ${r.report.warnings.size}")
        line()
        line("Warnings:")
        if (r.report.warnings.isEmpty()) {
            line("  (none)")
        } else {
            for ((name, msg) in r.report.warnings) line("  - $name: $msg")
        }
        return sb.toString()
    }
}
