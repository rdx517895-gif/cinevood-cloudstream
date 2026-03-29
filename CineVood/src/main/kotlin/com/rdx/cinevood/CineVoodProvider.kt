package com.rdx.cinevood

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
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

        private val API_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept"     to "application/json"
        )

        private val catCache = mutableMapOf<String, Int>()
    }

    private val apiUrl get() = "$mainUrl/wp-json/wp/v2"

    override val mainPage = mainPageOf(
        ""                   to "Latest",
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
        var url = "$apiUrl/posts?per_page=20&page=$page"

        if (slug.isNotBlank()) {
            val catId = getCategoryId(slug)
            if (catId != null) {
                url += "&categories=$catId"
            }
        }

        val items = fetchPosts(url)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ══════════════════════════════════════════════════════════════
    //  SEARCH
    // ══════════════════════════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return fetchPosts("$apiUrl/posts?search=$encoded&per_page=20")
    }

    // ══════════════════════════════════════════════════════════════
    //  LOAD — Movie detail page
    // ══════════════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse? {
        val postSlug = url.trimEnd('/').substringAfterLast('/')

        try {
            val resp = app.get(
                "$apiUrl/posts?slug=$postSlug",
                headers = API_HEADERS
            )
            val text = resp.text
            if (!text.trimStart().startsWith("[")) return null

            val posts = JSONArray(text)
            if (posts.length() == 0) return null

            val post = posts.getJSONObject(0)
            val title = htmlDecode(post.getJSONObject("title").getString("rendered"))

            // Poster from meta.fifu_image_url
            var poster: String? = null
            try {
                poster = post.getJSONObject("meta").getString("fifu_image_url")
                if (poster.isNullOrBlank()) poster = null
            } catch (_: Exception) {}

            // Parse content HTML
            val contentHtml = post.getJSONObject("content").getString("rendered")
            val contentDoc = Jsoup.parse(contentHtml)

            // Fallback poster from content
            if (poster == null) {
                poster = contentDoc.selectFirst("img[src*=tmdb]")?.attr("src")
                    ?: contentDoc.selectFirst("img[src*=bmscdn]")?.attr("src")
                    ?: contentDoc.selectFirst("img[src*=media-amazon]")?.attr("src")
            }

            // Plot
            val plot = contentDoc.selectFirst("span#summary")?.text()
                ?.substringAfter("Summary:")?.substringBefore("Read all")?.trim()
                ?: contentDoc.select("p").find { p ->
                    p.text().contains("Plot:", ignoreCase = true)
                }?.text()?.substringAfter("Plot:")?.trim()

            // Year
            val year = Regex("""\((\d{4})\)""").find(title)
                ?.groupValues?.get(1)?.toIntOrNull()

            // Tags from category IDs
            val tags = mutableListOf<String>()
            try {
                val catIds = post.getJSONArray("categories")
                for (i in 0 until minOf(catIds.length(), 5)) {
                    // We'll just use category names from content
                }
            } catch (_: Exception) {}

            // Genre from content
            val genreMatch = Regex("""Genres:</strong>\s*([^<]+)""").find(contentHtml)
            if (genreMatch != null) {
                genreMatch.groupValues[1].split(",").forEach {
                    tags.add(it.trim())
                }
            }

            // Series detection
            val isSeries = title.lowercase().contains("season") ||
                           url.contains("web-series") ||
                           url.contains("tv-shows")

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
        var found = false

        try {
            val resp = app.get(
                "$apiUrl/posts?slug=$postSlug",
                headers = API_HEADERS
            )
            val text = resp.text
            if (!text.trimStart().startsWith("[")) return false

            val posts = JSONArray(text)
            if (posts.length() == 0) return false

            val contentHtml = posts.getJSONObject(0)
                .getJSONObject("content")
                .getString("rendered")

            val doc = Jsoup.parse(contentHtml)

            // 1 — iframes (vidara.to player)
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.isNotBlank()) {
                    try {
                        loadExtractor(src, mainUrl, subtitleCallback, callback)
                        found = true
                    } catch (_: Exception) {}
                }
            }

            // 2 — Download buttons (maxbutton / oxxfile links)
            doc.select("a[class*=maxbutton][href], a[href*=oxxf]").forEach { btn ->
                val href = btn.attr("href").trim()
                if (href.isBlank()) return@forEach

                // Quality from the <h6> before the button
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

            // 3 — Direct extractor links
            doc.select("a[href*=hubcloud], a[href*=streamtape], a[href*=dood]").forEach { el ->
                val href = el.attr("href").trim()
                if (href.isNotBlank()) {
                    try {
                        loadExtractor(href, mainUrl, subtitleCallback, callback)
                        found = true
                    } catch (_: Exception) {}
                }
            }

        } catch (_: Exception) {}

        return found
    }

    // ══════════════════════════════════════════════════════════════
    //  FETCH POSTS FROM API
    // ══════════════════════════════════════════════════════════════
    private suspend fun fetchPosts(url: String): List<SearchResponse> {
        try {
            val resp = app.get(url, headers = API_HEADERS)
            val text = resp.text

            if (!text.trimStart().startsWith("[")) return emptyList()

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
        val title = htmlDecode(
            post.getJSONObject("title").getString("rendered")
        ).trim()
        if (title.isBlank()) return null

        val link = post.getString("link")

        // Poster from meta.fifu_image_url (FIFU plugin)
        var poster: String? = null
        try {
            val fifuUrl = post.getJSONObject("meta").getString("fifu_image_url")
            if (fifuUrl.isNotBlank()) poster = fifuUrl
        } catch (_: Exception) {}

        // Fallback: find poster in content HTML
        if (poster == null) {
            try {
                val content = post.getJSONObject("content").getString("rendered")
                poster = Regex("""src=["'](https?://image\.tmdb\.org[^"']+)["']""")
                    .find(content)?.groupValues?.get(1)
                if (poster == null) {
                    poster = Regex("""src=["'](https?://[^"']*(?:bmscdn|media-amazon)[^"']+)["']""")
                        .find(content)?.groupValues?.get(1)
                }
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

    // ══════════════════════════════════════════════════════════════
    //  GET CATEGORY ID
    // ══════════════════════════════════════════════════════════════
    private suspend fun getCategoryId(slug: String): Int? {
        catCache[slug]?.let { return it }

        try {
            val resp = app.get(
                "$apiUrl/categories?slug=$slug&per_page=1",
                headers = API_HEADERS
            )
            val text = resp.text
            if (!text.trimStart().startsWith("[")) return null

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

    private fun htmlDecode(html: String): String {
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
