package com.kannadadoc.converter

import com.kannadadoc.parser.DocBlock
import com.kannadadoc.parser.ParsedDocument
import com.kannadadoc.parser.TextRun

enum class KannadaFormat(
    val labelEn: String,
    val labelKn: String,
    val bilingualTitle: String,
    val bilingualSubtitle: String
) {
    UNICODE(
        labelEn = "Modern Universal Kannada",
        labelKn = "ಆಧುನಿಕ ಸಾರ್ವತ್ರಿಕ ಕನ್ನಡ",
        bilingualTitle = "Modern Universal Kannada • ಆಧುನಿಕ ಸಾರ್ವತ್ರಿಕ ಕನ್ನಡ",
        bilingualSubtitle = "Supported on all modern devices (phones, tablets, etc.), not supported in Nudi & Baraha • ಫೋನ್, ಟ್ಯಾಬ್ಲೆಟ್ ಮುಂತಾದ ಎಲ್ಲಾ ಆಧುನಿಕ ಸಾಧನಗಳಲ್ಲಿ ಬೆಂಬಲಿತವಾಗಿದೆ, ಆದರೆ ನುಡಿ ಮತ್ತು ಬರಹದಲ್ಲಿ ಬೆಂಬಲಿತವಾಗಿಲ್ಲ"
    ),
    NUDI(
        labelEn = "Nudi",
        labelKn = "ನುಡಿ",
        bilingualTitle = "Nudi • ನುಡಿ",
        bilingualSubtitle = "Supported on PC only • ಕಂಪ್ಯೂಟರ್‌ನಲ್ಲಿ (PC) ಮಾತ್ರ ಬೆಂಬಲಿತವಾಗಿದೆ"
    ),
    BARAHA(
        labelEn = "Baraha",
        labelKn = "ಬರಹ",
        bilingualTitle = "Baraha • ಬರಹ",
        bilingualSubtitle = "Supported on PC only • ಕಂಪ್ಯೂಟರ್‌ನಲ್ಲಿ (PC) ಮಾತ್ರ ಬೆಂಬಲಿತವಾಗಿದೆ"
    )
}

data class DetectedInputInfo(
    val detectedFormat: KannadaFormat,
    val confidenceNoteEn: String,
    val confidenceNoteKn: String
)

/**
 * Pure Kotlin bidirectional Kannada converter (exact parity with kannada-converter.js v2):
 * - Auto-detects Nudi / Baraha legacy ASCII vs Unicode Kannada
 * - Converts Nudi/Baraha ASCII -> Unicode Kannada (with ü mahaprana, base-u ಣ, ø/È post-ottakshara ೃ,
 *   ªÉÇ/ªÉÇÃ ವೊ/ವೋ, ö/÷ ottakshara spacers, Å short ು)
 * - Converts Unicode Kannada -> Nudi / Baraha ASCII (with English/Kannada script-splitting for PC Word export)
 */
object KannadaTextConverter {

    private val KANNADA_UNICODE_REGEX = Regex("[\\u0C80-\\u0CFF]")
    private val NUDI_HIGH_ASCII_REGEX = Regex("[\\u00A2-\\u00FF]")
    private val UNICODE_FONT_REGEX = Regex("(noto.*kannada|anek.*kannada|tunga|lohit|kedage|mallige|hubballi|baloo.*tamma)", RegexOption.IGNORE_CASE)
    private val LEGACY_FONT_REGEX = Regex("(^nudi|nudi[\\s\\-_]*\\d|kgp|kan[\\-_]?nudi|^brh|baraha|tunga[\\s\\-_]*brh|shree[\\-_]?kan|srilipi|akruti|prakashak)", RegexOption.IGNORE_CASE)
    private val KANNADA_SPAN_REGEX = Regex("([\\u0C80-\\u0CFF](?:[\\u0C80-\\u0CFF\\u200C\\u200D ]*[\\u0C80-\\u0CFF])?)")

    private val INDEPENDENT_VOWELS = mapOf(
        'C' to "ಅ", 'D' to "ಆ", 'E' to "ಇ", 'F' to "ಈ", 'G' to "ಉ", 'H' to "ಊ",
        'I' to "ಋ", 'J' to "ಎ", 'K' to "ಏ", 'L' to "ಐ", 'M' to "ಒ", 'N' to "ಓ", 'O' to "ಔ"
    )

