package com.rdx.cinevood

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

class CineVoodProvider : MainAPI() {

    override var mainUrl              = "https://one.1cinevood.watch"
    override var name                 = "CineVood"
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)
    override var lang                 = "hi"
    override val hasMainPage          = true
    override val hasChromecastSupport = true

    override val mainPage = mainPageOf(
        "$mainUrl/"                            to "Latest",
        "$mainUrl/bollywood/"                  to "Bollywood",
        "$mainUrl/hollywood/"                  to "Hollywood",
        "$mainUrl/tamil/"                      to "Tamil",
        "$mainUrl/telugu/"                     to "Telugu",
        "$mainUrl/malayalam/"                  to "Malayalam",
        "$mainUrl/kannada/"                    to "Kannada",
        "$mainUrl/hindi-dubbed/south-dubbed/"  to "South Dubbed",
        "$mainUrl/web-series/"                 to "Web Series",
        "$mainUrl/tv-shows/"                   to "TV Shows"
    )

    // ── Shared headers ────────────────────────────────────────────────────
    private val defaultHeaders = mapOf(
        "User-Agent"      to USER_AGENT,
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    // ══════════════════════════════════════════════════════════════════════
    //  MAIN PAGE
    // ══════════════════════════════════════════════════════════════════════
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data
                  else "${request.data}page/$page/"
        val doc   = app.get(url, timeout = 120, headers = defaultHeaders).document
        val items = doc.parsePostList()
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SEARCH
    // ══════════════════════════════════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/?s=${query.encodeUri()}",
            timeout = 120,
            headers = defaultHeaders
        ).document
        return doc.parsePostList()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  LOAD (movie / series detail page)
    // ══════════════════════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, timeout = 120, headers = defaultHeaders).document

        val title = doc.selectFirst(
            "h1.entry-title, h1.title.single-title, h1.title, h1"
        )?.text()?.trim() ?: return null

        val poster = doc.selectFirst(
            ".post-thumbnail img, .featured-thumbnail img, " +
            "div.thecontent img, article img"
        )?.getImgUrl()

        val plot = doc.selectFirst(
            "div.thecontent p, .entry-content p"
        )?.text()?.trim()

        val tags = doc.select(
            "div.thecategory a, .post-categories a, a[rel=tag]"
        ).map { it.text() }

        val year = Regex("""\b(20\d{2})\b""").find(title)
                       ?.groupValues?.get(1)?.toIntOrNull()

        val isSeries = tags.any {
            it.lowercase().run { contains("web series") || contains("tv show") }
        } || title.lowercase().contains("season")
          || title.contains(Regex("""S\d{2}"""))
          || url.contains("web-series")
          || url.contains("tv-shows")

        return if (isSeries) {
            newTvSeriesLoadResponse(
                title, url, TvType.TvSeries, doc.parseEpisodes(url)
            ) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.tags      = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.tags      = tags
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  LINK EXTRACTION
    // ══════════════════════════════════════════════════════════════════════
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(
            data, timeout = 120, headers = defaultHeaders
        ).document

        var found = false

        // iframes
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").trim()
            if (src.isNotBlank()) {
                try { loadExtractor(src, mainUrl, subtitleCallback, callback); found = true }
                catch (_: Exception) {}
            }
        }

        // OxxFile download links
        doc.select("a[href*=oxxfile]").forEach { el ->
            try {
                val streamUrl = resolveOxxFile(el.attr("abs:href").trim())
                if (!streamUrl.isNullOrBlank()) {
                    if (streamUrl.containsAny("hubcloud","streamtape","vidnest","dood")) {
                        loadExtractor(streamUrl, mainUrl, subtitleCallback, callback)
                    } else {
                        val label   = el.closestLabel()
                        val quality = resolveQuality(label)
                        callback(
                            newExtractorLink(
                                source = name,
                                name   = "$name ${qualityLabel(quality, label)}",
                                url    = streamUrl,
                                type   = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = quality
                                this.headers = defaultHeaders
                            }
                        )
                    }
                    found = true
                }
            } catch (_: Exception) {}
        }

        // HubCloud / StreamTape / DoodStream
        doc.select(
            "a[href*=hubcloud], a[href*=streamtape], " +
            "a[href*=dood], a[href*=doodstream]"
        ).forEach { el ->
            try {
                loadExtractor(el.attr("abs:href").trim(), mainUrl, subtitleCallback, callback)
                found = true
            } catch (_: Exception) {}
        }

        return found
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ★ FIXED — parsePostList with multi‑strategy selectors ★
    // ══════════════════════════════════════════════════════════════════════
    private fun Document.parsePostList(): List<SearchResponse> {

        /* ---------- scope to main content area ---------- */
        val content: Element = selectFirst(
            "#content, main, .site-content, .content-area, " +
            ".post-listing, .recent-movies, .gridlove-posts, " +
            "#content_box, .blog-items, #blog"
        ) ?: body() ?: this

        /* ---------- Strategy 1: <article> tags ---------- */
        var posts: Elements = content.select("article")

        /* ---------- Strategy 2: div‑based containers ---------- */
        if (posts.isEmpty()) {
            posts = content.select(
                "div.post-box, div.post-item, div.blog-item, " +
                "div.post, div.item, div.movie-box, " +
                "div.result-item, div.flw-item, div.ml-item"
            )
        }

        /* ---------- Strategy 3: list items with link+image ---------- */
        if (posts.isEmpty()) {
            posts = content.select("li:has(a[href]):has(img)")
        }

        /* ---------- Strategy 4: direct <a> cards with image ---------- */
        if (posts.isEmpty()) {
            posts = content.select("a[href]:has(img)")
        }

        return posts.mapNotNull { el ->
            elementToSearchResponse(el)
        }.distinctBy { it.url }
    }

    // ── Convert one HTML element into a SearchResponse ─────────────────
    private fun elementToSearchResponse(el: Element): SearchResponse? {

        /* ---- find the best <a> for title + href ---- */
        val a: Element = el.selectFirst(
            "h2 a[href], h3 a[href], h4 a[href], " +
            ".entry-title a, .post-title a, .post-box-title a, " +
            ".front-view-title a, .film-name a"
        )
        ?: el.selectFirst("a[href][title]")
        ?: el.takeIf { it.tagName() == "a" && it.hasAttr("href") }
        ?: el.selectFirst("a[href]")
        ?: return null

        /* ---- href ---- */
        val href = fixUrlNull(a.attr("href")) ?: return null
        if (href == mainUrl || href == "$mainUrl/"
            || href.contains("/category/")
            || href.contains("/tag/")
            || href.contains("/author/")
            || href.endsWith("/page/")
            || href.contains("#")
        ) return null

        /* ---- title (text first, then attribute, then img alt) ---- */
        val title = a.text().trim()
            .ifBlank { a.attr("title").trim() }
            .ifBlank { el.selectFirst("span.mname, span.title, div.title")?.text()?.trim() ?: "" }
            .ifBlank { el.selectFirst("img")?.attr("alt")?.trim() ?: "" }
            .ifBlank { return null }

        /* ---- poster (supports lazy loading) ---- */
        val poster = fixUrlNull(el.selectFirst("img")?.getImgUrl())

        /* ---- movie vs series ---- */
        val isSeries = title.lowercase().let { t ->
            t.contains("season") || t.contains("web series") || t.contains("tv show")
        } || title.contains(Regex("""S\d{2}"""))
          || href.contains("web-series")
          || href.contains("tv-shows")

        return if (isSeries)
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        else
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  EPISODES
    // ══════════════════════════════════════════════════════════════════════
    private fun Document.parseEpisodes(pageUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val headings = select("h2, h3, h4, strong").filter {
            it.text().contains(Regex("""(?i)(episode|ep\.?\s*\d|e\d{2})"""))
        }
        if (headings.isEmpty()) {
            episodes.add(newEpisode(pageUrl) {
                this.name    = "Watch / Download"
                this.episode = 1
                this.season  = 1
            })
        } else {
            headings.forEachIndexed { idx, h ->
                val epNum = Regex("""\d+""").find(h.text())
                    ?.value?.toIntOrNull() ?: (idx + 1)
                episodes.add(newEpisode(pageUrl) {
                    this.name    = h.text().trim()
                    this.episode = epNum
                    this.season  = 1
                })
            }
        }
        return episodes
    }

    // ══════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /** Get the real image URL — handles data‑src / lazy attributes */
    private fun Element.getImgUrl(): String? {
        val url = attr("data-src")
            .ifBlank { attr("data-lazy-src") }
            .ifBlank { attr("data-original") }
            .ifBlank { attr("src") }
        return url.ifBlank { null }
    }

    private fun String.containsAny(vararg terms: String) =
        terms.any { this.contains(it, ignoreCase = true) }

    /** Walk up siblings/parents for a quality label */
    private fun Element.closestLabel(): String =
        previousElementSibling()?.text()?.trim()
            ?: parent()?.previousElementSibling()?.text()?.trim()
            ?: text().trim()

    private suspend fun resolveOxxFile(url: String): String? {
        return try {
            val resp = app.get(
                url, timeout = 60, allowRedirects = true,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)
            )
            val doc = resp.document
            doc.selectFirst(
                "a[href*=hubcloud], a[href*=streamtape], a[href*=vidnest], " +
                "a[href*=dood], a[href*=.mkv], a[href*=.mp4], " +
                "a[href*=drive.google.com], source[src*=.mkv], source[src*=.mp4]"
            )?.let { el ->
                el.attr("abs:href").ifBlank { el.attr("abs:src") }
            } ?: resp.url.takeIf {
                it.containsAny(".mkv", ".mp4", "hubcloud", "streamtape")
            }
        } catch (_: Exception) { null }
    }

    private fun resolveQuality(label: String): Int {
        val t = label.lowercase()
        return when {
            t.contains("2160") || t.contains("4k") -> Qualities.P2160.value
            t.contains("1080") -> Qualities.P1080.value
            t.contains("720")  -> Qualities.P720.value
            t.contains("480")  -> Qualities.P480.value
            t.contains("360")  -> Qualities.P360.value
            else               -> Qualities.Unknown.value
        }
    }

    private fun qualityLabel(quality: Int, fallback: String) = when (quality) {
        Qualities.P2160.value  -> "2160p 4K"
        Qualities.P1080.value  -> "1080p"
        Qualities.P720.value   -> "720p"
        Qualities.P480.value   -> "480p"
        Qualities.P360.value   -> "360p"
        else -> fallback.take(30).ifBlank { "Unknown" }
    }

    private fun String.encodeUri(): String =
        java.net.URLEncoder.encode(this, "UTF-8")

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; SM-G973F) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
