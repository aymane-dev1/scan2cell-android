package com.scan2cell.app

import java.text.Normalizer

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
            if (!folded.contains("nom client")) continue

            val inline = line
                .replace(Regex("(?i)^.*?nom\\s*client\\s*[:\\-]?\\s*"), "")
                .trim(' ', ':', '-')
            if (inline.isNotBlank() && !looksLikeLabel(inline)) {
                return cleanName(inline)
            }

            val next = lines.drop(index + 1).firstOrNull { candidate ->
                !looksLikeLabel(candidate) && candidate.any { it.isLetter() }
            }
            if (!next.isNullOrBlank()) return cleanName(next)
        }
        return ""
    }

    private fun cleanName(value: String): String {
        return value
            .replace(Regex("(?i)\\b(date|montant|agence|recu|réf|ref|pid)\\b.*$"), "")
            .replace(Regex("[^\\p{L}'’ -]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun findAmount(lines: List<String>): String {
        val money = Regex("([0-9]{1,7}(?:[ .][0-9]{3})*(?:[,.][0-9]{2})|[0-9]{1,7}[,.][0-9]{2})")
        for (line in lines) {
            val folded = fold(line)
            if (!folded.contains("montant")) continue
            val match = money.find(line)?.groupValues?.getOrNull(1).orEmpty()
            if (match.isNotBlank()) return normalizeAmount(match)
        }
        return ""
    }

    private fun normalizeAmount(value: String): String {
        var text = value.replace(" ", "").trim()
        if (text.contains('.') && text.contains(',')) {
            text = if (text.lastIndexOf(',') > text.lastIndexOf('.')) {
                text.replace(".", "")
            } else {
                text.replace(",", "")
            }
        }
        return text
    }

    private fun findReferencePair(lines: List<String>): Pair<String, String> {
        data class Candidate(val left: String, val right: String, val score: Int)
        val candidates = mutableListOf<Candidate>()
        val pairRegex = Regex("([0-9OoIlLSsBb\\s]{7,22})\\s*[/|\\\\]\\s*([0-9OoIlLSsBb\\s]{7,22})")

        lines.forEachIndexed { index, line ->
            pairRegex.findAll(line).forEach { match ->
                val left = normalizeIdentifier(match.groupValues[1])
                val right = normalizeIdentifier(match.groupValues[2])
                if (left.length in 7..16 && right.length in 7..16) {
                    val neighborhood = lines.subList((index - 2).coerceAtLeast(0), (index + 2).coerceAtMost(lines.size))
                        .joinToString(" ") { fold(it) }
                    var score = 1
                    if (neighborhood.contains("pid")) score += 5
                    if (neighborhood.contains("ref. p/s") || neighborhood.contains("ref p/s")) score += 4
                    if (neighborhood.contains("ref client")) score += 3
                    candidates += Candidate(left, right, score)
                }
            }
        }

        candidates.maxByOrNull { it.score }?.let { return it.left to it.right }

        // Fallback for OCR that loses the slash: search long numeric tokens near PID / references.
        val tokenRegex = Regex("[0-9OoIlLSsBb]{7,16}")
        for ((index, line) in lines.withIndex()) {
            val folded = fold(line)
            if (!(folded.contains("pid") || folded.contains("ref"))) continue
            val neighborhood = lines.subList(index, (index + 3).coerceAtMost(lines.size)).joinToString(" ")
            val tokens = tokenRegex.findAll(neighborhood)
                .map { normalizeIdentifier(it.value) }
                .filter { it.length in 7..16 }
                .distinct()
                .toList()
            if (tokens.size >= 2) return tokens[0] to tokens[1]
        }
        return "" to ""
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
        return listOf("agence", "date", "montant", "signature", "ref", "pid", "recu", "copie client")
            .any { f.startsWith(it) }
    }
}
