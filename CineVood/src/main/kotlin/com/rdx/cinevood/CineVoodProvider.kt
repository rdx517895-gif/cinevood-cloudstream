package com.rdx.cinevood

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

class CineVoodProvider : MainAPI() {

    override var mainUrl        = "https://one.1cinevood.watch"
    override var name           = "CineVood"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang           = "hi"
    override val hasMainPage    = true
    override val hasChromecastSupport = true

    override val mainPage = mainPageOf(
        "$mainUrl/"                                to "Latest",
        "$mainUrl/bollywood/"                      to "Bollywood",
        "$mainUrl/hollywood/"                      to "Hollywood",
        "$mainUrl/tamil/"                          to "Tamil",
        "$mainUrl/telugu/"                         to "Telugu",
        "$mainUrl/malayalam/"                      to "Malayalam",
        "$mainUrl/kannada/"                        to "Kannada",
        "$mainUrl/hindi-dubbed/south-dubbed/"      to "South Dubbed",
        "$mainUrl/web-series/"                     to "Web Series",
        "$mainUrl/tv-shows/"                       to "TV Shows"
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

        private val defaultHeaders = mapOf(
            "User-Agent"      to USER_AGENT,
            "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5"
        )

        // Store cookies after first WebView solve
        private var cfCookies: Map<String, String> = emptyMap()
    }

    // ══════════════════════════════════════════════════════════════
    //  ★ CORE: Fetch page with Cloudflare bypass
    // ══════════════════════════════════════════════════════════════
    private suspend fun cfGet(url: String): NiceResponse {
        // First try: normal request with stored cookies
        if (cfCookies.isNotEmpty()) {
            val resp = app.get(
                url,
                headers = defaultHeaders,
                cookies = cfCookies
            )
            // Check if we got real content (not CF challenge)
            if (!isCloudflarePage(resp)) {
                return resp
            }
        }

        // Normal request without cookies
        val resp = app.get(url, headers = defaultHeaders)
        if (!isCloudflarePage(resp)) {
            return resp
        }

        // ★ Cloudflare detected — use WebView to solve
        val webViewResp = app.get(
            url,
            headers = defaultHeaders,
            interceptor = WebViewResolver(
                Regex("""one\.1cinevood\.watch""")
            )
        )

        // Store the cookies from WebView for future requests
        try {
            val cookieString = webViewResp.headers["set-cookie"] ?: ""
            if (cookieString.contains("cf_clearance")) {
                val cookies = mutableMapOf<String, String>()
                cookieString.split(";").forEach { part ->
                    val trimmed = part.trim()
                    if (trimmed.contains("=")) {
                        val key = trimmed.substringBefore("=").trim()
                        val value = trimmed.substringAfter("=").trim()
                        if (key.isNotBlank() && key != "path" && key != "expires"
                            && key != "domain" && key != "SameSite"
                            && key != "Secure" && key != "HttpOnly"
                        ) {
                            cookies[key] = value
                        }
                    }
                }
                if (cookies.isNotEmpty()) {
                    cfCookies = cookies
                }
            }
        } catch (_: Exception) {}

        return webViewResp
    }

    private fun isCloudflarePage(resp: NiceResponse): Boolean {
        val text = resp.text
        return text.contains("Just a moment") ||
               text.contains("cf-browser-verification") ||
               text.contains("challenge-platform") ||
               (text.length < 5000 && !text.contains("latestPost"))
    }

    // ══════════════════════════════════════════════════════════════
    //  HOME PAGE
    // ══════════════════════════════════════════════════════════════
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data
                  else "${request.data}page/$page/"

        val doc = cfGet(url).document

        // Primary: article.latestPost
        var items = doc.select("article.latestPost").mapNotNull { articleToResult(it) }

        // Fallback: all articles
        if (items.isEmpty()) {
            items = doc.select("article").mapNotNull { articleToResult(it) }
        }

        // Fallback: swiper trending items
        if (items.isEmpty()) {
            items = doc.select("div.swiper-slide div.box-in a[href][title]").mapNotNull {
                swiperToResult(it)
            }
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ══════════════════════════════════════════════════════════════
    //  SEARCH
    // ══════════════════════════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = cfGet("$mainUrl/?s=${query.encodeUri()}").document

        val results = doc.select("article.latestPost").mapNotNull { articleToResult(it) }
        if (results.isNotEmpty()) return results

        return doc.select("article").mapNotNull { articleToResult(it) }
    }

