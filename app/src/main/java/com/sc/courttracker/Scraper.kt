package com.sc.courttracker

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.Connection

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

    private val TAG = "SCTracker"

    fun fetch(): ScrapeResult? {
        // Try multiple URLs/methods
        return tryMainPage()
            ?: tryDisplayPage()
    }

    private fun tryMainPage(): ScrapeResult? {
        return try {
            Log.d(TAG, "Trying main page...")
            val doc = Jsoup.connect("https://cdb.sci.gov.in/")
                .userAgent("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .followRedirects(true)
                .timeout(20000)
                .get()

            val sciDisplay = doc.getElementById("sci-display")
            val bodyText = sciDisplay?.text() ?: doc.body()?.text() ?: ""
            Log.d(TAG, "Main page body length: ${doc.body()?.text()?.length}, sci-display: ${sciDisplay?.text()?.length}")

            val result = parseAll(doc)
            if (result.courts.isNotEmpty()) {
                Log.d(TAG, "Main page success: ${result.courts}")
                result
            } else {
                Log.d(TAG, "Main page had no courts")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Main page error: ${e.message}")
            null
        }
    }

    private fun tryDisplayPage(): ScrapeResult? {
        // Try fetching the display board page which the extension uses
        return try {
            Log.d(TAG, "Trying display board page...")
            val doc = Jsoup.connect("https://cdb.sci.gov.in/display_court_all.php")
                .userAgent("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36")
                .followRedirects(true)
                .timeout(20000)
                .get()
            val result = parseAll(doc)
            Log.d(TAG, "Display page courts: ${result.courts}")
            if (result.courts.isNotEmpty()) result else null
        } catch (e: Exception) {
            Log.e(TAG, "Display page error: ${e.message}")
            // Try with trailing slash variant
            try {
                val doc2 = Jsoup.connect("https://cdb.sci.gov.in/index.php")
                    .userAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                    .timeout(20000).get()
                parseAll(doc2).takeIf { it.courts.isNotEmpty() }
            } catch (e2: Exception) {
                Log.e(TAG, "index.php error: ${e2.message}")
                null
            }
        }
    }

    private fun parseAll(doc: org.jsoup.nodes.Document): ScrapeResult {
        val courts = mutableMapOf<Int, Int>()
        val sequences = mutableMapOf<Int, SequenceData>()

        // Try #sci-display
        doc.getElementById("sci-display")?.let { el ->
            parseCourtTiles(el.text(), courts)
        }

        // Try full body if still empty
        if (courts.isEmpty()) {
            parseCourtTiles(doc.body()?.text() ?: "", courts)
        }

        // Try every div
        if (courts.isEmpty()) {
            for (div in doc.select("div, span, td")) {
                val txt = div.text()
                if (Regex("C\\d{1,2}\\s+\\d{1,3}").containsMatchIn(txt)) {
                    parseCourtTiles(txt, courts)
                    if (courts.isNotEmpty()) break
                }
            }
        }

        // Sequences from notify-div and marquee
        val tickerParts = mutableListOf<String>()
        doc.getElementById("notify-div")?.text()?.let { tickerParts.add(it) }
        doc.select("marquee").forEach { tickerParts.add(it.text()) }
        val combined = tickerParts.joinToString(" ")
        if (combined.isNotBlank()) {
            val splits = combined.split(Regex("Court\\s+C(\\d{1,2})\\s*:", RegexOption.IGNORE_CASE))
            var bi = 1
            while (bi < splits.size - 1) {
                val courtNo = splits[bi].trim().toIntOrNull()
                val seqText = splits[bi+1].split(Regex("Court\\s+C\\d", RegexOption.IGNORE_CASE))[0].trim()
                if (courtNo != null && seqText.isNotEmpty()) sequences[courtNo] = parseSequence(seqText)
                bi += 2
            }
        }

        return ScrapeResult(courts, sequences)
    }

    private fun parseCourtTiles(text: String, courts: MutableMap<Int, Int>) {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        var i = 0
        while (i < tokens.size - 1) {
            val m = Regex("^C(\\d{1,2})$").find(tokens[i])
            if (m != null) {
                val courtNo = m.groupValues[1].toInt()
                val item = tokens[i+1].toIntOrNull()
                if (item != null && item > 0 && item < 500) {
                    courts[courtNo] = item; i += 2; continue
                }
            }
            i++
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
        val parts = cleaned.split(Regex("[,\\s]+")).filter { it.matches(Regex("^\\d+$|^TO$")) }
        var i = 0
        while (i < parts.size) {
            if (parts[i] == "TO") { i++; continue }
            val n = parts[i].toIntOrNull()
            if (n == null || n > 500) { i++; continue }
            if (i + 2 < parts.size && parts[i+1] == "TO") {
                val end = parts[i+2].toIntOrNull()
                if (end != null && end <= 500) { ranges.add(Pair(n, end)); i += 3; continue }
            }
            ranges.add(Pair(n, n)); i++
        }
        return SequenceData(ranges, restOfMatters, text.take(100))
    }
}
