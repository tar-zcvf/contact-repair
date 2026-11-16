package app.contactrepair.core

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType
import com.google.i18n.phonenumbers.Phonenumber
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Mutable run state shared across file/zip parsing. */
private class State(val options: RepairOptions, val region: String, val util: PhoneNumberUtil,
                    val parseContext: ParseContext = ParseContext(region, null, false)) {
    var photosDropped = 0
    var cardsRead = 0
    val fileWarnings = mutableListOf<String>()
}

/**
 * The contact repair engine. Pure Kotlin/Java — no Android imports.
 */
object RepairEngine {

    private val PARTICLES = setOf(
        "van", "von", "de", "da", "di", "la", "le", "der", "den", "ten", "ter", "du", "del", "dos", "das"
    )
    private val ACRONYMS = setOf("OK", "US", "UK", "EU", "HR")

    private const val MAX_ZIP_ENTRIES = 1000
    private const val MAX_UNCOMPRESSED = 80L * 1024 * 1024
    private const val MAX_SINGLE_FILE = 50L * 1024 * 1024

    // ---------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------

    fun repair(files: List<NamedBytes>, options: RepairOptions): RepairResult {
        val util = PhoneNumberUtil.getInstance()
        val region = options.defaultRegion.ifBlank { "US" }
        val parseContext = Regions.resolveParseContext(region, options.preferredCallingCode)
        val st = State(options, parseContext.regionCode, util, parseContext)
        val allCards = mutableListOf<RepairedContact>()

        for (file in files) {
            if (file.bytes.size.toLong() > MAX_SINGLE_FILE) {
                st.fileWarnings.add("${file.fileName}: file over 50 MB, skipped")
                continue
            }
            try {
                if (isZip(file.bytes)) {
                    extractZip(file, st, allCards)
                } else {
                    val text = decodeBytes(file.bytes)
                    val cards = if (looksLikeCsv(file.fileName, text)) {
                        parseCsv(text, st, file.fileName)
                    } else {
                        parseVCardText(text, st, file.fileName)
                    }
                    st.cardsRead += cards.size
                    allCards.addAll(cards)
                    if (cards.isEmpty() && text.isNotBlank() && !looksLikeCsv(file.fileName, text)) {
                        st.fileWarnings.add("${file.fileName}: no contacts found")
                    }
                }
            } catch (e: Exception) {
                st.fileWarnings.add("${file.fileName}: could not be parsed: ${e.message}")
            }
        }

        // Apply name fixing + compute name keys.
        for (c in allCards) {
            if (st.options.fixShoutyNames) c.fn = fixShouty(c.fn)
            c.nameKey = nameKey(c.fn)
        }

        // Empty drop — after cleanup, before merge.
        val kept = if (st.options.dropEmptyCards) allCards.filterNot { it.isEmptyCard() } else allCards
        val emptyDropped = allCards.size - kept.size

        // Merge
        val (mergedCards, mergeCount) = if (st.options.mergeDuplicates) mergeCards(kept) else (kept to 0)

        // Build the before/after change list and the original-details note for every
        // card whose exported values differ from their raw source. Done after merge so
        // merged sources are included.
        for (c in mergedCards) {
            computeChanges(c)
            appendOriginalNote(c)
        }

        // Redaction (opt-in): strip sensitive values before export.
        var redactedCount = 0
        if (st.options.redactSensitive) {
            for (c in mergedCards) redactedCount += redact(c)
        }

        // Warnings
        val warnings = mutableListOf<Pair<String, String>>()
        for (c in mergedCards) {
            val label = c.fn.ifBlank { c.nameKey }.ifBlank { "unknown" }
            for (w in c.warnings) warnings.add(label to w)
        }
        for (w in st.fileWarnings) warnings.add("file" to w)

        val phonesRewritten = mergedCards.sumOf { it.phones.count { p -> p.value.startsWith("+") } }
        val emailsRewritten = mergedCards.sumOf { it.emails.size }
        val invalidPhones = mergedCards.sumOf { it.phones.count { it.validity == PhoneValidity.INVALID } }

        val report = RepairReport(
            inputFiles = files.map { it.fileName },
            cardsRead = st.cardsRead,
            emptyDropped = emptyDropped,
            merged = mergeCount,
            phonesRewritten = phonesRewritten,
            emailsRewritten = emailsRewritten,
            photosDropped = st.photosDropped,
            invalidPhones = invalidPhones,
            redacted = redactedCount,
            warnings = warnings
        )

        return RepairResult(mergedCards, report)
    }

