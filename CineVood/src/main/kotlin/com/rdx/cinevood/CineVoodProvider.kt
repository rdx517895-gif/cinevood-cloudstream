package com.rdx.cinevood

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CineVoodProvider : MainAPI() {

    override var mainUrl        = "https://one.1cinevood.watch"
    override var name           = "CineVood"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang           = "hi"
    override val hasMainPage    = true
    override val hasChromecastSupport = true

    private val cfKiller = CloudflareKiller()

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"
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

    private suspend fun cfGet(url: String) = cfKiller.get(
        url,
        headers = mapOf(
            "User-Agent"      to USER_AGENT,
            "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5"
        )
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = cfGet(url).document
        var items = doc.select("article.latestPost").mapNotNull { articleToResult(it) }
        if (items.isEmpty()) items = doc.select("article").mapNotNull { articleToResult(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = cfGet("$mainUrl/?s=${query.encodeUri()}").document
        val results = doc.select("article.latestPost").mapNotNull { articleToResult(it) }
        if (results.isNotEmpty()) return results
        return doc.select("article").mapNotNull { articleToResult(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = cfGet(url).document
        val title = doc.selectFirst(
            "h1.title.single-title.entry-title, h1.entry-title, h1.title, h1"
        )?.text()?.trim() ?: return null

        val poster = fixUrlNull(
            doc.selectFirst("div.featured-thumbnail img")?.attr("src")
                ?: doc.selectFirst("div.thecontent img[src*=tmdb]")?.attr("src")
                ?: doc.selectFirst("div.thecontent img[src*=imgshare]")?.attr("src")
                ?: doc.selectFirst("img[src*=tmdb]")?.attr("src")
        )

        val plot = doc.selectFirst("div.thecontent p")?.text()?.trim()
        val tags = doc.select("div.thecategory a").map { it.text() }
        val year = Regex("""\b(20\d{2})\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val isSeries = tags.any {
            it.lowercase().contains("web series") || it.lowercase().contains("tv show")
        } || title.lowercase().contains("season") ||
            title.contains(Regex("""S\d{2}E\d{2}""")) ||
            url.contains("web-series") || url.contains("tv-shows")

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, doc.parseEpisodes(url)) {
                this.posterUrl = poster; this.plot = plot; this.year = year; this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster; this.plot = plot; this.year = year; this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = cfGet(data).document
        var found = false

        // 1 — All iframes (vidnest etc.)
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank()) {
                runCatching {
                    loadExtractor(fixUrl(src), mainUrl, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // 2 — OxxFile / maxbutton download buttons
        doc.select("a.maxbutton[href], a[href*=oxxfile]").forEach { btn ->
            val href = btn.attr("href").trim()
            if (href.isBlank()) return@forEach
            val qualityText = btn.previousElementSibling()?.text()?.trim()
                ?: btn.parent()?.previousElementSibling()?.text()?.trim()
                ?: btn.text().trim()
            val quality = getQuality(qualityText)
            runCatching {
                val resolved = resolveOxxFile(href)
                if (!resolved.isNullOrBlank()) {
                    when {
                        resolved.containsAny("hubcloud","streamtape","dood","vidnest") ->
                            loadExtractor(resolved, mainUrl, subtitleCallback, callback)
                        resolved.containsAny(".mkv", ".mp4") ->
                            callback.invoke(newExtractorLink(
                                source = name,
                                name   = "$name ${getQualityLabel(quality, qualityText)}",
                                url    = resolved,
                                type   = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = quality
                                this.headers = mapOf("User-Agent" to USER_AGENT)
                            })
                        else -> runCatching { loadExtractor(resolved, mainUrl, subtitleCallback, callback) }
                    }
                    found = true
                }
            }
        }

        // 3 — Direct hubcloud/streamtape/dood links
        doc.select("a[href*=hubcloud], a[href*=streamtape], a[href*=dood], a[href*=vidnest]")
            .forEach { el ->
                val href = el.attr("href").trim()
                if (href.isNotBlank()) runCatching {
                    loadExtractor(href, mainUrl, subtitleCallback, callback)
                    found = true
                }
            }

        return found
    }

    private fun articleToResult(article: Element): SearchResponse? {
        val h2Link = article.selectFirst("h2 a[href], h3 a[href], .front-view-title a[href]")
            ?: return null
        val title = h2Link.text().trim().ifBlank { h2Link.attr("title").trim() }.ifBlank { return null }
        val href  = fixUrlNull(h2Link.attr("href")) ?: return null
        if (href.contains("/category/") || href.contains("/tag/")) return null
        val poster = fixUrlNull(
            article.selectFirst("div.featured-thumbnail img")?.attr("src")
                ?: article.selectFirst("img")?.attr("src")
        )
        val isSeries = title.lowercase().contains("season") ||
                       title.contains(Regex("""S\d{2}E\d{2}""")) ||
                       href.contains("web-series") || href.contains("tv-shows")
        return if (isSeries)
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        else
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
    }

    private fun Document.parseEpisodes(pageUrl: String): List<Episode> {
        val headings = select("h2, h3, h4, strong").filter {
            it.text().contains(Regex("""(?i)(episode|ep\.?\s*\d|e\d{2})"""))
        }
        if (headings.isEmpty()) return listOf(newEpisode(pageUrl) {
            this.name = "Watch / Download"; this.episode = 1; this.season = 1
        })
        return headings.mapIndexed { idx, h ->
            val epNum = Regex("""\d+""").find(h.text())?.value?.toIntOrNull() ?: (idx + 1)
            newEpisode(pageUrl) { this.name = h.text().trim(); this.episode = epNum; this.season = 1 }
        }
    }

    private suspend fun resolveOxxFile(url: String): String? {
        return runCatching {
            val resp = app.get(url, timeout = 60, allowRedirects = true,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl))
            val finalUrl = resp.url
            if (finalUrl.containsAny(".mkv", ".mp4", "hubcloud", "streamtape")) return finalUrl
            resp.document.selectFirst(
                "a[href*=hubcloud], a[href*=streamtape], a[href*=dood], " +
                "a[href*=vidnest], a[href*=.mkv], a[href*=.mp4], " +
                "source[src*=.mkv], source[src*=.mp4]"
            )?.let { el -> el.attr("abs:href").ifBlank { el.attr("abs:src") } }
                ?: finalUrl.takeIf { it.containsAny("hubcloud","streamtape",".mkv",".mp4","vidnest") }
        }.getOrNull()
    }

    private fun getQuality(text: String): Int {
        val t = text.lowercase()
        return when {
            "2160" in t || "4k" in t -> 2160
            "1080" in t              -> 1080
            "720"  in t              -> 720
            "480"  in t              -> 480
            else                     -> -1
        }
    }

    private fun getQualityLabel(q: Int, fallback: String) = when (q) {
        2160 -> "4K 2160p"; 1080 -> "1080p"; 720 -> "720p"; 480 -> "480p"
        else -> fallback.take(50).ifBlank { "Download" }
    }

    private fun String.containsAny(vararg t: String) = t.any { this.contains(it, ignoreCase = true) }
    private fun String.encodeUri() = java.net.URLEncoder.encode(this, "UTF-8")
}
