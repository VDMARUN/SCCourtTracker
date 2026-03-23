package com.sc.courttracker

import org.jsoup.Jsoup

object Scraper {

    data class ScrapeResult(
        val courts: Map<Int, Int>,
        val sequences: Map<Int, SequenceData>
    )

    data class SequenceData(
        val ranges: List<Pair<Int,Int>>,
        val restOfMatters: Boolean,
        val raw: String
    )

    fun fetch(): ScrapeResult? {
        return try {
            val doc = Jsoup.connect("https://cdb.sci.gov.in/")
                .userAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                .timeout(15000)
                .get()

            val courts = mutableMapOf<Int, Int>()
            val sequences = mutableMapOf<Int, SequenceData>()

            // Scrape court item numbers from #sci-display
            val sciDisplay = doc.getElementById("sci-display")
            if (sciDisplay != null) {
                val tokens = sciDisplay.text()
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                var i = 0
                while (i < tokens.size - 1) {
                    val m = Regex("^C(\\d{1,2})$").find(tokens[i])
                    if (m != null) {
                        val courtNo = m.groupValues[1].toInt()
                        val item = tokens[i + 1].toIntOrNull()
                        if (item != null && item > 0 && item < 500) {
                            courts[courtNo] = item
                            i += 2
                            continue
                        }
                    }
                    i++
                }
            }

            // Scrape sequence ticker from notify-div and marquees
            val tickerParts = mutableListOf<String>()
            doc.getElementById("notify-div")?.let { tickerParts.add(it.text()) }
            doc.select("marquee").forEach { tickerParts.add(it.text()) }
            val tickerText = tickerParts.joinToString(" ")

            if (tickerText.isNotBlank()) {
                val splits = tickerText.split(Regex("Court\\s+C(\\d{1,2})\\s*:", RegexOption.IGNORE_CASE))
                var bi = 1
                while (bi < splits.size - 1) {
                    val courtNo = splits[bi].trim().toIntOrNull()
                    val seqText = splits[bi + 1]
                        .split(Regex("Court\\s+C\\d", RegexOption.IGNORE_CASE))[0]
                        .trim()
                    if (courtNo != null && seqText.isNotEmpty()) {
                        sequences[courtNo] = parseSequence(seqText)
                    }
                    bi += 2
                }
            }

            ScrapeResult(courts, sequences)
        } catch (e: Exception) {
            android.util.Log.e("SCTracker", "Fetch error: ${e.message}")
            null
        }
    }

    private fun parseSequence(text: String): SequenceData {
        val upper = text.uppercase()
        val restOfMatters = upper.contains("REST OF THE MATTER") || upper.contains("REST OF MATTER")
        val ranges = mutableListOf<Pair<Int, Int>>()

        val cleaned = upper
            .replace(Regex("SEQUENCE\\s+WOULD\\s+BE"), "")
            .replace(Regex("ITEM\\s+NOS?\\.?"), "")
            .replace(Regex("PASS\\s+OVER.*"), "")
            .replace(Regex("THEN\\s+REST.*"), "")

        val parts = cleaned.split(Regex("[,\\s]+"))
            .filter { it.matches(Regex("^\\d+$|^TO$")) }

        var i = 0
        while (i < parts.size) {
            if (parts[i] == "TO") { i++; continue }
            val n = parts[i].toIntOrNull()
            if (n == null || n > 500) { i++; continue }
            if (i + 2 < parts.size && parts[i + 1] == "TO") {
                val end = parts[i + 2].toIntOrNull()
                if (end != null && end <= 500) {
                    ranges.add(Pair(n, end))
                    i += 3
                    continue
                }
            }
            ranges.add(Pair(n, n))
            i++
        }

        return SequenceData(ranges, restOfMatters, text.take(100))
    }
}
