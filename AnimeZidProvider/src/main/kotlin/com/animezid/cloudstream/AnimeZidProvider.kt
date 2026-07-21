package com.animezid.cloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlin.math.abs

data class IframeResponse(val props: IframeProps)
data class IframeProps(val streams: StreamsData)
data class StreamsData(val data: List<StreamItem>)
data class StreamItem(val mirrors: List<MirrorItem>, val resolution: String)
data class MirrorItem(val driver: String, val link: String)

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
        Pair("new-movies", "أحدث الافلام المضافة"),
        Pair("dubbed-animation", "أفلام انيميشن مدبلجة"),
        Pair("subbed-animation", "أفلام انيميشن مترجمة"),
        Pair("english-movies", "أفلام أجنبية"),
        Pair("dubbed-movies", "أفلام لايف اكشن مدبلجة"),
        Pair("dubbed-anime", "مسلسلات انمي مدبلجة"),
        Pair("disney-series", "مسلسلات ديزني"),
        Pair("translated-anime", "مسلسلات انيميشن مترجمة"),
        Pair("cartoon", "مسلسلات كرتون"),
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
            this.score = Score.from10(rating)
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
            .ifEmpty { doc.select("title").text() }
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
                this.score = Score.from10(rating)
                this.tags = tags
            }
        }

        val episodes = mutableListOf<Episode>()
        val seenVids = mutableSetOf<String>()
        val currentVid = Regex("""[?&]vid=([^&]+)""").find(url)?.groupValues?.get(1)

        val episodeLinks = doc.select("#movies a.movie[href*=\"watch.php?vid=\"]")
            .ifEmpty { doc.select(".SeasonsEpisodes a[href*=\"watch.php?vid=\"]") }
            .ifEmpty { doc.select("a.movie[href*=\"watch.php?vid=\"]") }

        for (epLink in episodeLinks) {
            val epHref = epLink.attr("href")
            val epVid = Regex("""[?&]vid=([^&]+)""").find(epHref)?.groupValues?.get(1)
            if (epVid == null || epVid == currentVid || !seenVids.add(epVid)) continue

            val epNum = epLink.select("em").text().trim().toIntOrNull()
                ?: Regex("""الحلقة\s*(\d+)""").find(epLink.attr("title").ifEmpty { epLink.text() })
                    ?.groupValues?.get(1)?.toIntOrNull()
                ?: seenVids.size

            val season = epLink.parents().select("[data-serie]").firstOrNull()
                ?.attr("data-serie")?.toIntOrNull()
                ?: 1

            episodes.add(
                newEpisode(fixUrl(epHref)) {
                    this.name = "الحلقة $epNum"
                    this.season = season
                    this.episode = epNum
                    this.posterUrl = fixUrl(poster)
                }
            )
        }

        if (episodes.isEmpty()) {
            val subCats = doc.select("a.movie[href*=\"category.php\"]")
            for (sub in subCats) {
                val subUrl = fixUrl(sub.attr("href"))
                try {
                    val subDoc = app.get(subUrl).document
                    val subLinks = subDoc.select("#movies a.movie[href*=\"watch.php?vid=\"]")
                        .ifEmpty { subDoc.select(".SeasonsEpisodes a[href*=\"watch.php?vid=\"]") }
                        .ifEmpty { subDoc.select("a.movie[href*=\"watch.php?vid=\"]") }
                    for (epLink in subLinks) {
                        val epHref = epLink.attr("href")
                        val epVid = Regex("""[?&]vid=([^&]+)""").find(epHref)?.groupValues?.get(1)
                        if (epVid == null || !seenVids.add(epVid)) continue
                        val epNum = Regex("""الحلقة\s*(\d+)""").find(epLink.attr("title").ifEmpty { epLink.text() })
                            ?.groupValues?.get(1)?.toIntOrNull() ?: seenVids.size
                        episodes.add(
                            newEpisode(fixUrl(epHref)) {
                                this.name = "الحلقة $epNum"
                                this.season = 1
                                this.episode = epNum
                                this.posterUrl = fixUrl(poster)
                            }
                        )
                    }
                } catch (_: Exception) { }
            }
        }

        if (episodes.isEmpty()) {
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
            this.score = Score.from10(rating)
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val vid = Regex("""[?&]vid=([^&]+)""").find(data)?.groupValues?.get(1)
            ?: data.substringAfterLast("/")

        val embedDoc = app.get("$mainUrl/embed.php?vid=$vid").document
        val iframeSrc = embedDoc.selectFirst("iframe")?.attr("src") ?: return false

        val fixedUrl = when {
            iframeSrc.startsWith("//") -> "https:$iframeSrc"
            !iframeSrc.startsWith("http") -> "$mainUrl/$iframeSrc"
            else -> iframeSrc
        }

        val pageText = app.get(fixedUrl).text
        val version = Regex(""""version":"([^"]+)"""").find(pageText)?.groupValues?.get(1)
            ?: return false

        val inertiaHeaders = mapOf(
            "X-Inertia" to "true",
            "X-Inertia-Partial-Component" to "files/mirror/video",
            "X-Inertia-Partial-Data" to "streams",
            "X-Inertia-Version" to version
        )

        val jsonText = app.get(fixedUrl, headers = inertiaHeaders).text
        val response = AppUtils.tryParseJson<IframeResponse>(jsonText) ?: return false

        val standardQualities = listOf(144, 240, 360, 480, 720, 1080)

        for (stream in response.props.streams.data) {
            val height = stream.resolution.substringAfter("x").toIntOrNull()
            val quality = if (height != null) {
                standardQualities.minByOrNull { abs(it - height) } ?: height
            } else {
                Qualities.Unknown.value
            }

            for (mirror in stream.mirrors) {
                if (mirror.link.isBlank()) continue
                val videoUrl = mirror.link.let { if (it.startsWith("//")) "https:$it" else it }
                val extracted = loadExtractor(
                    url = videoUrl,
                    referer = fixedUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
                if (!extracted) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "${mirror.driver} - ${stream.resolution}",
                            url = videoUrl,
                        ) {
                            this.referer = fixedUrl
                            this.quality = quality
                        }
                    )
                }
            }
        }

        return true
    }
}
