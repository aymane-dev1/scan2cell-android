package com.scan2cell.app

import com.google.mlkit.vision.text.Text
import java.text.Normalizer

/**
 * Receipt parser tuned for the Al Amana receipt layout.
 *
 * Correct mapping:
 * - N° Trésorerie = TOP boxed alphanumeric code, e.g. 0147UDAS.
 * - Nom & Prénom = value after "Nom client".
 * - Montant = value beside/near "Montant reçu".
 * - Standard receipt (PID): N° Contrat = LEFT long number, Tiers / Réf. = RIGHT long number.
 * - Group receipt (PSD): one alphanumeric PSD code is copied to BOTH Contract and Tier.
 *   Example: J1ME0000303 -> Contract=J1ME0000303 and Tier=J1ME0000303.
 * - Group/PSD receipts do not require a client-name field.
 *
 * These values are scanned from the PAPER and sent to Excel for comparison
 * against BASE_FULL / BASE_SIMPLE. They are never replaced by database values.
 */
object ReceiptParser {
    fun parse(lines: List<String>): ReceiptData {
        val cleaned = lines
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }

        val treasury = findTreasuryNumber(cleaned)
        val psdCode = findPsdGroupCode(cleaned, treasury)
        val references = if (psdCode.isNotBlank()) {
            psdCode to psdCode
        } else {
            findReferencePair(cleaned)
        }
        val name = if (psdCode.isNotBlank()) "" else findClientName(cleaned)
        val amount = findAmount(cleaned)

