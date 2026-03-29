package com.rdx.cinevood

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

class CineVoodProvider : MainAPI() {

    override var mainUrl        = "https://one.1cinevood.watch"
    override var name           = "CineVood"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang           = "hi"
    override val hasMainPage    = true
    override val hasChromecastSupport = true

    companion object {
        // Mimic a real Chrome browser exactly
        private val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Accept-Encoding" to "gzip, deflate, br",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "Sec-CH-UA" to "\"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\", \"Not-A.Brand\";v=\"8\"",
            "Sec-CH-UA-Mobile" to "?1",
            "Sec-CH-UA-Platform" to "\"Android\""
        )
    }

    private val apiBase get() = "$mainUrl/wp-json/wp/v2"

    override val mainPage = mainPageOf(
        "latest"             to "Latest",
        "bollywood"          to "Bollywood",
        "hollywood"          to "Hollywood",
        "tamil"              to "Tamil",
        "telugu"             to "Telugu",
        "malayalam"          to "Malayalam",
        "kannada"            to "Kannada",
        "south-dubbed"       to "South Dubbed",
        "web-series"         to "Web Series",
        "tv-shows"           to "TV Shows"
    )

    // ══════════════════════════════════════════════════════════════
    //  HOME PAGE
    // ══════════════════════════════════════════════════════════════
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val slug = request.data
        val url = buildApiUrl(slug, page)

        val text = apiGet(url) ?: return newHomePageResponse(request.name, emptyList())

        val items = parsePostsJson(text)
        return newHomePageResponse(request.name, items, hasNext = items.size >= 20)
    }

    // ══════════════════════════════════════════════════════════════
    //  SEARCH
    // ══════════════════════════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val text = apiGet("$apiBase/posts?search=$encoded&per_page=20") ?: return emptyList()
        return parsePostsJson(text)
    }

    // ══════════════════════════════════════════════════════════════
    //  LOAD
    // ══════════════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse? {
        val postSlug = url.trimEnd('/').substringAfterLast('/')
        val text = apiGet("$apiBase/posts?slug=$postSlug") ?: return null

        try {
            val posts = JSONArray(text)
            if (posts.length() == 0) return null
            val post = posts.getJSONObject(0)

            val title = htmlDecode(post.getJSONObject("title").getString("rendered"))
            val contentHtml = post.getJSONObject("content").getString("rendered")
            val contentDoc = Jsoup.parse(contentHtml)

            // Poster
            var poster = getPostPoster(post, contentDoc)

            // Plot
            val plot = contentDoc.selectFirst("span#summary")?.text()
                ?.substringAfter("Summary:")?.substringBefore("Read all")?.trim()

            // Year
            val year = Regex("""\((\d{4})\)""").find(title)
                ?.groupValues?.get(1)?.toIntOrNull()

            // Tags
            val tags = mutableListOf<String>()
            Regex("""Genres:</strong>\s*([^<]+)""").find(contentHtml)?.let {
                it.groupValues[1].split(",").forEach { g -> tags.add(g.trim()) }
            }

            val isSeries = title.lowercase().contains("season") ||
                           url.contains("web-series") || url.contains("tv-shows")

            return if (isSeries) {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries,
                    listOf(newEpisode(url) {
                        this.name = "Watch / Download"
                        this.episode = 1
                        this.season = 1
                    })
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
        } catch (_: Exception) {
            return null
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
        val text = apiGet("$apiBase/posts?slug=$postSlug") ?: return false

        try {
            val posts = JSONArray(text)
            if (posts.length() == 0) return false

            val contentHtml = posts.getJSONObject(0)
                .getJSONObject("content").getString("rendered")
            val doc = Jsoup.parse(contentHtml)

            var found = false

            // Iframes (vidara.to player)
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.isNotBlank()) {
                    try {
                        loadExtractor(src, mainUrl, subtitleCallback, callback)
                        found = true
                    } catch (_: Exception) {}
                }
            }

            // Download buttons
            doc.select("a[class*=maxbutton][href], a[href*=oxxf]").forEach { btn ->
                val href = btn.attr("href").trim()
                if (href.isBlank()) return@forEach

                val qualityText = btn.previousElementSibling()?.text()?.trim()
                    ?: btn.parent()?.previousElementSibling()?.text()?.trim()
                    ?: ""
                val quality = getQuality(qualityText)

                try {
                    val resolved = resolveLink(href)
                    if (!resolved.isNullOrBlank()) {
                        if (resolved.containsAny("hubcloud", "streamtape", "dood", "vidara")) {
                            loadExtractor(resolved, mainUrl, subtitleCallback, callback)
                        } else {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name ${qualityLabel(quality, qualityText)}",
                                    url = resolved,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mainUrl
                                    this.quality = quality
                                    this.headers = HEADERS
                                }
                            )
                        }
                        found = true
                    }
                } catch (_: Exception) {}
            }

            // Direct links
            doc.select("a[href*=hubcloud], a[href*=streamtape], a[href*=dood]").forEach {
                try {
                    loadExtractor(it.attr("href").trim(), mainUrl, subtitleCallback, callback)
                    found = true
                } catch (_: Exception) {}
            }

            return found
        } catch (_: Exception) {
            return false
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  API REQUEST — tries multiple approaches
    // ══════════════════════════════════════════════════════════════
    private suspend fun apiGet(url: String): String? {
        // Attempt 1: JSON accept header
        try {
            val resp = app.get(url, headers = mapOf(
                "User-Agent" to HEADERS["User-Agent"]!!,
                "Accept" to "application/json"
            ))
            val text = resp.text
            if (text.trimStart().startsWith("[") || text.trimStart().startsWith("{")) {
                return text
            }
        } catch (_: Exception) {}

        // Attempt 2: Browser-like headers
        try {
            val resp = app.get(url, headers = HEADERS)
            val text = resp.text
            if (text.trimStart().startsWith("[") || text.trimStart().startsWith("{")) {
                return text
            }
        } catch (_: Exception) {}

        // Attempt 3: Minimal headers
        try {
            val resp = app.get(url, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
            ))
            val text = resp.text
            if (text.trimStart().startsWith("[") || text.trimStart().startsWith("{")) {
                return text
            }
        } catch (_: Exception) {}

        // Attempt 4: Pretend to be curl
        try {
            val resp = app.get(url, headers = mapOf(
                "User-Agent" to "curl/8.0",
                "Accept" to "*/*"
            ))
            val text = resp.text
            if (text.trimStart().startsWith("[") || text.trimStart().startsWith("{")) {
                return text
            }
        } catch (_: Exception) {}

        return null
    }

    // ══════════════════════════════════════════════════════════════
    //  BUILD API URL
    // ══════════════════════════════════════════════════════════════
    private suspend fun buildApiUrl(slug: String, page: Int): String {
        var url = "$apiBase/posts?per_page=20&page=$page"
        if (slug.isNotBlank() && slug != "latest") {
            val catId = getCategoryId(slug)
            if (catId != null) url += "&categories=$catId"
        }
        return url
    }

    // ══════════════════════════════════════════════════════════════
    //  PARSE JSON POSTS
    // ══════════════════════════════════════════════════════════════
    private fun parsePostsJson(text: String): List<SearchResponse> {
        try {
            val posts = JSONArray(text)
            val results = mutableListOf<SearchResponse>()
            for (i in 0 until posts.length()) {
                try {
                    val post = posts.getJSONObject(i)
                    val title = htmlDecode(post.getJSONObject("title").getString("rendered")).trim()
                    if (title.isBlank()) continue

                    val link = post.getString("link")

                    // Poster
                    var poster: String? = null
                    try {
                        val fifu = post.getJSONObject("meta").getString("fifu_image_url")
                        if (fifu.isNotBlank()) poster = fifu
                    } catch (_: Exception) {}

                    if (poster == null) {
                        try {
                            val content = post.getJSONObject("content").getString("rendered")
                            poster = Regex("""src=["'](https?://image\.tmdb\.org[^"']+)["']""")
                                .find(content)?.groupValues?.get(1)
                            if (poster == null) {
                                poster = Regex("""src=["'](https?://[^"']*(?:bmscdn|media-amazon)[^"']+\.(?:jpg|png|webp)[^"']*)["']""")
                                    .find(content)?.groupValues?.get(1)
                            }
                        } catch (_: Exception) {}
                    }

                    val isSeries = title.lowercase().contains("season") ||
                                   link.contains("web-series") || link.contains("tv-shows")

                    results.add(
                        if (isSeries) {
                            newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                                this.posterUrl = poster
                            }
                        } else {
                            newMovieSearchResponse(title, link, TvType.Movie) {
                                this.posterUrl = poster
                            }
                        }
                    )
                } catch (_: Exception) { continue }
            }
            return results
        } catch (_: Exception) {
            return emptyList()
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  CATEGORY ID
    // ══════════════════════════════════════════════════════════════
    private suspend fun getCategoryId(slug: String): Int? {
        catCache[slug]?.let { return it }
        val text = apiGet("$apiBase/categories?slug=$slug&per_page=1") ?: return null
        try {
            val cats = JSONArray(text)
            if (cats.length() > 0) {
                val id = cats.getJSONObject(0).getInt("id")
                catCache[slug] = id
                return id
            }
        } catch (_: Exception) {}
        return null
    }

    // ══════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════
    private fun getPostPoster(post: JSONObject, contentDoc: org.jsoup.nodes.Document): String? {
        try {
            val fifu = post.getJSONObject("meta").getString("fifu_image_url")
            if (fifu.isNotBlank()) return fifu
        } catch (_: Exception) {}

        return contentDoc.selectFirst("img[src*=tmdb]")?.attr("src")
            ?: contentDoc.selectFirst("img[src*=bmscdn]")?.attr("src")
            ?: contentDoc.selectFirst("img[src*=media-amazon]")?.attr("src")
    }

    private suspend fun resolveLink(url: String): String? {
        return try {
            val resp = app.get(url, allowRedirects = true, headers = HEADERS)
            val finalUrl = resp.url
            if (finalUrl.containsAny(".mkv", ".mp4", "hubcloud", "streamtape")) return finalUrl
            resp.document.selectFirst(
                "a[href*=hubcloud], a[href*=streamtape], a[href*=dood], a[href*=.mkv], a[href*=.mp4]"
            )?.attr("abs:href") ?: finalUrl
        } catch (_: Exception) { null }
    }

    private fun htmlDecode(html: String) = Jsoup.parse(html).text()

    private fun getQuality(text: String): Int {
        val t = text.lowercase()
        return when {
            "2160" in t || "4k" in t -> Qualities.P2160.value
            "1080" in t -> Qualities.P1080.value
            "720" in t -> Qualities.P720.value
            "480" in t -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    private fun qualityLabel(q: Int, fallback: String) = when (q) {
        Qualities.P2160.value -> "4K"
        Qualities.P1080.value -> "1080p"
        Qualities.P720.value -> "720p"
        Qualities.P480.value -> "480p"
        else -> fallback.take(50).ifBlank { "Download" }
    }

    private fun String.containsAny(vararg t: String) =
        t.any { this.contains(it, ignoreCase = true) }
}
