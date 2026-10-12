package com.cartoony

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

class Cartoony : MainAPI() {
    override var mainUrl = "https://cartoony.net"
    override var name = "Cartoony"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Cartoon, TvType.Movie)

    override val mainPage = mainPageOf(
        "recents" to "أحدث الحلقات",
        "sp-series" to "مسلسلات سبيستون",
        "sp-movies" to "أفلام سبيستون",
        "legacy-series" to "مسلسلات مدبلجة",
        "legacy-movies" to "أفلام مدبلجة",
        "legacy-stories" to "قصص عالمية",
        "planet:أكشن" to "أكشن",
        "planet:كوميدي" to "كوميدي",
        "planet:مغامرات" to "مغامرات",
        "planet:رياضة" to "رياضة",
    )

    private val apiHeaders = mapOf(
        "Accept" to "application/json",
        "Referer" to "$mainUrl/",
    )
    private val streamReferer = "$mainUrl/"
    private val aesKey = "7annaba3l_loves_crypto_safe_key!".toByteArray(Charsets.UTF_8)

    /** App joins relative data onto mainUrl (load() gets https://cartoony.net/sp:ID) — strip it back. */
    private fun cleanData(url: String): String {
        return url.removePrefix("$mainUrl/").removePrefix(mainUrl)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        return ByteArray(clean.length / 2) { i ->
            ((clean[i * 2].digitToInt(16) shl 4) + clean[i * 2 + 1].digitToInt(16)).toByte()
        }
    }

    /** Site wraps every API payload as {iv, encryptedData} (AES-256-CBC, hex, PKCS7, JSON). */
    private fun decryptPayload(text: String): String? {
        return try {
            val root = JSONObject(text)
            val iv = hexToBytes(root.getString("iv"))
            val ct = hexToBytes(root.getString("encryptedData"))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private suspend fun apiGet(path: String): String? {
        return try {
            decryptPayload(app.get("$mainUrl$path", headers = apiHeaders).text)
        } catch (_: Exception) { null }
    }

    private suspend fun apiPost(path: String, json: String): String? {
        return try {
            val text = app.post(
                "$mainUrl$path",
                requestBody = json.toRequestBody("application/json; charset=utf-8".toMediaType()),
                headers = apiHeaders,
            ).text
            decryptPayload(text)
        } catch (_: Exception) { null }
    }

    private fun jsonArray(text: String?): JSONArray {
        return try { JSONArray(text ?: "[]") } catch (_: Exception) { JSONArray() }
    }

    private var spShowsCache: JSONArray? = null
    private var legacyShowsCache: JSONArray? = null
    private var recentsCache: JSONArray? = null

    private suspend fun spShows(): JSONArray {
        spShowsCache?.let { return it }
        val arr = jsonArray(apiGet("/api/sp/tvshows"))
        if (arr.length() > 0) spShowsCache = arr
        return arr
    }

    private suspend fun legacyShows(): JSONArray {
        legacyShowsCache?.let { return it }
        val arr = jsonArray(apiGet("/api/tvshows"))
        if (arr.length() > 0) legacyShowsCache = arr
        return arr
    }

    private fun spItem(o: JSONObject): SearchResponse? {
        val id = o.optInt("id", 0)
        if (id == 0) return null
        val title = o.optString("name").trim()
        if (title.isBlank()) return null
        val isMovie = o.optInt("is_movie", 0) == 1
        val type = if (isMovie) TvType.Movie else TvType.Cartoon
        val poster = o.optString("cover_full_path").ifBlank { null }
        return if (isMovie) {
            newMovieSearchResponse(title, "sp:$id", type) { this.posterUrl = poster }
        } else {
            newAnimeSearchResponse(title, "sp:$id", type) { this.posterUrl = poster }
        }
    }

    private fun legacyItem(o: JSONObject): SearchResponse? {
        val id = o.optInt("id", 0)
        if (id == 0) return null
        val title = o.optString("title").trim()
        if (title.isBlank()) return null
        val category = o.optString("category")
        val isMovie = category.startsWith("فيلم")
        val type = if (isMovie) TvType.Movie else TvType.Cartoon
        val posterFile = o.optString("poster_cover").ifBlank { o.optString("poster_cover_alt") }
        val poster = posterFile.takeIf { it.isNotBlank() }?.let { "$mainUrl/assets/img/posters/$it" }
        return if (isMovie) {
            newMovieSearchResponse(title, "legacy:$id", type) { this.posterUrl = poster }
        } else {
            newAnimeSearchResponse(title, "legacy:$id", type) { this.posterUrl = poster }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse> = when {
            request.data == "recents" -> {
                if (recentsCache == null) {
                    val arr = jsonArray(apiGet("/api/sp/recentEpisodes"))
                    if (arr.length() > 0) recentsCache = arr
                }
                val arr = recentsCache ?: JSONArray()
                val out = ArrayList<SearchResponse>()
                val seenShows = mutableSetOf<Int>()
                for (i in 0 until arr.length()) {
                    val ep = arr.optJSONObject(i) ?: continue
                    val showId = ep.optInt("tv_series_id", 0)
                    if (showId == 0 || !seenShows.add(showId)) continue
                    val showName = ep.optString("name").trim()
                    if (showName.isBlank()) continue
                    val epNo = ep.optInt("number", 0)
                    val title = if (epNo > 0) "$showName - الحلقة $epNo" else showName
                    out.add(
                        newAnimeSearchResponse(title, "sp:$showId", TvType.Cartoon) {
                            this.posterUrl = ep.optString("cover_full_path").ifBlank { null }
                        }
                    )
                }
                out
            }
            request.data == "sp-series" || request.data == "sp-movies" -> {
                val wantMovie = request.data == "sp-movies"
                val out = ArrayList<SearchResponse>()
                val sp = spShows()
                for (i in 0 until sp.length()) {
                    val o = sp.optJSONObject(i) ?: continue
                    if ((o.optInt("is_movie", 0) == 1) != wantMovie) continue
                    spItem(o)?.let { out.add(it) }
                }
                out
            }
            request.data == "legacy-series" || request.data == "legacy-movies" || request.data == "legacy-stories" -> {
                val out = ArrayList<SearchResponse>()
                val legacy = legacyShows()
                for (i in 0 until legacy.length()) {
                    val o = legacy.optJSONObject(i) ?: continue
                    val category = o.optString("category")
                    val isMovie = category.startsWith("فيلم")
                    val keep = when (request.data) {
                        "legacy-movies" -> isMovie
                        "legacy-stories" -> category.contains("قصص عالمية")
                        else -> !isMovie
                    }
                    if (!keep) continue
                    legacyItem(o)?.let { out.add(it) }
                }
                out
            }
            request.data.startsWith("planet:") -> {
                val planet = request.data.removePrefix("planet:")
                val out = ArrayList<SearchResponse>()
                val sp = spShows()
                for (i in 0 until sp.length()) {
                    val o = sp.optJSONObject(i) ?: continue
                    if (o.optString("planet_name") != planet) continue
                    spItem(o)?.let { out.add(it) }
                }
                out
            }
            else -> emptyList()
        }
        if (items.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(HomePageList(request.name ?: "القائمة", items))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val out = ArrayList<SearchResponse>()
        val sp = spShows()
        for (i in 0 until sp.length()) {
            val o = sp.optJSONObject(i) ?: continue
            if (o.optString("name").contains(q, ignoreCase = true)) {
                spItem(o)?.let { out.add(it) }
                if (out.size >= 30) break
            }
        }
        if (out.size < 30) {
            val legacy = legacyShows()
            for (i in 0 until legacy.length()) {
                val o = legacy.optJSONObject(i) ?: continue
                if (o.optString("title").contains(q, ignoreCase = true)) {
                    legacyItem(o)?.let { out.add(it) }
                    if (out.size >= 30) break
                }
            }
        }
        return out
    }

    private fun JSONObject.intOrNull(key: String): Int? {
        val v = opt(key)
        return when (v) {
            is Number -> v.toInt()
            is String -> v.filter { it.isDigit() }.toIntOrNull()
            else -> null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = cleanData(url)
        val isLegacy = data.startsWith("legacy:")
        val showId = data.substringAfter(":").toIntOrNull() ?: throw ErrorLoadingException("bad url")
        val episodes = ArrayList<Episode>()

        if (!isLegacy) {
            val detail = apiGet("/api/sp/tvshow?id=$showId")?.let { JSONObject(it) }
                ?: throw ErrorLoadingException("no detail")
            val title = detail.optString("name").ifBlank { "Unknown" }
            val poster = detail.optString("cover_full_path").ifBlank { null }
            val plot = detail.optString("pref").ifBlank { null }
            val tags = detail.optString("tags").split(",").map { it.trim() }.filter { it.isNotBlank() }
            val isMovie = detail.optInt("is_movie", 0) == 1
            val type = if (isMovie) TvType.Movie else TvType.Cartoon
            val status = if (detail.optInt("is_completed", 0) == 1 || isMovie) ShowStatus.Completed else ShowStatus.Ongoing

            val eps = jsonArray(apiGet("/api/sp/episodes?id=$showId"))
            for (i in 0 until eps.length()) {
                val ep = eps.optJSONObject(i) ?: continue
                val epId = ep.optInt("id", 0)
                if (epId == 0) continue
                val num = ep.optInt("number", 0).takeIf { it > 0 }
                val epTitle = ep.optString("pref").trim()
                episodes.add(newEpisode("sp-ep:$showId:$epId") {
                    this.name = when {
                        epTitle.isNotBlank() && num != null -> "الحلقة $num - $epTitle"
                        epTitle.isNotBlank() -> epTitle
                        num != null -> "الحلقة $num"
                        else -> "مشاهدة"
                    }
                    this.episode = num
                    this.posterUrl = ep.optString("cover_full_path").ifBlank { poster }
                })
            }
            if (episodes.isEmpty()) throw ErrorLoadingException("no episodes")
            return if (isMovie && episodes.size == 1) {
                newMovieLoadResponse(title, url, type, episodes.first().data) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.tags = tags
                }
            } else {
                newTvSeriesLoadResponse(title, url, type, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.tags = tags
                    this.showStatus = status
                }
            }
        } else {
            val detail = apiGet("/api/tvshow?id=$showId")?.let { JSONObject(it) }
                ?: throw ErrorLoadingException("no detail")
            val title = detail.optString("title").ifBlank { "Unknown" }
            val category = detail.optString("category")
            val isMovie = category.startsWith("فيلم")
            val type = if (isMovie) TvType.Movie else TvType.Cartoon
            val posterFile = detail.optString("poster_cover").ifBlank { detail.optString("poster_cover_alt") }
            val poster = posterFile.takeIf { it.isNotBlank() }?.let { "$mainUrl/assets/img/posters/$it" }
            val plot = detail.optString("description").ifBlank { null }
            val year = detail.intOrNull("release_year")

            val eps = jsonArray(apiGet("/api/episodes?id=$showId"))
            for (i in 0 until eps.length()) {
                val ep = eps.optJSONObject(i) ?: continue
                val epId = ep.optInt("id", 0)
                if (epId == 0) continue
                val order = ep.optInt("order_id", 0).takeIf { it > 0 } ?: (i + 1)
                val epTitle = ep.optString("title").trim()
                episodes.add(newEpisode("legacy-ep:$showId:$epId") {
                    this.name = if (epTitle.isNotBlank() && epTitle != "اضغط للمشاهدة") "الحلقة $order - $epTitle" else "الحلقة $order"
                    this.episode = order
                    this.posterUrl = poster
                })
            }
            if (episodes.isEmpty()) throw ErrorLoadingException("no episodes")
            return if (isMovie && episodes.size == 1) {
                newMovieLoadResponse(title, url, type, episodes.first().data) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.tags = if (category.isNotBlank()) listOf(category) else null
                    this.year = year
                }
            } else {
                newTvSeriesLoadResponse(title, url, type, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.tags = if (category.isNotBlank()) listOf(category) else null
                    this.year = year
                    this.showStatus = if (isMovie) ShowStatus.Completed else ShowStatus.Ongoing
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = cleanData(data)
        val streamUrl = when {
            clean.startsWith("sp-ep:") -> {
                val parts = clean.removePrefix("sp-ep:").split(":")
                if (parts.size != 2) return false
                val manifest = apiPost(
                    "/api/sp/episode/link",
                    """{"showId":${parts[0].toIntOrNull() ?: return false},"episodeId":${parts[1].toIntOrNull() ?: return false}}"""
                ) ?: return false
                try { JSONObject(manifest).optString("link") } catch (_: Exception) { "" }
            }
            clean.startsWith("legacy-ep:") -> {
                val parts = clean.removePrefix("legacy-ep:").split(":")
                if (parts.size != 2) return false
                val showId = parts[0].toIntOrNull() ?: return false
                val epId = parts[1].toIntOrNull() ?: return false
                val manifest = apiGet("/api/episode?showId=$showId&episodeId=$epId") ?: return false
                try { JSONObject(manifest).optString("streamUrl") } catch (_: Exception) { "" }
            }
            else -> return false
        }
        if (streamUrl.isBlank()) return false
        return emitHlsVariants(streamUrl, callback)
    }

    private val standardQualities = listOf(144, 240, 360, 480, 720, 1080)

    /** Fetch the master playlist (needs site Referer) and emit one link per quality variant. */
    private suspend fun emitHlsVariants(
        masterUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        suspend fun emitDirect(url: String): Boolean {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name HLS",
                    url = url,
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.referer = streamReferer
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }
        val text = try {
            app.get(masterUrl, headers = mapOf("Referer" to streamReferer)).text
        } catch (_: Exception) { return emitDirect(masterUrl) }
        if (!text.contains("#EXT-X-STREAM-INF")) return emitDirect(masterUrl)
        val base = masterUrl.substringBeforeLast("/") + "/"
        val lines = text.lines()
        var found = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val tagName = Regex("NAME=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                val height = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
                val qNum = tagName?.filter { it.isDigit() }?.toIntOrNull() ?: height
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].trim().startsWith("#"))) j++
                if (j < lines.size) {
                    val uri = lines[j].trim()
                    val full = if (uri.startsWith("http")) uri else base + uri
                    val label = tagName ?: (if (height != null) "${height}p" else "HLS")
                    val quality = if (qNum != null) {
                        standardQualities.minByOrNull { abs(it - qNum) } ?: qNum
                    } else {
                        Qualities.Unknown.value
                    }
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name $label",
                            url = full,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.referer = streamReferer
                            this.quality = quality
                        }
                    )
                    found = true
                }
            }
            i++
        }
        if (!found) return emitDirect(masterUrl)
        return true
    }
}
