package com.rdx.cinevood

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class CineVoodProvider : MainAPI() {

    override var mainUrl        = "https://one.1cinevood.watch"
    override var name           = "CineVood"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang           = "en"
    override val hasMainPage    = true
    override val hasChromecastSupport = true

    override val mainPage = mainPageOf(
        "$mainUrl/page/"                           to "Latest",
        "$mainUrl/bollywood/page/"                 to "Bollywood",
        "$mainUrl/hollywood/page/"                 to "Hollywood",
        "$mainUrl/tamil/page/"                     to "Tamil",
        "$mainUrl/telugu/page/"                    to "Telugu",
        "$mainUrl/malayalam/page/"                 to "Malayalam",
        "$mainUrl/kannada/page/"                   to "Kannada",
        "$mainUrl/hindi-dubbed/south-dubbed/page/" to "South Dubbed",
        "$mainUrl/web-series/page/"                to "Web Series",
        "$mainUrl/tv-shows/page/"                  to "TV Shows"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page"
        val doc = app.get(url, timeout = 120, headers = mapOf("User-Agent" to USER_AGENT)).document
        val items = doc.parsePostList()
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/?s=${query.encodeUri()}",
            timeout = 120,
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document
        return doc.parsePostList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc    = app.get(url, timeout = 120, headers = mapOf("User-Agent" to USER_AGENT)).document
        val title  = doc.selectFirst("h1.title.single-title.entry-title, h1.entry-title, h1")
                        ?.text()?.trim() ?: return null
        val poster = doc.selectFirst("img[src*=tmdb], img[src*=imgpress], img[src*=imgshare]")
                        ?.attr("abs:src")
                     ?: doc.selectFirst(".featured-thumbnail img")?.attr("abs:src")
        val plot   = doc.selectFirst("div.thecontent p")?.text()?.trim()
        val tags   = doc.select("div.thecategory a").map { it.text() }
        val year   = Regex("""\b(20\d{2})\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val isSeries = tags.any {
                           it.lowercase().contains("web series") ||
                           it.lowercase().contains("tv show") } ||
                       title.lowercase().contains("season") ||
                       title.contains(Regex("""S\d{2}E\d{2}""")) ||
                       url.contains("web-series") || url.contains("tv-shows")

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, doc.parseEpisodes(url)) {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, timeout = 120, headers = mapOf("User-Agent" to USER_AGENT)).document
        doc.extractLinks(callback)
        return true
    }

    private fun Document.parsePostList(): List<SearchResponse> {
        return select("article.latestPost, article.g.post, article.post").mapNotNull { el ->
            val a      = el.selectFirst("a[href][title], h2 a, h3 a") ?: return@mapNotNull null
            val href   = a.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title  = a.attr("title").ifBlank { a.text() }.trim().ifBlank { return@mapNotNull null }
            val poster = el.selectFirst("img")?.attr("abs:src")
            val isSeries = title.lowercase().contains("season") ||
                           title.contains(Regex("""S\d{2}E\d{2}""")) ||
                           href.contains("web-series") || href.contains("tv-shows")
            if (isSeries)
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            else
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    private fun Document.parseEpisodes(pageUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val headings = select("h2, h3, h4, strong").filter { el ->
            el.text().matches(Regex("""(?i).*(episode|ep\.?|e\d{2}).+"""))
        }
        if (headings.isEmpty()) {
            episodes.add(newEpisode(pageUrl) {
                this.name = "Watch / Download"; this.episode = 1; this.season = 1
            })
        } else {
            headings.forEachIndexed { index, heading ->
                val epNum = Regex("""\d+""").find(heading.text())?.value?.toIntOrNull() ?: (index + 1)
                episodes.add(newEpisode(pageUrl) {
                    this.name = heading.text().trim(); this.episode = epNum; this.season = 1
                })
            }
        }
        return episodes
    }

    private suspend fun Document.extractLinks(callback: (ExtractorLink) -> Unit) {
        // 1 — Embedded iframes (vidnest, streamtape etc)
        select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").trim()
            if (src.isNotBlank()) {
                try { loadExtractor(src, mainUrl, { }, callback) } catch (_: Exception) {}
            }
        }

        // 2 — OxxFile links
        select("a[href*=oxxfile]").forEach { el ->
            val href    = el.attr("abs:href").trim()
            val label   = el.previousElementSibling()?.text()?.trim() ?: el.text().trim()
            val quality = resolveQuality(label)
            try {
                val finalUrl = resolveOxxFile(href) ?: return@forEach
                callback(
                    newExtractorLink(
                        source = name,
                        name   = "$name ${qualityLabel(quality, label)}",
                        url    = finalUrl,
                        type   = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = quality
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)
                    }
                )
            } catch (_: Exception) {}
        }

        // 3 — HubCloud / other supported extractors
        select("a[href*=hubcloud], a[href*=streamtape], a[href*=dood]").forEach { el ->
            val href = el.attr("abs:href").trim()
            try { loadExtractor(href, mainUrl, { }, callback) } catch (_: Exception) {}
        }
    }

    private suspend fun resolveOxxFile(url: String): String? {
        return try {
            val resp = app.get(
                url, timeout = 60,
                allowRedirects = true,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)
            )
            resp.document.selectFirst(
                "a[href*=.mkv], a[href*=.mp4], " +
                "a[href*=drive.google.com], " +
                "a[href*=hubcloud], a[href*=streamtape], " +
                "source[src]"
            )?.let {
                it.attr("abs:href").ifBlank { it.attr("abs:src") }
            } ?: if (resp.url.contains(".mkv") || resp.url.contains(".mp4")) resp.url else null
        } catch (_: Exception) { null }
    }

    private fun resolveQuality(label: String): Int {
        val text = label.lowercase()
        return when {
            text.contains("2160") || text.contains("4k") -> 2160
            text.contains("1080")                        -> 1080
            text.contains("720")                         -> 720
            text.contains("480")                         -> 480
            text.contains("360")                         -> 360
            else                                         -> -1
        }
    }

    private fun qualityLabel(quality: Int, fallback: String): String = when (quality) {
        2160 -> "2160p 4K"
        1080 -> "1080p"
        720  -> "720p"
        480  -> "480p"
        360  -> "360p"
        else -> fallback.take(30).ifBlank { "Unknown" }
    }

    private fun String.encodeUri() = java.net.URLEncoder.encode(this, "UTF-8")

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; SM-G973F) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
