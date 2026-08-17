package com.stardima

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Element
import java.net.URLDecoder

class StarDimaProvider : MainAPI() {
    override var mainUrl = "https://www.stardima.com"
    override var name = "ستارديما"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Anime,
        TvType.Live
    )

    private val mapper = jacksonObjectMapper()
    private val hw = "https://v2.hyperwatching.com"
    private val strema = "https://strema.top"

    companion object {
        private const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
        private val UA_HEADERS = mapOf("user-agent" to MOBILE_UA)
        private val XHR_HEADERS = mapOf("X-Requested-With" to "XMLHttpRequest")
    }

    override val mainPage = mainPageOf(
        "" to "الرئيسية",
        "aflam" to "أفلام",
        "mosalsalat" to "مسلسلات",
        "live" to "بث المباشر"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val list = ArrayList<HomePageList>()
        when (request.data) {
            "aflam", "mosalsalat" -> {
                val items = fetchListing(request.data, page)
                if (items.isEmpty()) throw ErrorLoadingException()
                list.add(HomePageList(request.name ?: "قائمة", items))
                return newHomePageResponse(list)
            }
            "live" -> {
                if (page > 1) return newHomePageResponse(emptyList())
                val doc = app.get(mainUrl).document
                val embla = doc.selectFirst("div[id^='embla-bth-almbashr']") ?: return newHomePageResponse(emptyList())
                val items = embla.select("div.embla__slide").mapNotNull { it.videoCardResponse() }
                    .distinctBy { it.url }
                if (items.isEmpty()) throw ErrorLoadingException()
                list.add(HomePageList("بث المباشر", items))
                return newHomePageResponse(list)
            }
            else -> {
                if (page > 1) return newHomePageResponse(emptyList())
                val doc = app.get(mainUrl).document
                doc.select("div.embla").forEach { embla ->
                    val heading = embla.previousElementSibling()
                    val title = heading?.selectFirst("h2")?.text()?.trim()
                        ?: heading?.selectFirst("h4")?.text()?.trim()
                        ?: return@forEach
                    if (title.isBlank()) return@forEach
                    val items = embla.select("div.embla__slide").mapNotNull { it.videoCardResponse() }
                        .distinctBy { it.url }
                    if (items.isNotEmpty()) list.add(HomePageList(title, items))
                }
                if (list.isEmpty()) throw ErrorLoadingException()
                return newHomePageResponse(list)
            }
        }
    }

    private suspend fun fetchListing(path: String, page: Int): List<SearchResponse> {
        val root = mapper.readTree(
            app.get("$mainUrl/$path", params = mapOf("page" to page.toString()), headers = XHR_HEADERS).text
        )
        val out = ArrayList<SearchResponse>()
        root["videos"]?.forEach { v ->
            val title = v["title"]?.asText() ?: return@forEach
            val url = v["url"]?.asText() ?: return@forEach
            val poster = v["poster_url"]?.asText()?.takeUnless { it.contains("placehold") }
            val year = v["year"]?.asText()?.toIntOrNull()
            val type = if (url.contains("/movie/")) TvType.Movie else TvType.TvSeries
            out.add(newAnimeSearchResponse(title, url, type) {
                this.posterUrl = poster
                this.year = year
            })
        }
        return out
    }

    private fun Element.videoCardResponse(): SearchResponse? {
        val a = selectFirst("a[href*='/tvshow/'], a[href*='/movie/'], a[href*='/live/']") ?: return null
        val url = a.attr("abs:href")
        if (url.isBlank()) return null
        val img = selectFirst("img")
        val alt = img?.attr("alt").orEmpty()
        var title = when {
            alt.startsWith("Poster for ") -> alt.removePrefix("Poster for ").trim()
            alt.startsWith("Logo for ") -> alt.removePrefix("Logo for ").trim()
            else -> a.attr("title").ifBlank { a.text() }.trim()
        }.ifBlank { return null }
        val poster = img?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.takeUnless { it.startsWith("data:") || it.contains("placehold") }
        val type = when {
            url.contains("/live/") -> {
                title = title.removePrefix("بث المباشر ").removePrefix("البث المباشر | ").trim()
                TvType.Live
            }
            url.contains("/movie/") -> TvType.Movie
            else -> TvType.TvSeries
        }
        return if (type == TvType.Live) {
            newLiveSearchResponse(title, url, type) { this.posterUrl = poster }
        } else {
            newAnimeSearchResponse(title, url, type) { this.posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/search",
            params = mapOf("query" to query),
            headers = XHR_HEADERS
        ).document
        return doc.select("div#video-grid > div").mapNotNull { it.videoCardResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/live/")) {
            val html = app.get(url, headers = UA_HEADERS).text
            val title = Regex("""og:title"?\s+content="([^"]+)"""").find(html)?.groupValues?.get(1)
                ?.substringBefore(" | ")?.trim()
                ?: Regex("""<title>([^<]+)</title>""").find(html)?.groupValues?.get(1)?.substringBefore(" - ")?.trim()
                ?: return null
            val poster = Regex("""property="og:image"[^>]*content="([^"]+)"""").find(html)?.groupValues?.get(1)
            val streamUrl = Regex("""streamUrl\s*=\s*["']([^"']+)["']""").find(html)
                ?.groupValues?.get(1)?.replace("\\/", "/") ?: return null
            return newLiveStreamLoadResponse(title, url, streamUrl) {
                this.posterUrl = poster
            }
        }

        val doc = app.get(url).document
        val title = doc.selectFirst("h1[class*='text-4xl']")?.text()?.trim()
            ?: doc.selectFirst("section[style*='background-image'] h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" | ")?.trim()
            ?: return null
        val poster = doc.selectFirst("section[style*='background-image']")?.let {
            Regex("""url\(['"]?([^'")]+)['"]?\)""").find(it.attr("style"))?.groupValues?.get(1)
        }
        val plot = doc.selectFirst("p[class*='line-clamp']")?.text()?.trim()
        val year = doc.selectFirst("div.info-item")?.text()?.toIntOrNull()
        val playUrl = doc.selectFirst("a[href*='/play/']")?.attr("abs:href")

        if (url.contains("/movie/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, playUrl ?: url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        val allEpisodes = ArrayList<Episode>()
        if (playUrl != null) {
            val playDoc = app.get(playUrl, headers = UA_HEADERS).document
            val seasons = ArrayList<Pair<String, Int>>()
            playDoc.select("a.season-item[data-season-id]").forEach { el ->
                val sid = el.attr("data-season-id")
                if (sid.isNotBlank()) {
                    val num = Regex("""S(\d+)""").find(el.attr("data-season-number"))
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    seasons.add(sid to num)
                }
            }
            if (seasons.isEmpty()) {
                playDoc.selectFirst("#episodes-list-container[data-initial-season-id]")
                    ?.attr("data-initial-season-id")?.let { seasons.add(it to 1) }
            }
            for ((seasonId, seasonNum) in seasons.distinctBy { it.first }) {
                try {
                    val root = mapper.readTree(
                        app.get("$mainUrl/series/season/$seasonId", headers = XHR_HEADERS).text
                    )
                    root["episodes"]?.forEach { ep ->
                        val epUrl = ep["watch_url"]?.asText() ?: return@forEach
                        val epName = ep["title"]?.asText()?.ifBlank { null }
                        val epNum = ep["episode_number"]?.asInt(0)
                        if (epUrl.isNotBlank()) {
                            allEpisodes.add(newEpisode(epUrl) {
                                name = epName ?: epNum?.toString() ?: "حلقة"
                                episode = epNum
                                season = seasonNum
                            })
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (allEpisodes.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Movie, playUrl ?: url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            when {
                data.contains("$strema/embed/") -> crackStrema(data, subtitleCallback, callback)
                data.contains("$hw/watch/") -> resolveHwWatch(data, subtitleCallback, callback)
                data.contains("/play/") -> {
                    val html = app.get(data).text
                    val frame = Regex("""iframe[^>]+src="([^"]+)"""").find(html)?.groupValues?.get(1) ?: return false
                    if (frame.contains("$hw/watch/")) resolveHwWatch(frame, subtitleCallback, callback)
                    else resolveStreamUrl(frame, subtitleCallback, callback)
                }
                data.contains("elahmad.com") -> resolveElahmad(data, subtitleCallback, callback)
                else -> resolveStreamUrl(data, subtitleCallback, callback)
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolveHwWatch(
        watchUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(watchUrl, headers = mapOf("user-agent" to MOBILE_UA, "Referer" to mainUrl)).document
        val pageData = doc.selectFirst("#app")?.attr("data-page") ?: return false
        val root = try { mapper.readTree(pageData) } catch (_: Exception) { return false }
        val hashid = root.path("props").path("video").path("hashid").asText()
        val servers = root.path("props").path("video").path("servers")
        if (hashid.isBlank() || !servers.isArray()) return false

        var found = false
        val seen = mutableSetOf<String>()
        for (srv in servers) {
            if (srv.path("status").asText() != "completed") continue
            val sid = srv.path("id").asInt(0)
            if (sid <= 0) continue
            try {
                val j = mapper.readTree(
                    app.get(
                        "$hw/embed/$hashid/server/$sid/url",
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to watchUrl)
                    ).text
                )
                val streamUrl = j.path("watch_url").asText()
                if (streamUrl.isBlank() || !seen.add(streamUrl)) continue
                resolveStreamUrl(streamUrl, subtitleCallback, callback)
                found = true
            } catch (_: Exception) {
            }
        }
        return found
    }

    private suspend fun resolveStreamUrl(
        streamUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (streamUrl.contains("$strema/embed2/")) {
            val inner = innerFromStrema(streamUrl)
            if (inner != null) {
                return try {
                    loadExtractor(inner, subtitleCallback, callback)
                } catch (_: Exception) {
                    false
                }
            }
        }
        return try {
            loadExtractor(streamUrl, subtitleCallback, callback)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolveElahmad(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(url, headers = UA_HEADERS).text
        var found = false
        Regex("""https?://[^"'\s<>]+\.m3u8(?:[^"'\s<>]*)""").findAll(html).forEach { m ->
            val file = m.value
            if (file.isNotBlank() && !file.contains("playerjs")) {
                callback.invoke(
                    newExtractorLink(source = name, name = "$name - Live", url = file) {
                        this.referer = url
                        this.quality = getQualityFromName("Original")
                        this.type = ExtractorLinkType.M3U8
                    }
                )
                found = true
            }
        }
        return found
    }

    private suspend fun crackStrema(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(embedUrl, headers = UA_HEADERS).text
        val kaken = Regex("""window\.kaken\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return false
        val root = try {
            mapper.readTree(
                app.post(
                    "$strema/api/",
                    requestBody = kaken.toRequestBody("text/plain; charset=UTF-8".toMediaType()),
                    headers = mapOf("Referer" to embedUrl)
                ).text
            )
        } catch (_: Exception) {
            return false
        }
        var found = false
        root["sources"]?.forEach { s ->
            val file = s["file"]?.asText() ?: return@forEach
            val label = s["label"]?.asText()?.ifBlank { "HLS" } ?: "HLS"
            if (file.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(source = name, name = "$name - $label", url = file) {
                        this.referer = embedUrl
                        this.quality = getQualityFromName(label)
                        this.type = ExtractorLinkType.M3U8
                    }
                )
                found = true
            }
        }
        return found
    }

    private fun innerFromStrema(url: String): String? {
        val i = url.indexOf("id=")
        if (i < 0) return null
        val raw = url.substring(i + 3).substringBefore("&")
        if (raw.isBlank()) return null
        return try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            null
        }
    }
}