    private val CONSONANT_WITH_A = mapOf(
        'P' to "ಕ", 'U' to "ಗ", 'W' to "ಘ", 'Z' to "ಚ", 'b' to "ಛ",
        'g' to "ರ", 'o' to "ಠ", 'q' to "ಡ", 'u' to "ಣ", 'v' to "ತ", 'x' to "ಥ",
        'z' to "ದ", '£' to "ನ", '¥' to "ಪ", '¨' to "ಬ", 'ª' to "ವ",
        '±' to "ಶ", 'µ' to "ಷ", '¸' to "ಸ", 'º' to "ಹ", '¼' to "ಳ",
        'S' to "ಖ", 'e' to "ಜ", 'm' to "ಟ", '¯' to "ಲ"
    )

    private val CONSONANT_STANDALONE = mapOf(
        'R' to "ಖ", 'Y' to "ಙ", 'd' to "ಜ", 'k' to "ಞ", 'l' to "ಟ",
        't' to "ಣ", '§' to "ಬ", '®' to "ಲ"
    )

    private val CONSONANT_I = mapOf(
        'Q' to "ಕ", 'T' to "ಖ", 'V' to "ಗ", 'X' to "ಘ", 'a' to "ಚ",
        'c' to "ಛ", 'f' to "ಜ", 'j' to "ರ", 'n' to "ಟ", 'p' to "ಠ",
        'r' to "ಡ", 'w' to "ತ", 'y' to "ಥ", '¢' to "ದ",
        '¤' to "ನ", '¦' to "ಪ", '©' to "ಬ", '«' to "ವ", '°' to "ಲ",
        '²' to "ಶ", '¶' to "ಷ", '¹' to "ಸ", '»' to "ಹ", '½' to "ಳ"
    )

    private val MAHAPRANA = mapOf(
        "ಕ" to "ಖ", "ಗ" to "ಘ", "ಚ" to "ಛ", "ಜ" to "ಝ",
        "ಟ" to "ಠ", "ಡ" to "ಢ", "ತ" to "ಥ", "ದ" to "ಧ",
        "ಪ" to "ಫ", "ಬ" to "ಭ"
    )

    private val OTTAKSHARA = mapOf(
        'Ì' to "್ಕ", 'Í' to "್ಖ", 'Î' to "್ಗ", 'Ï' to "್ಘ", 'Ð' to "್ಙ",
        'Ñ' to "್ಚ", 'Ò' to "್ಛ", 'Ó' to "್ಜ", 'Ô' to "್ಝ", 'Õ' to "್ಞ",
        'Ö' to "್ಟ", '×' to "್ಠ", 'Ø' to "್ಡ", 'Ù' to "್ಢ", 'Ú' to "್ಣ",
        'Û' to "್ತ", 'Ü' to "್ಥ", 'Ý' to "್ದ", 'Þ' to "್ಧ", 'ß' to "್ನ",
        'à' to "್ಪ", 'á' to "್ಫ", 'â' to "್ಬ", 'ã' to "್ಭ", 'ä' to "್ಮ",
        'å' to "್ಯ", 'æ' to "್ರ", 'ç' to "್ರ", 'è' to "್ಲ", 'é' to "್ವ",
        'ê' to "್ಶ", 'ë' to "್ಷ", 'ì' to "್ಸ", 'í' to "್ಹ", 'î' to "್ಳ"
    )

    private val IGNORE_SPACERS = setOf('ö', '÷')

