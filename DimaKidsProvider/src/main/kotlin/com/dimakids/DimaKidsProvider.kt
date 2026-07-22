package com.dimakids

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DimaKidsProvider : MainAPI() {
    override var mainUrl = "https://www.dimakids.com"
    override var name = "DimaKids"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Cartoon, TvType.Anime, TvType.AnimeMovie)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList())

        val homePageList = ArrayList<HomePageList>()

        if (request.data == "series") {
            val doc = app.get("$mainUrl/cartoon.php?next=1").document
            val items = doc.select("a[href*=-anime-streaming.html]").mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) homePageList.add(HomePageList(request.name, items))
        } else if (request.data == "movies") {
            val doc = app.get("$mainUrl/movies.php?next=1").document
            val items = doc.select("a[href*=-movies-streaming.html]").mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) homePageList.add(HomePageList(request.name, items))
        }

        return newHomePageResponse(homePageList)
    }

    override val mainPage = mainPageOf(
        "series" to "المسلسلات",
        "movies" to "الأفلام"
    )

    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank()) return null
        val title = selectFirst("p, .episode-title, .cinema-title, .series-title")?.text()?.trim()
            ?: attr("title").ifBlank { null }
            ?: return null
        val img = selectFirst("img")?.attr("src")
        val type = if (href.contains("-movies-streaming")) TvType.AnimeMovie else TvType.Cartoon
        return newAnimeSearchResponse(title, href, type) { this.posterUrl = img }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()

        val seriesDoc = app.get("$mainUrl/cartoon.php?s=${java.net.URLEncoder.encode(query, "utf-8")}").document
        results.addAll(seriesDoc.select("a[href*=-anime-streaming.html]").mapNotNull { it.toSearchResult() })

        val moviesDoc = app.get("$mainUrl/movies.php?s=${java.net.URLEncoder.encode(query, "utf-8")}").document
        results.addAll(moviesDoc.select("a[href*=-movies-streaming.html]").mapNotNull { it.toSearchResult() })

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl/$url"
        val doc = app.get(fullUrl).document

        if (fullUrl.contains("-movies-streaming")) {
            val title = doc.selectFirst("title")?.text()?.substringBefore("|")?.trim() ?: return null
            val poster = doc.selectFirst("img[src*=files.dimakids]")?.attr("src")
            val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            return newMovieLoadResponse(title, fullUrl, TvType.AnimeMovie, fullUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val title = doc.selectFirst("title")?.text()?.substringBefore("|")?.substringBefore("-")?.trim() ?: return null
        val poster = doc.selectFirst("img[src*=files.dimakids]")?.attr("src")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
        val seriesBase = url.substringBeforeLast("-anime-streaming")
        val seriesId = seriesBase.substringAfterLast("-").ifBlank { null }

        val episodes = doc.select("div.episode-item").mapNotNull { el ->
            val epId = el.attr("data-episode-id")
            val epNum = el.selectFirst("div.episode-number")?.text()?.toIntOrNull()
            val thumb = el.selectFirst("img")?.attr("src")
            if (epId.isNullOrBlank()) return@mapNotNull null
            val epUrl = if (seriesId != null && url.contains("-anime-streaming")) {
                val hash = seriesBase.substringBeforeLast("-")
                "$hash-$seriesId-$epId.html"
            } else {
                val base = fullUrl.substringBeforeLast("/")
                "$base/$epId.html"
            }
            newEpisode(epUrl) {
                name = "الحلقة ${epNum ?: epId}"
                episode = epNum
                posterUrl = thumb
            }
        }

        return newTvSeriesLoadResponse(title, fullUrl, TvType.Cartoon, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fullUrl = if (data.startsWith("http")) data else "$mainUrl/$data"
        val html = app.get(fullUrl).text

        val videoSrc = Regex("""const videoSrc = "([^"]+)""").find(html)?.groupValues?.get(1) ?: return false

        val finalUrl = if (videoSrc.startsWith("//")) "https:$videoSrc" else videoSrc

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = finalUrl,
            ) {
                referer = mainUrl
                quality = if (finalUrl.contains("1080")) 1080
                    else if (finalUrl.contains("720")) 720
                    else Qualities.Unknown.value
            }
        )
        return true
    }
}
