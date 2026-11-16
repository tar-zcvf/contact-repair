package app.contactrepair.core

import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

/**
 * A selectable region (country/territory) in the app's country control.
 */
data class RegionOption(
    val regionCode: String,
    val displayName: String,
    val callingCode: String
)

/**
 * How a raw phone value should be parsed.
 *
 * @param regionCode the libphonenumber region to parse national numbers against
 * @param callingCodeDigits the digits of the calling code (without `+`), or null
 * @param customUnknown true when the user typed a calling code that is not in the phone database
 */
data class ParseContext(
    val regionCode: String,
    val callingCodeDigits: String?,
    val customUnknown: Boolean
)

/**
 * Region catalog and calling-code resolution. Pure JVM so core unit tests can see it.
 *
 * The source of truth is [PhoneNumberUtil.getSupportedRegions]; every code it
 * returns appears exactly once, plus any ISO country code the library does not
 * support (shown with an empty calling code and a "(no phone metadata)" suffix).
 */
object Regions {

    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    /** The one readable fallback allowed for codes Java may not name. */
    private const val XK_FALLBACK = "Kosovo"

    /**
     * Every region libphonenumber can parse, plus ISO countries the library
     * does not support (so nothing is silently hidden).
     */
    fun regionCatalog(displayLocale: Locale): List<RegionOption> {
        val supported = util.getSupportedRegions()
        val options = mutableListOf<RegionOption>()
        val seen = HashSet<String>()

        for (code in supported) {
            seen.add(code)
            options.add(RegionOption(code, displayNameFor(code, displayLocale), callingCodeFor(code)))
        }

        // Append ISO countries the library does not support. Do not hide them.
        for (code in Locale.getISOCountries()) {
            if (seen.contains(code)) continue
            seen.add(code)
            val base = displayNameFor(code, displayLocale).removeSuffix(" (no phone metadata)").trim()
            options.add(
                RegionOption(
                    regionCode = code,
                    displayName = if (base.isBlank()) "$code (no phone metadata)"
                    else "$base (no phone metadata)",
                    callingCode = ""
                )
            )
        }

        // Sort by display name, case-insensitive. Empty-metadata rows sort
        // after real ones, then by name.
        return options.sortedWith(
            compareBy<RegionOption> { it.callingCode.isEmpty() }
                .thenBy { it.displayName.lowercase(Locale.ROOT) }
        )
    }

    private fun displayNameFor(code: String, displayLocale: Locale): String {
        var name = ""
        try {
            name = Locale("", code).getDisplayCountry(displayLocale)
        } catch (e: Exception) {
            name = ""
        }
        if (name.isBlank()) {
            try {
                name = Locale("", code).getDisplayCountry(Locale.ENGLISH)
            } catch (e: Exception) {
                name = ""
            }
        }
        if (name.isBlank()) {
            // Only the one allowed readable fallback.
            name = if (code == "XK") XK_FALLBACK else code
        }
        return name
    }

    private fun callingCodeFor(code: String): String {
        val cc = util.getCountryCodeForRegion(code)
        return if (cc > 0) "+$cc" else ""
    }

    /**
     * Filter [options] by a free-text query. Matches display name, region code,
     * or calling code (case-insensitive). A query of "+90" or "90" matches
     * calling code "+90". Spaces and dashes are ignored when comparing codes.
     * An empty query returns the full list.
     */
    fun filterRegions(options: List<RegionOption>, query: String): List<RegionOption> {
        val q = query.trim()
        if (q.isEmpty()) return options
        val qLower = q.lowercase(Locale.ROOT)
        val qDigits = q.replace("+", "").replace(" ", "").replace("-", "")
        return options.filter { o ->
            qLower in o.displayName.lowercase(Locale.ROOT) ||
                qLower == o.regionCode.lowercase(Locale.ROOT) ||
                (o.callingCode.isNotEmpty() && qDigits.isNotEmpty() &&
                    qDigits == o.callingCode.replace("+", "").replace(" ", "").replace("-", ""))
        }
    }

    /**
     * Resolve how a raw phone value should be parsed from the dropdown region and
     * the user-typed preferred calling code. Never throws.
     */
    fun resolveParseContext(dropdownRegion: String, preferredCallingCode: String): ParseContext {
        val supported = util.getSupportedRegions()

        var region = dropdownRegion.trim().uppercase(Locale.ROOT)
        if (region !in supported) region = "US"

        val preferred = preferredCallingCode.trim()
        if (preferred.isEmpty()) return ParseContext(region, null, false)

        // Two-letter region code?
        if (preferred.length == 2 && preferred.all { it.isLetter() }) {
            val up = preferred.uppercase(Locale.ROOT)
            if (up in supported) {
                val cc = util.getCountryCodeForRegion(up)
                return ParseContext(up, if (cc > 0) cc.toString() else null, false)
            }
            // Not a supported region: ignore the letters and continue with the dropdown region.
            return ParseContext(region, null, false)
        }

        // Otherwise take the digits.
        var digits = preferred.filter { it.isDigit() }
        if (digits.startsWith("00")) digits = digits.drop(2)
        if (digits.isEmpty()) return ParseContext(region, null, false)

        // More than 4 digits: keep the longest prefix (1..4) that maps to a real region.
        if (digits.length > 4) {
            for (len in 4 downTo 1) {
                val prefix = digits.substring(0, len)
                val mapped = util.getRegionCodeForCountryCode(prefix.toInt())
                if (mapped != null && mapped != "ZZ" && mapped != "001") {
                    return ParseContext(mapped, prefix, false)
                }
            }
            // Nothing maps: the whole string is a custom unknown code.
            return ParseContext(region, digits, true)
        }

        // 1..4 digits.
        val mapped = util.getRegionCodeForCountryCode(digits.toInt())
        if (mapped != null && mapped != "ZZ" && mapped != "001" && mapped in supported) {
            return ParseContext(mapped, digits, false)
        }
        // Digits do not map to a supported region: keep the dropdown region, custom unknown.
        return ParseContext(region, digits, true)
    }
}