    private val COMPOUND_SYLLABLES = listOf(
        Triple("gÉhÆÃ", "ಝ", "ೋ"), Triple("gÉhÆ", "ಝ", "ೊ"), Triple("gÉhÄÃ", "ಝ", "ೇ"),
        Triple("gÉhÄÊ", "ಝ", "ೈ"), Triple("gÉhÄ", "ಝ", "ೆ"), Triple("gÀhiÁ", "ಝ", "ಾ"),
        Triple("gÀhiË", "ಝ", "ೌ"), Triple("gÀhiï", "ಝ", "್"), Triple("gÀhÄÄ", "ಝ", "ು"),
        Triple("gÀhÄÆ", "ಝ", "ೂ"), Triple("gÀhÄÈ", "ಝ", "ೃ"), Triple("gÀhÄø", "ಝ", "ೃ"),
        Triple("gÀhÄ", "ಝ", ""), Triple("jhÄÃ", "ಝ", "ೀ"), Triple("jhÄ", "ಝ", "ಿ"),

        Triple("AiÉÆÃ", "ಯ", "ೋ"), Triple("AiÉÆ", "ಯ", "ೊ"), Triple("AiÉÄÃ", "ಯ", "ೇ"),
        Triple("AiÉÄÊ", "ಯ", "ೈ"), Triple("AiÉÄ", "ಯ", "ೆ"), Triple("AiÀiÁ", "ಯ", "ಾ"),
        Triple("AiÀiË", "ಯ", "ೌ"), Triple("AiÀiï", "ಯ", "್"), Triple("AiÀÄÄ", "ಯ", "ು"),
        Triple("AiÀÄÆ", "ಯ", "ೂ"), Triple("AiÀÄÈ", "ಯ", "ೃ"), Triple("AiÀÄø", "ಯ", "ೃ"),
        Triple("AiÀÄ", "ಯ", ""), Triple("¬ÄÃ", "ಯ", "ೀ"), Triple("¬Ä", "ಯ", "ಿ"),

        Triple("ªÉÆÃ", "ಮ", "ೋ"), Triple("ªÉÆ", "ಮ", "ೊ"), Triple("ªÉÄÃ", "ಮ", "ೇ"),
        Triple("ªÉÄÊ", "ಮ", "ೈ"), Triple("ªÉÄ", "ಮ", "ೆ"), Triple("ªÀiÁ", "ಮ", "ಾ"),
        Triple("ªÀiË", "ಮ", "ೌ"), Triple("ªÀiï", "ಮ", "್"), Triple("ªÀÄÄ", "ಮ", "ು"),
        Triple("ªÀÄÆ", "ಮ", "ೂ"), Triple("ªÀÄÈ", "ಮ", "ೃ"), Triple("ªÀÄø", "ಮ", "ೃ"),
        Triple("ªÀÄ", "ಮ", ""), Triple("«ÄÃ", "ಮ", "ೀ"), Triple("«Ä", "ಮ", "ಿ")
    )

    private val VOWEL_SUFFIXES = listOf(
        "ÉÆÃ" to "ೋ", "ÉÇÃ" to "ೋ",
        "ÉÆ" to "ೊ", "ÉÇ" to "ೊ",
        "ÉÃ" to "ೇ", "ÉÊ" to "ೈ",
        "ÀÄ" to "ು", "ÀÅ" to "ು", "ÀÆ" to "ೂ", "ÀÇ" to "ೂ",
        "ÀÈ" to "ೃ", "Àø" to "ೃ", "Àñ" to "ೄ",
        "ÂÃ" to "ೀ",
        "À" to "", "Á" to "ಾ", "Â" to "ಿ", "Ã" to "ೀ",
        "Ä" to "ು", "Å" to "ು", "Æ" to "ೂ", "Ç" to "ೂ",
        "È" to "ೃ", "ø" to "ೃ", "É" to "ೆ", "Ê" to "ೈ",
        "Ë" to "ೌ", "ï" to "್"
    )

    private val REV_VOWELS = mapOf(
        'ಅ' to "C", 'ಆ' to "D", 'ಇ' to "E", 'ಈ' to "F", 'ಉ' to "G", 'ಊ' to "H",
        'ಋ' to "IÄ", 'ೠ' to "IÆ", 'ಎ' to "J", 'ಏ' to "K", 'ಐ' to "L", 'ಒ' to "M", 'ಓ' to "N", 'ಔ' to "O",
        'ಂ' to "A", 'ಃ' to "B"
    )

    private val REV_CONSONANT_BASE = mapOf(
        'ಕ' to ("P" to true), 'ಖ' to ("S" to false), 'ಗ' to ("U" to true), 'ಘ' to ("W" to true), 'ಙ' to ("Y" to false),
        'ಚ' to ("Z" to true), 'ಛ' to ("b" to true), 'ಜ' to ("e" to false), 'ಞ' to ("k" to false),
        'ಟ' to ("m" to false), 'ಠ' to ("o" to true), 'ಡ' to ("q" to true), 'ಢ' to ("qs" to true), 'ಣ' to ("u" to false),
        'ತ' to ("v" to true), 'ಥ' to ("x" to true), 'ದ' to ("z" to true), 'ಧ' to ("zs" to true), 'ನ' to ("£" to true),
        'ಪ' to ("¥" to true), 'ಫ' to ("¥s" to true), 'ಬ' to ("¨" to true), 'ಭ' to ("¨s" to true),
        'ರ' to ("g" to true), 'ಲ' to ("¯" to false), 'ವ' to ("ª" to true),
        'ಶ' to ("±" to true), 'ಷ' to ("µ" to true), 'ಸ' to ("¸" to true), 'ಹ' to ("º" to true), 'ಳ' to ("¼" to true)
    )