        return ReceiptData(
            treasuryNumber = treasury,
            clientName = name,
            contractNumber = references.first,
            tierReference = references.second,
            amount = amount
        )
    }

    /**
     * Geometry-aware parser. ML Kit sometimes reads the two bottom IDs in an odd
     * text order (or several OCR lines apart). Here we use the actual position on
     * the receipt: Contract is the LEFT long ID, Tier / Réf. is the RIGHT long ID.
     */
    fun parse(recognized: Text): ReceiptData {
        val lines = recognized.textBlocks
            .flatMap { block -> block.lines.map { it.text } }
            .ifEmpty { recognized.text.lines() }

        val base = parse(lines)

        // A PSD/group receipt intentionally has ONE alphanumeric code used for both
        // Contract and Tier. Do not let the normal two-number geometry parser
        // overwrite that code with unrelated footer/date digits.
        if (isPsdGroupReceipt(base)) return base

        val geometric = findReferencePairByGeometry(recognized)

        return base.copy(
            contractNumber = geometric.first.ifBlank { base.contractNumber },
            tierReference = geometric.second.ifBlank { base.tierReference }
        )
    }

    /**
     * Used by the scanner's dedicated TOP receipt pass. Keeping treasury OCR
     * separate from PSD/reference correction avoids turning legitimate treasury
     * letters into digits.
     */
    fun parseTreasuryOnly(recognized: Text): String {
        val lines = recognized.textBlocks
            .flatMap { block -> block.lines.map { it.text } }
            .ifEmpty { recognized.text.lines() }

        return findTreasuryNumber(lines)
    }

    /**
     * Used by the scanner's high-resolution bottom-zone OCR pass.
     * This deliberately returns only the two long paper identifiers.
     */
    fun parseReferencePairOnly(recognized: Text): Pair<String, String> {
        val lines = recognized.textBlocks
            .flatMap { block -> block.lines.map { it.text } }
            .ifEmpty { recognized.text.lines() }

        // The dedicated bottom crop is ideal for group/PSD receipts because the
        // PSD label and its single code are usually in this exact area.
        val psdCode = findPsdGroupCode(lines)
        if (psdCode.isNotBlank()) return psdCode to psdCode

        val geometric = findReferencePairByGeometry(recognized)
        if (geometric.first.isNotBlank() && geometric.second.isNotBlank()) {
            return geometric
        }

        return findReferencePair(lines)
    }

    private fun fold(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace('’', '\'')
    }



    /**
     * Detect the group-receipt format where the bottom label is PSD and only one
     * alphanumeric reference exists. That ONE paper code is used by the database
     * in both Contract and Tier columns.
     *
     * Typical OCR layouts:
     *   PSD   J1ME0000303 /
     * or
     *   PSD
     *   J1ME0000303 /
     *
     * We deliberately search only near a PSD label so the top N° Trésorerie code
     * cannot be mistaken for the group reference.
     */
    private fun findPsdGroupCode(
        lines: List<String>,
        treasuryToExclude: String = ""
    ): String {
        data class Candidate(val value: String, val score: Int, val index: Int)

        val labelIndexes = lines.mapIndexedNotNull { index, line ->
            val f = fold(line)
            val compact = f.replace(Regex("[^a-z0-9]"), "")
            if (
                Regex("(^|[^a-z0-9])psd([^a-z0-9]|$)").containsMatchIn(f) ||
                compact == "psd" || compact.startsWith("psd") ||
                compact == "p5d" || compact.startsWith("p5d")
            ) index else null
        }
        if (labelIndexes.isEmpty()) return ""

        fun plausible(value: String): Boolean {
            if (value.length !in 7..20) return false
            if (treasuryToExclude.isNotBlank() && value == treasuryToExclude.uppercase()) return false
            if (value.count { it.isDigit() } < 3) return false
            if (value.count { it.isLetter() } < 1) return false
            if (value.startsWith("PSD") || value.startsWith("P5D")) return false
            if (value in setOf("MICROFINANCE", "ENCAISSEMENT", "SIGNATURE", "CLIENT")) return false
            return true
        }

        fun extract(line: String): List<String> {
            val upper = line.uppercase()
            val found = mutableListOf<String>()

            // Normal case: J1ME0000303
            Regex("(?<![A-Z0-9])([A-Z0-9]{7,20})(?![A-Z0-9])")
                .findAll(upper)
                .map { normalizePsdGroupCode(it.groupValues[1]) }
                .filter(::plausible)
                .forEach { found += it }

            // OCR can split the code: J1ME 0000303 / J1ME-0000303.
            Regex("(?<![A-Z0-9])([A-Z0-9]{2,8}(?:[ ._-]+[A-Z0-9]{2,10}){1,3})(?![A-Z0-9])")
                .findAll(upper)
                .map { normalizePsdGroupCode(it.groupValues[1]) }
                .filter(::plausible)
                .forEach { found += it }

            return found.distinct()
        }

        val candidates = mutableListOf<Candidate>()

        lines.forEachIndexed { index, line ->
            val nearest = labelIndexes.minOf { kotlin.math.abs(it - index) }
            if (nearest > 3) return@forEachIndexed

            extract(line).forEach { value ->
                var score = 100 - nearest * 20
                if (value.length in 10..12) score += 20
                if (index >= labelIndexes.minOrNull()!!) score += 8
                if (line.uppercase().contains("PSD")) score += 15
                candidates += Candidate(value, score, index)
            }
        }

        return candidates
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.index })
            .firstOrNull()
            ?.value
            .orEmpty()
    }

    private fun isPsdGroupReceipt(data: ReceiptData): Boolean {
        val contract = data.contractNumber.trim()
        val tier = data.tierReference.trim()
        return contract.isNotBlank() &&
            contract.equals(tier, ignoreCase = true) &&
            contract.any { it.isLetter() } &&
            contract.any { it.isDigit() }
    }


    /**
     * PSD group IDs are alphanumeric prefixes followed by a long numeric tail.
     * Example: J1ME0000303. In the numeric tail ML Kit commonly reads 0 as O/Q
     * (or 1 as I/L). Correct those confusions WITHOUT touching the prefix.
     *
     * J1MEO000303 -> J1ME0000303
     */
    private fun normalizePsdGroupCode(value: String): String {
        val compact = value.uppercase().replace(Regex("[^A-Z0-9]"), "")
        if (compact.isBlank()) return ""

        val digitMap = mapOf(
            'O' to '0', 'Q' to '0',
            'I' to '1', 'L' to '1',
            'Z' to '2', 'S' to '5',
            'G' to '6', 'T' to '7', 'B' to '8'
        )

        // Walk from the right while characters look numeric / digit-confusable.
        var suffixStart = compact.length
        var i = compact.lastIndex
        while (i >= 0) {
            val c = compact[i]
            if (c.isDigit() || digitMap.containsKey(c)) {
                suffixStart = i
                i--
            } else {
                break
            }
        }

        // Avoid rewriting short ambiguous suffixes. Real PSD IDs have a long
        // numeric tail, usually 6+ characters.
        if (compact.length - suffixStart < 5) return compact

        val prefix = compact.substring(0, suffixStart)
        val suffix = buildString {
            compact.substring(suffixStart).forEach { c -> append(digitMap[c] ?: c) }
        }
        return prefix + suffix
    }

    private fun findTreasuryNumber(lines: List<String>): String {
        data class Candidate(val value: String, val score: Int, val index: Int)

        val candidates = mutableListOf<Candidate>()

        // Real examples from the receipt:
        // 0146VIPA, 0149L1ZG, 014COKUS, 0147UDAS
        // Require BOTH letters and digits so dates / times / phone numbers cannot win.
        val tokenRegex = Regex("(?<![A-Z0-9])([A-Z0-9]{6,12})(?![A-Z0-9])", RegexOption.IGNORE_CASE)

        lines.forEachIndexed { index, line ->
            val folded = fold(line)
            tokenRegex.findAll(line.uppercase()).forEach { match ->
                val raw = match.groupValues[1]
                    .replace(" ", "")
                    .trim()

                if (!raw.any { it.isDigit() } || !raw.any { it.isLetter() }) {
                    return@forEach
                }

                // Exclude ordinary words that happen to be long.
                if (raw.all { it.isLetter() }) return@forEach

                var score = 10

                // The treasury code is printed in the top boxed "N° ..." area.
                if (folded.contains("n°") ||
                    folded.contains("nº") ||
                    folded.contains("n0") ||
                    folded.matches(Regex(".*\\bn\\s*[°ºo0]?\\s*.*"))
                ) score += 40

                val previous = lines.getOrNull(index - 1)?.let(::fold).orEmpty()
                val next = lines.getOrNull(index + 1)?.let(::fold).orEmpty()

                if (folded.contains("recu") || previous.contains("recu") || next.contains("recu")) {
                    score += 25
                }

                // It appears near the top of OCR reading order.
                if (index <= 5) score += 15
                if (index <= 10) score += 5

                // Do not confuse bottom PID/reference material with treasury.
                if (folded.contains("pid") || folded.contains("psd") || folded.contains("ref")) score -= 40

                candidates += Candidate(raw, score, index)
            }
        }

        return candidates
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenBy { it.index }
            )
            .firstOrNull()
            ?.value
            .orEmpty()
    }

    private fun findClientName(lines: List<String>): String {
        for ((index, line) in lines.withIndex()) {
            val folded = fold(line)
            if (!(folded.contains("nom client") ||
                  folded.contains("nomclient") ||
                  folded.contains("nom cllent") ||
                  folded.contains("nom ciient"))) continue

            val inline = line
                .replace(
                    Regex("(?i)^.*?nom\\s*c[l1i][i1l]ent\\s*[:\\-]?\\s*"),
                    ""
                )
                .trim(' ', ':', '-')

            val cleanedInline = cleanName(inline)
            if (isPlausibleName(cleanedInline)) return cleanedInline

            // ML Kit sometimes puts the value on the next OCR line.
            for (offset in 1..2) {
                val candidate = lines.getOrNull(index + offset) ?: continue
                val cleaned = cleanName(candidate)
                if (isPlausibleName(cleaned) && !looksLikeLabel(candidate)) {
                    return cleaned
                }
            }
        }

        // Conservative fallback: uppercase word groups, excluding obvious labels.
        return lines.asSequence()
            .map { cleanName(it) }
            .filter { isPlausibleName(it) }
            .filter { candidate ->
                val f = fold(candidate)
                !listOf(
                    "alamana", "microfinance", "signature", "cachet",
                    "quartier industriel", "copie client"
                ).any { f.contains(it) }
            }
            .maxByOrNull { candidate ->
                candidate.count { it.isLetter() } +
                    if (candidate == candidate.uppercase()) 8 else 0
            }
            .orEmpty()
    }

    private fun isPlausibleName(value: String): Boolean {
        if (value.length !in 4..60) return false
        if (value.count { it.isLetter() } < 4) return false
        if (value.count { it.isDigit() } > 0) return false
        val words = value.split(" ").filter { it.isNotBlank() }
        return words.size in 1..7
    }

    private fun cleanName(value: String): String {
        return value
            .replace(Regex("(?i)^.*?nom\\s*c[l1i][i1l]ent\\s*[:\\-]?\\s*"), "")
            .replace(
                Regex("(?i)\\b(date|montant|agence|re[cç]u|réf|ref|pid|signature|cachet)\\b.*$"),
                ""
            )
            .replace(Regex("[^\\p{L}'’ -]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private data class MoneyCandidate(
        val value: String,
        val score: Int,
        val lineIndex: Int
    )

    private fun findAmount(lines: List<String>): String {
        val candidates = mutableListOf<MoneyCandidate>()

        lines.forEachIndexed { index, line ->
            val folded = fold(line)
            val normalizedForMoney = normalizeOcrMoneyCharacters(line)

            // Accept OCR-confused digits: 487,0O / 487.OO / 1 250,00 etc.
            val moneyRegex = Regex(
                "(?<![0-9])([0-9]{1,7}(?:[ .][0-9]{3})*[,.][0-9]{1,2})(?![0-9])"
            )

            moneyRegex.findAll(normalizedForMoney).forEach { match ->
                val raw = match.groupValues[1]
                val normalized = normalizeAmount(raw)
                if (!isPlausibleAmount(normalized)) return@forEach

                var score = 1

                // Strongest signal: same OCR line as Montant reçu.
                if (folded.contains("montant")) score += 20
                if (folded.contains("recu") || folded.contains("reçu")) score += 4

                // Also allow OCR to split the amount onto the line directly
                // after "Montant reçu".
                val previous = lines.getOrNull(index - 1)?.let(::fold).orEmpty()
                val twoBack = lines.getOrNull(index - 2)?.let(::fold).orEmpty()
                if (previous.contains("montant")) score += 15
                if (twoBack.contains("montant")) score += 8

                // Dates / times / telephone text should never win.
                if (folded.contains("date")) score -= 12
                if (folded.contains("tel") || folded.contains("fax")) score -= 20
                if (folded.contains("encaissement")) score -= 8

                candidates += MoneyCandidate(normalized, score, index)
            }
        }

        return candidates
            .sortedWith(
                compareByDescending<MoneyCandidate> { it.score }
                    .thenBy { it.lineIndex }
            )
            .firstOrNull()
            ?.value
            .orEmpty()
    }

    private fun normalizeOcrMoneyCharacters(value: String): String {
        // Only replace characters that ML Kit commonly confuses inside numbers.
        // This is safe for candidate extraction because labels are ignored later.
        return value
            .replace('O', '0')
            .replace('o', '0')
            .replace('I', '1')
            .replace('l', '1')
            .replace('S', '5')
    }

    private fun isPlausibleAmount(value: String): Boolean {
        val canonical = value.replace(" ", "")
            .replace(",", ".")
        val number = canonical.toDoubleOrNull() ?: return false
        return number >= 0.01 && number <= 99_999_999.99
    }

    private fun normalizeAmount(value: String): String {
        var text = value
            .replace(" ", "")
            .trim()

        if (text.contains('.') && text.contains(',')) {
            text = if (text.lastIndexOf(',') > text.lastIndexOf('.')) {
                text.replace(".", "")
            } else {
                text.replace(",", "")
            }
        }

        // Keep the user's familiar French decimal comma in the review screen.
        if (text.contains('.')) {
            val lastDot = text.lastIndexOf('.')
            if (text.length - lastDot - 1 in 1..2) {
                text = text.substring(0, lastDot).replace(".", "") +
                    "," + text.substring(lastDot + 1)
            }
        }

        if (text.contains(',')) {
            val parts = text.split(",")
            val whole = parts.dropLast(1).joinToString("").filter { it.isDigit() }
            val decimals = parts.last().filter { it.isDigit() }.padEnd(2, '0').take(2)
            return "$whole,$decimals"
        }

        return text.filter { it.isDigit() }
    }

    private fun findReferencePair(lines: List<String>): Pair<String, String> {
        data class Candidate(
            val value: String,
            val lineIndex: Int,
            val tokenOrder: Int,
            val score: Int
        )

        // These two identifiers are normally 11 digits and start with zeros.
        // We keep a slightly wider range so the parser is not tied to one exact
        // database length, but we strongly prefer 11-digit values.
        fun isLikelyReference(value: String): Boolean {
            if (value.length !in 9..14) return false
            if (!value.startsWith("0")) return false
            if (looksLikePhoneNumber(value)) return false
            return true
        }

        fun extractIdentifiers(line: String): List<String> {
            val result = mutableListOf<String>()
            val digitLikeChars = "0-9OoQqIiLlZzSsGgBbTt"

            // First catch the most common left/right layout when ML Kit returns
            // both IDs on one OCR line, with either a slash or just whitespace.
            val obviousPair = Regex(
                "([$digitLikeChars]{9,14})\\s*(?:[/|\\\\]|\\s{1,})\\s*([$digitLikeChars]{9,14})",
                RegexOption.IGNORE_CASE
            )
            obviousPair.findAll(line).forEach { match ->
                val left = normalizeIdentifier(match.groupValues[1])
                val right = normalizeIdentifier(match.groupValues[2])
                if (isLikelyReference(left)) result += left
                if (isLikelyReference(right)) result += right
            }

            // 1) Normal OCR output: one long token.
            // 2) Broken OCR output: digits split by spaces/dots/dashes, for example
            //    "00000 234329" or "0000 666 5874".
            // Common letter/digit confusions are accepted and normalized later.
            val numericLike = Regex(
                "(?<![A-Z0-9])([0-9OoQqIiLlZzSsGgBbTt](?:[0-9OoQqIiLlZzSsGgBbTt._-]*[0-9OoQqIiLlZzSsGgBbTt])?)(?![A-Z0-9])",
                RegexOption.IGNORE_CASE
            )

            // Split on large visual gaps first. A single space may be inside an ID,
            // while two+ spaces usually separate the left and right receipt columns.
            line.split(Regex("\\s{2,}|[/|\\\\:;]+"))
                .forEach { chunk ->
                    // First try the whole chunk with internal spaces removed.
                    val whole = normalizeIdentifier(chunk)
                    if (isLikelyReference(whole)) result += whole

                    // Then inspect smaller numeric-like runs in the chunk.
                    numericLike.findAll(chunk.replace(" ", ""))
                        .map { normalizeIdentifier(it.groupValues[1]) }
                        .filter(::isLikelyReference)
                        .forEach { result += it }
                }

            // Extra pass for OCR that inserts single spaces inside an identifier.
            // We only accept the collapsed value if it remains a plausible ID.
            val collapsedRuns = Regex(
                "(?<![A-Z0-9])([0-9OoQqIiLlZzSsGgBbTt]{2,6}(?:\\s+[0-9OoQqIiLlZzSsGgBbTt]{2,7}){1,4})(?![A-Z0-9])",
                RegexOption.IGNORE_CASE
            )
            collapsedRuns.findAll(line).forEach { match ->
                val value = normalizeIdentifier(match.groupValues[1])
                if (isLikelyReference(value)) result += value
            }

            return result.distinct()
        }

        val candidates = mutableListOf<Candidate>()

        lines.forEachIndexed { index, line ->
            val folded = fold(line)
            val previous = lines.getOrNull(index - 1)?.let(::fold).orEmpty()
            val next = lines.getOrNull(index + 1)?.let(::fold).orEmpty()
            val twoBack = lines.getOrNull(index - 2)?.let(::fold).orEmpty()
            val twoAhead = lines.getOrNull(index + 2)?.let(::fold).orEmpty()
            val context = listOf(twoBack, previous, folded, next, twoAhead).joinToString(" ")

            extractIdentifiers(line).forEachIndexed { tokenOrder, value ->
                var score = 10
                if (value.length == 11) score += 20
                if (value.startsWith("000")) score += 10
                if (context.contains("pid")) score += 35
                if (context.contains("ref")) score += 25
                if (context.contains("p/s")) score += 15
                if (context.contains("client")) score += 5

                // The references are physically in the lower part of the receipt,
                // so later OCR reading-order lines are preferred.
                score += ((index.toDouble() / lines.size.coerceAtLeast(1)) * 15).toInt()

                candidates += Candidate(value, index, tokenOrder, score)
            }
        }

        // Search the neighborhood around any PID / Réf. label. This is deliberately
        // ±8 lines because ML Kit can interleave the left/right receipt columns and
        // place the second number several OCR lines away from the first one.
        val labelIndexes = lines.mapIndexedNotNull { index, line ->
            val f = fold(line)
            if (f.contains("pid") || f.contains("ref") || f.contains("p/s")) index else null
        }

        if (labelIndexes.isNotEmpty()) {
            val nearLabels = candidates
                .map { candidate ->
                    val distance = labelIndexes.minOf { kotlin.math.abs(it - candidate.lineIndex) }
                    candidate.copy(score = candidate.score + (40 - distance * 4).coerceAtLeast(0))
                }
                .filter { candidate ->
                    labelIndexes.any { kotlin.math.abs(it - candidate.lineIndex) <= 8 }
                }
                .sortedWith(
                    compareByDescending<Candidate> { it.score }
                        .thenBy { it.lineIndex }
                )
                .distinctBy { it.value }

            if (nearLabels.size >= 2) {
                // OCR reading order on these receipts normally keeps the left field
                // (Contract) before the right field (Tier / Réf.).
                val pair = nearLabels.take(2).sortedWith(
                    compareBy<Candidate> { it.lineIndex }.thenBy { it.tokenOrder }
                )
                return pair[0].value to pair[1].value
            }
        }

        // Last resort: choose the two strongest distinct long IDs from the lower
        // half of OCR reading order. This catches receipts where PID / Réf. itself
        // was not recognized at all.
        val lowerStart = (lines.size * 0.45).toInt().coerceAtLeast(0)
        val lower = candidates
            .filter { it.lineIndex >= lowerStart }
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenBy { it.lineIndex }
            )
            .distinctBy { it.value }

        if (lower.size >= 2) {
            val pair = lower.take(2).sortedWith(
                compareBy<Candidate> { it.lineIndex }.thenBy { it.tokenOrder }
            )
            return pair[0].value to pair[1].value
        }

        return "" to ""
    }

    private fun findReferencePairByGeometry(recognized: Text): Pair<String, String> {
        data class GeoCandidate(
            val value: String,
            val centerX: Float,
            val centerY: Float,
            val score: Int,
            val order: Int
        )

        data class RawIdentifier(val value: String, val start: Int)

        fun likely(value: String): Boolean {
            if (value.length !in 9..14) return false
            if (!value.startsWith("0")) return false
            if (looksLikePhoneNumber(value)) return false
            return true
        }

        fun rawIdentifiers(text: String): List<RawIdentifier> {
            val out = mutableListOf<RawIdentifier>()
            val chars = "0-9OoQqIiLlZzSsGgBbTt"

            // Best case: both 9-14-character IDs are returned on one OCR line.
            val pair = Regex(
                "([$chars]{9,14})\\s*(?:[/|\\\\]|\\s+)\\s*([$chars]{9,14})",
                RegexOption.IGNORE_CASE
            )
            pair.findAll(text).forEach { m ->
                val left = normalizeIdentifier(m.groupValues[1])
                val right = normalizeIdentifier(m.groupValues[2])
                if (likely(left)) out += RawIdentifier(left, m.groups[1]?.range?.first ?: m.range.first)
                if (likely(right)) out += RawIdentifier(right, m.groups[2]?.range?.first ?: m.range.first)
            }

            // Normal single token.
            val token = Regex("(?<![A-Z0-9])([$chars]{9,14})(?![A-Z0-9])", RegexOption.IGNORE_CASE)
            token.findAll(text).forEach { m ->
                val value = normalizeIdentifier(m.groupValues[1])
                if (likely(value)) out += RawIdentifier(value, m.range.first)
            }

            // OCR can split one identifier: 0000 666 5874 / 00000-234329.
            val broken = Regex(
                "(?<![A-Z0-9])([$chars]{2,6}(?:[ ._-]+[$chars]{2,7}){1,4})(?![A-Z0-9])",
                RegexOption.IGNORE_CASE
            )
            broken.findAll(text).forEach { m ->
                val value = normalizeIdentifier(m.groupValues[1])
                if (likely(value)) out += RawIdentifier(value, m.range.first)
            }

            return out.distinctBy { it.value }
        }

        val allLines = recognized.textBlocks.flatMap { it.lines }
        if (allLines.isEmpty()) return "" to ""

        val maxBottom = allLines.mapNotNull { it.boundingBox?.bottom }.maxOrNull()?.coerceAtLeast(1) ?: 1
        val labels = allLines.mapNotNull { line ->
            val f = fold(line.text)
            if (f.contains("pid") || f.contains("ref") || f.contains("p/s")) {
                line.boundingBox?.let { box -> (box.centerY()).toFloat() }
            } else null
        }

        val candidates = mutableListOf<GeoCandidate>()
        var order = 0

        allLines.forEach { line ->
            val box = line.boundingBox ?: return@forEach
            val ids = rawIdentifiers(line.text)
            ids.forEach { raw ->
                val fraction = if (line.text.length <= 1) 0.5f
                    else (raw.start.toFloat() / (line.text.length - 1).toFloat()).coerceIn(0f, 1f)
                val x = box.left + box.width() * fraction
                val y = box.centerY().toFloat()

                var score = 20
                if (raw.value.length == 11) score += 45
                if (raw.value.startsWith("000")) score += 20
                if (y >= maxBottom * 0.45f) score += 25
                if (y >= maxBottom * 0.60f) score += 10

                if (labels.isNotEmpty()) {
                    val dy = labels.minOf { kotlin.math.abs(it - y) }
                    score += (55 - (dy / maxBottom * 120).toInt()).coerceAtLeast(0)
                }

                candidates += GeoCandidate(raw.value, x, y, score, order++)
            }

            // Elements give more accurate X coordinates when ML Kit separated the IDs.
            line.elements.forEach { element ->
                val ebox = element.boundingBox ?: return@forEach
                rawIdentifiers(element.text).forEach { raw ->
                    val value = raw.value
                    val y = ebox.centerY().toFloat()
                    var score = 30
                    if (value.length == 11) score += 45
                    if (value.startsWith("000")) score += 20
                    if (y >= maxBottom * 0.45f) score += 25
                    if (labels.isNotEmpty()) {
                        val dy = labels.minOf { kotlin.math.abs(it - y) }
                        score += (55 - (dy / maxBottom * 120).toInt()).coerceAtLeast(0)
                    }
                    candidates += GeoCandidate(value, ebox.centerX().toFloat(), y, score, order++)
                }
            }
        }

        val bestByValue = candidates
            .groupBy { it.value }
            .mapNotNull { (_, sameValue) -> sameValue.maxByOrNull { it.score } }
            .sortedByDescending { it.score }

        if (bestByValue.size < 2) return "" to ""

        var bestA: GeoCandidate? = null
        var bestB: GeoCandidate? = null
        var bestPairScore = Int.MIN_VALUE

        for (i in 0 until minOf(bestByValue.size, 10)) {
            for (j in i + 1 until minOf(bestByValue.size, 10)) {
                val a = bestByValue[i]
                val b = bestByValue[j]
                val verticalDistance = kotlin.math.abs(a.centerY - b.centerY)
                val horizontalDistance = kotlin.math.abs(a.centerX - b.centerX)

                var pairScore = a.score + b.score
                // The two receipt IDs are printed on the same bottom row / area.
                pairScore += (70 - (verticalDistance / maxBottom * 180).toInt()).coerceAtLeast(-25)
                // Prefer a true left/right pair over two values stacked in one column.
                if (horizontalDistance > 20f) pairScore += 20

                if (pairScore > bestPairScore) {
                    bestPairScore = pairScore
                    bestA = a
                    bestB = b
                }
            }
        }

        val a = bestA ?: return "" to ""
        val b = bestB ?: return "" to ""

        // Physical receipt layout decides the mapping, not OCR reading order.
        return if (a.centerX < b.centerX) {
            a.value to b.value
        } else if (b.centerX < a.centerX) {
            b.value to a.value
        } else if (a.order <= b.order) {
            a.value to b.value
        } else {
            b.value to a.value
        }
    }

    private fun looksLikePhoneNumber(value: String): Boolean {
        // Al Amana footer phone/fax numbers are usually 10 digits beginning 05.
        return value.length == 10 && value.startsWith("05")
    }

    private fun normalizeIdentifier(value: String): String {
        return value.uppercase()
            .replace('O', '0')
            .replace('Q', '0')
            .replace('I', '1')
            .replace('L', '1')
            .replace('Z', '2')
            .replace('S', '5')
            .replace('G', '6')
            .replace('T', '7')
            .replace('B', '8')
            .filter { it.isDigit() }
    }

    private fun looksLikeLabel(value: String): Boolean {
        val f = fold(value)
        return listOf(
            "agence", "date", "montant", "signature", "ref", "pid", "psd",
            "recu", "copie client", "cachet"
        ).any { f.startsWith(it) }
    }
}
