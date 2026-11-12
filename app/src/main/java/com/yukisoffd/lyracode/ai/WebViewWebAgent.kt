package com.yukisoffd.lyracode.ai

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.yukisoffd.lyracode.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String = "",
    val score: Int = 0,
    val reason: String = "",
)

data class WebPageResult(
    val title: String,
    val url: String,
    val text: String,
    val extraction: String = "unknown",
)

class WebViewWebAgent internal constructor(
    context: Context,
    private val settings: AppSettings,
    private val deepSeekClient: DeepSeekWebClient,
) {
    constructor(context: Context, settings: AppSettings) : this(context, settings, DeepSeekWebClient())

    private val context = context
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, limit: Int = 6): String {
        val cleanQuery = query.trim()
        require(cleanQuery.isNotBlank()) { "Search query must not be empty." }
        val deepSeek = settings.deepSeekSearchConfig
        if (deepSeek.enabled) return deepSeekClient.search(deepSeek, cleanQuery, limit)
        val maxResults = limit.coerceIn(1, 10)
        val engines = listOf(
            SearchEngine("DuckDuckGo", "https://html.duckduckgo.com/html/?q=${Uri.encode(cleanQuery)}"),
            SearchEngine("Google", "https://www.google.com/search?hl=zh-CN&q=${Uri.encode(cleanQuery)}"),
            SearchEngine("Bing", "https://www.bing.com/search?q=${Uri.encode(cleanQuery)}"),
            SearchEngine("Bing China", "https://cn.bing.com/search?q=${Uri.encode(cleanQuery)}"),
            SearchEngine("Baidu", "https://www.baidu.com/s?wd=${Uri.encode(cleanQuery)}"),
        )
        var lastError = ""
        val results = (withTimeoutOrNull(18_000L) { httpSearch(engines, cleanQuery, maxResults * 4) } ?: emptyList())
            .takeIf { it.isNotEmpty() }
            ?: engines.firstNotNullOfOrNull { engine ->
                runCatching {
                    val json = loadAndEvaluate(url = engine.url, script = searchScript(maxResults), timeoutMs = 4_000L)
                    rankSearchResults(cleanQuery, parseSearchResults(json), maxResults).takeIf { it.isNotEmpty() }
                }.onFailure {
                    lastError = "${engine.name}: ${it::class.java.simpleName}: ${it.message.orEmpty()}"
                    Log.w(TAG, "WebView search failed: ${engine.url}", it)
                }.getOrNull()
            }.orEmpty()
        val blockedHosts = settings.webSearchBlockedHosts()
        if (results.isEmpty()) {
            val blockedNote = if (blockedHosts.isNotEmpty()) " User-blocked domains: ${blockedHosts.joinToString(", ")}." else ""
            return "WEB_SEARCH_EMPTY: No relevant result was found.$blockedNote last_error=$lastError. Refine the query or use another trustworthy source."
        }
        return buildString {
            appendLine("WEB_SEARCH_RESULTS schema=lyra_web_search_v2")
            appendLine("query: $cleanQuery")
            if (blockedHosts.isNotEmpty()) appendLine("blocked_hosts: ${blockedHosts.joinToString(", ")}")
            appendLine("guidance: Treat snippets as leads only. Read trustworthy candidate pages before using them as evidence.")
            results.take(maxResults).forEachIndexed { index, result ->
                appendLine()
                appendLine("result_${index + 1}:")
                appendLine("title: ${result.title}")
                appendLine("url: ${result.url}")
                appendLine("source: ${result.source}")
                appendLine("score: ${result.score}")
                appendLine("reason: ${result.reason}")
                appendLine("snippet: ${result.snippet.take(500)}")
            }
        }.trim()
    }

    private suspend fun httpSearch(engines: List<SearchEngine>, query: String, limit: Int): List<WebSearchResult> = coroutineScope {
        val collected = engines.map { engine ->
            async(Dispatchers.IO) {
                runCatching {
                    val results = when (engine.name) {
                        "DuckDuckGo" -> parseLinksFromHtml(httpGet(engine.url), limit)
                        "Bing" -> parseBingRss(httpGet("https://www.bing.com/search?q=${Uri.encode(query)}&format=rss"), limit)
                            .ifEmpty { parseLinksFromHtml(httpGet(engine.url), limit) }
                        "Bing China" ->
                            parseBingRss(httpGet("https://cn.bing.com/search?q=${Uri.encode(query)}&format=rss"), limit)
                                .ifEmpty { parseLinksFromHtml(httpGet(engine.url), limit) }
                        else -> parseLinksFromHtml(httpGet(engine.url), limit)
                    }
                    results.map { it.copy(source = engine.name) }
                }.onFailure {
                    Log.w(TAG, "HTTP search failed: ${engine.name}", it)
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
        rankSearchResults(query, collected, limit)
    }
    suspend fun readPage(url: String): String {
        val cleanUrl = url.trim()
        val deepSeek = settings.deepSeekFetchConfig
        if (deepSeek.enabled) return deepSeekClient.readPage(deepSeek, cleanUrl)
        require(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) { "read_web_page supports only http:// and https:// URLs." }
        blockedHostFor(cleanUrl)?.let { blocked ->
            return "WEB_PAGE_READ_RESULT schema=lyra_web_page_v2\nstatus: blocked_by_user\ntitle: \nurl: $cleanUrl\nnote: The user blocked domain $blocked.\n\nThe page was not read. Do not bypass the block; use another source."
        }
        var page = runCatching {
            val json = loadAndEvaluate(url = cleanUrl, script = pageScript(), timeoutMs = 10_000L)
            parsePage(json)
        }.onFailure {
            Log.w(TAG, "WebView read page failed: $cleanUrl", it)
        }.getOrElse {
            withTimeoutOrNull(8_000L) { httpReadFallback(cleanUrl) }
                ?: WebPageResult("", cleanUrl, "Page read timed out. Try another source or retry once if the page is essential.", "timeout")
        }
        if (pageReadStatus(page.text) != "readable") {
            val fallback = withTimeoutOrNull(8_000L) { runCatching { httpReadFallback(cleanUrl) }.getOrNull() }
            if (fallback != null && pageReadStatus(fallback.text) != "blocked_or_dynamic" && contentQuality(fallback.text) > contentQuality(page.text)) {
                page = fallback
            }
        }
        val status = pageReadStatus(page.text)
        val note = when (status) {
            "blocked_or_dynamic" -> "note: Access protection or dynamic rendering prevented reliable extraction. Do not use this page as factual evidence; choose another source."
            "limited" -> "note: Only limited text was extracted. Verify important claims with another readable source."
            else -> "note: Readable page text was extracted. Evaluate source quality before relying on it."
        }
        return "WEB_PAGE_READ_RESULT schema=lyra_web_page_v2\nstatus: $status\ntitle: ${page.title}\nurl: ${page.url}\nextraction: ${page.extraction}\n$note\n\n${page.text.ifBlank { "No readable body text was extracted. Use another source." }}"
    }

    private fun rankSearchResults(query: String, results: List<WebSearchResult>, limit: Int): List<WebSearchResult> {
        val tokens = queryTokens(query)
        val seen = mutableSetOf<String>()
        val ranked = results.asSequence()
            .mapNotNull { result ->
                val url = canonicalSearchUrl(result.url)
                if (url.isBlank() || isSearchEngineUrl(url) || isBlockedUrl(url)) return@mapNotNull null
                val dedupeKey = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
                if (!seen.add(dedupeKey)) return@mapNotNull null
                val title = result.title.trim().take(200)
                if (title.length < 2) return@mapNotNull null
                val scored = scoreSearchResult(query, tokens, result.copy(title = title, url = url))
                result.copy(title = title, url = url, score = scored.first, reason = scored.second)
            }
            .filter { it.score >= MIN_RELEVANCE_SCORE }
            .sortedWith(compareByDescending<WebSearchResult> { it.score }.thenBy { it.title.length })
            .toList()
        val hostCounts = mutableMapOf<String, Int>()
        return ranked.filter { result ->
            val host = runCatching { Uri.parse(result.url).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("")
            val count = hostCounts.getOrDefault(host, 0)
            if (count >= MAX_RESULTS_PER_HOST) false else {
                hostCounts[host] = count + 1
                true
            }
        }.take(limit)
    }

    private fun scoreSearchResult(query: String, tokens: List<String>, result: WebSearchResult): Pair<Int, String> {
        val host = runCatching { Uri.parse(result.url).host.orEmpty().lowercase() }.getOrDefault("")
        val path = runCatching { Uri.parse(result.url).path.orEmpty().lowercase() }.getOrDefault("")
        val title = result.title.lowercase()
        val snippet = result.snippet.lowercase()
        val urlText = "$host $path"
        val titleMatches = tokens.count { title.contains(it) }
        val snippetMatches = tokens.count { snippet.contains(it) }
        val urlMatches = tokens.count { urlText.contains(it) }
        val matchedTokens = tokens.count { title.contains(it) || snippet.contains(it) || urlText.contains(it) }
        val coverage = if (tokens.isEmpty()) 0 else matchedTokens * 100 / tokens.size
        val phrase = query.trim().lowercase()
        var score = titleMatches * 18 + snippetMatches * 7 + urlMatches * 4 + coverage / 4
        val reasons = mutableListOf<String>()
        if (phrase.length >= 3 && (title.contains(phrase) || snippet.contains(phrase))) {
            score += 35
            reasons += "exact query phrase match"
        }
        if (matchedTokens > 0) {
            reasons += "matched $matchedTokens/${tokens.size.coerceAtLeast(1)} query keywords"
        }
        val sourceBonus = hostQualityBonus(host, path)
        if (sourceBonus > 0) {
            score += sourceBonus
            reasons += "source-quality bonus +$sourceBonus"
        }
        val penalty = lowQualityPenalty(host, result.title, result.snippet)
        if (penalty > 0) {
            score -= penalty
            reasons += "low-quality signal -$penalty"
        }
        if (tokens.isNotEmpty() && matchedTokens == 0) {
            score -= 60
            reasons += "title and snippet do not match the query; down-ranked"
        } else if (tokens.size >= 3 && coverage < 25) {
            score -= 15
            reasons += "low keyword coverage"
        }
        return score to reasons.ifEmpty { listOf("ordinary candidate") }.joinToString("; ")
    }

    private fun hostQualityBonus(host: String, path: String): Int {
        return when {
            host.endsWith(".gov") || host.contains(".gov.") -> 35
            host.endsWith(".edu") || host.contains(".edu.") -> 25
            host.contains("github.com") || host.contains("gitlab.com") -> 20
            host.contains("developer.") || host.contains("docs.") || path.contains("/docs") || path.contains("/documentation") -> 20
            host.contains("wikipedia.org") -> 12
            else -> 0
        }
    }

    private fun lowQualityPenalty(host: String, title: String, snippet: String): Int {
        val text = "$title $snippet".lowercase()
        var penalty = 0
        val noisyHosts = listOf(
            "baijiahao.baidu.com", "m.sm.cn", "so.com", "pinterest.", "facebook.com",
            "instagram.com", "tiktok.com", "x.com", "twitter.com",
        )
        if (noisyHosts.any { host.contains(it) }) penalty += 35
        if (listOf("广告", "推广", "最新地址", "转载", "采集", "seo", "站长", "点击查看", "猜你喜欢").any { text.contains(it) }) penalty += 18
        if (title.length > 90 && snippet.isBlank()) penalty += 10
        if (snippet.isBlank()) penalty += 5
        return penalty
    }

    private fun queryTokens(query: String): List<String> {
        val clean = query.lowercase()
        val tokens = Regex("""[a-z0-9][a-z0-9_.+-]{1,}|[\u4E00-\u9FFF]{2,}""")
            .findAll(clean)
            .map { it.value.trim() }
            .filter { it.length >= 2 && it !in SEARCH_STOP_WORDS }
            .distinct()
            .toMutableList()
        clean.split(Regex("""[\s,，。；;:：/\\|"'“”‘’()（）\[\]{}<>《》]+"""))
            .map { it.trim() }
            .filter { it.length >= 2 && it !in SEARCH_STOP_WORDS }
            .forEach { if (it !in tokens) tokens += it }
        Regex("""[\u4E00-\u9FFF]{4,}""").findAll(clean).forEach { match ->
            match.value.windowed(2, 1)
                .filterNot { it in SEARCH_STOP_WORDS }
                .take(10)
                .forEach { if (it !in tokens) tokens += it }
        }
        return tokens.take(16)
    }
    private fun canonicalSearchUrl(url: String): String {
        val parsed = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return ""
        val scheme = parsed.scheme.orEmpty().lowercase()
        if (scheme != "http" && scheme != "https") return ""
        val host = parsed.host.orEmpty().lowercase()
        if (host.isBlank()) return ""
        val path = parsed.path.orEmpty().ifBlank { "/" }
        val query = parsed.queryParameterNames
            .filterNot { it.lowercase() in TRACKING_QUERY_PARAMS }
            .sorted()
            .joinToString("&") { name ->
                val value = parsed.getQueryParameter(name).orEmpty()
                "${Uri.encode(name)}=${Uri.encode(value)}"
            }
        return buildString {
            append(scheme).append("://").append(host).append(path)
            if (query.isNotBlank()) append("?").append(query)
        }.trimEnd('/')
    }

    private fun pageReadStatus(text: String): String {
        val clean = text.trim()
        val lower = clean.lowercase()
        val blockedSignals = listOf(
            "access denied",
            "forbidden",
            "403",
            "cloudflare",
            "just a moment",
            "verify you are human",
            "enable javascript",
            "请完成安全验证",
            "人机验证",
            "登录后查看",
            "网页读取超时",
        )
        val blockedPrefix = lower.take(2_000)
        return when {
            blockedSignals.any { blockedPrefix.contains(it) } && clean.length < 4_000 -> "blocked_or_dynamic"
            clean.length < 600 -> "limited"
            else -> "readable"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun loadAndEvaluate(url: String, script: String, timeoutMs: Long): String = withContext(Dispatchers.Main) {
        withTimeout(timeoutMs) {
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.loadsImagesAutomatically = false
            webView.settings.blockNetworkImage = true
            webView.settings.userAgentString = DESKTOP_USER_AGENT
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            try {
                suspendCancellableCoroutine { continuation ->
                    var evaluated = false
                    fun evaluate(view: WebView) {
                        if (evaluated || !continuation.isActive) return
                        evaluated = true
                        // This WebView is never attached to a window. View.postDelayed would
                        // leave the action waiting in its attach queue instead of evaluating.
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!continuation.isActive) return@postDelayed
                            view.evaluateJavascript(script) { value ->
                                Log.d(TAG, "Loaded $url, resultChars=${value?.length ?: 0}")
                                if (continuation.isActive) continuation.resume(value.orEmpty())
                            }
                        }, 1_200L)
                    }
                    continuation.invokeOnCancellation { webView.destroy() }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

                        override fun onPageFinished(view: WebView, loadedUrl: String) {
                            evaluate(view)
                        }
                    }
                    Log.d(TAG, "Loading $url")
                    webView.loadUrl(url)
                }.also {
                    webView.destroy()
                }
            } catch (error: Throwable) {
                webView.destroy()
                throw error
            }
        }
    }

    private fun searchScript(limit: Int): String = """
        (function() {
            const anchors = Array.from(document.querySelectorAll('a'));
            const rows = [];
            const seen = new Set();
            for (const a of anchors) {
                let href = a.href || '';
                let title = (a.innerText || a.textContent || '').replace(/\s+/g, ' ').trim();
                if (!href || !title || title.length < 2) continue;
                try {
                    const parsed = new URL(href);
                    const uddg = parsed.searchParams.get('uddg');
                    if (uddg) href = decodeURIComponent(uddg);
                    const googleTarget = parsed.searchParams.get('q');
                    if (parsed.hostname.includes('google.') && googleTarget && /^https?:\/\//i.test(googleTarget)) href = googleTarget;
                    const baiduTarget = parsed.searchParams.get('url');
                    if (parsed.hostname.includes('baidu.') && baiduTarget && /^https?:\/\//i.test(baiduTarget)) href = baiduTarget;
                } catch (error) {}
                if (!/^https?:\/\//i.test(href)) continue;
                if (isSearchEngineUrl(href)) continue;
                if (seen.has(href)) continue;
                seen.add(href);
                let parent = a.closest('div, article, li') || a.parentElement;
                let snippet = parent ? (parent.innerText || '').replace(/\s+/g, ' ').trim() : '';
                if (snippet.startsWith(title)) snippet = snippet.slice(title.length).trim();
                rows.push({ title, url: href, snippet });
                if (rows.length >= $limit) break;
            }
            function isSearchEngineUrl(url) {
                try {
                    const parsed = new URL(url);
                    const host = parsed.hostname;
                    const path = parsed.pathname;
                    return (host.includes('google.') && path.startsWith('/search')) ||
                        (host.includes('bing.com') && path.startsWith('/search')) ||
                        (host.includes('baidu.com') && (path === '/s' || path.startsWith('/s?')));
                } catch (error) {
                    return false;
                }
            }
            return rows;
        })()
    """.trimIndent()

    private fun pageScript(): String = """
        (function() {
            const remove = 'script,style,noscript,svg,canvas,iframe,nav,footer,header,aside,form,[role="navigation"],[role="dialog"],.cookie,.cookies,.advertisement,.ads,.sidebar';
            const normalize = value => (value || '').replace(/\n{3,}/g, '\n\n').replace(/[ \t]{2,}/g, ' ').trim();
            const structured = [];
            const visitJson = value => {
                if (!value) return;
                if (Array.isArray(value)) return value.forEach(visitJson);
                if (typeof value !== 'object') return;
                for (const [key, child] of Object.entries(value)) {
                    if (typeof child === 'string' && ['articlebody', 'text', 'content'].includes(key.toLowerCase()) && child.length > 300) structured.push(child);
                    else if (typeof child === 'object') visitJson(child);
                }
            };
            document.querySelectorAll('script[type="application/ld+json"],script#__NEXT_DATA__').forEach(node => {
                try { visitJson(JSON.parse(node.textContent || '')); } catch (error) {}
            });
            const selectors = ['article', 'main', '[role="main"]', '[itemprop="articleBody"]', '.article-body', '.article-content', '.post-content', '.entry-content', '#article', '#content'];
            const candidates = [];
            for (const selector of selectors) document.querySelectorAll(selector).forEach(node => candidates.push(node));
            if (document.body) candidates.push(document.body);
            let bestText = '';
            let bestScore = -1;
            for (const node of candidates) {
                const clone = node.cloneNode(true);
                clone.querySelectorAll(remove).forEach(child => child.remove());
                const text = normalize(clone.innerText || clone.textContent || '');
                if (!text) continue;
                const links = Array.from(clone.querySelectorAll('a')).reduce((sum, link) => sum + normalize(link.innerText).length, 0);
                const paragraphs = clone.querySelectorAll('p').length;
                const score = text.length + paragraphs * 80 - links * 1.5;
                if (score > bestScore) { bestScore = score; bestText = text; }
            }
            const structuredText = normalize(structured.sort((a, b) => b.length - a.length)[0] || '');
            let extraction = 'webview_main_content';
            if (structuredText.length > bestText.length * 1.15) {
                bestText = structuredText;
                extraction = 'webview_structured_data';
            }
            if (bestText.length < 300) {
                const description = document.querySelector('meta[name="description"],meta[property="og:description"]')?.content || '';
                bestText = normalize(bestText + '\n\n' + description);
            }
            return { title: document.title || '', url: location.href, text: bestText.slice(0, 30000), extraction };
        })()
    """.trimIndent()
    private suspend fun httpReadFallback(url: String): WebPageResult = withContext(Dispatchers.IO) {
        val document = httpGetDocument(url)
        val html = document.body
        val title = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.getOrNull(1)?.htmlToText().orEmpty()
        val mainCandidates = Regex("""<(article|main)\b[^>]*>(.*?)</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html)
            .map { it.groupValues[2] }
            .toList()
        val mainHtml = mainCandidates.maxByOrNull { it.length } ?: html
        val visibleText = htmlToPlainText(mainHtml)
        val structuredText = extractStructuredText(html)
        val description = extractMetaDescription(html)
        val best = listOf(visibleText, structuredText)
            .maxByOrNull { candidate -> contentQuality(candidate) }
            .orEmpty()
            .let { if (it.length < 600 && description.isNotBlank()) "$it\n\n$description".trim() else it }
        val method = when {
            structuredText.isNotBlank() && best.startsWith(structuredText.take(120)) -> "http_structured_data"
            mainCandidates.isNotEmpty() -> "http_main_content"
            else -> "http_full_html"
        }
        WebPageResult(title, document.finalUrl, best.take(30_000), method)
    }

    private fun httpGet(url: String): String = httpGetDocument(url).body

    private fun httpGetDocument(url: String): HttpDocument {
        val userAgents = listOf(DESKTOP_USER_AGENT, MOBILE_USER_AGENT)
        var lastFailure: Throwable? = null
        var lastCode = 0
        for (userAgent in userAgents) {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.7")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
                .header("Cache-Control", "no-cache")
            runCatching {
                val parsed = Uri.parse(url)
                if (!parsed.host.isNullOrBlank()) builder.header("Referer", "${parsed.scheme}://${parsed.host}/")
                httpClient.newCall(builder.build()).execute().use { response ->
                    lastCode = response.code
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    return HttpDocument(response.body?.string().orEmpty(), response.request.url.toString())
                }
            }.onFailure { lastFailure = it }
            if (lastCode !in setOf(401, 403, 406, 429, 503)) break
        }
        throw lastFailure ?: IllegalStateException("HTTP request failed")
    }

    private fun extractStructuredText(html: String): String {
        val candidates = mutableListOf<String>()
        val scriptRegex = Regex(
            """<script\b[^>]*(?:type=["']application/ld\+json["']|id=["']__NEXT_DATA__["'])[^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    val child = value.opt(key)
                    if (child is String && key.lowercase() in STRUCTURED_TEXT_KEYS && child.length > 300) candidates += child
                    else visit(child)
                }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
            }
        }
        scriptRegex.findAll(html).forEach { match ->
            val raw = match.groupValues[1].trim()
            runCatching {
                if (raw.startsWith("[")) visit(JSONArray(raw)) else visit(JSONObject(raw))
            }
        }
        return candidates
            .map { it.htmlToText() }
            .maxByOrNull { contentQuality(it) }
            .orEmpty()
    }

    private fun extractMetaDescription(html: String): String {
        val tags = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).map { it.value }
        for (tag in tags) {
            if (!Regex("""(?:name|property)=["'](?:description|og:description)["']""", RegexOption.IGNORE_CASE).containsMatchIn(tag)) continue
            val content = Regex("""content=["'](.*?)["']""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(tag)?.groupValues?.getOrNull(1)?.htmlToText().orEmpty()
            if (content.isNotBlank()) return content
        }
        return ""
    }

    private fun contentQuality(text: String): Int {
        if (text.isBlank()) return 0
        val sentences = Regex("""[。！？.!?]""").findAll(text).count()
        val lines = text.lineSequence().count { it.trim().length >= 40 }
        return text.length + sentences * 20 + lines * 30
    }
    private fun parseLinksFromHtml(html: String, limit: Int): List<WebSearchResult> {
        val seen = mutableSetOf<String>()
        val anchorRegex = Regex("""<a\b[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return anchorRegex.findAll(html).mapNotNull { match ->
            var url = match.groupValues[1].htmlToText()
            val title = match.groupValues[2].htmlToText()
            if (url.startsWith("/l/?")) {
                Uri.parse("https://duckduckgo.com$url").getQueryParameter("uddg")?.let { url = it }
            }
            Uri.parse(url).getQueryParameter("q")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { url = it }
            Uri.parse(url).getQueryParameter("url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { url = it }
            if (url.startsWith("/url?")) {
                Uri.parse("https://www.google.com$url").getQueryParameter("q")?.let { url = it }
            }
            if (url.startsWith("/link?")) {
                Uri.parse("https://www.baidu.com$url").getQueryParameter("url")?.let { url = it }
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@mapNotNull null
            if (isSearchEngineUrl(url)) return@mapNotNull null
            if (isBlockedUrl(url)) return@mapNotNull null
            if (title.length < 2 || !seen.add(url)) return@mapNotNull null
            WebSearchResult(title = title.take(200), url = url, snippet = "")
        }.take(limit).toList()
    }

    private fun parseBingRss(xml: String, limit: Int): List<WebSearchResult> {
        val itemRegex = Regex("""<item>(.*?)</item>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return itemRegex.findAll(xml).mapNotNull { match ->
            val item = match.groupValues[1]
            val title = item.xmlTag("title").htmlToText()
            val link = item.xmlTag("link").htmlToText()
            val snippet = item.xmlTag("description").htmlToText()
            if (title.isBlank() || !link.startsWith("http")) return@mapNotNull null
            if (isBlockedUrl(link)) return@mapNotNull null
            WebSearchResult(title = title.take(200), url = link, snippet = snippet.take(500))
        }.take(limit).toList()
    }

    private fun isBlockedUrl(url: String): Boolean = blockedHostFor(url) != null

    private fun blockedHostFor(url: String): String? {
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
            .lowercase()
            .trim('.')
        if (host.isBlank()) return null
        return settings.webSearchBlockedHosts().firstOrNull { blocked ->
            if (blocked.startsWith("*.")) {
                val suffix = blocked.removePrefix("*.").trim('.')
                suffix.isNotBlank() && host != suffix && host.endsWith(".$suffix")
            } else {
                host == blocked
            }
        }
    }

    private fun isSearchEngineUrl(url: String): Boolean {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = parsed.host.orEmpty()
        val path = parsed.path.orEmpty()
        return (host.contains("google.") && path.startsWith("/search")) ||
            (host.contains("bing.com") && path.startsWith("/search")) ||
            (host.contains("baidu.com") && path == "/s")
    }

    private fun parseSearchResults(raw: String): List<WebSearchResult> {
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    WebSearchResult(
                        title = item.optString("title"),
                        url = item.optString("url"),
                        snippet = item.optString("snippet"),
                    ),
                )
            }
        }
    }

    private fun parsePage(raw: String): WebPageResult {
        val root = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        return WebPageResult(
            title = root.optString("title"),
            url = root.optString("url"),
            text = root.optString("text"),
            extraction = root.optString("extraction", "webview"),
        )
    }

    private fun htmlToPlainText(html: String): String {
        return html
            .replace(Regex("""<script\b[^>]*>.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("""<style\b[^>]*>.*?</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("""<br\s*/?>|</p>|</div>|</li>|</h[1-6]>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]+>"""), " ")
            .htmlToText()
            .replace(Regex("[ \t]{2,}"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun String.htmlToText(): String {
        return replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.xmlTag(name: String): String {
        return Regex("""<$name[^>]*>(.*?)</$name>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
            .removePrefix("<![CDATA[")
            .removeSuffix("]]>")
    }

    companion object {
        private const val TAG = "LyraWebAgent"
        private const val MIN_RELEVANCE_SCORE = 20
        private const val MAX_RESULTS_PER_HOST = 2
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
        private const val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
        private val STRUCTURED_TEXT_KEYS = setOf("articlebody", "text", "content", "body", "description")
        private val SEARCH_STOP_WORDS = setOf(
            "the", "a", "an", "and", "or", "of", "to", "for", "in", "on", "with", "is", "are", "how", "what",
            "的", "了", "和", "与", "或", "在", "是", "有", "如何", "什么", "相关", "关于", "最新", "内容", "问题", "需要",
        )
        private val TRACKING_QUERY_PARAMS = setOf(
            "utm_source",
            "utm_medium",
            "utm_campaign",
            "utm_term",
            "utm_content",
            "spm",
            "from",
            "fbclid",
            "gclid",
        )
    }
}

private data class HttpDocument(
    val body: String,
    val finalUrl: String,
)

private data class SearchEngine(
    val name: String,
    val url: String,
)
