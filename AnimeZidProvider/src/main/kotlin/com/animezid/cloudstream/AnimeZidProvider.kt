package com.animezid.cloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
        val items = doc.select("article.az-card")
            .ifEmpty { doc.select("a[href*=\"watch.php?vid=\"]") }
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
        if (items.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search.php?keywords=$query").document
        return doc.select("article.az-card")
            .ifEmpty { doc.select("a[href*=\"watch.php?vid=\"]") }
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.run { if (tagName() == "a") this else selectFirst("a.az-card__link") } ?: return null
        val href = link.attr("href")
        if (href.isBlank()) return null

        val title = link.selectFirst(".az-card__title")?.text()
            ?: link.attr("aria-label").ifEmpty { link.attr("title") }
            ?: link.selectFirst("img")?.attr("alt")
            ?: return null

        val img = link.selectFirst("img")
        val posterUrl = img?.attr("src") ?: img?.attr("data-src") ?: img?.attr("data-original") ?: ""

        val ratingText = link.selectFirst(".az-badge--rating")?.text() ?: ""
        val rating = Regex("""(\d+(?:\.\d+)?)""").find(ratingText)?.groupValues?.get(1)?.toFloatOrNull()

        val isMovie = title.contains("فيلم", ignoreCase = true)
        val isSeries = href.contains("/series/") || link.parent()?.hasClass("az-card--series") == true
        val tvType = when {
            isSeries -> TvType.Anime
            isMovie -> TvType.Movie
            else -> TvType.Anime
        }
        val seriesName = title.extractSeriesName()

        return newAnimeSearchResponse(seriesName, fixUrl(href), tvType) {
            this.posterUrl = fixUrl(posterUrl)
            this.score = Score.from10(rating)
        }
    }

    private fun String.extractSeriesName(): String {
        val clean = removePrefix("انمي ").removePrefix("فيلم ").removePrefix("مسلسل ")
        val noSeason = clean.replace(Regex("""\s*-\s*الموسم\s*\d+.*"""), "").trim()
        val episodePattern = Regex("""^(.*?)\s*(?:الحلقة|الجزء)\s+\d+.*""")
        val match = episodePattern.find(noSeason)
        return match?.groupValues?.get(1)?.trim() ?: clean
    }

    private suspend fun appendEpisodePages(
        baseUrl: String,
        season: Int,
        fallbackPoster: String,
        episodes: MutableList<Episode>,
        seenVids: MutableSet<String>
    ) {
        var page = 1
        while (true) {
            val pageUrl = if (page == 1) baseUrl else if (baseUrl.contains("?")) "$baseUrl&page=$page" else "$baseUrl?page=$page"
            val doc = try { app.get(pageUrl).document } catch (_: Exception) { break }
            val links = doc.select("article.az-card a.az-card__link[href*=\"watch.php?vid=\"]")
                .ifEmpty { doc.select("a[href*=\"watch.php?vid=\"]") }
            var added = 0
            for (epLink in links) {
                val epHref = epLink.attr("href")
                val epVid = Regex("""[?&]vid=([^&]+)""").find(epHref)?.groupValues?.get(1)
                if (epVid == null || !seenVids.add(epVid)) continue
                added++
                val epNum = epLink.selectFirst(".az-badge--episode strong")?.text()?.toIntOrNull()
                    ?: Regex("""الحلقة\s*(\d+)""").find(
                        epLink.attr("aria-label").ifEmpty { epLink.attr("title").ifEmpty { epLink.text() } }
                    )?.groupValues?.get(1)?.toIntOrNull()
                    ?: seenVids.size
                val epPoster = epLink.selectFirst("img")?.attr("src") ?: fallbackPoster
                episodes.add(
                    newEpisode(fixUrl(epHref)) {
                        this.name = "الحلقة $epNum"
                        this.season = season
                        this.episode = epNum
                        this.posterUrl = fixUrl(epPoster)
                    }
                )
            }

            var maxShown = page
            for (link in doc.select("nav.az-pagination a.page-link")) {
                val href = link.attr("href")
                if (!href.contains("page=")) continue
                href.substringAfter("page=").substringBefore("&").toIntOrNull()?.let {
                    if (it > maxShown) maxShown = it
                }
            }
            if (maxShown <= page || page > 100 || (added == 0 && page > 1)) break
            page++
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.select("meta[property=og:title]").attr("content").trim()
            .ifEmpty { doc.select("h1").firstOrNull()?.text() ?: "" }
            .ifEmpty { throw ErrorLoadingException("Could not find title") }

        val poster = doc.select("meta[property=og:image]").attr("content")
            .ifEmpty {
                doc.select("img").firstOrNull()?.attr("src") ?: ""
            }

        val yearFromTitle = Regex("""\b(19\d\d|20\d\d)\b""").find(title)
            ?.groupValues?.get(1)?.toIntOrNull()

        val isMovie = title.contains("فيلم", ignoreCase = true)

        if (isMovie) {
            val year = doc.select("a[href*=\"filter=years\"]").text().toIntOrNull() ?: yearFromTitle
            val plot = doc.select(".az-cinema-summary").text()
            val ratingText = doc.select(".az-cinema-meta__copy strong").text()
            val rating = Regex("""([\d.]+)\s*/\s*10""").find(ratingText)?.groupValues?.get(1)?.toFloatOrNull()
            val tags = doc.select("a[href*=\"filter=genres\"]").text()
                .split("،").map { it.trim() }.filter { it.isNotBlank() }

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrl(poster)
                this.year = year
                this.plot = plot.ifEmpty { null }
                this.score = Score.from10(rating)
                this.tags = tags
            }
        }

        val plot = doc.select(".az-series-detail-hero__content > p").text()
            .ifEmpty { doc.select(".az-cinema-summary").text() }
        val year = doc.select(".az-series-detail-hero__stats strong").firstOrNull()?.text()?.toIntOrNull()
            ?: yearFromTitle

        val episodes = mutableListOf<Episode>()
        val seenVids = mutableSetOf<String>()

        val seasonLinks = doc.select(".az-series-seasons-grid a.az-card__link[href*=\"/season/\"]")

        if (seasonLinks.isNotEmpty()) {
            for (seasonLink in seasonLinks) {
                val seasonUrl = fixUrl(seasonLink.attr("href"))
                val season = Regex("""/season/(\d+)""").find(seasonUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                appendEpisodePages(seasonUrl, season, poster, episodes, seenVids)
            }
        } else {
            appendEpisodePages(url, 1, poster, episodes, seenVids)
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
            this.plot = plot.ifEmpty { null }
            this.tags = emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val vid = Regex("""[?&]vid=([^&]+)""").find(data)?.groupValues?.get(1)
            ?: data.substringAfterLast("/").ifEmpty { return false }

        val playUrl = "$mainUrl/play.php?vid=$vid"
        val playDoc = try { app.get(playUrl).document } catch (_: Exception) { return false }
        val csrf = playDoc.selectFirst("[data-playback-csrf]")?.attr("data-playback-csrf") ?: return false
        val createUrl = playDoc.selectFirst("[data-playback-create-url]")?.attr("data-playback-create-url")
            ?: "$mainUrl/web-playback/sessions"

        val mediaType = "application/json; charset=UTF-8".toMediaType()
        val jsonHeaders = mapOf(
            "Content-Type" to "application/json; charset=UTF-8",
            "X-Playback-CSRF" to csrf,
            "Origin" to mainUrl,
            "Referer" to playUrl,
        )

        val sessionJson = try {
            app.post(
                createUrl,
                requestBody = """{"content_id":"$vid"}""".toRequestBody(mediaType),
                headers = jsonHeaders
            ).text
        } catch (_: Exception) { return false }

        val session = try { JSONObject(sessionJson) } catch (_: Exception) { return false }
        val sessionId = session.optString("session_id").ifBlank { return false }
        val sources = session.optJSONArray("sources") ?: return false

        var found = false
        val standardQualities = listOf(144, 240, 360, 480, 720, 1080)

        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i) ?: continue
            if (src.optString("type") != "embedded_web") continue
            if (src.optString("id").isBlank()) continue
            val srcId = src.optString("id")
            val provider = src.optString("provider").ifBlank { name }

            val launchUrl = try {
                app.post(
                    "$createUrl/$sessionId/sources/$srcId/resolve",
                    requestBody = "{}".toRequestBody(mediaType),
                    headers = jsonHeaders
                ).text
            } catch (_: Exception) { continue }
            val launch = try { JSONObject(launchUrl).optString("launch_url") } catch (_: Exception) { continue }
            if (launch.isBlank()) continue

            val launchResponse = try { app.get(launch, referer = playUrl) } catch (_: Exception) { continue }
            val pageText = launchResponse.text
            val finalUrl = launchResponse.url

            val version = Regex(""""version":"([^"]+)"""").find(pageText)?.groupValues?.get(1)
            val inertiaUrl = Regex(""""url":"([^"]+)"""").find(pageText)?.groupValues?.get(1)

            if (!version.isNullOrBlank() && !inertiaUrl.isNullOrBlank()) {
                val host = Regex("""(?<=://)[^/]+""").find(finalUrl)?.value ?: ""
                val hostBase = if (host.contains("megamax")) "https://$host" else "$mainUrl"
                val fixedUrl = fixUrl(hostBase + inertiaUrl.replace("\\/", "/"))

                val inertiaHeaders = mapOf(
                    "X-Inertia" to "true",
                    "X-Inertia-Partial-Component" to "files/mirror/video",
                    "X-Inertia-Partial-Data" to "streams",
                    "X-Inertia-Version" to version,
                    "Referer" to launch,
                )

                val jsonText = try {
                    app.get(fixedUrl, headers = inertiaHeaders, referer = launch).text
                } catch (_: Exception) { continue }

                val response = AppUtils.tryParseJson<IframeResponse>(jsonText) ?: continue

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
                        val mirrorExtracted = loadExtractor(
                            url = videoUrl,
                            referer = fixedUrl,
                            subtitleCallback = subtitleCallback,
                            callback = callback,
                        )
                        if (!mirrorExtracted) {
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
                        found = true
                    }
                }
            } else {
                val extracted = loadExtractor(
                    url = finalUrl,
                    referer = playUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
                if (extracted) {
                    found = true
                } else {
                    for (m in Regex("""https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*""").findAll(pageText)) {
                        val u = m.groupValues.first()
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$provider - HLS",
                                url = u,
                            ) {
                                this.referer = finalUrl
                            }
                        )
                        found = true
                    }
                }
            }
        }

        return found
    }
}