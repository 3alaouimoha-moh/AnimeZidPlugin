package com.ooanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class OOAnime : MainAPI() {
    override var mainUrl = "https://www.ooanime.com"
    override var name = "OOAnime"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.Cartoon, TvType.Movie)

    override val mainPage = mainPageOf(
        "" to "الرئيسية",
        "movies" to "أفلام",
        "tvseries" to "مسلسلات",
        "cartoon" to "كرتون",
    )

    private fun Element.cardResponse(): SearchResponse? {
        val a = selectFirst(".bottom-left a") ?: return null
        val href = a.attr("abs:href").trim()
        if (href.isBlank()) return null
        val title = a.text().trim()
        if (title.isBlank()) return null
        val poster = selectFirst("img")?.attr("abs:src")?.ifBlank { null }
        val type = if ("/movie/" in href) TvType.Movie else TvType.Anime
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) { this.posterUrl = poster }
        } else {
            newAnimeSearchResponse(title, href, type) { this.posterUrl = poster }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())
        val url = if (request.data.isBlank()) mainUrl else "$mainUrl/${request.data}"
        val doc = app.get(url).document
        val list = ArrayList<HomePageList>()
        if (request.data.isBlank()) {
            doc.select("div.slidermv").forEach { sec ->
                val title = sec.selectFirst(".title-home")?.text()?.trim() ?: return@forEach
                val items = sec.select(".tcb-product-item").mapNotNull { it.cardResponse() }
                    .distinctBy { it.url }
                if (items.isNotEmpty()) list.add(HomePageList(title, items))
            }
        } else {
            val items = doc.select(".tcb-product-item").mapNotNull { it.cardResponse() }
                .distinctBy { it.url }
            if (items.isEmpty()) throw ErrorLoadingException()
            list.add(HomePageList(request.name ?: "القائمة", items))
        }
        if (list.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?q=$query").document
        return doc.select(".tcb-product-item").mapNotNull { it.cardResponse() }
            .distinctBy { it.url }
    }

    private fun storyOf(doc: org.jsoup.nodes.Document): String? {
        doc.select("div.movie-title").forEach { h ->
            if (!h.text().contains("قصة")) return@forEach
            var sib = h.nextElementSibling()
            var hops = 0
            while (sib != null && hops < 6) {
                val t = sib.text().trim()
                if (t.length > 40) return t
                sib = sib.nextElementSibling()
                hops++
            }
            // fallback: raw text node right after the header div
            val html = doc.html()
            val m = Regex("""قصة (?:المسلسل|الفيلم|الحلقة)\s*</div>\s*<div class="clr"></div>\s*([^<]{40,})""").find(html)
            if (m != null) return m.groupValues[1].trim()
        }
        return null
    }

    private fun metaOf(doc: org.jsoup.nodes.Document): Map<String, String> {
        val map = mutableMapOf<String, String>()
        doc.select("div.movie-info div:has(i.fa)").forEach { row ->
            val text = row.text().trim()
            val parts = text.split(":", limit = 2)
            if (parts.size == 2) map[parts[0].trim()] = parts[1].trim()
        }
        return map
    }

    private suspend fun seriesEpisodes(seasonUrls: List<String>, poster: String?): List<Episode> {
        val episodes = ArrayList<Episode>()
        for (seasonUrl in seasonUrls) {
            try {
                val sdoc = app.get(seasonUrl).document
                sdoc.select("a[href*=\"/episode/\"]").forEach { a ->
                    val epUrl = a.attr("abs:href").trim()
                    if (epUrl.isBlank() || episodes.any { it.data == epUrl }) return@forEach
                    val epText = a.text().trim().replace(Regex("\\s+"), " ")
                    if (epText.isBlank()) return@forEach
                    val epNum = epText.replace(Regex("[^0-9]"), "").toIntOrNull()
                    episodes.add(newEpisode(epUrl) {
                        this.name = epText
                        this.episode = epNum
                        this.posterUrl = poster
                    })
                }
            } catch (_: Exception) {}
        }
        return episodes.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.title().trim().ifBlank { "Unknown" }
        val poster = doc.selectFirst("div.movie-img img")?.attr("abs:src")?.ifBlank { null }
        val meta = metaOf(doc)
        val category = meta.entries.firstOrNull { it.key.contains("تصنيف") }?.value.orEmpty()
        val year = meta.entries.firstOrNull { it.key.contains("سنة") }?.value
            ?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val rating = meta.entries.firstOrNull { it.key.contains("التقييم") }?.value
            ?.let { Regex("""\d+(\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
        val score = rating?.let { Score.from10(it) }
        val plot = storyOf(doc)

        // Direct episode page: single-episode wrapper.
        if ("/episode/" in url) {
            val ep = newEpisode(url) {
                this.name = title
                this.episode = title.replace(Regex("[^0-9]"), "").toIntOrNull()
                this.posterUrl = poster
            }
            return newTvSeriesLoadResponse(title, url, TvType.Anime, listOf(ep)) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
            }
        }

        val seasonUrls = doc.select("a[href*=\"/seasons/\"]")
            .map { it.attr("abs:href").trim() }
            .filter { it.isNotBlank() }
            .distinct()

        // Movie/detail page with seasons: full series flow.
        if (seasonUrls.isNotEmpty()) {
            val episodes = seriesEpisodes(seasonUrls, poster)
            if (episodes.isEmpty()) throw ErrorLoadingException("no episodes")
            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = if (category.isNotBlank()) listOf(category) else null
                this.year = year
                this.score = score
            }
        }

        // Series landing (/series/, /tvshow/): meta + seasons (same flow, seasons required).
        if ("/series/" in url || "/tvshow/" in url) {
            val episodes = seriesEpisodes(seasonUrls, poster)
            if (episodes.isEmpty()) throw ErrorLoadingException("no episodes")
            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = if (category.isNotBlank()) listOf(category) else null
                this.year = year
                this.score = score
            }
        }

        // Single video (/movie/ film or episode-entry): movie response, watch URL is the page itself.
        val type = if (title.contains("الحلقة")) TvType.AnimeMovie else TvType.Movie
        return newMovieLoadResponse(title, url, type, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = if (category.isNotBlank()) listOf(category) else null
            this.year = year
            this.score = score
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val doc = app.get(data).document
            val frames = doc.select("div.tab-pane[id^=\"pills-s\"] iframe[src]")
            for (f in frames) {
                val src = f.attr("abs:src").trim()
                if (src.isBlank() || "youtube.com" in src || "youtu.be" in src) continue
                try {
                    if (loadExtractor(src, data, subtitleCallback, callback)) found = true
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return found
    }
}
