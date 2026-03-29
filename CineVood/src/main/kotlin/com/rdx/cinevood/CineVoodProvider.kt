package com.rdx.cinevood

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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

        val doc = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document

        // ★ Select every <article class="latestPost"> on the page
        val items = doc.select("article.latestPost").mapNotNull { article ->
            articleToResult(article)
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ══════════════════════════════════════════════════════════════
    //  SEARCH
    // ══════════════════════════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/?s=${query.encodeUri()}",
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document

        return doc.select("article.latestPost").mapNotNull { articleToResult(it) }
    }

    // ══════════════════════════════════════════════════════════════
    //  ★ CORE FIX — Parse one <article> into a SearchResponse
    // ══════════════════════════════════════════════════════════════
    private fun articleToResult(article: Element): SearchResponse? {
        /*
         * HTML structure (confirmed from your site):
         *
         *  <article class="latestPost excerpt">
         *    <a href="URL" title="TITLE" class="post-image post-image-left">
         *      <div class="featured-thumbnail">
         *        <img src="POSTER" ...>        ← poster
         *      </div>
         *    </a>
         *    <header>
         *      <h2 class="title front-view-title">
         *        <a href="URL" title="TITLE">MOVIE NAME</a>   ← ★ title lives HERE
         *      </h2>
         *    </header>
         *  </article>
         *
         *  BUG IN ORIGINAL CODE:
         *    selectFirst("a[href][title]") grabs the WRAPPER <a> (which has NO text).
         *    .text() on that <a> returns "" because it only contains a <div> with an <img>.
         *
         *  FIX:
         *    Select the <a> inside <h2> instead — it has the visible movie name as text.
         */

        // ── Get title from the <h2> link (NOT the wrapper <a>) ──
        val h2Link = article.selectFirst("h2.title a[href]")
            ?: article.selectFirst("h2 a[href]")
            ?: return null

        val title = h2Link.text().trim()                     // visible text = movie name
            .ifBlank { h2Link.attr("title").trim() }         // fallback to title attribute
            .ifBlank { return null }

        val href = fixUrlNull(h2Link.attr("href")) ?: return null

        // ── Get poster from the <img> inside featured-thumbnail ──
        val poster = fixUrlNull(
            article.selectFirst("div.featured-thumbnail img")?.attr("src")
                ?: article.selectFirst("img")?.attr("src")
        )

        // ── Detect series vs movie ──
        val lower = title.lowercase()
        val isSeries = lower.contains("season")
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
    //  LOAD — movie/series detail page
    // ══════════════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document

        // ── Title: <h1 class="title single-title entry-title">...</h1> ──
        val title = doc.selectFirst("h1.title.single-title.entry-title")?.text()?.trim()
            ?: doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        // ── Poster ──
        val poster = fixUrlNull(
            doc.selectFirst("div.single_post div.featured-thumbnail img")?.attr("src")
                ?: doc.selectFirst("div.featured-thumbnail img")?.attr("src")
                ?: doc.selectFirst("div.thecontent img[src*=tmdb]")?.attr("src")
                ?: doc.selectFirst("div.thecontent img")?.attr("src")
        )

        // ── Plot from IMDB widget ──
        val plot = doc.selectFirst("span#summary")?.text()
            ?.substringAfter("Summary:")?.trim()
            ?.ifBlank { null }
            ?: doc.select("div.thecontent p").find {
                it.text().startsWith("Plot:", ignoreCase = true)
            }?.text()?.substringAfter(":")?.trim()

        // ── Categories: <div class="thecategory"><a>...</a></div> ──
        val tags = doc.select("div.thecategory a").map { it.text() }

        // ── Year from title like "(2026)" ──
        val year = Regex("""\((\d{4})\)""").find(title)
            ?.groupValues?.get(1)?.toIntOrNull()

        // ── Series detection ──
        val isSeries = tags.any {
            it.lowercase().let { t ->
                t.contains("web series") || t.contains("tv show")
            }
        } || title.lowercase().contains("season")
          || title.contains(Regex("""S\d{2}"""))
          || url.contains("web-series")
          || url.contains("tv-shows")

        return if (isSeries) {
            val episodes = doc.parseEpisodes(url)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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

    // ══════════════════════════════════════════════════════════════
    //  LOAD LINKS  —  ★ FIXED selectors for vidara.to + oxxfle ★
    // ══════════════════════════════════════════════════════════════
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(
            data,
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document

        var found = false

        // ── 1. ALL iframes (vidara.to, etc.) ──
        //    Your site uses: <iframe src="https://vidara.to/e/...">
        //    Original code only checked for "vidnest" which doesn't exist on this site
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank()) {
                runCatching {
                    loadExtractor(fixUrl(src), mainUrl, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // ── 2. Download buttons (OxxFile) ──
        //    Your site uses: <a class="maxbutton-8 maxbutton maxbutton-oxxfle" href="...">
        //    Original code searched for "oxxfile" but actual domain is "oxxfle"
        //    ★ FIX: Select by class "maxbutton" which catches ALL download buttons
        doc.select("a.maxbutton[href]").forEach { btn ->
            val href = btn.attr("href").trim()
            if (href.isBlank()) return@forEach

            // Quality info is in the <h6> just before the button:
            //   <h6><span>...1080p...CineVood.mkv [3.83 GB]</span></h6>
            //   <a class="maxbutton" href="...">
            val qualityText = findQualityLabel(btn)
            val quality = getQuality(qualityText)

            runCatching {
                val streamUrl = resolveOxxFile(href)
                if (!streamUrl.isNullOrBlank()) {
                    // Check if resolved URL is an extractor link
                    if (streamUrl.containsAny("hubcloud", "streamtape", "dood", "vidara")) {
                        loadExtractor(streamUrl, mainUrl, subtitleCallback, callback)
                    } else {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name   = "$name ${getQualityLabel(quality, qualityText)}",
                                url    = streamUrl,
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

        // ── 3. Fallback: any direct extractor links in content ──
        doc.select(
            "a[href*=hubcloud], a[href*=streamtape], " +
            "a[href*=dood], a[href*=doodstream]"
        ).forEach { el ->
            runCatching {
                loadExtractor(el.attr("href").trim(), mainUrl, subtitleCallback, callback)
                found = true
            }
        }

        return found
    }

    // ══════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════

    /** Walk DOM upward/backward to find the quality label (from <h6>) */
    private fun findQualityLabel(btn: Element): String {
        // Try: <h6> right before the button
        btn.previousElementSibling()?.let { prev ->
            if (prev.tagName() == "h6") return prev.text().trim()
        }
        // Try: parent's previous sibling
        btn.parent()?.previousElementSibling()?.let { prev ->
            if (prev.tagName() == "h6") return prev.text().trim()
        }
        return btn.text().trim()
    }

    /** Resolve OxxFile redirect to get actual download URL */
    private suspend fun resolveOxxFile(url: String): String? {
        return runCatching {
            val resp = app.get(
                url,
                allowRedirects = true,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)
            )
            val finalUrl = resp.url

            // If redirect landed on a video file
            if (finalUrl.contains(".mkv") || finalUrl.contains(".mp4")) {
                return finalUrl
            }

            // Parse the landing page for links
            val doc = resp.document
            doc.selectFirst(
                "a[href*=hubcloud], a[href*=streamtape], " +
                "a[href*=dood], a[href*=vidara], " +
                "a[href*=.mkv], a[href*=.mp4], " +
                "a[href*=drive.google.com], " +
                "source[src*=.mkv], source[src*=.mp4]"
            )?.let { el ->
                el.attr("abs:href").ifBlank { el.attr("abs:src") }
            } ?: finalUrl.takeIf {
                it.containsAny("hubcloud", "streamtape", ".mkv", ".mp4")
            }
        }.getOrNull()
    }

    /** Parse episodes for series pages */
    private fun org.jsoup.nodes.Document.parseEpisodes(pageUrl: String): List<Episode> {
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
            "360"  in t              -> Qualities.P360.value
            else                     -> Qualities.Unknown.value
        }
    }

    private fun getQualityLabel(quality: Int, fallback: String) = when (quality) {
        Qualities.P2160.value -> "4K 2160p"
        Qualities.P1080.value -> "1080p"
        Qualities.P720.value  -> "720p"
        Qualities.P480.value  -> "480p"
        Qualities.P360.value  -> "360p"
        else -> fallback.take(50).ifBlank { "Download" }
    }

    private fun String.containsAny(vararg terms: String) =
        terms.any { this.contains(it, ignoreCase = true) }

    private fun String.encodeUri(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
