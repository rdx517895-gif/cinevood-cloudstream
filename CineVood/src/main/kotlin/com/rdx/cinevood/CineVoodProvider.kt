package com.rdx.cinevood

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CineVoodProvider : MainAPI() {

    // ✅ REAL URL confirmed from your HTML
    override var mainUrl              = "https://one.1cinevood.watch"
    override var name                 = "CineVood"
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)
    override var lang                 = "hi"
    override val hasMainPage          = true
    override val hasChromecastSupport = true

    // ✅ FIXED: No CloudflareKiller — use headers-based bypass instead
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

        private val CF_HEADERS = mapOf(
            "User-Agent"                to USER_AGENT,
            "Accept"                    to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language"           to "en-US,en;q=0.9",
            "Connection"                to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest"            to "document",
            "Sec-Fetch-Mode"            to "navigate",
            "Sec-Fetch-Site"            to "none",
        )
    }

    // ── Categories confirmed from real site HTML ───────────────────────────────
    override val mainPage = mainPageOf(
        "$mainUrl/"                                      to "🔥 Latest",
        "$mainUrl/bollywood/"                            to "🎬 Bollywood",
        "$mainUrl/hollywood/"                            to "🌍 Hollywood",
        "$mainUrl/hindi-dubbed/hollywood-dubbed/"        to "🌍 Hollywood Dubbed",
        "$mainUrl/hindi-dubbed/south-dubbed/"            to "🔁 South Dubbed",
        "$mainUrl/punjabi/"                              to "🎵 Punjabi",
        "$mainUrl/tamil/"                                to "🎭 Tamil",
        "$mainUrl/telugu/"                               to "🎭 Telugu",
        "$mainUrl/malayalam/"                            to "🎭 Malayalam",
        "$mainUrl/kannada/"                              to "🎭 Kannada",
        "$mainUrl/bengali/"                              to "🎭 Bengali",
        "$mainUrl/marathi/"                              to "🎭 Marathi",
        "$mainUrl/gujarati/"                             to "🎭 Gujarati",
        "$mainUrl/tv-shows/"                             to "📡 TV Shows",
        "$mainUrl/web-series/"                           to "📺 Web Series",
        "$mainUrl/web-series/netflix-web-series/"        to "🔴 Netflix",
        "$mainUrl/web-series/amazon-web-series-webshow/" to "📦 Amazon",
        "$mainUrl/web-series/hotstar-web-series/"        to "⭐ Hotstar",
        "$mainUrl/web-series/zee5-web-series/"           to "🎬 Zee5",
        "$mainUrl/web-series/sony-liv-web-show/"         to "🎵 Sony LIV",
    )

    // ✅ FIXED: .document resolved by using Jsoup.parse(app.get().text)
    private suspend fun safeGet(url: String): Document {
        val response = app.get(url, headers = CF_HEADERS, timeout = 30)
        return Jsoup.parse(response.text)
    }

    // ── HOME PAGE ──────────────────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data
                  else "${request.data.trimEnd('/')}/page/$page/"

        val doc   = safeGet(url)

        // ✅ CONFIRMED from real HTML: article.latestPost
        // title: h2.title.front-view-title > a
        // poster: div.featured-thumbnail > img
        val items = doc.select("article.latestPost").mapNotNull { it.toSearchResult() }

        // ✅ CONFIRMED from real HTML: <a class="next page-numbers">
        val hasNext = doc.selectFirst("a.next.page-numbers") != null

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    // ── SEARCH ─────────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc     = safeGet("$mainUrl/?s=$encoded")
        return doc.select("article.latestPost").mapNotNull { it.toSearchResult() }
    }

    // ── LOAD DETAIL PAGE ───────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        val doc = safeGet(url)

        // ✅ CONFIRMED from real HTML: h1.title.single-title.entry-title
        val title = doc.selectFirst("h1.title.single-title.entry-title")
            ?.text()?.trim() ?: return null

        // ✅ Poster confirmed from real HTML
        val poster = fixUrlNull(
            doc.selectFirst("div.single_post img.wp-post-image")?.attr("src")
                ?: doc.selectFirst(".featured-thumbnail img")?.attr("src")
                ?: doc.selectFirst("img.wp-post-image")?.attr("src")
        )

        // ✅ CONFIRMED from real HTML: div.thecategory > a
        val tags = doc.select("div.thecategory a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val plot = doc.selectFirst("div.thecontent p")
            ?.text()?.trim()
            ?.takeIf { it.length > 30 }

        val year = Regex("""\b(20\d{2})\b""").find(title)
            ?.groupValues?.get(1)?.toIntOrNull()

        val isSeries = isTvSeries(title, url, tags)

        return if (isSeries) {
            newTvSeriesLoadResponse(
                title, url, TvType.TvSeries, doc.extractEpisodes(url)
            ) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.tags      = tags
                // ✅ FIXED: removed deprecated rating/toRatingInt
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.tags      = tags
                // ✅ FIXED: removed deprecated rating/toRatingInt
            }
        }
    }

    // ── LOAD LINKS ─────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc   = safeGet(data)
        var found = false

        // ── STEP 1: iframes (vidara.to confirmed from real HTML) ──────────────
        doc.select("div.thecontent iframe").forEach { iframe ->
            val src = iframe.attr("src").trim()
                .ifBlank { iframe.attr("data-src").trim() }
            if (src.isNotBlank() && src.startsWith("http")) {
                runCatching {
                    loadExtractor(src, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // ── STEP 2: OxxFile buttons (confirmed from real HTML) ────────────────
        // class="maxbutton-8 maxbutton maxbutton-oxxfile"
        // href="https://new10.oxxfile.info/s/XXXXX"
        // quality label in <h6> tag immediately after each button
        doc.select("a.maxbutton-oxxfile, a[href*=oxxfile], a[href*=oxi.file]")
            .forEach { btn ->
                val href = btn.attr("href").trim()
                if (href.isBlank() || href == "#") return@forEach

                // ✅ Quality from h6 sibling — confirmed from real HTML
                val qualityText = btn.nextElementSibling()
                    ?.takeIf { it.tagName() == "h6" }
                    ?.text() ?: btn.text()
                val quality = detectQuality(qualityText)

                runCatching {
                    val resolved = resolveOxxFile(href)
                    if (!resolved.isNullOrBlank()) {
                        when {
                            resolved.containsAny(
                                "streamtape", "dood", "vidnest",
                                "filelions", "hubcloud", "vidara",
                                "streamhub", "voe.sx", "mixdrop"
                            ) -> {
                                loadExtractor(resolved, data, subtitleCallback, callback)
                                found = true
                            }
                            resolved.endsWith(".mkv") || resolved.endsWith(".mp4") -> {
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name   = "$name ${qualityLabel(quality)}",
                                        url    = resolved,
                                        type   = ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = mainUrl
                                        this.quality = quality
                                        this.headers = mapOf("User-Agent" to USER_AGENT)
                                    }
                                )
                                found = true
                            }
                            resolved.startsWith("http") -> {
                                runCatching {
                                    loadExtractor(resolved, data, subtitleCallback, callback)
                                    found = true
                                }
                            }
                        }
                    }
                }
            }

        // ── STEP 3: krakenfiles/buzzheavier sample button ─────────────────────
        doc.select("a.maxbutton-download").forEach { btn ->
            val href = btn.attr("href").trim()
            if (href.isBlank() || href == "#") return@forEach
            if (href.containsAny("krakenfiles", "buzzheavier")) {
                runCatching {
                    loadExtractor(href, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // ── STEP 4: Scan all links for known hosters ──────────────────────────
        doc.select("a[href]").forEach { el ->
            val href = el.attr("abs:href").trim()
            if (href.isBlank() || href.contains(mainUrl)) return@forEach
            if (href.containsAny(
                    "streamtape", "doodstream", "dood.pm", "dood.re",
                    "vidnest", "filelions", "streamhub", "voe.sx",
                    "upstream.to", "mixdrop", "hubcloud", "vidara.to"
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

    // ── article.latestPost → SearchResponse ───────────────────────────────────
    private fun Element.toSearchResult(): SearchResponse? {
        // ✅ CONFIRMED exact selectors from your real HTML
        val titleEl = selectFirst("h2.title.front-view-title a")
            ?: selectFirst("h2.front-view-title a")
            ?: return null

        val title = titleEl.text().trim()
            .ifBlank { titleEl.attr("title").trim() }
            .ifBlank { return null }

        val href = fixUrlNull(titleEl.attr("abs:href")) ?: return null
        if (href.containsAny("/category/", "/tag/", "/page/")) return null

        // ✅ CONFIRMED from real HTML: div.featured-thumbnail > img
        val poster = fixUrlNull(
            selectFirst("div.featured-thumbnail img")?.attr("src")
                ?: selectFirst("a.post-image img")?.attr("src")
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

    // ── Is TV Series? ──────────────────────────────────────────────────────────
    private fun isTvSeries(title: String, url: String, tags: List<String>): Boolean {
        return tags.any { it.lowercase().containsAny("web series", "tv show", "series") }
            || title.contains(Regex("""(?i)(season\s*\d|S\d{2}E\d{2}|\bS\d{2}\b)"""))
            || url.containsAny("web-series", "tv-shows", "season")
    }

    // ── Extract Episodes ───────────────────────────────────────────────────────
    private fun Document.extractEpisodes(pageUrl: String): List<Episode> {
        val headings = select("div.thecontent h2, div.thecontent h3, div.thecontent h4")
            .filter { it.text().contains(Regex("""(?i)(episode|ep\.?\s*\d|E\d{2})""")) }

        if (headings.isEmpty()) {
            return listOf(newEpisode(pageUrl) {
                name    = "Watch / Download"
                episode = 1
                season  = 1
            })
        }

        return headings.mapIndexed { idx, el ->
            val text   = el.text().trim()
            val epNum  = Regex("""\d+""").find(text)?.value?.toIntOrNull() ?: (idx + 1)
            val season = Regex("""(?i)season\s*(\d+)""")
                .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            newEpisode(pageUrl) {
                name        = text
                episode     = epNum
                this.season = season
            }
        }
    }

    // ── OxxFile Resolver ───────────────────────────────────────────────────────
    private suspend fun resolveOxxFile(url: String): String? {
        return runCatching {
            val response = app.get(
                url,
                allowRedirects = true,
                timeout        = 20,
                headers        = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer"    to mainUrl
                )
            )
            val finalUrl = response.url
            if (finalUrl.containsAny(
                    ".mkv", ".mp4", "streamtape", "dood",
                    "vidnest", "filelions", "hubcloud", "vidara"
                )
            ) return@runCatching finalUrl

            val doc = Jsoup.parse(response.text)
            doc.selectFirst(
                "a#download-btn, a.btn-download, " +
                "a[href*=streamtape], a[href*=dood], " +
                "a[href*=vidnest], a[href*=filelions], " +
                "a[href*=hubcloud], a[href*=vidara], " +
                "a[href*=.mkv], a[href*=.mp4], " +
                "a[href*=buzzheavier], a[href*=krakenfiles]"
            )?.attr("abs:href")?.ifBlank { null } ?: finalUrl
        }.getOrNull()
    }

    // ── Quality Helpers ────────────────────────────────────────────────────────
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

    private fun qualityLabel(q: Int): String = when (q) {
        Qualities.P2160.value -> "4K"
        Qualities.P1080.value -> "1080p"
        Qualities.P720.value  -> "720p"
        Qualities.P480.value  -> "480p"
        Qualities.P360.value  -> "360p"
        else                  -> "Download"
    }

    // ── String Util ───────────────────────────────────────────────────────────
    private fun String.containsAny(
        vararg tokens: String,
        ignoreCase: Boolean = true
    ): Boolean = tokens.any { this.contains(it, ignoreCase = ignoreCase) }
}