    private val REV_CONSONANT_STANDALONE = mapOf(
        'ಖ' to "R", 'ಜ' to "d", 'ಟ' to "l", 'ಣ' to "t", 'ಬ' to "§", 'ಲ' to "®"
    )

    private val REV_CONSONANT_I = mapOf(
        'ಕ' to "Q", 'ಖ' to "T", 'ಗ' to "V", 'ಘ' to "X", 'ಚ' to "a", 'ಛ' to "c", 'ಜ' to "f",
        'ಟ' to "n", 'ಠ' to "p", 'ಡ' to "r", 'ಢ' to "rü", 'ಣ' to "tÂ", 'ತ' to "w", 'ಥ' to "y",
        'ದ' to "¢", 'ಧ' to "¢ü", 'ನ' to "¤", 'ಪ' to "¦", 'ಫ' to "¦ü", 'ಬ' to "©", 'ಭ' to "©ü",
        'ರ' to "j", 'ಲ' to "°", 'ವ' to "«", 'ಶ' to "²", 'ಷ' to "¶", 'ಸ' to "¹", 'ಹ' to "»", 'ಳ' to "½"
    )

    private val REV_OTTA = mapOf(
        'ಕ' to "Ì", 'ಖ' to "Í", 'ಗ' to "Î", 'ಘ' to "Ï", 'ಙ' to "Ð", 'ಚ' to "Ñ", 'ಛ' to "Ò",
        'ಜ' to "Ó", 'ಝ' to "Ô", 'ಞ' to "Õ", 'ಟ' to "Ö", 'ಠ' to "×", 'ಡ' to "Ø", 'ಢ' to "Ù",
        'ಣ' to "Ú", 'ತ' to "Û", 'ಥ' to "Ü", 'ದ' to "Ý", 'ಧ' to "Þ", 'ನ' to "ß", 'ಪ' to "à",
        'ಫ' to "á", 'ಬ' to "â", 'ಭ' to "ã", 'ಮ' to "ä", 'ಯ' to "å", 'ರ' to "æ", 'ಲ' to "è",
        'ವ' to "é", 'ಶ' to "ê", 'ಷ' to "ë", 'ಸ' to "ì", 'ಹ' to "í", 'ಳ' to "î"
    )

    private val REV_COMPOUND = mapOf(
        'ಮ' to mapOf("" to "ªÀÄ", "ಾ" to "ªÀiÁ", "ಿ" to "«Ä", "ೀ" to "«ÄÃ", "ು" to "ªÀÄÄ", "ೂ" to "ªÀÄÆ", "ೃ" to "ªÀÄÈ", "ೆ" to "ªÉÄ", "ೇ" to "ªÉÄÃ", "ೈ" to "ªÉÄÊ", "ೊ" to "ªÉÆ", "ೋ" to "ªÉÆÃ", "ೌ" to "ªÀiË", "್" to "ªÀiï"),
        'ಯ' to mapOf("" to "AiÀÄ", "ಾ" to "AiÀiÁ", "ಿ" to "¬Ä", "ೀ" to "¬ÄÃ", "ು" to "AiÀÄÄ", "ೂ" to "AiÀÄÆ", "ೃ" to "AiÀÄÈ", "ೆ" to "AiÉÄ", "ೇ" to "AiÉÄÃ", "ೈ" to "AiÉÄÊ", "ೊ" to "AiÉÆ", "ೋ" to "AiÉÆÃ", "ೌ" to "AiÀiË", "್" to "AiÀiï"),
        'ಝ' to mapOf("" to "gÀhÄ", "ಾ" to "gÀhiÁ", "ಿ" to "jhÄ", "ೀ" to "jhÄÃ", "ು" to "gÀhÄÄ", "ೂ" to "gÀhÄÆ", "ೃ" to "gÀhÄÈ", "ೆ" to "gÉhÄ", "ೇ" to "gÉhÄÃ", "ೈ" to "gÉhÄÊ", "ೊ" to "gÉhÆ", "ೋ" to "gÉhÆÃ", "ೌ" to "gÀhiË", "್" to "gÀhiï")
    )

