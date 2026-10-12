package com.witanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.Jsoup
import org.json.JSONObject

class WitAnime : MainAPI() {
    override var mainUrl = "https://witanime.site"
    override var name = "WitAnime"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private suspend fun fetchWithJS(url: String): String {
        return try {
            app.get(url, interceptor = WebViewResolver(interceptUrl = Regex(url))).text
        } catch (_: Exception) {
            try { app.get(url).text } catch (_: Exception) { "" }
        }
    }

    private suspend fun fetchDoc(url: String): org.jsoup.nodes.Document {
        val html = fetchWithJS(url)
        return try { Jsoup.parse(html, url) } catch (_: Exception) { Jsoup.parse("") }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = fetchDoc(mainUrl)
        val homePageList = ArrayList<HomePageList>()
        document.select("section.mb-14").forEach { section ->
            val title = section.selectFirst("h2")?.text()?.trim() ?: return@forEach
            val items = section.select("a.block, a[href*=\"/movie/\"]").mapNotNull { a ->
                val itemUrl = a.attr("href")?.trim() ?: return@mapNotNull null
                if (itemUrl.isBlank()) return@mapNotNull null
                val itemName = a.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
                if (itemName.isBlank()) return@mapNotNull null
                val badge = a.selectFirst(":containsOwn(الحلقة)")?.text()?.trim()
                val finalTitle = if (!badge.isNullOrBlank() && !itemName.contains(badge)) "$itemName - $badge" else itemName
                val itemPoster = a.selectFirst("img")?.attr("src")
                val type = if ("/movie/" in itemUrl) TvType.AnimeMovie else TvType.Anime
                newAnimeSearchResponse(finalTitle, itemUrl, type) { posterUrl = itemPoster }
            }.distinctBy { it.url }
            if (items.isNotEmpty()) homePageList.add(HomePageList(title, items))
        }
        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = fetchDoc("$mainUrl/search?q=$query")
        return document.select("a.block").mapNotNull { a ->
            val href = a.attr("href")?.trim() ?: return@mapNotNull null
            if (href.isBlank()) return@mapNotNull null
            val title = a.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            val poster = a.selectFirst("img")?.attr("src")
            val type = if ("/movie/" in href) TvType.AnimeMovie else TvType.Anime
            newAnimeSearchResponse(title, href, type) { this.posterUrl = poster }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetchDoc(url)
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { "Unknown" }
        val poster = document.select("img[src*=\"/posters/\"]").firstOrNull()?.attr("src")
        val description = document.selectFirst("section p.leading-relaxed")?.text()?.trim()
        val genres = document.select("span[class*=\"bg-white/10\"]").map { it.text().trim() }.filter { it.isNotBlank() }
        val badges = document.select("div.mb-4 span").map { it.text().trim() }
        val isMovie = url.contains("/movie/") || badges.any { it.contains("فيلم") || it.contains("Movie", true) }
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime
        val status = when {
            badges.any { it.contains("مكتمل") } -> ShowStatus.Completed
            isMovie -> ShowStatus.Completed
            else -> ShowStatus.Ongoing
        }
        var year: Int? = null
        document.select("div.grid div").forEach {
            if (it.selectFirst(":containsOwn(السنة)") != null) {
                year = it.text().filter { c -> c.isDigit() }.take(4).toIntOrNull()
            }
        }
        val episodeLinks = document.select("div[data-episode-list] a[href*=\"/watch/\"]")
            .ifEmpty { document.select("a[href*=\"/watch/\"]") }
        val episodes = episodeLinks.distinctBy { it.attr("href") }.mapNotNull { a ->
            val epUrl = a.attr("href")?.trim() ?: return@mapNotNull null
            if (epUrl.isBlank()) return@mapNotNull null
            val epText = a.text().trim().replace(Regex("\\s+"), " ")
            val epNum = Regex("/(\\d+)$").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: epText.replace(Regex("[^0-9]"), "").toIntOrNull()
            newEpisode(epUrl) {
                this.name = epText.ifBlank { if (epNum != null) "الحلقة $epNum" else "مشاهدة" }
                this.episode = epNum
                this.posterUrl = poster
            }
        }
        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            this.year = year
            this.showStatus = status
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val watchResp = try {
                app.get(data, interceptor = WebViewResolver(interceptUrl = Regex.fromLiteral(data)))
            } catch (_: Exception) { app.get(data) }
            val html = watchResp.text
            val cookies = watchResp.cookies
            val csrf = Regex("""<meta name="csrf-token" content="([^"]+)"""").find(html)?.groupValues?.get(1).orEmpty()
            val srcPath = Regex("""sourcesUrl:\s*'((?:\\.|[^'])+)'""").find(html)?.groupValues?.get(1)
                ?.replace("\\/", "/").orEmpty()
            if (srcPath.isBlank()) return false
            val sourcesUrl = if (srcPath.startsWith("http")) srcPath else mainUrl + srcPath
            val apiHeaders = mapOf(
                "Accept" to "application/json",
                "X-CSRF-TOKEN" to csrf,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to data,
            )
            val manifest = try {
                app.post(sourcesUrl, headers = apiHeaders, cookies = cookies).text
            } catch (_: Exception) { return false }
            val root = try { JSONObject(manifest) } catch (_: Exception) { return false }
            val gates = mutableListOf<String>()
            root.optJSONObject("players")?.let { players ->
                players.keys().forEach { q ->
                    players.optJSONArray(q)?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.optString("token")?.takeIf { it.isNotBlank() }?.let {
                                gates.add("$mainUrl/watch/stream-gate/$it")
                            }
                        }
                    }
                }
            }
            root.optJSONObject("downloads")?.let { dls ->
                dls.keys().forEach { q ->
                    dls.optJSONArray(q)?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.optString("token")?.takeIf { it.isNotBlank() }?.let {
                                gates.add("$mainUrl/watch/download-gate/$it")
                            }
                        }
                    }
                }
            }
            for (gate in gates.distinct()) {
                try {
                    val finalUrl = app.get(gate, referer = data, cookies = cookies).url
                    if (finalUrl.isNotBlank() && loadExtractor(finalUrl, data, subtitleCallback, callback)) {
                        found = true
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return found
    }

}
