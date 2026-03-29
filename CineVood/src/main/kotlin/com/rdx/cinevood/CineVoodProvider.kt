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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data
                  else "${request.data}page/$page/"
        val doc = app.get(
            url,
            timeout = 120,
            headers = mapOf(
                "User-Agent"      to USER_AGENT,
                "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5"
            )
        ).document
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
        val doc    = app.get(
            url,
            timeout = 120,
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document

        val title  = doc.selectFirst(
            "h1.title.single-title.entry-title, h1.entry-title, h1.title, h1"
        )?.text()?.trim() ?: return null

        val poster = doc.selectFirst(
            "img[src*=tmdb], img[src*=imgpress], img[src*=imgshare], " +
            ".featured-thumbnail img, .post-thumbnail img, " +
            "div.thecontent img"
        )?.attr("abs:src")

        val plot   = doc.selectFirst("div.thecontent p")?.text()?.trim()
        val tags   = doc.select("div.thecategory a").map { it.text() }
        val year   = Regex("""\b(20\d{2})\b""").find(title)
                        ?.groupValues?.get(1)?.toIntOrNull()

        val isSeries = tags.any {
                           it.lowercase().contains("web series") ||
                           it.lowercase().contains("tv show") } ||
                       title.lowercase().contains("season") ||
                       title.contains(Regex("""S\d{2}E\d{2}""")) ||
                       url.contains("web-series") ||
                       url.contains("tv-shows")

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, doc.parseEpisodes(url)) {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(
            data,
            timeout = 120,
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document

        var foundLinks = false

        // 1 — vidnest.io iframe (primary player)
        doc.select("iframe[src*=vidnest]").forEach { iframe ->
            val src = iframe.attr("abs:src").trim()
            if (src.isNotBlank()) {
                try {
                    loadExtractor(src, mainUrl, subtitleCallback, callback)
                    foundLinks = true
                } catch (_: Exception) {}
            }
        }

        // 2 — All other iframes
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").trim()
            if (src.isNotBlank() && !src.contains("vidnest")) {
                try {
                    loadExtractor(src, mainUrl, subtitleCallback, callback)
                    foundLinks = true
                } catch (_: Exception) {}
            }
        }

        // 3 — OxxFile download links
        doc.select("a[href*=oxxfile]").forEach { el ->
            val href    = el.attr("abs:href").trim()
            val label   = el.previousElementSibling()?.text()?.trim()
                          ?: el.parent()?.previousElementSibling()?.text()?.trim()
                          ?: el.text().trim()
            val quality = resolveQuality(label)
            try {
                val streamUrl = resolveOxxFile(href)
                if (!streamUrl.isNullOrBlank()) {
                    // If OxxFile resolved to another extractor
                    if (streamUrl.contains("hubcloud") ||
                        streamUrl.contains("streamtape") ||
                        streamUrl.contains("vidnest") ||
                        streamUrl.contains("dood")) {
                        loadExtractor(streamUrl, mainUrl, subtitleCallback, callback)
                    } else {
                        callback(
                            newExtractorLink(
                                source = name,
                                name   = "$name ${qualityLabel(quality, label)}",
                                url    = streamUrl,
                                type   = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = quality
                                this.headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer"    to mainUrl
                                )
                            }
                        )
                    }
                    foundLinks = true
                }
            } catch (_: Exception) {}
        }

        // 4 — HubCloud
        doc.select("a[href*=hubcloud]").forEach { el ->
            val href = el.attr("abs:href").trim()
            try {
                loadExtractor(href, mainUrl, subtitleCallback, callback)
                foundLinks = true
            } catch (_: Exception) {}
        }

        // 5 — StreamTape
        doc.select("a[href*=streamtape]").forEach { el ->
            val href = el.attr("abs:href").trim()
            try {
                loadExtractor(href, mainUrl, subtitleCallback, callback)
                foundLinks = true
            } catch (_: Exception) {}
        }

        // 6 — DoodStream
        doc.select("a[href*=dood], a[href*=doodstream]").forEach { el ->
            val href = el.attr("abs:href").trim()
            try {
                loadExtractor(href, mainUrl, subtitleCallback, callback)
                foundLinks = true
            } catch (_: Exception) {}
        }

        return foundLinks
    }

    // ── Parse post list ───────────────────────────────────────────────────
    private fun Document.parsePostList(): List<SearchResponse> {
        // CineVood uses article.latestPost for listing pages
        val posts = select("article.latestPost, article.g.post, article.post")
        return posts.mapNotNull { el ->
            val a = el.selectFirst("a[href][title], h2 a, h3 a, .front-view-title a")
                    ?: return@mapNotNull null
            val href  = a.attr("abs:href").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
            val title = a.attr("title").ifBlank { a.text() }.trim()
                        .ifBlank { return@mapNotNull null }
            val poster = el.selectFirst("img")?.attr("abs:src")
            val isSeries = title.lowercase().contains("season") ||
                           title.contains(Regex("""S\d{2}E\d{2}""")) ||
                           href.contains("web-series") ||
                           href.contains("tv-shows")
            if (isSeries)
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            else
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
        }
    }

    // ── Parse episodes ────────────────────────────────────────────────────
    private fun Document.parseEpisodes(pageUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val headings = select("h2, h3, h4, strong").filter { el ->
            el.text().matches(Regex("""(?i).*(episode|ep\.?|e\d{2}).+"""))
        }
        if (headings.isEmpty()) {
            episodes.add(newEpisode(pageUrl) {
                this.name    = "Watch / Download"
                this.episode = 1
                this.season  = 1
            })
        } else {
            headings.forEachIndexed { index, heading ->
                val epNum = Regex("""\d+""").find(heading.text())
                    ?.value?.toIntOrNull() ?: (index + 1)
                episodes.add(newEpisode(pageUrl) {
                    this.name    = heading.text().trim()
                    this.episode = epNum
                    this.season  = 1
                })
            }
        }
        return episodes
    }

    // ── Resolve OxxFile ───────────────────────────────────────────────────
    private suspend fun resolveOxxFile(url: String): String? {
        return try {
            val resp = app.get(
                url,
                timeout = 60,
                allowRedirects = true,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer"    to mainUrl
                )
            )
            val doc = resp.document

            // Find any playable link in the page
            doc.selectFirst(
                "a[href*=hubcloud], " +
                "a[href*=streamtape], " +
                "a[href*=vidnest], " +
                "a[href*=dood], " +
                "a[href*=.mkv], " +
                "a[href*=.mp4], " +
                "a[href*=drive.google.com], " +
                "source[src*=.mkv], " +
                "source[src*=.mp4]"
            )?.let { el ->
                el.attr("abs:href").ifBlank { el.attr("abs:src") }
            } ?: run {
                val finalUrl = resp.url
                if (finalUrl.contains(".mkv") ||
                    finalUrl.contains(".mp4") ||
                    finalUrl.contains("hubcloud") ||
                    finalUrl.contains("streamtape")) finalUrl
                else null
            }
        } catch (_: Exception) { null }
    }

    // ── Quality helpers ───────────────────────────────────────────────────
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
