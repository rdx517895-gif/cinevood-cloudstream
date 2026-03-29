package com.rdx.cinevood

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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

        private val DEFAULT_HEADERS = mapOf(
            "User-Agent"      to USER_AGENT,
            "Accept"          to "*/*",
            "Accept-Language" to "en-US,en;q=0.5"
        )

        private val catCache = mutableMapOf<String, Int>()
    }

    private val apiUrl get() = "$mainUrl/wp-json/wp/v2"

    override val mainPage = mainPageOf(
        ""                        to "Latest",
        "bollywood"               to "Bollywood",
        "hollywood"               to "Hollywood",
        "tamil"                   to "Tamil",
        "telugu"                  to "Telugu",
        "malayalam"               to "Malayalam",
        "kannada"                 to "Kannada",
        "south-dubbed"            to "South Dubbed",
        "web-series"              to "Web Series",
        "tv-shows"                to "TV Shows"
    )

    // ══════════════════════════════════════════════════════════════
    //  HOME PAGE
    // ══════════════════════════════════════════════════════════════
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val slug = request.data

        var url = "$apiUrl/posts?per_page=20&page=$page&_embed"

        if (slug.isNotBlank()) {
            val catId = getCategoryId(slug)
            if (catId != null) {
                url += "&categories=$catId"
            }
        }

        val items = fetchPosts(url)

        // DEBUG - remove after it works
        if (items.isEmpty()) {
            try {
                val resp = app.get(url, headers = DEFAULT_HEADERS)
                val text = resp.text
                val isCF = text.contains("Just a moment")
                val isJson = text.trimStart().startsWith("[") || text.trimStart().startsWith("{")
                val debugMsg = "API: cf=$isCF | json=$isJson | len=${text.length} | start=${text.take(50)}"

                return newHomePageResponse(
                    request.name,
                    listOf(
                        newMovieSearchResponse(debugMsg, mainUrl, TvType.Movie) {
                            this.posterUrl = null
                        }
                    )
                )
            } catch (e: Exception) {
                return newHomePageResponse(
                    request.name,
                    listOf(
                        newMovieSearchResponse(
                            "ERROR: ${e.message?.take(100)}",
                            mainUrl,
                            TvType.Movie
                        ) { this.posterUrl = null }
                    )
                )
            }
        }
        // END DEBUG

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ══════════════════════════════════════════════════════════════
    //  SEARCH
    // ══════════════════════════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$apiUrl/posts?search=$encoded&per_page=20&_embed"
        return fetchPosts(url)
    }

    // ══════════════════════════════════════════════════════════════
    //  LOAD
    // ══════════════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse? {
        val postSlug = url.trimEnd('/').substringAfterLast('/')

        val apiResult = loadFromApi(postSlug, url)
        if (apiResult != null) return apiResult

        try {
            val doc = app.get(url, headers = DEFAULT_HEADERS).document
            if (!doc.html().contains("Just a moment")) {
                return loadFromHtml(doc, url)
            }
        } catch (_: Exception) {}

        return null
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

        val contentHtml = getPostContent(postSlug)
        if (contentHtml != null) {
            val doc = Jsoup.parse(contentHtml)
            found = extractLinks(doc, subtitleCallback, callback)
        }

        if (!found) {
            try {
                val doc = app.get(data, headers = DEFAULT_HEADERS).document
                if (!doc.html().contains("Just a moment")) {
                    found = extractLinks(doc, subtitleCallback, callback)
                }
            } catch (_: Exception) {}
        }

        return found
    }

    // ══════════════════════════════════════════════════════════════
    //  API HELPERS
    // ══════════════════════════════════════════════════════════════

    private suspend fun fetchPosts(url: String): List<SearchResponse> {
        try {
            val resp = app.get(url, headers = DEFAULT_HEADERS)
            val text = resp.text

            if (text.contains("Just a moment") || !text.trimStart().startsWith("[")) {
                return emptyList()
            }

            val posts = JSONArray(text)
            val results = mutableListOf<SearchResponse>()

            for (i in 0 until posts.length()) {
                try {
                    val post = posts.getJSONObject(i)
                    val result = postToSearchResponse(post) ?: continue
                    results.add(result)
                } catch (_: Exception) { continue }
            }

            return results
        } catch (_: Exception) {
            return emptyList()
        }
    }

    private fun postToSearchResponse(post: JSONObject): SearchResponse? {
        val title = decodeHtml(
            post.getJSONObject("title").getString("rendered")
        ).trim()
        if (title.isBlank()) return null

        val link = post.getString("link")

        var poster: String? = null
        try {
            val embedded = post.getJSONObject("_embedded")
            val media = embedded.getJSONArray("wp:featuredmedia")
            if (media.length() > 0) {
                poster = media.getJSONObject(0).getString("source_url")
            }
        } catch (_: Exception) {}

        if (poster == null) {
            try {
                val content = post.getJSONObject("content").getString("rendered")
                val match = Regex("""src="(https?://[^"]*(?:tmdb|bmscdn)[^"]*)"""""").find(content)
                poster = match?.groupValues?.get(1)
            } catch (_: Exception) {}
        }

        val isSeries = title.lowercase().contains("season") ||
                       link.contains("web-series") ||
                       link.contains("tv-shows")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    private suspend fun getCategoryId(slug: String): Int? {
        catCache[slug]?.let { return it }

        try {
            val resp = app.get(
                "$apiUrl/categories?slug=$slug",
                headers = DEFAULT_HEADERS
            )
            val text = resp.text
            if (text.contains("Just a moment") || !text.trimStart().startsWith("[")) {
                return null
            }

            val cats = JSONArray(text)
            if (cats.length() > 0) {
                val id = cats.getJSONObject(0).getInt("id")
                catCache[slug] = id
                return id
            }
        } catch (_: Exception) {}

        return null
    }

    private suspend fun loadFromApi(slug: String, originalUrl: String): LoadResponse? {
        try {
            val resp = app.get(
                "$apiUrl/posts?slug=$slug&_embed",
                headers = DEFAULT_HEADERS
            )
            val text = resp.text
            if (text.contains("Just a moment") || !text.trimStart().startsWith("[")) {
                return null
            }

            val posts = JSONArray(text)
            if (posts.length() == 0) return null

            val post = posts.getJSONObject(0)
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
                ?: contentDoc.select("p").find { p ->
                    p.text().contains("Plot:", ignoreCase = true)
                }?.text()?.substringAfter("Plot:")?.trim()

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

            val isSeries = tags.any { tag ->
                tag.lowercase().let {
                    it.contains("web series") || it.contains("tv show")
                }
            } || title.lowercase().contains("season") ||
              originalUrl.contains("web-series") ||
              originalUrl.contains("tv-shows")

            return if (isSeries) {
                newTvSeriesLoadResponse(
                    title, originalUrl, TvType.TvSeries,
                    listOf(newEpisode(originalUrl) {
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
                newMovieLoadResponse(title, originalUrl, TvType.Movie, originalUrl) {
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

    private suspend fun getPostContent(slug: String): String? {
        try {
            val resp = app.get(
                "$apiUrl/posts?slug=$slug",
                headers = DEFAULT_HEADERS
            )
            val text = resp.text
            if (text.contains("Just a moment") || !text.trimStart().startsWith("[")) {
                return null
            }
            val posts = JSONArray(text)
            if (posts.length() == 0) return null
            return posts.getJSONObject(0)
                .getJSONObject("content")
                .getString("rendered")
        } catch (_: Exception) {
            return null
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  HTML FALLBACK
    // ══════════════════════════════════════════════════════════════

    private suspend fun loadFromHtml(doc: Document, url: String): LoadResponse? {
        val title = doc.selectFirst("h1.title.single-title.entry-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = doc.selectFirst("div.featured-thumbnail img")?.attr("src")
            ?: doc.selectFirst("img[src*=tmdb]")?.attr("src")

        val plot = doc.selectFirst("div.thecontent p")?.text()?.trim()
        val tags = doc.select("div.thecategory a").map { it.text() }
        val year = Regex("""\((\d{4})\)""").find(title)
            ?.groupValues?.get(1)?.toIntOrNull()

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

        // 1 — iframes
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank()) {
                try {
                    loadExtractor(src, mainUrl, subtitleCallback, callback)
                    found = true
                } catch (_: Exception) {}
            }
        }

        // 2 — Download buttons
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

        // 3 — Direct links
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
            if (finalUrl.containsAny(".mkv", ".mp4", "hubcloud", "streamtape")) {
                return finalUrl
            }
            resp.document.selectFirst(
                "a[href*=hubcloud], a[href*=streamtape], a[href*=dood], " +
                "a[href*=.mkv], a[href*=.mp4]"
            )?.attr("abs:href") ?: finalUrl
        } catch (_: Exception) { null }
    }

    private fun decodeHtml(html: String): String {
        return html
            .replace("&#8217;", "'")
            .replace("&#8216;", "'")
            .replace("&#8211;", "–")
            .replace("&#8212;", "—")
            .replace("&#038;", "&")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#8230;", "…")
            .replace("&#039;", "'")
            .replace(Regex("&#(\\d+);")) { mr ->
                val code = mr.groupValues[1].toIntOrNull()
                if (code != null) String(Character.toChars(code)) else mr.value
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
        Qualities.P2160.value -> "4K"
        Qualities.P1080.value -> "1080p"
        Qualities.P720.value  -> "720p"
        Qualities.P480.value  -> "480p"
        else -> fallback.take(50).ifBlank { "Download" }
    }

    private fun String.containsAny(vararg t: String) =
        t.any { this.contains(it, ignoreCase = true) }
}
