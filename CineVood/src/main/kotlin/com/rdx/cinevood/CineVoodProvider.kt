package com.rdx.cinevood

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CineVoodProvider : MainAPI() {

    // ✅ FIXED URL (was "one.1cinevood.watch" before — wrong!)
    override var mainUrl              = "https://one1.cinevood.watch"
    override var name                 = "CineVood"
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)
    override var lang                 = "hi"
    override val hasMainPage          = true
    override val hasChromecastSupport = true

    private val cfKiller = CloudflareKiller()

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    // ── Categories shown on home screen ──────────────────────────────────────
    override val mainPage = mainPageOf(
        "$mainUrl/"                           to "🔥 Latest",
        "$mainUrl/bollywood/"                 to "🎬 Bollywood",
        "$mainUrl/hollywood/"                 to "🌍 Hollywood",
        "$mainUrl/tamil/"                     to "🎭 Tamil",
        "$mainUrl/telugu/"                    to "🎭 Telugu",
        "$mainUrl/malayalam/"                 to "🎭 Malayalam",
        "$mainUrl/kannada/"                   to "🎭 Kannada",
        "$mainUrl/hindi-dubbed/south-dubbed/" to "🔁 South Dubbed",
        "$mainUrl/web-series/"                to "📺 Web Series",
        "$mainUrl/tv-shows/"                  to "📡 TV Shows",
        "$mainUrl/netflix/"                   to "🔴 Netflix",
        "$mainUrl/amazon-prime-video/"        to "📦 Amazon Prime",
    )

    // ── Cloudflare-safe GET: tries plain first, falls back to cfKiller ────────
    private suspend fun safeGet(url: String): Document {
        return try {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent"                to USER_AGENT,
                    "Accept"                    to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                    "Accept-Language"           to "en-US,en;q=0.9",
                    "Accept-Encoding"           to "gzip, deflate, br",
                    "Connection"                to "keep-alive",
                    "Upgrade-Insecure-Requests" to "1",
                    "Sec-Fetch-Dest"            to "document",
                    "Sec-Fetch-Mode"            to "navigate",
                    "Sec-Fetch-Site"            to "none",
                    "Cache-Control"             to "max-age=0",
                ),
                timeout = 30
            ).document
        } catch (e: Exception) {
            // Fallback — CloudflareKiller uses internal WebView to bypass JS challenge
            cfKiller.get(
                url,
                headers = mapOf("User-Agent" to USER_AGENT)
            ).document
        }
    }

    // ── HOME PAGE ─────────────────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data
                  else "${request.data.trimEnd('/')}/page/$page/"

        val doc   = safeGet(url)
        val items = doc.select("article").mapNotNull { it.toSearchResult() }

        // ✅ FIXED: properly checks for next page instead of infinite loop
        val hasNext = items.isNotEmpty() &&
                      doc.selectFirst("a.next.page-numbers") != null

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    // ── SEARCH ────────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc     = safeGet("$mainUrl/?s=$encoded")
        return doc.select("article").mapNotNull { it.toSearchResult() }
    }

    // ── LOAD (detail / info page) ─────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        val doc = safeGet(url)

        val title = doc.selectFirst(
            "h1.entry-title, h1.title, h1.single-title, h1"
        )?.text()?.trim() ?: return null

        val poster = fixUrlNull(
            doc.selectFirst("div.featured-thumbnail img, div.post-thumbnail img")
                ?.attr("src")
                ?: doc.selectFirst(".wp-post-image")?.attr("src")
                ?: doc.selectFirst("img[src*=tmdb]")?.attr("src")
                ?: doc.selectFirst("img[src*=imgshare]")?.attr("src")
                ?: doc.selectFirst("article img")?.attr("src")
        )

        val plot = doc.selectFirst(
            "div.thecontent p, div.entry-content p, div.post-content p"
        )?.text()?.trim()

        val year = Regex("""\b(20\d{2})\b""").find(title)
            ?.groupValues?.get(1)?.toIntOrNull()

        val tags = doc.select(
            "a[rel=category tag], .cat-links a, .thecategory a"
        ).map { it.text().trim() }.filter { it.isNotBlank() }

        val rating = doc.selectFirst(".imdb-rating, .rating")
            ?.text()?.trim()?.toRatingInt()

        val isSeries = isTvSeries(title, url, tags)

        return if (isSeries) {
            newTvSeriesLoadResponse(
                title, url, TvType.TvSeries, doc.extractEpisodes(url)
            ) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.tags      = tags
                this.rating    = rating
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.tags      = tags
                this.rating    = rating
            }
        }
    }

    // ── LOAD LINKS (extract playable links) ───────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc   = safeGet(data)
        var found = false

        // STEP 1 — iframes (vidnest, streamtape embedded, etc.)
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").trim()
                .ifBlank { iframe.attr("data-src").trim() }
            if (src.isNotBlank() && src.startsWith("http")) {
                runCatching {
                    loadExtractor(src, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // STEP 2 — MaxButton / OxxFile / download buttons
        doc.select(
            "a.maxbutton, a[href*=oxxfile], a[href*=oxi.file], " +
            "a[href*=gdtot], a[href*=hubdrive], a[href*=drivebot], " +
            "a[href*=hubcloud], a.btn, .dl-link a"
        ).forEach { btn ->
            val href = btn.attr("href").trim()
            if (href.isBlank() || href == "#" || href == data) return@forEach

            val labelText = btn.text().trim()
                .ifBlank { btn.previousElementSibling()?.text()?.trim() ?: "Download" }
            val quality = detectQuality(labelText)

            runCatching {
                when {
                    // Known hosters — pass directly to loadExtractor
                    href.containsAny(
                        "streamtape", "doodstream", "dood.pm", "dood.re",
                        "vidnest", "filelions", "streamhub", "voe.sx",
                        "hubcloud", "upstream"
                    ) -> {
                        loadExtractor(href, data, subtitleCallback, callback)
                        found = true
                    }
                    // Redirect/shortener links — resolve chain first
                    href.containsAny(
                        "oxxfile", "oxi.file", "gdtot",
                        "hubdrive", "drivebot"
                    ) -> {
                        val resolved = resolveRedirectChain(href)
                        if (!resolved.isNullOrBlank()) {
                            when {
                                resolved.containsAny(
                                    "streamtape", "dood", "vidnest",
                                    "filelions", "hubcloud"
                                ) -> {
                                    loadExtractor(resolved, data, subtitleCallback, callback)
                                    found = true
                                }
                                resolved.endsWith(".mkv") || resolved.endsWith(".mp4") -> {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = name,
                                            name   = "$name ${qualityLabel(quality, labelText)}",
                                            url    = resolved,
                                            type   = ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer  = mainUrl
                                            this.quality  = quality
                                            this.headers  = mapOf("User-Agent" to USER_AGENT)
                                        }
                                    )
                                    found = true
                                }
                                else -> {
                                    runCatching {
                                        loadExtractor(resolved, data, subtitleCallback, callback)
                                        found = true
                                    }
                                }
                            }
                        }
                    }
                    // Any other external link
                    href.startsWith("http") && !href.contains(mainUrl) -> {
                        runCatching {
                            loadExtractor(href, data, subtitleCallback, callback)
                            found = true
                        }
                    }
                }
            }
        }

        // STEP 3 — Direct <source> / <video> tags
        doc.select("source[src], video[src]").forEach { el ->
            val src = el.attr("abs:src").trim()
            if (src.isNotBlank() && (src.contains(".mp4") || src.contains(".mkv"))) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name   = "$name Direct",
                        url    = src,
                        type   = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to USER_AGENT)
                    }
                )
                found = true
            }
        }

        // STEP 4 — Scan ALL page links for known hosters
        doc.select("a[href]").forEach { el ->
            val href = el.attr("abs:href").trim()
            if (href.isBlank()) return@forEach
            if (href.containsAny(
                    "streamtape", "doodstream", "dood.pm", "dood.re",
                    "vidnest", "filelions", "streamhub", "voe.sx",
                    "upstream.to", "mixdrop", "hubcloud"
                )
            ) {
                runCatching {
                    loadExtractor(href, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        return found
    }

    // ── Convert <article> element → SearchResponse ────────────────────────────
    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = selectFirst(
            "h2.entry-title a, h3.entry-title a, " +
            "h2 a[rel=bookmark], h3 a[rel=bookmark], " +
            ".front-view-title a, .entry-title a"
        ) ?: return null

        val title = linkEl.text().trim()
            .ifBlank { linkEl.attr("title").trim() }
            .ifBlank { return null }

        val href = fixUrlNull(linkEl.attr("abs:href")) ?: return null

        if (href.containsAny("/category/", "/tag/", "/page/")) return null

        val poster = fixUrlNull(
            selectFirst("img.wp-post-image, .featured-thumbnail img, img")
                ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
        )

        return if (isTvSeries(title, href, emptyList())) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // ── Check if content is a TV Series ───────────────────────────────────────
    private fun isTvSeries(title: String, url: String, tags: List<String>): Boolean {
        return tags.any {
            it.lowercase().containsAny("web series", "tv show", "series")
        }
            || title.contains(Regex("""(?i)season\s*\d"""))
            || title.contains(Regex("""S\d{2}E\d{2}"""))
            || url.containsAny("web-series", "tv-shows", "season")
    }

    // ── Parse episodes from a series page ─────────────────────────────────────
    private fun Document.extractEpisodes(pageUrl: String): List<Episode> {
        val headings = select("h2, h3, h4, strong, b").filter { el ->
            el.text().contains(Regex("""(?i)(episode|ep\.?\s*\d|e\d{2}|\bep\b)"""))
        }

        if (headings.isEmpty()) {
            return listOf(newEpisode(pageUrl) {
                name    = "Watch / Download"
                episode = 1
                season  = 1
            })
        }

        return headings.mapIndexed { idx, heading ->
            val epText = heading.text().trim()
            val epNum  = Regex("""\d+""").find(epText)?.value?.toIntOrNull() ?: (idx + 1)
            val season = Regex("""(?i)season\s*(\d+)""")
                .find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            newEpisode(pageUrl) {
                name    = epText
                episode = epNum
                this.season = season
            }
        }
    }

    // ── Resolve OxxFile / redirect chain ──────────────────────────────────────
    private suspend fun resolveRedirectChain(url: String): String? {
        return runCatching {
            val response = app.get(
                url,
                allowRedirects = true,
                timeout = 20,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer"    to mainUrl
                )
            )
            val finalUrl = response.url

            if (finalUrl.containsAny(".mkv", ".mp4", "streamtape",
                    "dood", "vidnest", "filelions", "hubcloud")) {
                return@runCatching finalUrl
            }

            response.document.selectFirst(
                "a[href*=streamtape], a[href*=dood], a[href*=vidnest], " +
                "a[href*=filelions], a[href*=hubcloud], " +
                "a[href*=.mkv], a[href*=.mp4], " +
                "source[src*=.mp4], source[src*=.mkv]"
            )?.let { el ->
                el.attr("abs:href").ifBlank { el.attr("abs:src") }
            } ?: finalUrl

        }.getOrNull()
    }

    // ── Quality helpers ────────────────────────────────────────────────────────
    private fun detectQuality(text: String): Int {
        val t = text.lowercase()
        return when {
            "2160" in t || "4k" in t -> Qualities.P2160.value
            "1080" in t              -> Qualities.P1080.value
            "720"  in t              -> Qualities.P720.value
            "480"  in t              -> Qualities.P480.value
            "360"  in t              -> Qualities.P360.value
            else                     -> Qualities.Unknown.value
        }
    }

    private fun qualityLabel(q: Int, fallback: String): String = when (q) {
        Qualities.P2160.value -> "4K 2160p"
        Qualities.P1080.value -> "1080p"
        Qualities.P720.value  -> "720p"
        Qualities.P480.value  -> "480p"
        Qualities.P360.value  -> "360p"
        else                  -> fallback.take(40).ifBlank { "Download" }
    }

    // ── Extension util ─────────────────────────────────────────────────────────
    private fun String.containsAny(
        vararg tokens: String,
        ignoreCase: Boolean = true
    ): Boolean = tokens.any { this.contains(it, ignoreCase = ignoreCase) }
}