    private val MATRAS = setOf('ಾ', 'ಿ', 'ೀ', 'ು', 'ೂ', 'ೃ', 'ೄ', 'ೆ', 'ೇ', 'ೈ', 'ೊ', 'ೋ', 'ೌ')

    fun hasKannadaUnicode(text: String): Boolean = KANNADA_UNICODE_REGEX.containsMatchIn(text)

    fun hasLegacyAsciiSignature(text: String): Boolean =
        text.isNotEmpty() && !hasKannadaUnicode(text) && NUDI_HIGH_ASCII_REGEX.containsMatchIn(text)

    /**
     * Auto-detects whether pasted text is Unicode Kannada or Legacy Nudi/Baraha ASCII.
     */
    fun detectInputFormat(text: String): DetectedInputInfo {
        if (text.isBlank()) {
            return DetectedInputInfo(
                detectedFormat = KannadaFormat.NUDI,
                confidenceNoteEn = "Waiting for text",
                confidenceNoteKn = "ಪಠ್ಯಕ್ಕಾಗಿ ಕಾಯಲಾಗುತ್ತಿದೆ"
            )
        }
        val unicodeCount = KANNADA_UNICODE_REGEX.findAll(text).count()
        val legacyCount = NUDI_HIGH_ASCII_REGEX.findAll(text).count()

        return when {
            unicodeCount > 0 && unicodeCount >= legacyCount -> DetectedInputInfo(
                detectedFormat = KannadaFormat.UNICODE,
                confidenceNoteEn = "Modern Universal Kannada",
                confidenceNoteKn = "ಆಧುನಿಕ ಸಾರ್ವತ್ರಿಕ ಕನ್ನಡ"
            )
            legacyCount > 0 -> DetectedInputInfo(
                detectedFormat = KannadaFormat.NUDI,
                confidenceNoteEn = "Nudi / Baraha (PC)",
                confidenceNoteKn = "ನುಡಿ / ಬರಹ (PC)"
            )
            else -> DetectedInputInfo(
                detectedFormat = KannadaFormat.UNICODE,
                confidenceNoteEn = "Plain / English Text",
                confidenceNoteKn = "ಸಾಮಾನ್ಯ / ಇಂಗ್ಲಿಷ್ ಪಠ್ಯ"
            )
        }
    }

    /**
     * Returns allowed output formats strictly excluding the detected/selected input format.
     */
    fun allowedOutputFormats(inputFormat: KannadaFormat): List<KannadaFormat> =
        when (inputFormat) {
            KannadaFormat.NUDI -> listOf(KannadaFormat.UNICODE, KannadaFormat.BARAHA)
            KannadaFormat.BARAHA -> listOf(KannadaFormat.UNICODE, KannadaFormat.NUDI)
            KannadaFormat.UNICODE -> listOf(KannadaFormat.NUDI, KannadaFormat.BARAHA)
        }

    /**
     * Converts input text from sourceFormat to targetFormat.
     * Preserves plain English tokens when converting mixed text.
     */
    fun convert(text: String, sourceFormat: KannadaFormat, targetFormat: KannadaFormat): String {
        if (text.isEmpty() || sourceFormat == targetFormat) return text
        return when {
            (sourceFormat == KannadaFormat.NUDI || sourceFormat == KannadaFormat.BARAHA) &&
                targetFormat == KannadaFormat.UNICODE -> convertMixedAsciiToUnicode(text)

            sourceFormat == KannadaFormat.UNICODE &&
                (targetFormat == KannadaFormat.NUDI || targetFormat == KannadaFormat.BARAHA) -> convertUnicodeToNudi(text)

            // Nudi <-> Baraha ANSI (KGP): round-trip normalize through Unicode so clean KGP ASCII is emitted
            (sourceFormat == KannadaFormat.NUDI && targetFormat == KannadaFormat.BARAHA) ||
                (sourceFormat == KannadaFormat.BARAHA && targetFormat == KannadaFormat.NUDI) -> {
                val unicode = convertMixedAsciiToUnicode(text)
                convertUnicodeToNudi(unicode)
            }

            else -> text
        }
    }

