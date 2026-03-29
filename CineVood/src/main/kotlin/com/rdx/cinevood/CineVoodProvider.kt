package com.rdx.cinevood

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import org.jsoup.nodes.Element

class CineVoodProvider : MainAPI() {

    override var mainUrl        = "https://one.1cinevood.watch"
    override var name           = "CineVood"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang           = "hi"
    override val hasMainPage    = true
    override val hasChromecastSupport = true

    // ★ Cloudflare bypass
    private val cfKiller = CloudflareKiller()

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return cfKiller
    }

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

    // ── Shared request helper with CF bypass ──────────────────────
    private suspend fun cfGet(url: String) = app.get(
        url,
        headers = mapOf(
            "User-Agent"      to USER_AGENT,
            "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Referer"         to mainUrl
        ),
        interceptor = cfKiller
    )

    // ══════════════════════════════════════════════════════════════
    //  HOME PAGE  (with debug output)
    // ══════════════════════════════════════════════════════════════
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data
                  else "${request.data}page/$page/"

        val doc = cfGet(url).document

        val items = doc.select("article.latestPost").mapNotNull { article ->
            articleToResult(article)
        }

        // ★ DEBUG — remove this block after confirming it works
        if (items.isEmpty()) {
            val debugTitle = doc.title() ?: "NO TITLE"
            val articleCount = doc.select("article").size
            val bodyLength = doc.body()?.text()?.length ?: 0
            val hasCloudflare = doc.html().contains("Just a moment")
                    || doc.html().contains("cf-browser-verification")
                    || doc.html().contains("challenge-platform")

            val debugMsg = buildString {
                append(request.name)
                append(" [DEBUG:")
                append(" pageTitle='$debugTitle'")
                append(" articles=$articleCount")
                append(" bodyLen=$bodyLength")
                append(" cf=$hasCloudflare")
                append("]")
            }

            return newHomePageResponse(debugMsg, items)
        }
        // ★ END DEBUG

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ══════════════════════════════════════════════════════════════
    //  SEARCH
    // ══════════════════════════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = cfGet("$mainUrl/?s=${query.encodeUri()}").document
        return doc.select("article.latestPost").mapNotNull { articleToResult(it) }
    }

    // ══════════════════════════════════════════════════════════════
    //  ★ Parse one <article> into SearchResponse
    // ══════════════════════════════════════════════════════════════
    private fun articleToResult(article: Element): SearchResponse? {
        // Title: <h2 class="title front-view-title"><a href="..." title="...">TEXT</a></h2>
        val h2Link = article.selectFirst("h2.title a[href], h2 a[href]")
            ?: return null

        val title = h2Link.text().trim()
            .ifBlank { h2Link.attr("title").trim() }
            .ifBlank { return null }

        val href = fixUrlNull(h2Link.attr("href")) ?: return null

        // Poster: <div class="featured-thumbnail"><img src="..."></div>
        val poster = fixUrlNull(
            article.selectFirst("div.featured-thumbnail img")?.attr("src")
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

    // ══════════════════════════════════════════════════════════════
    //  LOAD — detail page
    // ══════════════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse? {
        val doc = cfGet(url).document

        val title = doc.selectFirst("h1.title.single-title.entry-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(
            doc.selectFirst("div.featured-thumbnail img")?.attr("src")
                ?: doc.selectFirst("div.thecontent img[src*=tmdb]")?.attr("src")
        )

        val plot = doc.selectFirst("span#summary")?.text()
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

        // 1 — All iframes (vidara.to player etc.)
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
        //     <h6><span>...1080p...[3.83 GB]</span></h6>
        //     <a class="maxbutton" href="https://new9.oxxfle.info/s/...">
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

        // 3 — Fallback: direct links
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
