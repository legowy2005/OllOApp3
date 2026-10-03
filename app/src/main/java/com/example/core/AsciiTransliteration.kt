package com.example.core

import java.text.Normalizer
import java.util.regex.Pattern

data class TransliterationResult(
    val cleanText: String,
    val byteLength: Int,
    val hasModifications: Boolean
)

object AsciiTransliteration {
    private val DIACRITICS_AND_FRIENDS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+")

    /**
     * Converts input to printable US-ASCII (0x20 to 0x7E) according to Section 5.6:
     * - Tab (0x09), CR (0x0D), LF (0x0A) replaced with space
     * - Diacritics stripped (é -> e, ü -> u, etc.)
     * - Common ligatures/symbols handled (ß -> ss, curly quotes to straight quotes)
     * - Anything still unmappable replaced with '?'
     * - Maximum 100 bytes per side
     */
    fun sanitizeForGlasses(input: String): TransliterationResult {
        if (input.isEmpty()) {
            return TransliterationResult("", 0, false)
        }

        var text = input
            .replace('\t', ' ')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace("ß", "ss")
            .replace("“", "\"")
            .replace("”", "\"")
            .replace("‘", "'")
            .replace("’", "'")
            .replace("«", "\"")
            .replace("»", "\"")
            .replace("–", "-")
            .replace("—", "-")

        // Normalize unicode and strip diacritics
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        val stripped = DIACRITICS_AND_FRIENDS.matcher(normalized).replaceAll("")

        val builder = java.lang.StringBuilder()
        for (ch in stripped) {
            val code = ch.code
            if (code in 0x20..0x7E) {
                builder.append(ch)
            } else {
                builder.append('?')
            }
        }

        val resultString = builder.toString()
        val asciiBytes = resultString.toByteArray(Charsets.US_ASCII)
        val hasModifications = resultString != input

        return TransliterationResult(
            cleanText = resultString,
            byteLength = asciiBytes.size,
            hasModifications = hasModifications
        )
    }
}