    fun convertMixedAsciiToUnicode(text: String): String {
        if (!NUDI_HIGH_ASCII_REGEX.containsMatchIn(text)) return text
        val tokenRegex = Regex("(\\s+)")
        val sb = StringBuilder(text.length)
        var lastEnd = 0
        for (match in tokenRegex.findAll(text)) {
            val token = text.substring(lastEnd, match.range.first)
            if (token.isNotEmpty()) {
                sb.append(if (hasLegacyAsciiSignature(token)) convertNudiToUnicode(token) else token)
            }
            sb.append(match.value)
            lastEnd = match.range.last + 1
        }
        val tail = text.substring(lastEnd)
        if (tail.isNotEmpty()) {
            sb.append(if (hasLegacyAsciiSignature(tail)) convertNudiToUnicode(tail) else tail)
        }
        return sb.toString()
    }

    private data class ModResult(
        val nextIdx: Int,
        val ottas: String,
        val arkavattu: Boolean,
        val vowel: String
    )

    private fun consumeTrailingModifiers(text: String, startIdx: Int, initialVowel: String): ModResult {
        val n = text.length
        var i = startIdx
        var vowel = initialVowel
        val ottas = StringBuilder()
        var arkavattu = false

        while (i < n) {
            val c2 = text[i]
            when {
                c2 in IGNORE_SPACERS -> i++
                OTTAKSHARA.containsKey(c2) -> {
                    ottas.append(OTTAKSHARA[c2])
                    i++
                }
                c2 == 'ð' -> {
                    arkavattu = true
                    i++
                }
                c2 == 'Ã' -> {
                    vowel = when (vowel) {
                        "ಿ" -> "ೀ"
                        "ೆ" -> "ೇ"
                        "ೊ" -> "ೋ"
                        "" -> "ೀ"
                        else -> vowel
                    }
                    i++
                }
                c2 == 'Ê' && vowel == "ೆ" -> {
                    vowel = "ೈ"
                    i++
                }
                c2 == 'Æ' || c2 == 'Ç' -> {
                    if (vowel == "ೆ") vowel = "ೊ" else if (vowel == "") vowel = "ೂ"
                    i++
                }
                c2 == 'È' || c2 == 'ø' -> {
                    vowel = "ೃ"
                    i++
                }
                (c2 == 'Ä' || c2 == 'Å') && vowel == "" -> {
                    vowel = "ು"
                    i++
                }
                else -> break
            }
        }
        return ModResult(i, ottas.toString(), arkavattu, vowel)
    }

    fun convertNudiToUnicode(text: String): String {
        if (text.isEmpty()) return ""
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length

        while (i < n) {
            // 1. Compound syllables (ಝ, ಯ, ಮ)
            var matchedCompound: Triple<String, String, String>? = null
            for (entry in COMPOUND_SYLLABLES) {
                if (text.startsWith(entry.first, i)) {
                    matchedCompound = entry
                    break
                }
            }
            if (matchedCompound != null) {
                i += matchedCompound.first.length
                val mod = consumeTrailingModifiers(text, i, matchedCompound.third)
                i = mod.nextIdx
                if (mod.arkavattu) out.append("ರ್")
                out.append(matchedCompound.second).append(mod.ottas).append(mod.vowel)
                continue
            }

            val ch = text[i]
            if (ch in IGNORE_SPACERS) {
                i++
                continue
            }

            // 2. Precomposed consonant + 'ಿ'
            val consI = CONSONANT_I[ch]
            if (consI != null) {
                var base = consI
                i++
                if (i < n && (text[i] == 's' || text[i] == 'ü') && MAHAPRANA.containsKey(base)) {
                    base = MAHAPRANA[base]!!
                    i++
                }
                val mod = consumeTrailingModifiers(text, i, "ಿ")
                i = mod.nextIdx
                if (mod.arkavattu) out.append("ರ್")
                out.append(base).append(mod.ottas).append(mod.vowel)
                continue
            }

            // 3. Regular consonant
            val regBase = CONSONANT_WITH_A[ch] ?: CONSONANT_STANDALONE[ch]
            if (regBase != null) {
                var base = regBase
                i++
                if (i < n && (text[i] == 's' || text[i] == 'ü') && MAHAPRANA.containsKey(base)) {
                    base = MAHAPRANA[base]!!
                    i++
                }
                var vowel = ""
                for ((suf, vuni) in VOWEL_SUFFIXES) {
                    if (text.startsWith(suf, i)) {
                        vowel = vuni
                        i += suf.length
                        break
                    }
                }
                if (i < n && (text[i] == 's' || text[i] == 'ü') && MAHAPRANA.containsKey(base)) {
                    base = MAHAPRANA[base]!!
                    i++
                    if (vowel.isEmpty()) {
                        for ((suf, vuni) in VOWEL_SUFFIXES) {
                            if (text.startsWith(suf, i)) {
                                vowel = vuni
                                i += suf.length
                                break
                            }
                        }
                    }
                }
                val mod = consumeTrailingModifiers(text, i, vowel)
                i = mod.nextIdx
                if (mod.arkavattu) out.append("ರ್")
                out.append(base).append(mod.ottas).append(mod.vowel)
                continue
            }

            // 4. Independent vowels
            if (text.startsWith("IÄ", i)) { out.append("ಋ"); i += 2; continue }
            if (text.startsWith("IÆ", i)) { out.append("ೠ"); i += 2; continue }
            val indVowel = INDEPENDENT_VOWELS[ch]
            if (indVowel != null) { out.append(indVowel); i++; continue }

            // 5. Yogavaha & stray virama/ottakshara
            when {
                ch == 'A' -> { out.append("ಂ"); i++ }
                ch == 'B' -> { out.append("ಃ"); i++ }
                OTTAKSHARA.containsKey(ch) -> { out.append(OTTAKSHARA[ch]); i++ }
                ch == 'ï' -> { out.append("್"); i++ }
                else -> { out.append(ch); i++ }
            }
        }
        return out.toString()
    }

