package com.rdx.cinevood

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class CineVoodProvider : MainAPI() {

    override var mainUrl        = "https://one.1cinevood.watch"
    override var name           = "CineVood"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang           = "hi"
    override val hasMainPage    = true
    override val hasChromecastSupport = true

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/feed/"                                to "Latest",
        "$mainUrl/bollywood/feed/"                      to "Bollywood",
        "$mainUrl/hollywood/feed/"                      to "Hollywood",
        "$mainUrl/tamil/feed/"                          to "Tamil",
        "$mainUrl/telugu/feed/"                         to "Telugu",
        "$mainUrl/malayalam/feed/"                      to "Malayalam",
        "$mainUrl/kannada/feed/"                        to "Kannada",
        "$mainUrl/hindi-dubbed/south-dubbed/feed/"      to "South Dubbed",
        "$mainUrl/web-series/feed/"                     to "Web Series",
        "$mainUrl/tv-shows/feed/"                       to "TV Shows"
    )

    // ══════════════════════════════════════════════════════════════
    //  HOME PAGE — Using RSS Feed
    // ══════════════════════════════════════════════════════════════
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // RSS feed URL with paging
        val url = if (page == 1) request.data
                  else request.data.replace("/feed/", "/feed/?paged=$page")

        try {
            val resp = app.get(url, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/rss+xml, application/xml, text/xml, */*"
            ))
            val text = resp.text

            // Check if blocked
            if (text.contains("Just a moment")) {
                // Try alternative: Google cache
                val items = tryGoogleCache(request.data.replace("/feed/", "/"))
                if (items.isNotEmpty()) {
                    return newHomePageResponse(request.name, items, hasNext = false)
                }

                return newHomePageResponse(
                    request.name,
                    listOf(
                        newMovieSearchResponse(
                            "RSS also blocked by CF. Trying alt methods...",
                            mainUrl, TvType.Movie
                        ) { this.posterUrl = null }
                    )
                )
            }

            // Parse RSS XML
            val items = parseRssFeed(text)

            if (items.isEmpty()) {
                // Debug: show what we got
                val isXml = text.contains("<rss") || text.contains("<channel")
                return newHomePageResponse(
                    request.name,
                    listOf(
                        newMovieSearchResponse(
                            "RSS: xml=$isXml | len=${text.length} | start=${text.take(80)}",
                            mainUrl, TvType.Movie
                        ) { this.posterUrl = null }
                    )
                )
            }

            return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())

        } catch (e: Exception) {
            return newHomePageResponse(
                request.name,
                listOf(
                    newMovieSearchResponse(
                        "ERROR: ${e.message?.take(100)}",
                        mainUrl, TvType.Movie
                    ) { this.posterUrl = null }
                )
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  SEARCH
    // ══════════════════════════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")

        // Try RSS search
        val rssUrl = "$mainUrl/feed/?s=$encoded"
        try {
            val resp = app.get(rssUrl, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/rss+xml, application/xml, text/xml, */*"
            ))
            if (!resp.text.contains("Just a moment")) {
                val items = parseRssFeed(resp.text)
                if (items.isNotEmpty()) return items
            }
        } catch (_: Exception) {}

        // Try API search
        try {
            val apiUrl = "$mainUrl/wp-json/wp/v2/posts?search=$encoded&per_page=20"
            val resp = app.get(apiUrl, headers = mapOf("User-Agent" to USER_AGENT))
            if (!resp.text.contains("Just a moment") && resp.text.trimStart().startsWith("[")) {
                val posts = org.json.JSONArray(resp.text)
                val results = mutableListOf<SearchResponse>()
                for (i in 0 until posts.length()) {
                    val post = posts.getJSONObject(i)
                    val title = decodeHtml(post.getJSONObject("title").getString("rendered")).trim()
                    val link = post.getString("link")
                    if (title.isNotBlank()) {
                        results.add(
                            newMovieSearchResponse(title, link, TvType.Movie) {
                                this.posterUrl = null
                            }
                        )
                    }
                }
                if (results.isNotEmpty()) return results
            }
        } catch (_: Exception) {}

        // Try Google search
        return tryGoogleSearch(query)
    }

    // ══════════════════════════════════════════════════════════════
    //  LOAD
    // ══════════════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse? {
        val postSlug = url.trimEnd('/').substringAfterLast('/')

        // Try API first
        try {
            val apiUrl = "$mainUrl/wp-json/wp/v2/posts?slug=$postSlug&_embed"
            val resp = app.get(apiUrl, headers = mapOf("User-Agent" to USER_AGENT))
            val text = resp.text

            if (!text.contains("Just a moment") && text.trimStart().startsWith("[")) {
                val posts = org.json.JSONArray(text)
                if (posts.length() > 0) {
                    val post = posts.getJSONObject(0)
                    return apiPostToLoadResponse(post, url)
                }
            }
        } catch (_: Exception) {}

        // Try direct HTML
        try {
            val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
            if (!doc.html().contains("Just a moment")) {
                return htmlToLoadResponse(doc, url)
            }
        } catch (_: Exception) {}

        // Fallback: return minimal response so user can still try to get links
        return newMovieLoadResponse(
            postSlug.replace("-", " ").replaceFirstChar { it.uppercase() },
            url, TvType.Movie, url
        ) {
            this.plot = "Could not load details (Cloudflare). Tap to try playing."
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
        val postSlug = data.trimEnd('/').substringAfterLast('/')
        var found = false

        // Try API to get content HTML
        try {
            val apiUrl = "$mainUrl/wp-json/wp/v2/posts?slug=$postSlug"
            val resp = app.get(apiUrl, headers = mapOf("User-Agent" to USER_AGENT))
            val text = resp.text

            if (!text.contains("Just a moment") && text.trimStart().startsWith("[")) {
                val posts = org.json.JSONArray(text)
                if (posts.length() > 0) {
                    val content = posts.getJSONObject(0)
                        .getJSONObject("content")
                        .getString("rendered")
                    val doc = Jsoup.parse(content)
                    found = extractLinks(doc, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) {}

        // Try direct HTML
        if (!found) {
            try {
                val doc = app.get(data, headers = mapOf("User-Agent" to USER_AGENT)).document
                if (!doc.html().contains("Just a moment")) {
                    found = extractLinks(doc, subtitleCallback, callback)
                }
            } catch (_: Exception) {}
        }

        return found
    }

    // ══════════════════════════════════════════════════════════════
    //  RSS PARSER
    // ══════════════════════════════════════════════════════════════
    private fun parseRssFeed(xml: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        try {
            val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
            val items = doc.select("item")

            for (item in items) {
                val title = item.selectFirst("title")?.text()?.trim() ?: continue
                val link = item.selectFirst("link")?.text()?.trim()
                    ?: item.selectFirst("guid")?.text()?.trim()
                    ?: continue

                // Try to get poster from content
                var poster: String? = null
                val content = item.selectFirst("content|encoded")?.text()
                    ?: item.selectFirst("description")?.text()
                if (content != null) {
                    val imgMatch = Regex("""src=["'](https?://[^"']*(?:tmdb|bmscdn|imgbb)[^"']*)["']""")
                        .find(content)
                    poster = imgMatch?.groupValues?.get(1)

                    // Also try any image
                    if (poster == null) {
                        val anyImg = Regex("""src=["'](https?://[^"']+\.(?:jpg|jpeg|png|webp)[^"']*)["']""")
                            .find(content)
                        poster = anyImg?.groupValues?.get(1)
                    }
                }

                // Try media:thumbnail
                if (poster == null) {
                    poster = item.selectFirst("media|thumbnail")?.attr("url")
                        ?: item.selectFirst("media|content")?.attr("url")
                        ?: item.selectFirst("enclosure[type*=image]")?.attr("url")
                }

                val isSeries = title.lowercase().contains("season") ||
                               link.contains("web-series") ||
                               link.contains("tv-shows")

                val result = if (isSeries) {
                    newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                } else {
                    newMovieSearchResponse(title, link, TvType.Movie) {
                        this.posterUrl = poster
                    }
                }
                results.add(result)
            }
        } catch (_: Exception) {}

        return results
    }

    // ══════════════════════════════════════════════════════════════
    //  GOOGLE CACHE / SEARCH FALLBACK
    // ══════════════════════════════════════════════════════════════
    private suspend fun tryGoogleCache(pageUrl: String): List<SearchResponse> {
        try {
            val cacheUrl = "https://webcache.googleusercontent.com/search?q=cache:$pageUrl"
            val doc = app.get(cacheUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
            return doc.select("article.latestPost").mapNotNull { article ->
                val h2Link = article.selectFirst("h2 a[href]") ?: return@mapNotNull null
                val title = h2Link.text().trim().ifBlank { return@mapNotNull null }
                val href = h2Link.attr("href").ifBlank { return@mapNotNull null }
                val poster = article.selectFirst("div.featured-thumbnail img")?.attr("src")
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
    }

    private suspend fun tryGoogleSearch(query: String): List<SearchResponse> {
        try {
            val encoded = java.net.URLEncoder.encode("site:one.1cinevood.watch $query", "UTF-8")
            val url = "https://www.google.com/search?q=$encoded"
            val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
            return doc.select("a[href*=cinevood]").mapNotNull { a ->
                val href = a.attr("href")
                val cleanUrl = Regex("""(https?://[^&]+cinevood[^&]+)""").find(href)
                    ?.groupValues?.get(1) ?: return@mapNotNull null
                if (cleanUrl.contains("/category/") || cleanUrl.contains("/tag/")) return@mapNotNull null
                val title = a.text().trim().ifBlank { return@mapNotNull null }
                newMovieSearchResponse(title, cleanUrl, TvType.Movie) {
                    this.posterUrl = null
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  HTML PARSERS
    // ══════════════════════════════════════════════════════════════
    private suspend fun apiPostToLoadResponse(
        post: org.json.JSONObject,
        originalUrl: String
    ): LoadResponse? {
        val title = decodeHtml(post.getJSONObject("title").getString("rendered")).trim()
        val content = post.getJSONObject("content").getString("rendered")

        var poster: String? = null
        try {
            poster = post.getJSONObject("_embedded")
                .getJSONArray("wp:featuredmedia")
                .getJSONObject(0)
                .getString("source_url")
        } catch (_: Exception) {}

        val contentDoc = Jsoup.parse(content)
        val plot = contentDoc.selectFirst("span#summary")?.text()
            ?.replace("Summary:", "")?.trim()

        val tags = mutableListOf<String>()
        try {
            val terms = post.getJSONObject("_embedded").getJSONArray("wp:term")
            for (i in 0 until terms.length()) {
                val termGroup = terms.getJSONArray(i)
                for (j in 0 until termGroup.length()) {
                    tags.add(termGroup.getJSONObject(j).getString("name"))
                }
            }
        } catch (_: Exception) {}

        val year = Regex("""\((\d{4})\)""").find(title)
            ?.groupValues?.get(1)?.toIntOrNull()

        val isSeries = title.lowercase().contains("season") ||
                       originalUrl.contains("web-series") ||
                       originalUrl.contains("tv-shows")

        return if (isSeries) {
            newTvSeriesLoadResponse(title, originalUrl, TvType.TvSeries,
                listOf(newEpisode(originalUrl) {
                    this.name = "Watch / Download"
                    this.episode = 1; this.season = 1
                })
            ) {
                this.posterUrl = poster; this.plot = plot
                this.year = year; this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, originalUrl, TvType.Movie, originalUrl) {
                this.posterUrl = poster; this.plot = plot
                this.year = year; this.tags = tags
            }
        }
    }

    private suspend fun htmlToLoadResponse(doc: Document, url: String): LoadResponse? {
        val title = doc.selectFirst("h1.title.single-title.entry-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null
        val poster = doc.selectFirst("div.featured-thumbnail img")?.attr("src")
        val plot = doc.selectFirst("div.thecontent p")?.text()?.trim()
        val tags = doc.select("div.thecategory a").map { it.text() }
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val isSeries = title.lowercase().contains("season") ||
                       url.contains("web-series") || url.contains("tv-shows")

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries,
                listOf(newEpisode(url) {
                    this.name = "Watch / Download"
                    this.episode = 1; this.season = 1
                })
            ) {
                this.posterUrl = poster; this.plot = plot
                this.year = year; this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster; this.plot = plot
                this.year = year; this.tags = tags
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  LINK EXTRACTION
    // ══════════════════════════════════════════════════════════════
    private suspend fun extractLinks(
        doc: Document,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank()) {
                try {
                    loadExtractor(src, mainUrl, subtitleCallback, callback)
                    found = true
                } catch (_: Exception) {}
            }
        }

        doc.select("a[class*=maxbutton][href], a[href*=oxxf]").forEach { btn ->
            val href = btn.attr("href").trim()
            if (href.isBlank()) return@forEach
            val qualityText = btn.previousElementSibling()?.text()?.trim()
                ?: btn.parent()?.previousElementSibling()?.text()?.trim()
                ?: btn.text().trim()
            val quality = getQuality(qualityText)

            try {
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
            } catch (_: Exception) {}
        }

        doc.select("a[href*=hubcloud], a[href*=streamtape], a[href*=dood]").forEach { el ->
            val href = el.attr("href").trim()
            if (href.isNotBlank()) {
                try {
                    loadExtractor(href, mainUrl, subtitleCallback, callback)
                    found = true
                } catch (_: Exception) {}
            }
        }

        return found
    }

    // ══════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════
    private suspend fun resolveOxxFile(url: String): String? {
        return try {
            val resp = app.get(url, allowRedirects = true,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl))
            val finalUrl = resp.url
            if (finalUrl.containsAny(".mkv", ".mp4", "hubcloud", "streamtape")) return finalUrl
            resp.document.selectFirst(
                "a[href*=hubcloud], a[href*=streamtape], a[href*=dood], " +
                "a[href*=.mkv], a[href*=.mp4]"
            )?.attr("abs:href") ?: finalUrl
        } catch (_: Exception) { null }
    }

    private fun decodeHtml(html: String): String {
        return Jsoup.parse(html).text()
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
        Qualities.P2160.value -> "4K"
        Qualities.P1080.value -> "1080p"
        Qualities.P720.value  -> "720p"
        Qualities.P480.value  -> "480p"
        else -> fallback.take(50).ifBlank { "Download" }
    }

    private fun String.containsAny(vararg t: String) =
        t.any { this.contains(it, ignoreCase = true) }
}
