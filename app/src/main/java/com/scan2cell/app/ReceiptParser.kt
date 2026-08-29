package com.scan2cell.app

import java.text.Normalizer

/**
 * Receipt parser tuned for the Al Amana receipt layout.
 *
 * Important:
 * - The paper does not explicitly label the two long PID/reference values as
 *   "Treasury" and "Contract". We therefore return the two strongest reference
 *   candidates left-to-right. Excel v1.2.1 tests BOTH orientations against
 *   BASE_FULL + BASE_SIMPLE and keeps the matching orientation automatically.
 * - Amount detection is deliberately global as a fallback because ML Kit can
 *   split "Montant reçu" and "487,00" into different OCR lines.
 */
object ReceiptParser {
    fun parse(lines: List<String>): ReceiptData {
        val cleaned = lines
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }

        val name = findClientName(cleaned)
        val amount = findAmount(cleaned)
        val ids = findReferencePair(cleaned)

        return ReceiptData(
            treasuryNumber = ids.first,
            clientName = name,
            contractNumber = ids.second,
            amount = amount
        )
    }

    private fun fold(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace('’', '\'')
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
            val left: String,
            val right: String,
            val score: Int,
            val lineIndex: Int
        )

        val candidates = mutableListOf<Candidate>()

        // Allow OCR confusions in long numeric references.
        val pairRegex = Regex(
            "([0-9OoIlLSsBb\\s]{7,24})\\s*[/|\\\\]\\s*([0-9OoIlLSsBb\\s]{7,24})"
        )

        lines.forEachIndexed { index, line ->
            pairRegex.findAll(line).forEach { match ->
                val left = normalizeIdentifier(match.groupValues[1])
                val right = normalizeIdentifier(match.groupValues[2])

                if (left.length in 7..16 && right.length in 7..16) {
                    val from = (index - 3).coerceAtLeast(0)
                    val to = (index + 3).coerceAtMost(lines.size)
                    val neighborhood = lines.subList(from, to)
                        .joinToString(" ") { fold(it) }

                    var score = 5
                    if (neighborhood.contains("pid")) score += 25
                    if (neighborhood.contains("ref")) score += 12
                    if (neighborhood.contains("p/s")) score += 8
                    if (neighborhood.contains("client")) score += 4

                    // Penalize the top alphanumeric receipt number area.
                    if (neighborhood.contains("recu") && !neighborhood.contains("pid")) {
                        score -= 10
                    }

                    candidates += Candidate(left, right, score, index)
                }
            }
        }

        candidates
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenByDescending { it.lineIndex }
            )
            .firstOrNull()
            ?.let { return it.left to it.right }

        // OCR can lose the slash. Search for two long numeric-like tokens near
        // PID / Réf. and nowhere else first.
        val tokenRegex = Regex("[0-9OoIlLSsBb]{7,16}")

        lines.forEachIndexed { index, line ->
            val folded = fold(line)
            if (!(folded.contains("pid") || folded.contains("ref"))) return@forEachIndexed

            val from = index
            val to = (index + 4).coerceAtMost(lines.size)
            val neighborhood = lines.subList(from, to).joinToString(" ")

            val tokens = tokenRegex.findAll(neighborhood)
                .map { normalizeIdentifier(it.value) }
                .filter { it.length in 7..16 }
                .filterNot { looksLikePhoneNumber(it) }
                .distinct()
                .toList()

            if (tokens.size >= 2) return tokens[0] to tokens[1]
        }

        // Last resort: two long numeric tokens in the lower portion of OCR
        // reading order. This avoids mistaking the short top receipt code.
        val lowerStart = (lines.size * 0.45).toInt().coerceAtLeast(0)
        val lowerTokens = lines.drop(lowerStart)
            .flatMap { line ->
                tokenRegex.findAll(line)
                    .map { normalizeIdentifier(it.value) }
                    .toList()
            }
            .filter { it.length in 7..16 }
            .filterNot { looksLikePhoneNumber(it) }
            .distinct()

        return if (lowerTokens.size >= 2) {
            lowerTokens[0] to lowerTokens[1]
        } else {
            "" to ""
        }
    }

    private fun looksLikePhoneNumber(value: String): Boolean {
        // Al Amana footer phone/fax numbers are usually 10 digits beginning 05.
        return value.length == 10 && value.startsWith("05")
    }

    private fun normalizeIdentifier(value: String): String {
        return value.uppercase()
            .replace('O', '0')
            .replace('I', '1')
            .replace('L', '1')
            .replace('S', '5')
            .replace('B', '8')
            .filter { it.isDigit() }
    }

    private fun looksLikeLabel(value: String): Boolean {
        val f = fold(value)
        return listOf(
            "agence", "date", "montant", "signature", "ref", "pid",
            "recu", "copie client", "cachet"
        ).any { f.startsWith(it) }
    }
}
