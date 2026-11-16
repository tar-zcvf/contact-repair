package app.contactrepair.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the capability expansion (changes-3): phone intelligence, fuzzy merge
 * + confidence, before/after diff, custom-field preservation, input auto-detect,
 * URL normalization, redaction, CJK decode, and the new output formats.
 */
class CapabilityTest {

    private fun vcf(text: String): NamedBytes = NamedBytes("t.vcf", text.toByteArray(Charsets.UTF_8))

    private fun csv(text: String): NamedBytes = NamedBytes("t.csv", text.toByteArray(Charsets.UTF_8))

    private fun opts(
        region: String = "US",
        code: String = "",
        redact: Boolean = false
    ) = RepairOptions(region, true, true, true, code, redact)

    // ------------------------------------------------------------------
    // 1. Phone intelligence — validity + readable type label
    // ------------------------------------------------------------------

    @Test
    fun phoneValidAndLabeled() {
        // +14155552671 is a valid US number (same one the Google-CSV test uses).
        val r = repair(listOf(vcf("BEGIN:VCARD\nFN:John Doe\nTEL:+14155552671\nEND:VCARD\n")), opts())
        val p = r.cards[0].phones.first()
        assertEquals(PhoneValidity.VALID, p.validity)
        // The exact type label depends on libphonenumber's data; assert it is a
        // non-empty readable word, not a raw enum name.
        assertTrue("label should be a readable word, got '${p.typeLabel}'", p.typeLabel.isNotBlank())
        assertFalse("label should not be the raw enum name", p.typeLabel.startsWith("PHONE_TYPE"))
    }

    @Test
    fun phoneInvalidFlagged() {
        // A number libphonenumber cannot validate.
        val r = repair(listOf(vcf("BEGIN:VCARD\nFN:Bad\nTEL:12345\nEND:VCARD\n")), opts())
        val p = r.cards[0].phones.first()
        assertTrue(
            "expected INVALID/POSSIBLE, got ${p.validity}",
            p.validity == PhoneValidity.INVALID || p.validity == PhoneValidity.POSSIBLE
        )
        assertTrue(r.cards[0].warnings.any { it.contains("phone") || it.contains("Phone") })
    }

    @Test
    fun phoneDedupedSameContact() {
        val r = repair(listOf(vcf("BEGIN:VCARD\nFN:John\nTEL:+15550109999\nTEL:555-010-9999\nEND:VCARD\n")), opts())
        val phones = r.cards[0].phones.map { it.value }
        assertEquals("duplicate E.164 should be deduped", 1, phones.toSet().size)
    }

    // ------------------------------------------------------------------
    // 2. Fuzzy merge + confidence
    // ------------------------------------------------------------------

    @Test
    fun fuzzyNameMergeWithSharedPhone() {
        // Two cards, same phone, near-identical names -> merge.
        val v = "BEGIN:VCARD\nFN:Jon Smith\nTEL:+15550100001\nEMAIL:a@x.com\nEND:VCARD\n" +
                "BEGIN:VCARD\nFN:John Smith\nTEL:+15550100001\nEND:VCARD\n"
        val r = repair(listOf(vcf(v)), opts())
        assertEquals("near-identical names with shared phone should merge", 1, r.cards.size)
        assertEquals(MergeConfidence.HIGH, r.cards[0].mergeConfidence)
    }

    @Test
    fun sharedPhoneIncompatibleNamesNoMerge() {
        // Same phone, clearly different names -> stays separate (frozen behavior).
        val v = "BEGIN:VCARD\nFN:Alice Wonder\nTEL:+15550100002\nEND:VCARD\n" +
                "BEGIN:VCARD\nFN:Bob Builder\nTEL:+15550100002\nEND:VCARD\n"
        val r = repair(listOf(vcf(v)), opts())
        assertEquals("incompatible names with shared phone must NOT merge", 2, r.cards.size)
    }

    @Test
    fun mergeKeepsAllPhones() {
        // Two cards share a phone (strong key) -> merge; both distinct phones kept.
        val v = "BEGIN:VCARD\nFN:Jane Roe\nTEL:+15550100003\nEND:VCARD\n" +
                "BEGIN:VCARD\nFN:Jane Roe\nTEL:+15550100003\nTEL:+15550100004\nEMAIL:j@x.com\nEND:VCARD\n"
        val r = repair(listOf(vcf(v)), opts())
        assertEquals(1, r.cards.size)
        val vals = r.cards[0].phones.map { it.value }.toSet()
        assertTrue("both phones kept", vals.contains("+15550100003") && vals.contains("+15550100004"))
    }