    private fun isKannadaConsonant(ch: Char): Boolean = ch.code in 0x0C95..0x0CB9

    private fun encodeConsonantWithVowel(cons: Char, vowel: String, hasOtta: Boolean): String {
        REV_COMPOUND[cons]?.get(vowel)?.let { return it }
        if ((vowel == "ಿ" || vowel == "ೀ") && REV_CONSONANT_I.containsKey(cons)) {
            return REV_CONSONANT_I[cons]!! + if (vowel == "ೀ") "Ã" else ""
        }
        if (vowel.isEmpty() && REV_CONSONANT_STANDALONE.containsKey(cons)) {
            return REV_CONSONANT_STANDALONE[cons]!!
        }
        if (cons == 'ವ') {
            when (vowel) {
                "ು" -> return "ªÀÅ"
                "ೂ" -> return "ªÀÇ"
                "ೊ" -> return "ªÉÇ"
                "ೋ" -> return "ªÉÇÃ"
            }
        }
        if (cons == 'ಪ' || cons == 'ಫ') {
            val baseG = if (cons == 'ಪ') "¥" else "¥s"
            if (vowel == "ು") return "${baseG}ÀÅ"
            if (vowel == "ೂ") return "${baseG}ÀÇ"
        }
        val info = REV_CONSONANT_BASE[cons] ?: return "$cons$vowel"
        val (glyph, needsA) = info
        val baseWithA = if (needsA) "${glyph}À" else (REV_CONSONANT_STANDALONE[cons] ?: glyph)
        return when (vowel) {
            "" -> if (needsA) "${glyph}À" else glyph
            "ಾ" -> "${glyph}Á"
            "ು" -> "${baseWithA}Ä"
            "ೂ" -> "${baseWithA}Æ"
            "ೃ" -> baseWithA + if (hasOtta) "ø" else "È"
            "ೄ" -> "${baseWithA}Àñ"
            "ೆ" -> "${glyph}É"
            "ೇ" -> "${glyph}ÉÃ"
            "ೈ" -> "${glyph}ÉÊ"
            "ೊ" -> "${glyph}ÉÆ"
            "ೋ" -> "${glyph}ÉÆÃ"
            "ೌ" -> "${glyph}Ë"
            "್" -> "${glyph}ï"
            else -> "$glyph$vowel"
        }
    }

