package com.griha.inspection.core.ai

/**
 * Removes this inspection's own client details from text before it goes to DeepSeek: inspectors
 * sometimes write "Mr. Rao says the leak started in May" or the flat number into notes. Done on the
 * phone because only the phone always has them (an inspection may not be synced yet). The server
 * additionally removes any phone numbers and email addresses. Pure Kotlin, unit-tested.
 */
class PiiScrubber(clientName: String, clientPhone: String, propertyAddress: String, unitNumber: String?) {
    private val rules: List<Pair<Regex, String>> = buildList {
        // Longest first: the whole address and name before their parts.
        propertyAddress.trim().takeIf { it.length >= 6 }?.let { add(literal(it) to "the property") }
        // Flat/house numbers like "B-302" or "3B"; a bare "302" could just as well be a measurement.
        unitNumber?.trim()?.takeIf { u -> u.length >= 2 && u.any { it.isLetter() || it in "-/" } }?.let { add(word(it) to "[unit]") }
        val name = clientName.trim()
        if (name.length >= 3) add(word(name) to "the client")
        name.split(Regex("[\\s.,]+")).filter { it.length >= 3 }.forEach { add(word(it) to "the client") }
        // The client's number with any spacing ("98480 22338", "+91-98480-22338").
        clientPhone.filter(Char::isDigit).takeLast(10).takeIf { it.length >= 6 }?.let { digits ->
            add(Regex(digits.toCharArray().joinToString("[\\s.-]?") { Regex.escape(it.toString()) }) to "[phone]")
        }
    }

    fun scrub(text: String): String = rules.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }

    private fun literal(s: String) = Regex(Regex.escape(s), RegexOption.IGNORE_CASE)

    private fun word(s: String) = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(s) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
}