    // ══════════════════════════════════════════════════════════════
    //  Parse <article class="latestPost"> → SearchResponse
    //
    //  <article class="latestPost excerpt">
    //    <a href="URL" title="TITLE" class="post-image">
    //      <div class="featured-thumbnail">
    //        <img src="POSTER">
    //      </div>
    //    </a>
    //    <header>
    //      <h2 class="title front-view-title">
    //        <a href="URL" title="TITLE">MOVIE NAME</a>  ← text here
    //      </h2>
    //    </header>
    //  </article>
    // ══════════════════════════════════════════════════════════════
    private fun articleToResult(article: Element): SearchResponse? {
        val h2Link = article.selectFirst("h2 a[href]") ?: return null

        val title = h2Link.text().trim()
            .ifBlank { h2Link.attr("title").trim() }
            .ifBlank { return null }

        val href = fixUrlNull(h2Link.attr("href")) ?: return null
        if (href.contains("/category/") || href.contains("/tag/")) return null

        val poster = fixUrlNull(
            article.selectFirst("div.featured-thumbnail img")?.attr("src")
                ?: article.selectFirst("img")?.attr("src")
        )

        val isSeries = title.lowercase().contains("season")
                || href.contains("web-series")
                || href.contains("tv-shows")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    private fun swiperToResult(a: Element): SearchResponse? {
        val title = a.attr("title").trim().ifBlank { return null }
        val href = fixUrlNull(a.attr("href")) ?: return null
        val poster = fixUrlNull(a.selectFirst("img")?.attr("src"))

        val isSeries = title.lowercase().contains("season")
                || href.contains("web-series")
                || href.contains("tv-shows")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  LOAD — detail page
    // ══════════════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse? {
        val doc = cfGet(url).document

        val title = doc.selectFirst("h1.title.single-title.entry-title")?.text()?.trim()
            ?: doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(
            doc.selectFirst("div.featured-thumbnail img")?.attr("src")
                ?: doc.selectFirst("div.thecontent img[src*=tmdb]")?.attr("src")
                ?: doc.selectFirst("div.thecontent img[src*=image.tmdb]")?.attr("src")
        )

        val plot = doc.selectFirst("span#summary")?.ownText()
            ?.substringAfter("Summary:")?.trim()
            ?.substringBefore("Read all")?.trim()

        val tags = doc.select("div.thecategory a").map { it.text() }

        val year = Regex("""\((\d{4})\)""").find(title)
            ?.groupValues?.get(1)?.toIntOrNull()

        val isSeries = tags.any {
            it.lowercase().run {
                contains("web series") || contains("tv show")
            }
        } || title.lowercase().contains("season")
          || url.contains("web-series")
          || url.contains("tv-shows")

        return if (isSeries) {
            newTvSeriesLoadResponse(
                title, url, TvType.TvSeries,
                doc.parseEpisodes(url)
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  LOAD LINKS
    // ══════════════════════════════════════════════════════════════
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = cfGet(data).document
        var found = false

        // 1 — All iframes (vidara.to etc.)
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank()) {
                runCatching {
                    loadExtractor(fixUrl(src), mainUrl, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // 2 — Download buttons (class="maxbutton")
        doc.select("a.maxbutton[href]").forEach { btn ->
            val href = btn.attr("href").trim()
            if (href.isBlank()) return@forEach

            val qualityText = btn.previousElementSibling()?.text()?.trim()
                ?: btn.parent()?.previousElementSibling()?.text()?.trim()
                ?: ""
            val quality = getQuality(qualityText)

            runCatching {
                val resolved = resolveOxxFile(href)
                if (!resolved.isNullOrBlank()) {
                    if (resolved.containsAny("hubcloud", "streamtape", "dood", "vidara")) {
                        loadExtractor(resolved, mainUrl, subtitleCallback, callback)
                    } else {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name   = "$name ${getQualityLabel(quality, qualityText)}",
                                url    = resolved,
                                type   = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = quality
                                this.headers = mapOf("User-Agent" to USER_AGENT)
                            }
                        )
                    }
                    found = true
                }
            }
        }

        // 3 — Fallback direct links
        doc.select(
            "a[href*=hubcloud], a[href*=streamtape], " +
            "a[href*=dood], a[href*=doodstream]"
        ).forEach {
            runCatching {
                loadExtractor(it.attr("href").trim(), mainUrl, subtitleCallback, callback)
                found = true
            }
        }

        return found
    }

    // ══════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════

    private suspend fun resolveOxxFile(url: String): String? {
        return runCatching {
            val resp = app.get(
                url,
                allowRedirects = true,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)
            )
            val finalUrl = resp.url
            if (finalUrl.containsAny(".mkv", ".mp4")) return finalUrl

            resp.document.selectFirst(
                "a[href*=hubcloud], a[href*=streamtape], " +
                "a[href*=dood], a[href*=vidara], " +
                "a[href*=.mkv], a[href*=.mp4], " +
                "source[src*=.mkv], source[src*=.mp4]"
            )?.let {
                it.attr("abs:href").ifBlank { it.attr("abs:src") }
            } ?: finalUrl.takeIf {
                it.containsAny("hubcloud", "streamtape", ".mkv", ".mp4")
            }
        }.getOrNull()
    }

    private fun org.jsoup.nodes.Document.parseEpisodes(
        pageUrl: String
    ): List<Episode> {
        val headings = select("h2, h3, h4, strong").filter {
            it.text().contains(Regex("""(?i)(episode|ep\.?\s*\d|e\d{2})"""))
        }
        if (headings.isEmpty()) {
            return listOf(newEpisode(pageUrl) {
                this.name = "Watch / Download"
                this.episode = 1; this.season = 1
            })
        }
        return headings.mapIndexed { idx, h ->
            val epNum = Regex("""\d+""").find(h.text())
                ?.value?.toIntOrNull() ?: (idx + 1)
            newEpisode(pageUrl) {
                this.name = h.text().trim()
                this.episode = epNum; this.season = 1
            }
        }
    }

    private fun getQuality(text: String): Int {
        val t = text.lowercase()
        return when {
            "2160" in t || "4k" in t -> Qualities.P2160.value
            "1080" in t              -> Qualities.P1080.value
            "720"  in t              -> Qualities.P720.value
            "480"  in t              -> Qualities.P480.value
            else                     -> Qualities.Unknown.value
        }
    }

    private fun getQualityLabel(q: Int, fallback: String) = when (q) {
        Qualities.P2160.value -> "4K 2160p"
        Qualities.P1080.value -> "1080p"
        Qualities.P720.value  -> "720p"
        Qualities.P480.value  -> "480p"
        else -> fallback.take(50).ifBlank { "Download" }
    }

    private fun String.containsAny(vararg t: String) =
        t.any { this.contains(it, ignoreCase = true) }

    private fun String.encodeUri(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
