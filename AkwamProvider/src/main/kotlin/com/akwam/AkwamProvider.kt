package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AkwamProvider : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "أكوام"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    private fun Element.poster(): String? {
        return selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
    }

    private fun newSearchResult(el: Element): SearchResponse? {
        val a = el.selectFirst("h3.entry-title a") ?: return null
        val title = a.text().trim().takeIf { it.isNotEmpty() } ?: return null
        val href = el.selectFirst("a")?.attr("abs:href") ?: return null
        val poster = el.poster()
        return newAnimeSearchResponse(title, "$href#${poster ?: ""}") { this.posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.isNullOrBlank()) {
            val sections = listOf(
                "$mainUrl/movies" to "أحدث الأفلام",
                "$mainUrl/series" to "أحدث المسلسلات",
                "$mainUrl/shows" to "العروض",
                "$mainUrl/series?section=29" to "مسلسلات عربي",
                "$mainUrl/series?section=32" to "مسلسلات تركي",
                "$mainUrl/series?section=33" to "مسلسلات اسيوية",
                "$mainUrl/series?section=30" to "مسلسلات اجنبي",
                "$mainUrl/series?section=31" to "مسلسلات هندي",
                "$mainUrl/movies?section=29" to "أفلام عربي",
                "$mainUrl/movies?section=32" to "أفلام تركي",
                "$mainUrl/movies?section=33" to "أفلام اسيوية",
                "$mainUrl/movies?section=30" to "أفلام اجنبي",
                "$mainUrl/movies?section=31" to "أفلام هندي"
            )

            val homeList = ArrayList<HomePageList>()
            for ((baseUrl, sectionTitle) in sections) {
                try {
                    val url = if (page > 1) {
                        if (baseUrl.contains("?")) "$baseUrl&page=$page" else "$baseUrl?page=$page"
                    } else baseUrl
                    val doc = app.get(url).document
                    val items = doc.select("div.col-lg-auto.col-md-4.col-6").mapNotNull { newSearchResult(it) }
                    if (items.isNotEmpty()) homeList.add(HomePageList(sectionTitle, items))
                } catch (_: Exception) { }
            }
            if (homeList.isEmpty()) throw ErrorLoadingException()
            return newHomePageResponse(homeList)
        }

        val base = request.data.trim()
        val fullBase = if (base.startsWith("http")) base else "$mainUrl$base"
        val pageUrl = if (page > 1) {
            when {
                fullBase.endsWith("/page/") -> "$fullBase$page/"
                fullBase.contains("?") -> "$fullBase&page=$page"
                else -> "$fullBase?page=$page"
            }
        } else fullBase

        val doc = app.get(pageUrl).document
        val list = doc.select("div.col-lg-auto.col-md-4.col-6").mapNotNull { newSearchResult(it) }
        if (list.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(listOf(HomePageList(request.name ?: "قائمة", list)))
    }

    override val mainPage = mainPageOf(
        "" to "الرئيسية",
        "/movies" to "أفلام",
        "/series" to "مسلسلات",
        "/shows" to "عروض"
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?q=${URLEncoder.encode(query, "utf-8")}").document
        return doc.select("div.col-lg-auto.col-md-4.col-6").mapNotNull { newSearchResult(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split("#")
        val pageUrl = parts[0]
        val poster = parts.getOrNull(1)?.ifBlank { null }

        val mainDoc = app.get(pageUrl, headers = mapOf("Referer" to mainUrl)).document

        val title = mainDoc.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val plot = mainDoc.selectFirst("h2:contains(قصة المسلسل) + div > p")?.text()?.trim()
            ?: mainDoc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val rating = mainDoc.selectFirst("span.mx-2:contains(/)")
            ?.text()?.substringAfter("/")?.trim()?.toFloatOrNull()
        val score = rating?.let { Score.from10(it) }

        val tags = mainDoc.select("div.font-size-16.text-white a[href*='/genre/'], div.font-size-16.text-white a[href*='/category/']")
            .map { it.text() }

        val year = mainDoc.selectFirst("div.font-size-16.text-white a[href*='/year/']")?.text()?.toIntOrNull()

        val recommendations = mainDoc.select("div.widget-body div[class*='col-']").mapNotNull {
            val recTitle = it.selectFirst("h3 a")?.text()?.trim() ?: return@mapNotNull null
            val recHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val recPoster = it.poster()
            newMovieSearchResponse(recTitle, "$recHref#${recPoster ?: ""}", TvType.Movie) { this.posterUrl = recPoster }
        }

        val seasonsMap = linkedMapOf<String, Pair<String, String>>()
        val currentSeasonName = mainDoc.selectFirst("h1.entry-title")?.text()?.trim() ?: title
        seasonsMap[pageUrl] = Pair(currentSeasonName, pageUrl)

        mainDoc.select("div.widget-body > a.btn[href*='/series/']").forEach { a ->
            val href = a.attr("href")
            if (href.isNotBlank()) {
                val seasonUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                val seasonName = a.text().trim()
                if (!seasonsMap.containsKey(seasonUrl)) {
                    seasonsMap[seasonUrl] = Pair(seasonName, seasonUrl)
                }
            }
        }

        val directEpisodes = mainDoc.select("div#series-episodes div[class*='col-']")
        val isSeries = seasonsMap.size > 1 || directEpisodes.isNotEmpty()

        if (!isSeries) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.recommendations = recommendations
            }
        }

        val sortedSeasons = seasonsMap.values.sortedBy { getSeasonNumber(it.first) }
        val allEpisodes = mutableListOf<Episode>()
        val docCache = mutableMapOf(pageUrl to mainDoc)

        for ((seasonName, seasonUrl) in sortedSeasons) {
            val seasonNumber = getSeasonNumber(seasonName)
            val seasonDoc = docCache.getOrPut(seasonUrl) { app.get(seasonUrl, headers = mapOf("Referer" to mainUrl)).document }
            seasonDoc.select("div#series-episodes div.col-lg-4, div#series-episodes div.col-md-6").forEach { container ->
                val episodeLink = container.selectFirst("a[href*='/episode/']") ?: return@forEach
                val epUrl = episodeLink.attr("abs:href")
                val epName = episodeLink.selectFirst("h2")?.text()?.trim() ?: episodeLink.text().trim()
                val epPoster = container.poster()
                if (epUrl.isNotBlank() && epName.isNotBlank()) {
                    allEpisodes.add(newEpisode(epUrl) {
                        name = epName
                        this.season = seasonNumber
                        this.episode = getEpisodeNumberFromString(epName)
                        this.posterUrl = epPoster
                    })
                }
            }
        }

        if (allEpisodes.isEmpty()) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.recommendations = recommendations
            }
        }

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, allEpisodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = score
            this.recommendations = recommendations
        }
    }

    private fun getEpisodeNumberFromString(name: String): Int? {
        return Regex("""\d+""").findAll(name).lastOrNull()?.value?.toIntOrNull()
    }

    private fun getSeasonNumber(seasonName: String): Int {
        val map = mapOf(
            "الاول" to 1, "الأول" to 1, "الثاني" to 2, "الثالث" to 3,
            "الرابع" to 4, "الخامس" to 5, "السادس" to 6, "السابع" to 7,
            "الثامن" to 8, "التاسع" to 9, "العاشر" to 10, "الحادي عشر" to 11,
            "الثاني عشر" to 12, "الثالث عشر" to 13, "الرابع عشر" to 14,
            "الخامس عشر" to 15, "السادس عشر" to 16, "السابع عشر" to 17,
            "الثامن عشر" to 18, "التاسع عشر" to 19, "العشرون" to 20,
            "الحادي والعشرون" to 21, "الثاني والعشرون" to 22,
            "الثالث والعشرون" to 23, "الرابع والعشرون" to 24,
            "الخامس والعشرون" to 25, "السادس والعشرون" to 26,
            "السابع والعشرون" to 27, "الثامن والعشرون" to 28,
            "التاسع والعشرون" to 29, "الثلاثون" to 30
        )
        val lower = seasonName.lowercase()
        for ((k, v) in map) {
            if (lower.contains(k)) return v
        }
        val nums = Regex("\\d+").findAll(seasonName).map { it.value.toIntOrNull() ?: 0 }.toList()
        if (nums.isNotEmpty()) return nums.last()
        return 999
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val step1Doc = app.get(data).document

            val watchPath = step1Doc.selectFirst("a.link-show")?.attr("href")?.ifBlank { null }
                ?: step1Doc.selectFirst("a.link-show")?.attr("abs:href")
                ?: return false
            val pageId = step1Doc.selectFirst("input#page_id")?.attr("value")?.ifBlank { null }
                ?: step1Doc.selectFirst("input#page_id")?.attr("data-value")
                ?: return false

            val watchSuffix = watchPath.substringAfter("watch", watchPath).trim()
            val watchUrl = "${mainUrl.trimEnd('/')}/watch${watchSuffix.trimEnd('/')}/$pageId"
                .replace("//watch", "/watch")
                .replace(":/", "://")

            val step2Doc = try {
                app.get(watchUrl).document
            } catch (_: Exception) {
                app.get(watchUrl, headers = mapOf("Referer" to data)).document
            }

            val seen = mutableSetOf<String>()
            for (srcEl in step2Doc.select("source[src]")) {
                val videoUrl = srcEl.attr("abs:src").ifBlank { srcEl.attr("src") }.trim()
                if (videoUrl.isBlank() || !seen.add(videoUrl)) continue
                val qualityAttr = srcEl.attr("size").ifBlank { srcEl.attr("label") }.ifBlank { "direct" }
                callback(newExtractorLink(name, name, videoUrl) {
                    this.referer = data
                    this.quality = getQualityFromName(qualityAttr)
                })
            }
            return seen.isNotEmpty()
        } catch (_: Exception) {
            return false
        }
    }
}