    fun convertUnicodeToNudi(text: String): String {
        if (text.isEmpty() || !hasKannadaUnicode(text)) return text
        val out = StringBuilder(text.length * 2)
        var i = 0
        val n = text.length

        while (i < n) {
            val ch = text[i]
            val revV = REV_VOWELS[ch]
            if (revV != null) {
                out.append(revV)
                i++
                continue
            }
            if (isKannadaConsonant(ch)) {
                var arkavattu = false
                var base = ch
                if (ch == 'ರ' && i + 2 < n && text[i + 1] == '್' && isKannadaConsonant(text[i + 2])) {
                    arkavattu = true
                    base = text[i + 2]
                    i += 3
                } else {
                    i++
                }
                val ottas = StringBuilder()
                var trailingHalant = false
                while (i < n && text[i] == '್') {
                    if (i + 1 < n && isKannadaConsonant(text[i + 1])) {
                        REV_OTTA[text[i + 1]]?.let { ottas.append(it) }
                        i += 2
                    } else {
                        trailingHalant = true
                        i++
                        break
                    }
                }
                var vowel = if (trailingHalant) "್" else ""
                if (!trailingHalant && i < n && text[i] in MATRAS) {
                    vowel = text[i].toString()
                    i++
                }
                val ottaStr = ottas.toString()
                if (vowel == "ೃ" && ottaStr.isNotEmpty()) {
                    out.append(encodeConsonantWithVowel(base, "", true)).append(ottaStr).append('ø')
                } else {
                    out.append(encodeConsonantWithVowel(base, vowel, ottaStr.isNotEmpty())).append(ottaStr)
                }
                if (arkavattu) out.append('ð')
                continue
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    /**
     * Normalizes a ParsedDocument's legacy runs to Unicode Kannada (for Home-screen direct export).
     */
    fun normalizeDocumentToUnicode(doc: ParsedDocument): ParsedDocument {
        fun convertPara(p: DocBlock.Paragraph): DocBlock.Paragraph {
            val newRuns = p.runs.map { run ->
                val raw = run.text
                if (raw.isEmpty() || hasKannadaUnicode(raw)) {
                    run.copy(fontFamily = "Noto Sans Kannada")
                } else {
                    val font = run.fontFamily ?: ""
                    val converted = when {
                        UNICODE_FONT_REGEX.containsMatchIn(font) -> raw
                        LEGACY_FONT_REGEX.containsMatchIn(font) -> convertNudiToUnicode(raw)
                        hasLegacyAsciiSignature(raw) -> convertMixedAsciiToUnicode(raw)
                        else -> raw
                    }
                    run.copy(text = converted, fontFamily = "Noto Sans Kannada")
                }
            }
            return p.copy(runs = newRuns)
        }

        val newBlocks = doc.blocks.map { block ->
            when (block) {
                is DocBlock.Paragraph -> convertPara(block)
                is DocBlock.Table -> DocBlock.Table(
                    rows = block.rows.map { row -> row.map { cell -> cell.map { convertPara(it) } } }
                )
            }
        }
        return doc.copy(blocks = newBlocks)
    }

    /**
     * Splits mixed English + Kannada Unicode runs into script-isolated runs for PC Nudi/Baraha export.
     */
    fun prepareDocumentForLegacyExport(unicodeDoc: ParsedDocument, legacyFontName: String): ParsedDocument {
        fun splitRun(run: TextRun): List<TextRun> {
            val text = run.text
            if (text.isEmpty()) return emptyList()
            val result = mutableListOf<TextRun>()
            var lastEnd = 0
            for (match in KANNADA_SPAN_REGEX.findAll(text)) {
                if (match.range.first > lastEnd) {
                    result.add(
                        run.copy(
                            text = text.substring(lastEnd, match.range.first),
                            fontFamily = "Calibri"
                        )
                    )
                }
                result.add(
                    run.copy(
                        text = convertUnicodeToNudi(match.value),
                        fontFamily = legacyFontName
                    )
                )
                lastEnd = match.range.last + 1
            }
            if (lastEnd < text.length) {
                result.add(
                    run.copy(
                        text = text.substring(lastEnd),
                        fontFamily = "Calibri"
                    )
                )
            }
            return result
        }

        fun transformPara(p: DocBlock.Paragraph): DocBlock.Paragraph =
            p.copy(runs = p.runs.flatMap { splitRun(it) })

        val newBlocks = unicodeDoc.blocks.map { block ->
            when (block) {
                is DocBlock.Paragraph -> transformPara(block)
                is DocBlock.Table -> DocBlock.Table(
                    rows = block.rows.map { row -> row.map { cell -> cell.map { transformPara(it) } } }
                )
            }
        }
        return unicodeDoc.copy(blocks = newBlocks)
    }
}