    // ---------------------------------------------------------------------
    // Byte decoding
    // ---------------------------------------------------------------------

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    fun decodeBytes(bytes: ByteArray): String {
        var b = bytes
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            b = b.copyOfRange(3, b.size)
        }
        if (b.any { it == 0x00.toByte() }) {
            if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) {
                return String(b.copyOfRange(2, b.size), Charsets.UTF_16BE)
            }
            if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) {
                return String(b.copyOfRange(2, b.size), Charsets.UTF_16LE)
            }
            val le = String(b, Charsets.UTF_16LE)
            if (le.none { it.code == 0xFFFE }) return le
            return String(b, Charsets.UTF_16BE)
        }
        return if (isValidUtf8(b)) String(b, StandardCharsets.UTF_8)
        else if (b.hasCjkSignatures()) decodeCjk(b)
        else String(b, Charset.forName("Windows-1252"))
    }

    /**
     * Best-effort CJK decode for bytes that are not valid UTF-8/1252. Tries
     * Shift-JIS, EUC-JP, and GBK based on byte-range signatures. Never throws —
     * falls back to UTF-8 replacement on failure.
     */
    private fun decodeCjk(b: ByteArray): String {
        for (name in listOf("Shift_JIS", "EUC-JP", "x-EUC-JP", "GBK", "x-SJIS")) {
            try {
                val s = String(b, Charset.forName(name))
                if (s.count { it.code in 0x3000..0x9FFF } >= 2) return s
            } catch (e: Exception) { /* try next */ }
        }
        return String(b, StandardCharsets.UTF_8)
    }

    /** Heuristic: a meaningful share of bytes fall in CJK double-byte ranges. */
    private fun ByteArray.hasCjkSignatures(): Boolean {
        if (size < 8) return false
        var cjk = 0
        for (i in 0 until size - 1) {
            val hi = (this[i].toInt() and 0xFF)
            val lo = (this[i + 1].toInt() and 0xFF)
            // Shift-JIS lead 0x81-0x9F,0xE0-0xEF ; GBK lead 0x81-0xFE, lo 0x40-0xFE
            if ((hi in 0x81..0x9F || hi in 0xE0..0xEF) && lo in 0x40..0xFC) cjk++
            else if (hi in 0x81..0xFE && lo in 0x40..0xFE) cjk++
        }
        return cjk * 4 >= size // at least ~25% of byte pairs look CJK
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        val decoder = StandardCharsets.UTF_8.newDecoder()
        decoder.onMalformedInput(CodingErrorAction.REPORT)
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes))
            true
        } catch (e: CharacterCodingException) {
            false
        }
    }

    // ---------------------------------------------------------------------
    // ZIP extraction
    // ---------------------------------------------------------------------

    private fun extractZip(file: NamedBytes, st: State, out: MutableList<RepairedContact>) {
        var entryCount = 0
        var uncompressed = 0L
        val cis = ZipInputStream(ByteArrayInputStream(file.bytes))
        while (true) {
            val e = cis.nextEntry ?: break
            entryCount++
            if (entryCount > MAX_ZIP_ENTRIES) break
            val name = e.name
            if (name.endsWith("/")) continue
            if (name.startsWith("__MACOSX") || name.startsWith(".")) continue
            val data = cis.readAllBytes()
            uncompressed += data.size.toLong()
            if (uncompressed > MAX_UNCOMPRESSED) break

            if (isZip(data)) {
                st.fileWarnings.add("$name: nested zip ignored")
                continue
            }
            if (data.size.toLong() > MAX_SINGLE_FILE) continue
            if (!isAcceptedExtension(name)) continue

            val text = decodeBytes(data)
            val cards = if (looksLikeCsv(name, text)) parseCsv(text, st, name) else parseVCardText(text, st, name)
            st.cardsRead += cards.size
            out.addAll(cards)
        }
        cis.close()
    }

    private fun isAcceptedExtension(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".vcf") || lower.endsWith(".vcard") ||
            lower.endsWith(".csv") || lower.endsWith(".txt")
    }

    // ---------------------------------------------------------------------
    // CSV
    // ---------------------------------------------------------------------

    private fun looksLikeCsv(fileName: String, text: String): Boolean {
        if (fileName.lowercase(Locale.ROOT).endsWith(".csv")) return true
        val firstNonEmpty = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
        if (!firstNonEmpty.contains(',')) return false
        val norm = firstNonEmpty.lowercase()
        return norm.contains("name") ||
            norm.contains("given name") ||
            norm.contains("e-mail 1 - value") ||
            norm.contains("phone 1 - value")
    }

    private fun parseCsv(text: String, st: State, fileName: String): List<RepairedContact> {
        val rows = splitCsv(text)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { normHeader(it) }
        val colIndex = header.mapIndexed { i, h -> h to i }.toMap()
        val result = mutableListOf<RepairedContact>()

        for (i in 1 until rows.size) {
            val row = rows[i]
            if (row.all { it.isBlank() }) continue
            val c = RepairedContact()

            val allText = row.joinToString("")
            if (allText.none { it.isLetterOrDigit() }) {
                c.warnings.add("garbage row skipped")
                result.add(c)
                continue
            }

            fun cell(vararg keys: String): String {
                for (k in keys) {
                    val idx = colIndex[k] ?: continue
                    if (idx < row.size && row[idx].isNotBlank()) return row[idx]
                }
                return ""
            }

            val name = cell("name", "full name", "display name")
            val given = cell("given name", "first name", "first")
            val additional = cell("additional name", "middle name", "middle")
            val family = cell("family name", "last name", "last", "surname")
            val prefix = cell("name prefix", "prefix")
            val suffix = cell("name suffix", "suffix")
            val nickname = cell("nickname")
            val notes = cell("notes", "note", "comments")
            val groups = cell("group membership", "groups")
            val bdayRaw = cell("birthday", "bday")
            val org = cell("organization 1 - name", "company", "company name", "organization", "employer")
            val title = cell("organization 1 - title", "title", "job title")
            val url = cell("website 1 - value", "website", "web site", "homepage")
            val street = cell("address 1 - street", "street", "street address", "address")
            val city = cell("address 1 - city", "city")
            val regionA = cell("address 1 - region", "state", "province")
            val postal = cell("address 1 - postal code", "zip", "zip code", "postal code", "postal")
            val country = cell("address 1 - country", "country")
            val formatted = cell("address 1 - formatted")
            val addrType = cell("address 1 - type")

            val src = SourceSnapshot(fileName = fileName)
            var phoneSeen = false
            var emailSeen = false
            // Numbered phone/email columns (Google, Apple).
            for (n in 1..10) {
                val pv = cell("phone $n - value")
                if (pv.isNotBlank()) {
                    phoneSeen = true
                    src.phones.addAll(splitMulti(pv))
                    for (p in splitMulti(pv)) addPhone(c, p, cell("phone $n - type"), st)
                }
                val ev = cell("e-mail $n - value", "email $n - value")
                if (ev.isNotBlank()) {
                    emailSeen = true
                    for (em in splitMulti(ev)) {
                        src.emails.add(em)
                        addEmail(c, em, cell("e-mail $n - type", "email $n - type"), src)
                    }
                }
            }
            // Generic phone/email columns (Outlook: Mobile, Business, Home, E-mail Address).
            if (!phoneSeen) {
                for (h in header) {
                    val hl = h.lowercase(Locale.ROOT)
                    if (hl == "phone" || hl == "mobile" || hl == "cell" || hl == "fax" ||
                        hl.contains("phone") || hl.contains("mobile")) {
                        val pv = cell(h)
                        if (pv.isNotBlank()) {
                            src.phones.addAll(splitMulti(pv))
                            for (p in splitMulti(pv)) addPhone(c, p, h, st)
                        }
                    }
                }
            }
            if (!emailSeen) {
                for (h in header) {
                    val hl = h.lowercase(Locale.ROOT)
                    if (hl == "email" || hl == "e-mail" || hl == "e-mail address" || hl.contains("mail")) {
                        val ev = cell(h)
                        if (ev.isNotBlank()) {
                            for (em in splitMulti(ev)) {
                                src.emails.add(em)
                                addEmail(c, em, h, src)
                            }
                        }
                    }
                }
            }

            c.fn = cleanName(name)
            c.given = cleanName(given)
            c.additional = cleanName(additional)
            c.family = cleanName(family)
            c.prefix = cleanName(prefix)
            c.suffix = cleanName(suffix)
            c.nickname = cleanName(nickname)
            c.note = cleanName(notes)
            c.org = cleanName(org)
            c.title = cleanName(title)
            if (url.isNotBlank()) c.urls.add(normalizeUrl(url))
            if (groups.isNotBlank()) c.categories.addAll(groups.split(":::").map { it.trim() }.filter { it.isNotBlank() })
            c.bday = parseDate(bdayRaw)
            if (bdayRaw.isNotBlank() && c.bday == null) c.warnings.add("invalid birthday: $bdayRaw")
            if (street.isNotBlank() || city.isNotBlank() || regionA.isNotBlank() || postal.isNotBlank() || country.isNotBlank() || formatted.isNotBlank()) {
                c.address = Address(
                    street = cleanName(street), city = cleanName(city), region = cleanName(regionA),
                    postal = cleanName(postal), country = cleanName(country),
                    formatted = cleanName(formatted), type = cleanName(addrType)
                )
            }

            // Raw source strings for the original-details note.
            src.fn = name.trim()
            src.prefix = prefix.trim()
            src.given = given.trim()
            src.additional = additional.trim()
            src.family = family.trim()
            src.suffix = suffix.trim()
            src.nickname = nickname.trim()
            src.org = org.trim()
            src.title = title.trim()
            src.bday = if (bdayRaw.isNotBlank()) bdayRaw.trim() else null
            if (url.isNotBlank()) src.urls.add(url.trim())
            if (groups.isNotBlank()) src.categories.addAll(groups.split(":::").map { it.trim() }.filter { it.isNotBlank() })
            if (street.isNotBlank() || city.isNotBlank() || regionA.isNotBlank() || postal.isNotBlank() || country.isNotBlank() || formatted.isNotBlank()) {
                src.address = Address(
                    street = street.trim(), city = city.trim(), region = regionA.trim(),
                    postal = postal.trim(), country = country.trim(),
                    formatted = formatted.trim(), type = addrType.trim()
                )
            }
            c.sources.add(src)

            if (c.given.isBlank() && c.family.isBlank() && c.fn.isNotBlank()) {
                val (g, f, a) = deriveNFromFn(c.fn)
                c.given = g; c.family = f; c.additional = a
            }
            if (c.fn.isBlank()) {
                c.fn = synthesizeFn(c)
                c.fnSynthesized = true
            }
            if (c.isEmptyCard()) continue
            result.add(c)
        }
        return result
    }

    private fun normHeader(s: String): String = s.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun splitMulti(s: String): List<String> =
        s.split(Regex(" / | \\| |\\n")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun splitCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        for (line in text.replace("\r\n", "\n").split("\n")) {
            val cur = mutableListOf<String>()
            val field = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                if (inQuotes) {
                    if (ch == '"') {
                        if (i + 1 < line.length && line[i + 1] == '"') { field.append('"'); i += 2; continue }
                        inQuotes = false; i++; continue
                    } else { field.append(ch); i++; continue }
                } else when (ch) {
                    '"' -> { inQuotes = true; i++; continue }
                    ',' -> { cur.add(field.toString()); field.clear(); i++; continue }
                    else -> { field.append(ch); i++; continue }
                }
            }
            cur.add(field.toString())
            rows.add(cur)
        }
        return rows
    }

    // ---------------------------------------------------------------------
    // vCard
    // ---------------------------------------------------------------------

    private fun parseVCardText(text: String, st: State, fileName: String): List<RepairedContact> {
        var t = text.replace("\r\n", "\n").replace('\r', '\n')
        t = unfold(t)
        t = t.replace("END:VCARDBEGIN:VCARD", "END:VCARD\nBEGIN:VCARD")

        val hasBegin = t.contains("BEGIN:VCARD", ignoreCase = true)
        if (!hasBegin) {
            val hasProp = t.lineSequence().any {
                val u = it.trim().uppercase(Locale.ROOT)
                u.startsWith("N:") || u.startsWith("FN:") || u.startsWith("TEL:") || u.startsWith("EMAIL:")
            }
            if (hasProp) t = "BEGIN:VCARD\n" + t + "\nEND:VCARD"
        }

        val blocks = splitCards(t)
        return blocks.map { parseCardBlock(it, st, fileName) }
    }

    private fun unfold(t: String): String {
        val lines = t.split("\n")
        val result = mutableListOf<String>()
        for (line in lines) {
            if (result.isNotEmpty()) {
                val prev = result[result.size - 1]
                // vCard continuation: line starts with space/tab
                if (line.startsWith(" ") || line.startsWith("\t")) {
                    result[result.size - 1] = prev + line.drop(1)
                    continue
                }
                // QP soft line break: previous line ends with '='
                if (prev.endsWith("=")) {
                    result[result.size - 1] = prev.dropLast(1) + line
                    continue
                }
            }
            result.add(line)
        }
        return result.joinToString("\n")
    }

    private fun splitCards(t: String): List<String> {
        val cards = mutableListOf<String>()
        val cur = mutableListOf<String>()
        for (line in t.split("\n")) {
            val u = line.trim().uppercase(Locale.ROOT)
            when {
                u == "BEGIN:VCARD" -> {
                    if (cur.isNotEmpty()) cards.add(cur.joinToString("\n"))
                    cur.clear(); cur.add("BEGIN:VCARD")
                }
                u == "END:VCARD" -> {
                    cur.add("END:VCARD")
                    cards.add(cur.joinToString("\n"))
                    cur.clear()
                }
                cur.isNotEmpty() -> cur.add(line)
            }
        }
        if (cur.isNotEmpty()) cards.add(cur.joinToString("\n"))
        return cards
    }

    private fun parseCardBlock(block: String, st: State, fileName: String): RepairedContact {
        val c = RepairedContact()
        val src = SourceSnapshot(fileName = fileName)
        var hasN = false
        for (rawLine in block.split("\n")) {
            if (rawLine.isBlank()) continue
            val line = rawLine.trim()
            val u = line.uppercase(Locale.ROOT)
            if (u == "BEGIN:VCARD" || u == "END:VCARD") continue
            val (name, params, value) = parseProperty(line)
            when (name) {
                "FN" -> {
                    c.fn = cleanName(value)
                    src.fn = value.trim()
                }
                "N" -> {
                    hasN = true
                    val parts = value.split(';')
                    c.family = cleanName(parts.getOrNull(0).orEmpty())
                    c.given = cleanName(parts.getOrNull(1).orEmpty())
                    c.additional = cleanName(parts.getOrNull(2).orEmpty())
                    c.prefix = cleanName(parts.getOrNull(3).orEmpty())
                    c.suffix = cleanName(parts.getOrNull(4).orEmpty())
                    src.family = parts.getOrNull(0).orEmpty().trim()
                    src.given = parts.getOrNull(1).orEmpty().trim()
                    src.additional = parts.getOrNull(2).orEmpty().trim()
                    src.prefix = parts.getOrNull(3).orEmpty().trim()
                    src.suffix = parts.getOrNull(4).orEmpty().trim()
                }
                "TEL" -> {
                    src.phones.add(value.trim())
                    addPhone(c, value, params["TYPE"].orEmpty(), st)
                }
                "EMAIL" -> {
                    val rawEmail = value.trim()
                    src.emails.add(rawEmail)
                    addEmail(c, value, params["TYPE"].orEmpty(), src)
                }
                "ADR" -> {
                    val parts = value.split(';')
                    c.address = Address(
                        street = cleanName(parts.getOrNull(2).orEmpty()),
                        city = cleanName(parts.getOrNull(3).orEmpty()),
                        region = cleanName(parts.getOrNull(4).orEmpty()),
                        postal = cleanName(parts.getOrNull(5).orEmpty()),
                        country = cleanName(parts.getOrNull(6).orEmpty()),
                        formatted = "",
                        type = cleanName(params["TYPE"].orEmpty())
                    )
                    src.address = Address(
                        street = parts.getOrNull(2).orEmpty().trim(),
                        city = parts.getOrNull(3).orEmpty().trim(),
                        region = parts.getOrNull(4).orEmpty().trim(),
                        postal = parts.getOrNull(5).orEmpty().trim(),
                        country = parts.getOrNull(6).orEmpty().trim(),
                        formatted = "",
                        type = params["TYPE"].orEmpty().trim()
                    )
                }
                "ORG" -> {
                    c.org = cleanName(value)
                    src.org = value.trim()
                }
                "TITLE" -> {
                    c.title = cleanName(value)
                    src.title = value.trim()
                }
                "ROLE" -> c.role = cleanName(value)
                "NICKNAME" -> {
                    c.nickname = value.split(';').joinToString(" ") { it.trim() }
                    src.nickname = value.trim()
                }
                "NOTE" -> c.note = value
                "BDAY" -> {
                    c.bday = parseDate(value)
                    if (c.bday == null && value.isNotBlank()) c.warnings.add("invalid birthday: $value")
                    src.bday = if (value.isNotBlank()) value.trim() else null
                }
                "URL" -> if (value.isNotBlank()) {
                    c.urls.add(normalizeUrl(value))
                    src.urls.add(value.trim())
                }
                "CATEGORIES" -> value.split(';').forEach { if (it.isNotBlank()) {
                    c.categories.add(it.trim())
                    src.categories.add(it.trim())
                } }
                "PHOTO" -> handlePhoto(c, params, value, st, src)
                "VERSION", "UID", "REV", "PRODID" -> {}
                else -> {
                    // Preserve the custom/unknown property verbatim so no data is lost.
                    c.customFields.add(line)
                }
            }
        }

        if (!hasN && c.fn.isNotBlank()) {
            val (g, f, a) = deriveNFromFn(c.fn)
            c.given = g; c.family = f; c.additional = a
        }
        if (c.fn.isBlank()) {
            c.fn = synthesizeFn(c)
            c.fnSynthesized = true
        }
        c.sources.add(src)
        return c
    }

    private fun handlePhoto(c: RepairedContact, params: Map<String, String>, value: String, st: State, src: SourceSnapshot) {
        val v = value.trim()
        val enc = params["ENCODING"]?.uppercase(Locale.ROOT).orEmpty()
        fun drop(msg: String) { c.warnings.add(msg); st.photosDropped++; src.photoDropped = true }
        when {
            enc == "BASE64" -> {
                try {
                    val bytes = Base64.getDecoder().decode(v.replace(Regex("\\s"), ""))
                    if (bytes.size in 32..200_000) c.photo = v
                    else drop("photo dropped (size ${bytes.size}B)")
                } catch (e: Exception) {
                    drop("photo dropped (invalid base64)")
                }
            }
            v.startsWith("http://") || v.startsWith("https://") -> {
                if (v.length < 500) c.photo = v
                else drop("photo dropped (url too long)")
            }
            v.isEmpty() -> {}
            else -> drop("photo dropped (unsupported encoding)")
        }
    }

    private fun parseProperty(line: String): Triple<String, Map<String, String>, String> {
        val idx = line.indexOf(':')
        if (idx < 0) return Triple(line.trim().uppercase(Locale.ROOT), emptyMap(), "")
        val head = line.substring(0, idx).trim()
        val value = line.substring(idx + 1)
        val parts = head.split(';')
        val name = parts[0].trim().uppercase(Locale.ROOT)
        val params = mutableMapOf<String, String>()
        for (p in parts.drop(1)) {
            val eq = p.indexOf('=')
            if (eq >= 0) params[p.substring(0, eq).trim().uppercase(Locale.ROOT)] = p.substring(eq + 1).trim()
        }
        return Triple(name, params, unescapeText(decodeValue(params, value)))
    }

    /** Unescape vCard 2.1/3.0/4.0 text escapes (single-pass). */
    private fun unescapeText(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    ',' -> { sb.append(','); i += 2 }
                    ';' -> { sb.append(';'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    else -> { sb.append(s[i]); i += 1 }
                }
            } else {
                sb.append(s[i]); i += 1
            }
        }
        return sb.toString()
    }

    private fun decodeValue(params: Map<String, String>, value: String): String {
        val enc = params["ENCODING"]?.uppercase(Locale.ROOT).orEmpty()
        val charsetName = params["CHARSET"]?.let { charsetFor(it) } ?: "UTF-8"
        return when (enc) {
            "QUOTED-PRINTABLE" -> {
                // Soft line breaks (=\n) are already removed by unfold().
                // Decode =XX sequences; skip the two hex chars after '='.
                val sb = java.nio.ByteBuffer.allocate(value.length)
                var i = 0
                while (i < value.length) {
                    val ch = value[i]
                    if (ch == '=' && i + 2 < value.length) {
                        sb.put((value.substring(i + 1, i + 3).toIntOrNull(16) ?: 0x3D).toByte())
                        i += 3
                    } else {
                        sb.put(ch.code.toByte())
                        i += 1
                    }
                }
                try { String(sb.array(), 0, sb.position(), Charset.forName(charsetName)) }
                catch (e: Exception) { String(sb.array(), 0, sb.position(), StandardCharsets.UTF_8) }
            }
            "BASE64" -> {
                try {
                    String(Base64.getDecoder().decode(value.trim().replace(Regex("\\s"), "")), StandardCharsets.UTF_8)
                } catch (e: Exception) { value.trim() }
            }
            else -> value
        }
    }

    private fun charsetFor(name: String): String = when (name.trim().uppercase(Locale.ROOT)) {
        "UTF-8", "UTF8" -> "UTF-8"
        "ISO-8859-1", "ISO8859-1", "LATIN1" -> "ISO-8859-1"
        "WINDOWS-1252", "CP1252" -> "Windows-1252"
        "UTF-16", "UTF16" -> "UTF-16"
        else -> "UTF-8"
    }

    // ---------------------------------------------------------------------
    // Names
    // ---------------------------------------------------------------------

    fun cleanName(s: String): String =
        s.filter { it.code >= 0x20 && it.code != 0x7F }.trim().replace(Regex("\\s+"), " ")

    fun fixShouty(name: String): String {
        val letters = name.filter { it.isLetter() }
        if (letters.length < 3) return name
        val allUpper = name.all { !it.isLetter() || it.isUpperCase() }
        val allLower = name.all { !it.isLetter() || it.isLowerCase() }
        if (!allUpper && !allLower) return name
        val tokens = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return tokens.mapIndexed { i, tok ->
            when {
                i > 0 && tok.lowercase(Locale.ROOT) in PARTICLES -> tok.lowercase(Locale.ROOT)
                tok in ACRONYMS -> tok
                else -> titleCaseToken(tok)
            }
        }.joinToString(" ")
    }

    private fun titleCaseToken(tok: String): String {
        if (tok.isEmpty()) return tok
        val hasUpper = tok.any { it.isUpperCase() }
        val hasLower = tok.any { it.isLowerCase() }
        if (hasUpper && hasLower) return tok
        return tok[0].uppercaseChar() + tok.drop(1).lowercase(Locale.ROOT)
    }

    fun deriveNFromFn(fn: String): Triple<String, String, String> {
        val clean = fn.trim()
        if (clean.contains(',')) {
            val parts = clean.split(',', limit = 2)
            return Triple(if (parts.size > 1) parts[1].trim() else "", parts[0].trim(), "")
        } else {
            val tokens = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.size <= 1) return Triple(tokens.firstOrNull().orEmpty(), "", "")
            return Triple(tokens[0], tokens.drop(1).joinToString(" "), "")
        }
    }

    fun synthesizeFn(c: RepairedContact): String {
        val fromN = listOf(c.given, c.family, c.additional).filter { it.isNotBlank() }.joinToString(" ")
        if (fromN.isNotBlank()) return fromN
        if (c.org.isNotBlank()) return c.org
        val email = c.emails.firstOrNull()?.value
        if (email != null) {
            val local = email.substringBefore('@').replace('.', ' ')
            if (local.isNotBlank()) return local
        }
        val phone = c.phones.firstOrNull()?.value
        if (phone != null) return phone
        return "Unknown"
    }

    fun nameKey(fn: String): String {
        var s = fn.lowercase(Locale.ROOT)
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        s = s.map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }.toCharArray().concatToString()
        return s.trim().replace(Regex("\\s+"), " ")
    }

    // ---------------------------------------------------------------------
    // Phones / Emails
    // ---------------------------------------------------------------------

    private fun phoneType(typeParam: String, num: Phonenumber.PhoneNumber, util: PhoneNumberUtil): String {
        val tp = typeParam.lowercase(Locale.ROOT)
        return when {
            tp.isEmpty() -> {
                val possible = util.getNumberType(num)
                if (possible == PhoneNumberType.MOBILE || possible == PhoneNumberType.FIXED_LINE_OR_MOBILE) "CELL" else "VOICE"
            }
            tp.contains("mobile") || tp.contains("cell") -> "CELL"
            tp.contains("fax") -> "FAX"
            tp.contains("home") -> "HOME"
            tp.contains("work") -> "WORK"
            else -> "VOICE"
        }
    }

    /**
     * Resolve a single raw phone value to E.164 using [ctx].
     * Returns Triple(e164OrNull, extension, customUnknownUsed). e164 is null when
     * the value cannot be resolved to E.164.
     */
    private fun resolvePhone(raw: String, ctx: ParseContext, util: PhoneNumberUtil): Triple<String?, String?, Boolean> {
        val trimmed = raw.trim()
        fun of(num: Phonenumber.PhoneNumber) =
            Triple(util.format(num, PhoneNumberUtil.PhoneNumberFormat.E164), num.extension.ifBlank { null }, false)

        // Already international (+ or 00 prefix): parse as-is, no preferred code prepended.
        if (trimmed.startsWith("+") || trimmed.startsWith("00")) {
            return try { of(util.parse(trimmed, ctx.regionCode)) }
            catch (e: Exception) { Triple(null, null, false) }
        }

        // National number with a known region: libphonenumber applies the trunk prefix.
        if (!ctx.customUnknown) {
            try {
                return of(util.parse(trimmed, ctx.regionCode))
            } catch (e: Exception) {
                // fall through to the fallback build
            }
        }

        // Fallback: strip one leading 0, then prepend the calling code if present.
        var digits = trimmed.replace(Regex("[^0-9]"), "")
        if (digits.startsWith("0")) digits = digits.drop(1)
        val cc = ctx.callingCodeDigits
        if (cc != null && digits.isNotEmpty() && !digits.startsWith(cc)) {
            try {
                return of(util.parse("+$cc$digits", "ZZ"))
            } catch (e: Exception) {
                // ignore
            }
        }
        return Triple(null, null, ctx.customUnknown)
    }

    private fun addPhone(c: RepairedContact, raw: String, typeParam: String, st: State) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        val (e164, ext, customUsed) = resolvePhone(trimmed, st.parseContext, st.util)
        if (e164 != null) {
            val num = try { st.util.parse(e164, st.parseContext.regionCode) } catch (e: Exception) { null }
            val type = if (num != null) phoneType(typeParam, num, st.util) else "VOICE"
            val validity = if (num != null) {
                when {
                    st.util.isValidNumber(num) -> PhoneValidity.VALID
                    st.util.isPossibleNumber(num) -> PhoneValidity.POSSIBLE
                    else -> PhoneValidity.INVALID
                }
            } else PhoneValidity.UNKNOWN
            val typeLabel = num?.let { typeLabelFor(it, st.util) } ?: "Unknown"
            if (c.phones.none { it.value == e164 }) c.phones.add(Phone(e164, type, ext, validity, typeLabel))
            else c.warnings.add("duplicate phone: $e164")
            if (validity == PhoneValidity.INVALID) c.warnings.add("invalid phone: $e164")
            else if (validity == PhoneValidity.POSSIBLE) c.warnings.add("phone may be invalid: $e164")
        } else {
            if (c.phones.none { it.value == trimmed }) c.phones.add(Phone(trimmed, "VOICE", null, PhoneValidity.INVALID, "Unknown"))
            c.warnings.add("unparsed phone: $trimmed")
            if (customUsed) c.warnings.add("custom calling code not in the phone database")
        }
    }

    /** Build a [Phone] with validity and readable type label computed by libphonenumber. */
    private fun makePhone(e164: String, type: String, ext: String?, util: PhoneNumberUtil, region: String): Phone {
        val num = try { util.parse(e164, region) } catch (e: Exception) { null }
        val validity = if (num != null) {
            when {
                util.isValidNumber(num) -> PhoneValidity.VALID
                util.isPossibleNumber(num) -> PhoneValidity.POSSIBLE
                else -> PhoneValidity.INVALID
            }
        } else PhoneValidity.UNKNOWN
        val label = num?.let { typeLabelFor(it, util) } ?: "Unknown"
        return Phone(e164, type, ext, validity, label)
    }

    /** Readable phone-type label from libphonenumber. */
    private fun typeLabelFor(num: Phonenumber.PhoneNumber, util: PhoneNumberUtil): String =
        when (util.getNumberType(num)) {
            PhoneNumberType.MOBILE -> "Mobile"
            PhoneNumberType.FIXED_LINE -> "Landline"
            PhoneNumberType.FIXED_LINE_OR_MOBILE -> "Mobile/Landline"
            PhoneNumberType.TOLL_FREE -> "Toll-free"
            PhoneNumberType.PREMIUM_RATE -> "Premium"
            PhoneNumberType.SHARED_COST -> "Shared cost"
            PhoneNumberType.VOICEMAIL -> "Voicemail"
            PhoneNumberType.UAN -> "UAN"
            PhoneNumberType.VOIP -> "VOIP"
            PhoneNumberType.PAGER -> "Pager"
            else -> "Unknown"
        }


    private fun addEmail(c: RepairedContact, raw: String, typeParam: String, src: SourceSnapshot?) {
        val e = raw.trim().lowercase(Locale.ROOT)
        if (e.isEmpty()) return
        if (!validEmail(e)) {
            c.warnings.add("invalid email dropped: $raw")
            src?.droppedEmails?.add(raw.trim())
            return
        }
        val tp = typeParam.lowercase(Locale.ROOT)
        val type = when {
            tp.contains("work") -> "WORK"
            tp.contains("home") -> "HOME"
            else -> "INTERNET"
        }
        if (c.emails.none { it.value == e }) c.emails.add(Email(e, type))
        else c.warnings.add("duplicate email: $e")
    }

    private fun validEmail(e: String): Boolean {
        if (e.contains(' ')) return false
        if (e.count { it == '@' } != 1) return false
        val local = e.substringBefore('@')
        val domain = e.substringAfter('@')
        return local.isNotEmpty() && domain.isNotEmpty() && domain.contains('.')
    }

    // ---------------------------------------------------------------------
    // URLs
    // ---------------------------------------------------------------------

    /** Normalize a URL: add a scheme when missing, lowercase the host, trim. */
    fun normalizeUrl(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s)) {
            if (s.startsWith("www.")) s = "http://$s"
            else s = "http://$s"
        }
        // Lowercase scheme + host (before the first "/" after ://).
        val m = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)([a-zA-Z0-9.-]+)").find(s)
        if (m != null) {
            s = m.groupValues[1].lowercase(Locale.ROOT) + m.groupValues[2].lowercase(Locale.ROOT) + s.substring(m.value.length)
        }
        return s
    }

    // ---------------------------------------------------------------------
    // Dates
    // ---------------------------------------------------------------------

    fun parseDate(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        var m = Regex("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})$").find(s)
        if (m != null) return validateDate(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        m = Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$").find(s)
        if (m != null) return validateDate(m.groupValues[3], m.groupValues[1], m.groupValues[2])
        m = Regex("^(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})$").find(s)
        if (m != null) return validateDate(m.groupValues[3], m.groupValues[2], m.groupValues[1])
        m = Regex("^(\\w+) (\\d{1,2}), (\\d{4})$").find(s)
        if (m != null) {
            val mon = englishMonth(m.groupValues[1])
            if (mon != null) return validateDate(m.groupValues[3], mon.toString(), m.groupValues[2])
        }
        return null
    }

    private fun validateDate(year: String, month: String, day: String): String? {
        val y = year.toIntOrNull() ?: return null
        val mo = month.toIntOrNull() ?: return null
        val d = day.toIntOrNull() ?: return null
        if (y < 1000 || y > 9999) return null
        if (mo < 1 || mo > 12) return null
        if (d < 1 || d > daysInMonth(y, mo)) return null
        return String.format(Locale.ROOT, "%04d-%02d-%02d", y, mo, d)
    }

    private fun daysInMonth(y: Int, mo: Int): Int = when (mo) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28
        else -> 31
    }

    private fun englishMonth(name: String): Int? = when (name.lowercase(Locale.ROOT)) {
        "january" -> 1; "february" -> 2; "march" -> 3; "april" -> 4
        "may" -> 5; "june" -> 6; "july" -> 7; "august" -> 8
        "september" -> 9; "october" -> 10; "november" -> 11; "december" -> 12
        else -> null
    }

    // ---------------------------------------------------------------------
    // Merge
    // ---------------------------------------------------------------------

    private fun mergeCards(cards: List<RepairedContact>): Pair<List<RepairedContact>, Int> {
        if (cards.size <= 1) return cards to 0
        val n = cards.size
        val parent = IntArray(n) { it }
        fun find(x0: Int): Int {
            var x = x0
            var r = x
            while (parent[r] != r) r = parent[r]
            while (parent[x] != x) { val nx = parent[x]; parent[x] = r; x = nx }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val strong = shareStrongKey(cards[i], cards[j])
                val nameScore = nameCompatibility(cards[i].nameKey, cards[j].nameKey)
                // Merge when a strong key is shared and the names are not clearly
                // different (fuzzy-compatible). A shared phone/email alone with
                // incompatible names stays separate (sharedPhoneNoMerge behavior).
                if (strong && nameScore >= 0.6) {
                    union(i, j)
                }
            }
        }

        val groups = mutableMapOf<Int, MutableList<RepairedContact>>()
        for (i in 0 until n) groups.getOrPut(find(i)) { mutableListOf() }.add(cards[i])

        val result = mutableListOf<RepairedContact>()
        var mergeCount = 0
        for ((_, group) in groups) {
            if (group.size == 1) result.add(group[0])
            else {
                result.add(mergeCluster(group))
                mergeCount += group.size - 1
            }
        }
        return result to mergeCount
    }

    private fun shareStrongKey(a: RepairedContact, b: RepairedContact): Boolean {
        if (a.phones.map { it.value }.toSet().intersect(b.phones.map { it.value }.toSet()).isNotEmpty()) return true
        return a.emails.map { it.value }.toSet().intersect(b.emails.map { it.value }.toSet()).isNotEmpty()
    }

    /**
     * Fuzzy name compatibility score in [0,1]. 1.0 = identical/one contains the
     * other. Catches "Jon Smith" ~ "John Smith" via token overlap + normalized
     * Levenshtein. A missing name on one side scores 1.0 (cannot be ruled out).
     */
    private fun nameCompatibility(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 1.0
        if (a == b) return 1.0
        val ta = a.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
        val tb = b.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
        if (ta.all { tb.contains(it) }) return 1.0
        if (tb.all { ta.contains(it) }) return 1.0
        val uni = ta.union(tb).size
        if (uni == 0) return 1.0
        val jaccard = ta.intersect(tb).size.toDouble() / uni
        // Normalized Levenshtein on the whole (already normalized) name keys.
        val lev = 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length).toDouble()
        return maxOf(jaccard, lev)
    }

    /** Levenshtein edit distance. */
    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        var prev = IntArray(n + 1) { it }
        var cur = IntArray(n + 1)
        for (i in 1..m) {
            cur[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[n]
    }

    private fun mergeCluster(group: List<RepairedContact>): RepairedContact {
        val base = group.maxByOrNull { if (it.fnSynthesized) -1 else it.fn.length }!!
        val merged = RepairedContact()
        merged.fn = base.fn
        merged.given = base.given
        merged.additional = base.additional
        merged.family = base.family
        merged.prefix = base.prefix
        merged.suffix = base.suffix
        merged.fnSynthesized = base.fnSynthesized
        merged.nickname = group.map { it.nickname }.filter { it.isNotBlank() }.maxByOrNull { it.length }.orEmpty()
        merged.org = pickLonger(group.map { it.org }, merged, "org")
        merged.title = pickLonger(group.map { it.title }, merged, "title")
        merged.role = group.map { it.role }.filter { it.isNotBlank() }.maxByOrNull { it.length }.orEmpty()

        for (g in group) {
            for (p in g.phones) if (merged.phones.none { it.value == p.value }) merged.phones.add(p)
            for (e in g.emails) if (merged.emails.none { it.value == e.value }) merged.emails.add(e)
            for (u in g.urls) if (u.isNotBlank() && merged.urls.none { it == u }) merged.urls.add(u)
            for (cat in g.categories) if (merged.categories.none { it == cat }) merged.categories.add(cat)
        }

        // Merge confidence: HIGH when a strong key is shared and names are near-identical,
        // MEDIUM when a strong key is shared, LOW otherwise.
        val hasStrong = group.any { g -> group.any { o -> g !== o && shareStrongKey(g, o) } }
        var minName = 1.0
        for (i in group.indices) for (j in i + 1 until group.size) {
            minName = minOf(minName, nameCompatibility(group[i].nameKey, group[j].nameKey))
        }
        merged.mergeConfidence = when {
            hasStrong && minName >= 0.9 -> MergeConfidence.HIGH
            hasStrong -> MergeConfidence.MEDIUM
            else -> MergeConfidence.LOW
        }

        merged.note = group.map { it.note.trim() }.filter { it.isNotBlank() }.distinct().joinToString("\n\n")

        val bdays = group.map { it.bday }.filterNotNull()
        if (bdays.isNotEmpty()) {
            merged.bday = bdays.first()
            if (bdays.distinct().size > 1) merged.warnings.add("conflicting birthdays, kept ${bdays.first()}")
        }

        merged.address = group.mapNotNull { it.address }.maxByOrNull {
            it.formatted.length + it.street.length + it.city.length
        }
        merged.photo = group.mapNotNull { it.photo }.firstOrNull()
        for (g in group) merged.warnings.addAll(g.warnings)
        // Preserve every source card so the original-details note can label
        // merged-away sources with their file names.
        for (g in group) for (s in g.sources) merged.sources.add(s)
        merged.nameKey = nameKey(merged.fn)
        return merged
    }

    private fun pickLonger(values: List<String>, merged: RepairedContact, field: String): String {
        val nonBlank = values.filter { it.isNotBlank() }
        if (nonBlank.size <= 1) return nonBlank.firstOrNull().orEmpty()
        val distinct = nonBlank.distinct()
        if (distinct.size == 1) return distinct[0]
        val sorted = distinct.sortedByDescending { it.length }
        merged.warnings.add("conflicting $field, kept longest")
        val others = sorted.drop(1).joinToString("\n")
        merged.note = if (merged.note.isBlank()) others else merged.note + "\n" + others
        return sorted[0]
    }

    // ---------------------------------------------------------------------
    // Redaction (opt-in): strip sensitive values before export
    // ---------------------------------------------------------------------

    private val SSN_REGEX = Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b")

    /**
     * Redact sensitive values on [c]: blank home/street addresses, drop phone
     * extensions, and remove SSN-like tokens from the note. Returns the number of
     * fields redacted.
     */
    fun redact(c: RepairedContact): Int {
        var n = 0
        val a = c.address
        if (a != null) {
            if (a.street.isNotBlank()) { c.address = a.copy(street = ""); n++ }
            if (a.formatted.isNotBlank()) { c.address = a.copy(formatted = ""); n++ }
        }
        for (i in c.phones.indices) {
            if (c.phones[i].extension != null) {
                c.phones[i] = c.phones[i].copy(extension = null)
                n++
            }
        }
        if (SSN_REGEX.containsMatchIn(c.note)) {
            c.note = SSN_REGEX.replace(c.note, "[REDACTED]")
            n++
        }
        return n
    }

    // ---------------------------------------------------------------------
    // Before/after change tracking (for the review diff)
    // ---------------------------------------------------------------------

    /**
     * Populate [c.changes] with a [FieldChange] for every field whose exported
     * value differs (trimmed) from the raw source. Mirrors the comparison done in
     * [appendOriginalNote]. Empty when nothing changed.
     */
    fun computeChanges(c: RepairedContact) {
        c.changes.clear()
        if (c.sources.isEmpty()) return
        val exportedPhones = c.phones.map { it.value }.toSet()
        val exportedEmails = c.emails.map { it.value }.toSet()

        for (src in c.sources) {
            if (src.fn.isNotBlank() && src.fn.trim() != c.fn.trim())
                c.changes.add(FieldChange("Name", src.fn, c.fn))
            else if (src.fn.isBlank() && c.fnSynthesized)
                c.changes.add(FieldChange("Name", "(empty)", c.fn))
            if (src.given.isNotBlank() && src.given.trim() != c.given.trim())
                c.changes.add(FieldChange("Given", src.given, c.given))
            if (src.family.isNotBlank() && src.family.trim() != c.family.trim())
                c.changes.add(FieldChange("Family", src.family, c.family))
            if (src.nickname.isNotBlank() && src.nickname.trim() != c.nickname.trim())
                c.changes.add(FieldChange("Nickname", src.nickname, c.nickname))
            for (p in src.phones) if (p.trim() !in exportedPhones)
                c.changes.add(FieldChange("Phone", p, exportedPhones.firstOrNull() ?: ""))
            for (em in src.emails) if (em.trim() !in exportedEmails)
                c.changes.add(FieldChange("Email", em, exportedEmails.firstOrNull() ?: ""))
            if (src.org.isNotBlank() && src.org.trim() != c.org.trim())
                c.changes.add(FieldChange("Organization", src.org, c.org))
            if (src.title.isNotBlank() && src.title.trim() != c.title.trim())
                c.changes.add(FieldChange("Title", src.title, c.title))
            val srcBday = src.bday
            if (srcBday != null && srcBday.trim() != (c.bday ?: ""))
                c.changes.add(FieldChange("Birthday", srcBday, c.bday ?: ""))
            for (u in src.urls) if (u.trim() !in c.urls)
                c.changes.add(FieldChange("Website", u, c.urls.firstOrNull() ?: ""))
            val srcAddr = src.address?.short().orEmpty()
            if (srcAddr.isNotBlank() && srcAddr != c.address?.short().orEmpty())
                c.changes.add(FieldChange("Address", srcAddr, c.address?.short().orEmpty()))
            val srcCats = src.categories.joinToString(", ")
            if (srcCats.isNotBlank() && srcCats != c.categories.joinToString(", "))
                c.changes.add(FieldChange("Groups", srcCats, c.categories.joinToString(", ")))
            if (src.photoDropped)
                c.changes.add(FieldChange("Photo", "(present)", "removed"))
        }
        // Deduplicate identical (label,before,after) triples.
        val distinct = c.changes.distinct()
        c.changes.clear()
        for (ch in distinct) c.changes.add(ch)
    }

    // ---------------------------------------------------------------------
    // Original-details note (previous value of every changed field)
    // ---------------------------------------------------------------------

    private const val ORIGINAL_MARKER = "Original details (before repair):"
    private const val MANUAL_MARKER = "Before manual edit:"
    private const val NOTE_BLOCK_CAP = 4000

    private fun fieldLine(label: String, raw: String): String =
        if (raw.isBlank()) "$label: (empty)" else "$label: $raw"

    private fun addressText(a: Address?): String =
        if (a == null) "" else listOf(a.street, a.city, a.region, a.postal, a.country).filter { it.isNotBlank() }.joinToString(", ")

    /**
     * Append an "Original details (before repair):" block to [c].note for every
     * field whose exported value differs (trimmed) from the raw source. No-op when
     * nothing changed. Idempotent: a card that already contains the marker does not
     * get a second copy of the same lines. The block is capped at [NOTE_BLOCK_CAP]
     * characters.
     */
    fun appendOriginalNote(c: RepairedContact) {
        if (c.sources.isEmpty()) return
        val exportedPhones = c.phones.map { it.value }.toSet()
        val exportedEmails = c.emails.map { it.value }.toSet()

        // Build the raw source lines (label, value) in a stable order.
        val lines = mutableListOf<Pair<String, String>>()
        for (src in c.sources) {
            val fileLabel = src.fileName

            // Name
            val fnRaw = src.fn
            if (fnRaw.isNotBlank() && fnRaw.trim() != c.fn.trim()) lines.add(fileLabel to fieldLine("Name", fnRaw))
            else if (fnRaw.isBlank() && c.fnSynthesized) lines.add(fileLabel to fieldLine("Name", ""))
            // Structured name parts
            if (src.prefix.isNotBlank() && src.prefix.trim() != c.prefix.trim()) lines.add(fileLabel to fieldLine("Prefix", src.prefix))
            if (src.given.isNotBlank() && src.given.trim() != c.given.trim()) lines.add(fileLabel to fieldLine("Given", src.given))
            if (src.additional.isNotBlank() && src.additional.trim() != c.additional.trim()) lines.add(fileLabel to fieldLine("Additional", src.additional))
            if (src.family.isNotBlank() && src.family.trim() != c.family.trim()) lines.add(fileLabel to fieldLine("Family", src.family))
            if (src.suffix.isNotBlank() && src.suffix.trim() != c.suffix.trim()) lines.add(fileLabel to fieldLine("Suffix", src.suffix))
            if (src.nickname.isNotBlank() && src.nickname.trim() != c.nickname.trim()) lines.add(fileLabel to fieldLine("Nickname", src.nickname))
            // Phones — one line per changed value (raw source string)
            for (p in src.phones) if (p.trim() !in exportedPhones) lines.add(fileLabel to fieldLine("Phone", p))
            // Emails — one per changed valid value + dropped invalid emails
            for (em in src.emails) if (em.trim() !in exportedEmails) lines.add(fileLabel to fieldLine("Email", em))
            for (em in src.droppedEmails) lines.add(fileLabel to fieldLine("Email", em))
            if (src.org.isNotBlank() && src.org.trim() != c.org.trim()) lines.add(fileLabel to fieldLine("Organization", src.org))
            if (src.title.isNotBlank() && src.title.trim() != c.title.trim()) lines.add(fileLabel to fieldLine("Title", src.title))
            val srcBday = src.bday
            if (srcBday != null) {
                val exportBday = c.bday ?: ""
                if (srcBday.trim() != exportBday) lines.add(fileLabel to fieldLine("Birthday", srcBday))
            }
            for (u in src.urls) if (u.trim() !in c.urls) lines.add(fileLabel to fieldLine("Website", u))
            val srcAddr = addressText(src.address)
            if (srcAddr.isNotBlank() && srcAddr != addressText(c.address)) lines.add(fileLabel to fieldLine("Address", srcAddr))
            val srcCats = src.categories.joinToString(", ")
            if (srcCats.isNotBlank() && srcCats != c.categories.joinToString(", ")) lines.add(fileLabel to fieldLine("Groups", srcCats))
            if (src.photoDropped) lines.add(fileLabel to "Photo: removed (not a usable image)")
        }

        if (lines.isEmpty()) return

        // Deduplicate identical (label,value) pairs.
        val distinct = lines.distinct()

        // Group by file label; add " #2", " #3" for repeated file names.
        val labelCounts = HashMap<String, Int>()
        val labelled = distinct.map { (fileLabel, line) ->
            val count = labelCounts.getOrPut(fileLabel) { 0 } + 1
            labelCounts[fileLabel] = count
            val suffix = if (count > 1) " #$count" else ""
            (fileLabel + suffix) to line
        }

        // Build the block. If this contact already has the marker, only add lines
        // not already present (avoids duplicating on a second repair pass).
        val existing = c.note
        val hasMarker = existing.contains(ORIGINAL_MARKER)
        val existingLines = if (hasMarker) existing.lineSequence().map { it.trim() }.toSet() else emptySet()
        val newLines = labelled.filter { (_, l) -> l !in existingLines }

        if (newLines.isEmpty()) return

        val sb = StringBuilder()
        if (!hasMarker) {
            sb.append(ORIGINAL_MARKER).append("\n")
        }
        // Emit a "From <file>:" header when there is more than one distinct
        // source label (so merged-away sources are attributable to a file).
        val distinctLabels = labelled.map { it.first }.distinct()
        val emitHeaders = distinctLabels.size > 1
        var lastLabel: String? = null
        for ((label, line) in newLines) {
            if (emitHeaders) {
                if (label != lastLabel) {
                    sb.append("From $label:\n")
                    lastLabel = label
                }
            }
            sb.append(line).append("\n")
        }

        var block = sb.toString().trimEnd('\n')
        // Cap at NOTE_BLOCK_CAP characters.
        if (block.length > NOTE_BLOCK_CAP) {
            val kept = mutableListOf<String>()
            var total = 0
            val allLines = block.lines()
            for (ln in allLines) {
                val cost = ln.length + 1
                if (total + cost > NOTE_BLOCK_CAP - 28) break
                kept.add(ln); total += cost
            }
            kept.add("Original details truncated.")
            block = kept.joinToString("\n")
        }

        c.note = if (c.note.isBlank()) block else c.note + "\n\n" + block
    }

    // ---------------------------------------------------------------------
    // Manual edit (review dialog) — appends a "Before manual edit:" block
    // ---------------------------------------------------------------------

    fun applyManualEdit(contact: RepairedContact, edit: ManualEdit, options: RepairOptions): RepairedContact {
        val util = PhoneNumberUtil.getInstance()
        val nc = contact.copy(
            phones = contact.phones.map { it.copy() }.toMutableList(),
            emails = contact.emails.map { it.copy() }.toMutableList(),
            urls = contact.urls.toMutableList(),
            categories = contact.categories.toMutableList(),
            warnings = contact.warnings.toMutableList(),
            sources = contact.sources.map { it }.toMutableList()
        )

        val changed = mutableListOf<Pair<String, String>>() // (label, previous value)

        // Display name
        if (edit.displayName != null && edit.displayName.trim() != nc.fn.trim()) {
            changed.add("Name" to nc.fn.trim())
            nc.fn = edit.displayName.trim()
        }
        // Primary phone
        if (edit.primaryPhone != null && edit.primaryPhone.isNotBlank() &&
            edit.primaryPhone.trim() != (nc.phones.firstOrNull()?.value ?: "")) {
            val previous = nc.phones.firstOrNull()?.value.orEmpty()
            val trimmed = edit.primaryPhone.trim()
            val ctx = Regions.resolveParseContext(options.defaultRegion, options.preferredCallingCode)
            val (e164, ext, custom) = resolvePhone(trimmed, ctx, util)
            if (e164 != null) {
                val p = makePhone(e164, nc.phones.firstOrNull()?.type ?: "VOICE", ext, util, ctx.regionCode)
                if (nc.phones.isNotEmpty()) nc.phones[0] = p
                else nc.phones.add(p)
            } else {
                val p = Phone(trimmed, "VOICE", null, PhoneValidity.INVALID, "Unknown")
                if (nc.phones.isNotEmpty()) nc.phones[0] = p
                else nc.phones.add(p)
                nc.warnings.add("unparsed phone: $trimmed")
                if (custom) nc.warnings.add("custom calling code not in the phone database")
            }
            changed.add("Phone" to previous)
        }
        // Primary email
        if (edit.primaryEmail != null && edit.primaryEmail.isNotBlank() &&
            edit.primaryEmail.trim().lowercase(Locale.ROOT) != (nc.emails.firstOrNull()?.value ?: "")) {
            val previous = nc.emails.firstOrNull()?.value.orEmpty()
            val e = edit.primaryEmail.trim().lowercase(Locale.ROOT)
            if (validEmail(e)) {
                if (nc.emails.isNotEmpty()) nc.emails[0] = Email(e, nc.emails[0].type)
                else nc.emails.add(Email(e, "INTERNET"))
                changed.add("Email" to previous)
            }
        }

        // Record manual-edit changes for the before/after diff.
        for ((label, prev) in changed) {
            val after = when (label) {
                "Name" -> nc.fn
                "Phone" -> nc.phones.firstOrNull()?.value.orEmpty()
                "Email" -> nc.emails.firstOrNull()?.value.orEmpty()
                else -> ""
            }
            if (prev != after) nc.changes.add(FieldChange(label, prev, after))
        }

        // Append a "Before manual edit:" block only for the changed fields.
        if (changed.isNotEmpty()) {
            val existing = nc.note
            val existingLines = existing.lineSequence().map { it.trim() }.toSet()
            val sb = StringBuilder()
            if (existing.contains(MANUAL_MARKER)) sb.append("\n")
            else sb.append("\n\n").append(MANUAL_MARKER).append("\n")
            var any = false
            for ((label, prev) in changed) {
                val line = fieldLine(label, prev)
                if (line in existingLines) continue // do not duplicate an original-details line
                sb.append(line).append("\n"); any = true
            }
            if (any) nc.note = if (nc.note.isBlank()) sb.toString().trimEnd('\n') else nc.note + "\n\n" + sb.toString().trimEnd('\n')
        }

        nc.nameKey = nameKey(nc.fn)
        return nc
    }

    // ---------------------------------------------------------------------
    // Legacy edit override (kept for compatibility; prefer applyManualEdit)
    // ---------------------------------------------------------------------

    fun applyEdit(c: RepairedContact, fn: String, phone: String, email: String, region: String): RepairedContact {
        val util = PhoneNumberUtil.getInstance()
        val nc = c.copy(
            phones = c.phones.map { it.copy() }.toMutableList(),
            emails = c.emails.map { it.copy() }.toMutableList(),
            urls = c.urls.toMutableList(),
            categories = c.categories.toMutableList(),
            warnings = c.warnings.toMutableList()
        )
        if (fn.isNotBlank()) nc.fn = fn.trim()
        if (phone.isNotBlank()) {
            val trimmed = phone.trim()
            val parsed = try {
                val num = util.parse(trimmed, region)
                util.format(num, PhoneNumberUtil.PhoneNumberFormat.E164) to num.extension.ifBlank { null }
            } catch (e: Exception) { null }
            if (parsed != null) {
                if (nc.phones.isNotEmpty()) nc.phones[0] = Phone(parsed.first, nc.phones[0].type, parsed.second)
                else nc.phones.add(Phone(parsed.first, "VOICE", parsed.second))
            } else {
                if (nc.phones.isNotEmpty()) nc.phones[0] = Phone(trimmed, "VOICE", null)
                else nc.phones.add(Phone(trimmed, "VOICE", null))
            }
        }
        if (email.isNotBlank()) {
            val e = email.trim().lowercase(Locale.ROOT)
            if (validEmail(e)) {
                if (nc.emails.isNotEmpty()) nc.emails[0] = Email(e, nc.emails[0].type)
                else nc.emails.add(Email(e, "INTERNET"))
            }
        }
        nc.nameKey = nameKey(nc.fn)
        return nc
    }
}
