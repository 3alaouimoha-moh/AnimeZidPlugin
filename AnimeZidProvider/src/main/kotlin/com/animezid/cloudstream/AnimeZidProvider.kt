package com.animezid.cloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AnimeZidProvider : MainAPI() {
    override var mainUrl = "https://animezid.cam"
    override var name = "AnimeZid"
    override var lang = "ar"
    override var hasMainPage = true
    override var hasDownloadSupport = false
    override var hasChromecastSupport = true
    override var supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        Pair("anime", "الأنمي"),
        Pair("movies", "الأفلام"),
        Pair("series", "المسلسلات"),
        Pair("disney-masr", "ديزني بالمصري"),
        Pair("spacetoon", "سبيستون"),
        Pair("newvideos", "أحدث الإضافات"),
        Pair("new-eps", "حلقات جديدة"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/category.php?cat=${request.data}" + if (page > 1) "&page=$page" else ""
        val doc = app.get(url).document
        val items = doc.select("a.movie")
            .ifEmpty { doc.select("a[href*=\"watch.php?vid=\"]") }
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search.php?keywords=$query").document
        return doc.select("a.movie")
            .ifEmpty { doc.select("a[href*=\"watch.php?vid=\"]") }
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.run { if (tagName() == "a") this else select("a").first() } ?: return null
        val href = link.attr("href")
        val title = this.attr("title").ifEmpty { select("span.title").text() }
            .ifEmpty { link.attr("title") } ?: return null
        val img = select("img").first()
        val posterUrl = img?.attr("data-src") ?: img?.attr("src") ?: ""
        val ratingText = select(".r i, .rating i, .i-fav.rating i").text().trim()
        val rating = ratingText.toFloatOrNull()
        val isMovie = title.contains("فيلم", ignoreCase = true)
        val tvType = if (isMovie) TvType.Movie else TvType.Anime
        val seriesName = title.extractSeriesName()

        return newAnimeSearchResponse(seriesName, fixUrl(href), tvType) {
            this.posterUrl = fixUrl(posterUrl)
            this.score = rating
        }
    }

    private fun String.extractSeriesName(): String {
        val clean = removePrefix("انمي ").removePrefix("فيلم ").removePrefix("مسلسل ")
        val episodePattern = Regex("""^(.*?)\s*(?:الحلقة|الجزء)\s+\d+.*""")
        val match = episodePattern.find(clean)
        return match?.groupValues?.get(1)?.trim() ?: clean
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.select("meta[property=og:title]").attr("content")
            .ifEmpty { doc.select("h1 span, .movie_title h1").text() }
            .ifEmpty { throw ErrorLoadingException("Could not find title") }

        val poster = doc.select("meta[property=og:image]").attr("content")
            .ifEmpty {
                doc.select("img.lazy, .movie_img img, .movie_cover img").firstOrNull()
                    ?.attr("data-src") ?: ""
            }

        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val plot = doc.select(".pm-video-description p, .description p, .pm-video-description").text()
        val ratingText = doc.select(".rating i, .i-fav.rating i, .movieTable .rating").text().trim()
        val rating = Regex("""[\d.]+""").find(ratingText)?.groupValues?.firstOrNull()?.toFloatOrNull()
        val tags = doc.select(".movieTable a[href*=\"filter=genres\"]").map { it.text() }
        val quality = doc.select(".movieTable a[href*=\"filter=quality\"]").text().trim()

        val isMovie = title.contains("فيلم", ignoreCase = true)

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrl(poster)
                this.year = year
                this.plot = plot
                this.score = rating
                this.tags = tags
            }
        }

        val episodes = mutableListOf<Episode>()
        val seasonTabs = doc.select(".tab-seasons li")
        val episodeContainers = doc.select(".SeasonsEpisodes")

        if (seasonTabs.isNotEmpty() && episodeContainers.isNotEmpty()) {
            seasonTabs.forEachIndexed { index, seasonTab ->
                val seasonNum = seasonTab.attr("data-serie").toIntOrNull() ?: (index + 1)
                val container = episodeContainers.getOrNull(index) ?: return@forEachIndexed

                container.select("a").forEach { epLink ->
                    val epHref = epLink.attr("href")
                    val epNum = epLink.select("em").text().trim().toIntOrNull() ?: 0
                    val epTitleText = epLink.select("span").text().trim()

                    if (epHref.isNotBlank()) {
                        episodes.add(
                            newEpisode(fixUrl(epHref)) {
                                this.name = epTitleText
                                this.season = seasonNum
                                this.episode = epNum
                                this.posterUrl = fixUrl(poster)
                            }
                        )
                    }
                }
            }
        } else {
            episodes.add(
                newEpisode(url) {
                    this.name = title
                    this.season = 1
                    this.episode = 1
                }
            )
        }

        val seriesName = title.extractSeriesName()

        return newTvSeriesLoadResponse(seriesName, url, TvType.Anime, episodes) {
            this.posterUrl = fixUrl(poster)
            this.year = year
            this.plot = plot
            this.score = rating
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val embedUrls = doc.select("#xservers button[data-embed]").mapNotNull {
            it.attr("data-embed").trim().ifBlank { null }
        }.ifEmpty {
            doc.select("iframe").mapNotNull { it.attr("src").trim().ifBlank { null } }
        }

        for (embedUrl in embedUrls) {
            val fixedUrl = when {
                embedUrl.startsWith("//") -> "https:$embedUrl"
                !embedUrl.startsWith("http") -> "$mainUrl/$embedUrl"
                else -> embedUrl
            }
            loadExtractor(fixedUrl, subtitleCallback, callback)
        }

        return true
    }
}