    // ------------------------------------------------------------------
    // 3. Before/after change tracking
    // ------------------------------------------------------------------

    @Test
    fun changesRecordedForShoutyName() {
        val r = repair(listOf(vcf("BEGIN:VCARD\nFN:JOHN DOE\nTEL:+15550109999\nEND:VCARD\n")), opts())
        val c = r.cards[0]
        assertEquals("John Doe", c.fn)
        assertTrue("name change should be tracked", c.changes.any { it.label == "Name" && it.before == "JOHN DOE" && it.after == "John Doe" })
    }

    @Test
    fun noChangesForWellFormed() {
        val r = repair(listOf(vcf("BEGIN:VCARD\nFN:Ada Lovelace\nN:Lovelace;Ada\nTEL:+442079460958\nEND:VCARD\n")), opts("GB"))
        assertTrue("no changes expected", r.cards[0].changes.isEmpty())
    }

    @Test
    fun manualEditRecordsChange() {
        val r = repair(listOf(vcf("BEGIN:VCARD\nFN:John Doe\nTEL:+15550109999\nEND:VCARD\n")), opts())
        val edited = RepairEngine.applyManualEdit(r.cards[0], ManualEdit("Johnny Doe", null, null), opts())
        assertTrue(edited.changes.any { it.label == "Name" && it.before == "John Doe" && it.after == "Johnny Doe" })
    }

    // ------------------------------------------------------------------
    // 4. Custom/unknown field preservation
    // ------------------------------------------------------------------

    @Test
    fun customFieldPreserved() {
        val v = "BEGIN:VCARD\nFN:Test\nX-CUSTOM:hello world\nEND:VCARD\n"
        val r = repair(listOf(vcf(v)), opts())
        assertTrue("custom field should be captured", r.cards[0].customFields.any { it.contains("X-CUSTOM") })
        // And it should round-trip into the vCard output.
        val out = Writers.writeVCard(r.cards)
        assertTrue("custom field should be in output", out.contains("X-CUSTOM:hello world"))
    }

    // ------------------------------------------------------------------
    // 5. Input auto-detect — Outlook-style CSV
    // ------------------------------------------------------------------

    @Test
    fun outlookCsvAutoDetect() {
        val c = csv("First Name,Last Name,Mobile,Email Address\n" +
                "Jane,Smith,+14155552671,jane@example.com\n")
        val r = repair(listOf(c), opts())
        assertEquals(1, r.cards.size)
        val card = r.cards[0]
        assertEquals("Jane", card.given)
        assertEquals("Smith", card.family)
        assertEquals("+14155552671", card.phones.first().value)
        assertEquals("jane@example.com", card.emails.first().value)
    }

    @Test
    fun csvWithExtraAddressColumns() {
        val c = csv("Name,Phone,Email,City,State\n" +
                "Bob Jones,555-010-2222,bob@x.com,Springfield,IL\n")
        val r = repair(listOf(c), opts())
        val card = r.cards[0]
        assertEquals("Springfield", card.address?.city)
        assertEquals("IL", card.address?.region)
    }

    // ------------------------------------------------------------------
    // 6. URL normalization
    // ------------------------------------------------------------------

    @Test
    fun urlNormalized() {
        val r = repair(listOf(vcf("BEGIN:VCARD\nFN:Web\nURL:WWW.Example.COM\nEND:VCARD\n")), opts())
        assertEquals("http://www.example.com", r.cards[0].urls.first())
    }

    @Test
    fun urlWithSchemeLowercasedHost() {
        assertEquals("https://example.com/path", RepairEngine.normalizeUrl("https://Example.com/path"))
        assertEquals("http://foo.org", RepairEngine.normalizeUrl("foo.org"))
        assertEquals("http://www.foo.org", RepairEngine.normalizeUrl("www.foo.org"))
    }

    // ------------------------------------------------------------------
    // 7. Redaction
    // ------------------------------------------------------------------

    @Test
    fun redactionBlanksHomeAddressAndExtension() {
        val v = "BEGIN:VCARD\nFN:Homey\nTEL;TYPE=HOME;ext=1234:+15550109999\n" +
                "ADR;TYPE=HOME:;;123 Main St;Town;CA;90210;USA\nEND:VCARD\n"
        val r = repair(listOf(vcf(v)), opts(redact = true))
        val c = r.cards[0]
        assertEquals("street should be blanked", "", c.address?.street)
        assertEquals("extension should be dropped", null, c.phones.first().extension)
        assertTrue(r.report.redacted > 0)
    }

