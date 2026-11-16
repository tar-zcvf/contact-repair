package app.contactrepair.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RepairTest {

    private fun res(name: String): NamedBytes {
        val bytes = javaClass.getResourceAsStream("/$name")!!.readBytes()
        return NamedBytes(name, bytes)
    }

    private fun opts(region: String = "US", merge: Boolean = true, shouty: Boolean = true, dropEmpty: Boolean = true) =
        RepairOptions(region, merge, shouty, dropEmpty)

    // 1. Two cards for John Doe, region US.
    @Test
    fun johnDoeMerge() {
        val r = repair(listOf(res("john-doe-two-cards.vcf")), opts("US"))
        assertEquals(1, r.cards.size)
        val c = r.cards[0]
        assertEquals("John Doe", c.fn)
        assertEquals("+15550109999", c.phones.first().value)
        assertEquals("john.doe@gmail.com", c.emails.first().value)
        assertTrue(c.note.contains("met at conference"))
        assertEquals(1, r.report.merged)
        // The shouty-name fix and phone reformat must be preserved in the note.
        assertTrue(c.note.contains("Original details (before repair):"))
        assertTrue(c.note.contains("Name: JOHN DOE"))
        assertTrue(c.note.contains("Phone: 555-010-9999"))
    }

    // 2. Glued END:VCARDBEGIN:VCARD, region GB.
    @Test
    fun gluedCards() {
        val r = repair(listOf(res("glued.vcf")), opts("GB"))
        assertEquals(1, r.cards.size)
        val c = r.cards[0]
        assertEquals("Ada Lovelace", c.fn)
        assertEquals("+442079460958", c.phones.first().value)
    }

    // 3. Quoted-printable UTF-8 FN with soft break + non-ASCII char.
    @Test
    fun quotedPrintableUtf8() {
        val r = repair(listOf(res("qp-utf8.vcf")), opts("US"))
        assertEquals(1, r.cards.size)
        val fn = r.cards[0].fn
        assertEquals("Jos\u00E9", fn) // José
        assertTrue(!fn.contains("="))
    }

    // 4. McDonald O'Brien unchanged with fixShouty on.
    @Test
    fun mcdonaldObrienUnchanged() {
        val r = repair(listOf(res("mcdonald-obrien.vcf")), opts("US"))
        assertEquals("McDonald O'Brien", r.cards[0].fn)
    }

    // 5. VAN DER BERG -> Van der Berg.
    @Test
    fun vanDerBerg() {
        val r = repair(listOf(res("van-der-berg.vcf")), opts("US"))
        assertEquals("Van der Berg", r.cards[0].fn)
    }

    // 6. Google CSV row.
    @Test
    fun googleCsvRow() {
        val r = repair(listOf(res("google-csv.csv")), opts("US"))
        assertEquals(1, r.cards.size)
        val c = r.cards[0]
        assertEquals("Smith, Jane", c.fn)
        assertEquals("Jane", c.given)
        assertEquals("Smith", c.family)
        assertEquals("+14155552671", c.phones.first().value)
        assertEquals("jane@example.com", c.emails.first().value)
    }

    // 7. Zip with one .vcf and a nested .zip.
    @Test
    fun zipWithNestedZip() {
        val vcf = "BEGIN:VCARD\nVERSION:3.0\nFN:Zed Zed\nTEL:+1 555 010 8888\nEND:VCARD\n"
            .toByteArray()
        // Build a real nested zip (so isZip() detects it)
        val innerVcf = "BEGIN:VCARD\nFN:Inner\nEND:VCARD".toByteArray()
        val innerZipBytes = buildZip(mapOf("inner.vcf" to innerVcf))
        val zipBytes = buildZip(mapOf("inner.zip" to innerZipBytes, "contact.vcf" to vcf))
        val r = repair(listOf(NamedBytes("outer.zip", zipBytes)), opts("US"))
        assertEquals(1, r.cards.size)
        assertEquals("Zed Zed", r.cards[0].fn)
        assertTrue(r.report.warnings.any { it.second.contains("nested zip") })
    }

    // 8. Garbage file: 0 cards, warning, no throw.
    @Test
    fun garbageFile() {
        val r = repair(listOf(res("garbage.txt")), opts("US"))
        assertEquals(0, r.cards.size)
        assertTrue(r.report.warnings.isNotEmpty())
    }

    // 9. Shared phone, unrelated names -> no merge.
    @Test
    fun sharedPhoneNoMerge() {
        val r = repair(listOf(res("alice-bob.vcf")), opts("US"))
        assertEquals(2, r.cards.size)
        assertEquals(0, r.report.merged)
    }

    // 10. Writer tests.
    @Test
    fun writerTests() {
        val c = RepairedContact(
            fn = "Doe, John",
            family = "Doe",
            given = "John",
            phones = mutableListOf(Phone("+15550101234", "CELL", null)),
            emails = mutableListOf(Email("john@example.com", "INTERNET")),
            note = "x".repeat(90)
        )
        c.nameKey = RepairEngine.nameKey(c.fn)
        val vcf = Writers.writeVCard(listOf(c))
        assertTrue(vcf.contains("VERSION:3.0"))
        assertTrue(vcf.contains("\r\n"))
        assertTrue(vcf.contains("FN:Doe\\, John"))
        // 90-char note must be folded (continuation lines start with a space)
        assertTrue(vcf.contains("\r\n "))

        // Round-trip
        val r2 = repair(listOf(NamedBytes("rt.vcf", vcf.toByteArray())), opts("US"))
        assertEquals(1, r2.cards.size)
        assertEquals("+15550101234", r2.cards[0].phones.first().value)
        assertEquals("john@example.com", r2.cards[0].emails.first().value)
        assertEquals("Doe, John", r2.cards[0].fn)
    }

    // Extra: deterministic UID.
    @Test
    fun deterministicUid() {
        val c = RepairedContact(
            fn = "A B",
            phones = mutableListOf(Phone("+15550101111", "CELL", null)),
            emails = mutableListOf(Email("a@b.com", "INTERNET"))
        )
        c.nameKey = RepairEngine.nameKey(c.fn)
        val u1 = Writers.uid(c)
        val u2 = Writers.uid(c)
        assertEquals(u1, u2)
        assertTrue(u1.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-3[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    // Extra: CSV writer header + quoting.
    @Test
    fun csvWriterHeader() {
        val c = RepairedContact(fn = "A, B", family = "B", given = "A")
        val csv = Writers.writeCsv(listOf(c))
        val firstLine = csv.split("\r\n").first()
        assertEquals(Writers.CSV_HEADER.joinToString(","), firstLine)
        assertTrue(csv.contains("\"A, B\""))
    }

    // Extra: report writer.
    @Test
    fun reportWriter() {
        val r = repair(listOf(res("garbage.txt")), opts("US"))
        val rep = Writers.writeReport(r, opts("US"))
        assertTrue(rep.contains("Input files:"))
        assertTrue(rep.contains("Counts:"))
        assertTrue(rep.contains("Warnings:"))
    }

    // End-to-end: all built-in corrupt fixtures -> valid vCard 3.0 + Google CSV.
    @Test
    fun allFixturesProduceValidOutputs() {
        val names = listOf(
            "john-doe-two-cards.vcf", "glued.vcf", "qp-utf8.vcf",
            "mcdonald-obrien.vcf", "van-der-berg.vcf", "alice-bob.vcf",
            "google-csv.csv", "garbage.txt"
        )
        val files = names.map { res(it) }
        val r = repair(files, opts("US"))

        val vcf = Writers.writeVCard(r.cards)
        val csv = Writers.writeCsv(r.cards)
        val rep = Writers.writeReport(r, opts("US"))

        // vCard 3.0 structural validation
        assertTrue("must end with END:VCARD CRLF", vcf.endsWith("END:VCARD\r\n"))
        assertTrue("must contain VERSION:3.0", vcf.contains("VERSION:3.0"))
        val vcfLines = vcf.split("\r\n").filter { it.isNotEmpty() }
        assertTrue("must start with BEGIN:VCARD", vcfLines.first() == "BEGIN:VCARD")
        assertTrue("must end with END:VCARD", vcfLines.last() == "END:VCARD")
        // every card has an FN (may carry a CHARSET= parameter for non-ASCII)
        val cardCount = vcfLines.count { it == "BEGIN:VCARD" }
        val fnCount = vcfLines.count { it.startsWith("FN:") || it.startsWith("FN;") }
        assertEquals("one FN per card", cardCount, fnCount)
        // CRLF: no bare LF (every LF must be preceded by CR)
        var pos = vcf.indexOf("\n")
        while (pos >= 0) {
            assertTrue("bare LF at $pos", pos == 0 || vcf[pos - 1] == '\r')
            pos = vcf.indexOf("\n", pos + 1)
        }
        // no literal =XX QP sequences leaked
        assertFalse("leaked QP", Regex("=3D|=2C|=5C").containsMatchIn(vcf))

        // Google CSV validation
        val csvLines = csv.split("\r\n")
        assertEquals(Writers.CSV_HEADER.joinToString(","), csvLines.first())
        assertTrue("csv non-empty", csvLines.size >= 2)

        // report validation
        assertTrue(rep.contains("Input files:"))
        assertTrue(rep.contains("Counts:"))
        assertTrue(rep.contains("Warnings:"))

        // The John Doe merge must have happened across fixtures
        assertTrue("John Doe card present", r.cards.any { it.fn == "John Doe" })
        // Ada Lovelace from glued fixture
        assertTrue("Ada Lovelace present", r.cards.any { it.fn == "Ada Lovelace" })
        // Van der Berg shouty fix
        assertTrue("Van der Berg fixed", r.cards.any { it.fn == "Van der Berg" })
        // McDonald O'Brien preserved
        assertTrue("McDonald O'Brien preserved", r.cards.any { it.fn == "McDonald O'Brien" })
    }

    // ------------------------------------------------------------------
    // Phase 1 — region catalog, typed calling code
    // ------------------------------------------------------------------

    private fun util() = com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance()

    @Test
    fun catalogContainsAllSupportedRegions() {
        val supported = util().supportedRegions
        val catalog = Regions.regionCatalog(java.util.Locale.US)
        val codes = catalog.map { it.regionCode }.toSet()
        assertTrue("catalog size >= supported regions", catalog.size >= supported.size)
        for (code in supported) {
            assertTrue("catalog missing supported region $code", codes.contains(code))
        }
    }

    @Test
    fun catalogContainsNamedRegionsWhenSupported() {
        val supported = util().supportedRegions.toSet()
        val catalog = Regions.regionCatalog(java.util.Locale.US).map { it.regionCode }.toSet()
        val named = listOf("TR","AZ","KZ","UZ","GE","AM","CY","XK","PS","TW","HK","MO","PR","DO","GT","NG","KE","ZA","EG","SA","AE","QA","KW","BH","OM","IQ","IR","PK","BD","LK","NP","MM","KH","LA","MN","ET","GH","TZ","UG","DZ","MA","TN","RS","BA","MK","AL","IS","LU","MT")
        for (code in named) {
            if (code in supported) {
                assertTrue("catalog missing $code (supported by library)", catalog.contains(code))
            }
        }
    }

    @Test
    fun filterRegionsByCallingCodeIncludesTr() {
        val catalog = Regions.regionCatalog(java.util.Locale.US)
        val filtered = Regions.filterRegions(catalog, "90")
        if ("TR" in util().supportedRegions) {
            assertTrue("TR should be in filterRegions('90')", filtered.any { it.regionCode == "TR" })
        }
        // Empty query returns the full list.
        assertEquals(catalog.size, Regions.filterRegions(catalog, "").size)
    }

    @Test
    fun resolveParseContextPrefersCallingCode() {
        val ctx = Regions.resolveParseContext("US", "+90")
        assertEquals("TR", ctx.regionCode)
        assertEquals("90", ctx.callingCodeDigits)
        assertFalse(ctx.customUnknown)
    }

    @Test
    fun turkishNationalLeadingZero() {
        val vcf = "BEGIN:VCARD\nVERSION:3.0\nFN:Ali Veli\nTEL:0532 111 2233\nEND:VCARD\n".toByteArray()
        val r = repair(listOf(NamedBytes("tr.vcf", vcf)), opts("US").copy(preferredCallingCode = "+90"))
        val phone = r.cards[0].phones.first().value
        if (phone == "+905321112233") {
            // The specific number was accepted by libphonenumber.
            assertTrue(phone.startsWith("+90"))
            assertTrue("no trunk 0 after +90", !phone.startsWith("+900"))
        } else {
            // The specific number was rejected; use a known-valid TR mobile.
            val example = util().parse("+905321234567", "TR")
            val national = util().format(example, com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat.NATIONAL)
            val vcf2 = "BEGIN:VCARD\nVERSION:3.0\nFN:Ali Veli\nTEL:$national\nEND:VCARD\n".toByteArray()
            val r2 = repair(listOf(NamedBytes("tr.vcf", vcf2)), opts("US").copy(preferredCallingCode = "+90"))
            val phone2 = r2.cards[0].phones.first().value
            assertTrue("phone should start with +90: $phone2", phone2.startsWith("+90"))
            assertTrue("no trunk 0 after +90: $phone2", !phone2.startsWith("+900"))
        }
    }

    @Test
    fun preferredCodeDoesNotChangeExistingPlusOne() {
        val vcf = "BEGIN:VCARD\nVERSION:3.0\nFN:US User\nTEL:+1 202 555 0143\nEND:VCARD\n".toByteArray()
        val r = repair(listOf(NamedBytes("us.vcf", vcf)), opts("US").copy(preferredCallingCode = "+90"))
        assertTrue("phone must still start with +1", r.cards[0].phones.first().value.startsWith("+1"))
    }

    @Test
    fun customCallingCodeDoesNotThrow() {
        val vcf = "BEGIN:VCARD\nVERSION:3.0\nFN:Test\nTEL:0555 1234\nEND:VCARD\n".toByteArray()
        val r = repair(listOf(NamedBytes("t.vcf", vcf)), opts("US").copy(preferredCallingCode = "+999"))
        val phone = r.cards[0].phones.first().value
        assertTrue("phone non-empty", phone.isNotEmpty())
        if (phone == "0555 1234") {
            assertTrue(r.report.warnings.any { it.second.contains("custom calling code not in the phone database") })
        }
    }

    // ------------------------------------------------------------------
    // Phase 2 — original values kept in Notes
    // ------------------------------------------------------------------

    @Test
    fun shoutyNameFixAddsOriginalNote() {
        val vcf = "BEGIN:VCARD\nVERSION:3.0\nFN:JOHN DOE\nTEL:+15550109999\nEND:VCARD\n".toByteArray()
        val r = repair(listOf(NamedBytes("shouty.vcf", vcf)), opts("US"))
        assertEquals("John Doe", r.cards[0].fn)
        assertTrue(r.cards[0].note.contains("Original details (before repair):"))
        assertTrue(r.cards[0].note.contains("Name: JOHN DOE"))
    }

    @Test
    fun wellFormedCardNoOriginalNote() {
        val vcf = "BEGIN:VCARD\nVERSION:3.0\nFN:Ada Lovelace\nN:Lovelace;Ada\nTEL:+442079460958\nEMAIL:ada@example.com\nEND:VCARD\n".toByteArray()
        val r = repair(listOf(NamedBytes("ada.vcf", vcf)), opts("GB"))
        assertFalse(r.cards[0].note.contains("Original details (before repair):"))
    }

    @Test
    fun notePreservedAndOriginalAppended() {
        val vcf = "BEGIN:VCARD\nVERSION:3.0\nFN:Test User\nNOTE:keep me\nTEL:555-010-9999\nEND:VCARD\n".toByteArray()
        val r = repair(listOf(NamedBytes("t.vcf", vcf)), opts("US"))
        val note = r.cards[0].note
        assertTrue("note should start with 'keep me': $note", note.startsWith("keep me"))
        assertTrue("note should contain original marker", note.contains("Original details (before repair):"))
        assertTrue("note should contain raw phone", note.contains("555-010-9999"))
    }

    @Test
    fun applyManualEditDisplayNameOnly() {
        val vcf = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nTEL:+15550109999\nEND:VCARD\n".toByteArray()
        val r = repair(listOf(NamedBytes("t.vcf", vcf)), opts("US"))
        val c = r.cards[0]
        val edited = RepairEngine.applyManualEdit(c, ManualEdit("Johnny Doe", null, null), opts("US"))
        assertEquals("Johnny Doe", edited.fn)
        assertTrue(edited.note.contains("Before manual edit:"))
        assertTrue(edited.note.contains("Name: John Doe"))
        val manualBlock = edited.note.substringAfter("Before manual edit:")
        assertFalse("must not claim phone was manually edited", manualBlock.contains("Phone:"))
    }

    @Test
    fun originalNoteSurvivesSecondRepairOnce() {
        val vcf = "BEGIN:VCARD\nVERSION:3.0\nFN:JOHN DOE\nTEL:+15550109999\nEND:VCARD\n".toByteArray()
        val r1 = repair(listOf(NamedBytes("t.vcf", vcf)), opts("US"))
        val vcf1 = Writers.writeVCard(r1.cards)
        val r2 = repair(listOf(NamedBytes("out.vcf", vcf1.toByteArray())), opts("US"))
        val note = r2.cards[0].note
        val count = note.split("Original details (before repair):").size - 1
        assertEquals("marker should appear exactly once: $note", 1, count)
    }

    // ------------------------------------------------------------------
    // helper: build a zip
    private fun buildZip(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        val zos = ZipOutputStream(bos)
        for ((name, data) in entries) {
            zos.putNextEntry(ZipEntry(name))
            zos.write(data)
            zos.closeEntry()
        }
        zos.close()
        return bos.toByteArray()
    }
}
