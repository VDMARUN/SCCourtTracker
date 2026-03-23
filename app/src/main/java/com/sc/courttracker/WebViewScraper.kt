package com.sc.courttracker

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

// WebView-based scraper that fully executes JavaScript — same as the Chrome extension
class WebViewScraper(private val context: Context) {

    private val TAG = "SCTracker"

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(): Scraper.ScrapeResult? = withTimeoutOrNull(25000) {
        suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val webView = WebView(context)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                var completed = false

                // JS interface to receive scraped data
                webView.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onData(json: String) {
                        if (completed) return
                        completed = true
                        Log.d(TAG, "WebView data received: ${json.take(200)}")
                        try {
                            val result = parseJson(json)
                            Log.d(TAG, "WebView courts: ${result.courts}")
                            webView.post { webView.destroy() }
                            cont.resume(result)
                        } catch (e: Exception) {
                            Log.e(TAG, "WebView parse error: ${e.message}")
                            cont.resume(null)
                        }
                    }

                    @JavascriptInterface
                    fun onError(msg: String) {
                        Log.e(TAG, "WebView JS error: $msg")
                        if (!completed) { completed = true; cont.resume(null) }
                    }
                }, "Android")

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Wait 4 seconds for JS to populate the board
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!completed) {
                                view?.evaluateJavascript("""
                                    (function() {
                                        try {
                                            var courts = {};
                                            var sequences = {};
                                            
                                            // Scrape #sci-display
                                            var el = document.getElementById('sci-display');
                                            if (el) {
                                                var text = el.innerText || el.textContent || '';
                                                var tokens = text.split(/\s+/).filter(function(t) { return t.length > 0; });
                                                for (var i = 0; i < tokens.length - 1; i++) {
                                                    var m = tokens[i].match(/^C(\d{1,2})$/);
                                                    if (m) {
                                                        var courtNo = parseInt(m[1]);
                                                        var item = parseInt(tokens[i+1]);
                                                        if (!isNaN(item) && item > 0 && item < 500) {
                                                            courts[courtNo] = item;
                                                            i++;
                                                        }
                                                    }
                                                }
                                            }
                                            
                                            // Scrape sequences
                                            var seqText = '';
                                            var nd = document.getElementById('notify-div');
                                            if (nd) seqText += nd.innerText + ' ';
                                            var mqs = document.querySelectorAll('marquee');
                                            mqs.forEach(function(m) { seqText += m.innerText + ' '; });
                                            
                                            Android.onData(JSON.stringify({courts: courts, sequences: seqText.substring(0, 2000)}));
                                        } catch(e) {
                                            Android.onError(e.toString());
                                        }
                                    })();
                                """.trimIndent(), null)
                            }
                        }, 4000)
                    }
                }

                webView.loadUrl("https://cdb.sci.gov.in/")

                cont.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post { 
                        if (!completed) { completed = true; webView.destroy() }
                    }
                }
            }
        }
    }

    private fun parseJson(json: String): Scraper.ScrapeResult {
        val root = org.json.JSONObject(json)
        val courtsObj = root.getJSONObject("courts")
        val courts = mutableMapOf<Int, Int>()
        courtsObj.keys().forEach { k ->
            courts[k.toInt()] = courtsObj.getInt(k)
        }

        val seqText = root.optString("sequences", "")
        val sequences = mutableMapOf<Int, Scraper.SequenceData>()
        if (seqText.isNotBlank()) {
            val splits = seqText.split(Regex("Court\\s+C(\\d{1,2})\\s*:", RegexOption.IGNORE_CASE))
            var bi = 1
            while (bi < splits.size - 1) {
                val courtNo = splits[bi].trim().toIntOrNull()
                val st = splits[bi+1].split(Regex("Court\\s+C\\d", RegexOption.IGNORE_CASE))[0].trim()
                if (courtNo != null && st.isNotEmpty()) {
                    sequences[courtNo] = Scraper.SequenceData(emptyList(), false, st.take(100))
                }
                bi += 2
            }
        }
        return Scraper.ScrapeResult(courts, sequences)
    }
}