    @Test
    fun redactionNotDefault() {
        val v = "BEGIN:VCARD\nFN:Homey\nADR;TYPE=HOME:;;123 Main St;Town;CA;90210;USA\nEND:VCARD\n"
        val r = repair(listOf(vcf(v)), opts(redact = false))
        assertEquals("street kept when redaction off", "123 Main St", r.cards[0].address?.street)
        assertEquals(0, r.report.redacted)
    }

    @Test
    fun redactionStripsSsnFromNote() {
        val v = "BEGIN:VCARD\nFN:SSN\nNOTE:ssn is 123-45-6789 ok\nEND:VCARD\n"
        val r = repair(listOf(vcf(v)), opts(redact = true))
        assertTrue("SSN should be redacted", r.cards[0].note.contains("[REDACTED]"))
        assertFalse("raw SSN should be gone", r.cards[0].note.contains("123-45-6789"))
    }

    // ------------------------------------------------------------------
    // 8. CJK decode (best-effort)
    // ------------------------------------------------------------------

    @Test
    fun cjkDecodeDoesNotThrow() {
        // A GBK-encoded vCard with a Chinese name. Must not throw; name should be non-empty.
        val raw = "BEGIN:VCARD\r\nFN:\u4F53\u6D4B\u540D\u5B57\r\nTEL:+8613800000000\r\nEND:VCARD\r\n".toByteArray(java.nio.charset.Charset.forName("GBK"))
        val r = repair(listOf(NamedBytes("cjk.vcf", raw)), opts("CN"))
        assertTrue("CJK name should be non-empty", r.cards[0].fn.isNotBlank())
        assertEquals("+8613800000000", r.cards[0].phones.first().value)
    }

    // ------------------------------------------------------------------
    // 9. Output formats
    // ------------------------------------------------------------------

    private fun sample(): RepairedContact {
        val c = RepairedContact(
            fn = "Doe, John",
            family = "Doe",
            given = "John",
            phones = mutableListOf(Phone("+15550101234", "CELL", null, PhoneValidity.VALID, "Mobile")),
            emails = mutableListOf(Email("john@example.com", "INTERNET")),
            urls = mutableListOf("https://example.com"),
            categories = mutableListOf("Friends"),
            bday = "1980-01-02",
            note = "a note"
        )
        c.nameKey = RepairEngine.nameKey(c.fn)
        c.customFields.add("X-TEST:1")
        return c
    }

    @Test
    fun vCard40Output() {
        val out = Writers.writeVCard4(listOf(sample()))
        assertTrue(out.contains("VERSION:4.0"))
        assertTrue(out.contains("BEGIN:VCARD"))
        assertTrue("BDAY should be ISO in 4.0", out.contains("BDAY:1980-01-02"))
        assertTrue("custom field preserved", out.contains("X-TEST:1"))
    }

    @Test
    fun jsonOutput() {
        val out = Writers.writeJson(listOf(sample()))
        assertTrue(out.startsWith("["))
        assertTrue(out.contains("\"fn\": \"Doe, John\""))
        assertTrue(out.contains("\"validity\": \"VALID\""))
        assertTrue(out.contains("\"label\": \"Mobile\""))
        assertTrue(out.contains("\"mergeConfidence\": null"))
    }

    @Test
    fun perContactVcf() {
        val map = Writers.writeVcfPerContact(listOf(sample(), RepairedContact(fn = "A B")))
        assertTrue("one file per contact", map.size == 2)
        assertTrue("file names end with .vcf", map.keys.all { it.endsWith(".vcf") })
        assertTrue("each value is a vCard", map.values.all { it.contains("BEGIN:VCARD") && it.contains("END:VCARD") })
    }

    @Test
    fun reportIncludesNewCounts() {
        val c = RepairedContact(fn = "X", phones = mutableListOf(Phone("123", "VOICE", null, PhoneValidity.INVALID, "Unknown")))
        val r = RepairResult(listOf(c), RepairReport(invalidPhones = 1, redacted = 2))
        val rep = Writers.writeReport(r, opts(redact = true))
        assertTrue(rep.contains("invalid phones"))
        assertTrue(rep.contains("redacted fields"))
        assertTrue(rep.contains("preferred calling code"))
    }
}
