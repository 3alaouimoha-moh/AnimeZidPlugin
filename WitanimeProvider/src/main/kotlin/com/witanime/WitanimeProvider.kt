package com.witanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import android.util.Base64
import com.lagradost.cloudstream3.mvvm.logError
import org.jsoup.Jsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset

class WitAnime : MainAPI() {
    override var mainUrl = "https://witanime.you"
    override var name = "WitAnime"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    private val FRAMEWORK_HASH = "23a97133-caf3-4eb4-9466-93d0a4ff8198"

    private suspend fun fetchWithJS(url: String): String {
        return try {
            app.get(url, interceptor = WebViewResolver(interceptUrl = Regex(url))).text
        } catch (_: Exception) {
            try { app.get(url).text } catch (_: Exception) { "" }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()
        document.select("div.main-widget").forEach { widget ->
            val title = widget.selectFirst("div.main-didget-head h3")?.text()?.trim() ?: return@forEach
            val isEpisodeList = title.contains("حلقات")
            val items = widget.select(if (isEpisodeList) "div.episodes-card-container" else "div.anime-card-container")
                .mapNotNull {
                    val a = if (isEpisodeList) it.selectFirst(".ep-card-anime-title a") else it.selectFirst("a.overlay")
                    val itemUrl = a?.attr("href") ?: return@mapNotNull null
                    val itemName = (if (isEpisodeList) a?.text() else it.selectFirst(".anime-card-title a")?.text()) ?: ""
                    val itemPoster = it.selectFirst("img")?.attr("src")
                    val finalTitle = if (isEpisodeList) {
                        val epTitle = it.selectFirst(".episodes-card-title a")?.text() ?: ""
                        "$itemName - $epTitle"
                    } else itemName
                    newAnimeSearchResponse(finalTitle, itemUrl, TvType.Anime) { posterUrl = itemPoster }
                }
            if (items.isNotEmpty()) homePageList.add(HomePageList(title, items))
        }
        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?search_param=animes&s=$query").document
        return document.select("div.anime-list-content div.anime-card-container").mapNotNull {
            val a = it.selectFirst("div.anime-card-poster a")
            val href = a?.attr("href") ?: return@mapNotNull null
            val title = it.selectFirst("div.anime-card-title h3 a")?.text() ?: return@mapNotNull null
            val poster = it.selectFirst("img.img-responsive")?.attr("src")
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = fetchWithJS(url)
        val document = html.let { try { Jsoup.parse(it) } catch (_: Exception) { Jsoup.parse("") } }
        val title = document.selectFirst("h1.anime-details-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.anime-thumbnail img")?.attr("src")
        val description = document.selectFirst("p.anime-story")?.text()?.trim()
        val genres = document.select("ul.anime-genres li a").map { it.text() }
        var status = ShowStatus.Ongoing
        var tvType = TvType.Anime
        document.select(".anime-info").forEach {
            val infoText = it.text()
            if (infoText.startsWith("حالة الأنمي:")) status = if (infoText.contains("مكتمل")) ShowStatus.Completed else ShowStatus.Ongoing
            if (infoText.startsWith("النوع:")) tvType = if (infoText.contains("Movie")) TvType.AnimeMovie else TvType.Anime
        }
        var episodes = listOf<Episode>()
        val regex = Regex("""var\s+processedEpisodeData\s*=\s*'([^']+)'""")
        val match = regex.find(html)
        val encodedData = match?.groupValues?.get(1)
        if (!encodedData.isNullOrBlank()) {
            try {
                val parts = encodedData.split(".")
                if (parts.size == 2) {
                    val part1 = String(Base64.decode(parts[0], Base64.DEFAULT))
                    val part2 = String(Base64.decode(parts[1], Base64.DEFAULT))
                    val decodedJson = StringBuilder()
                    for (i in part1.indices) decodedJson.append((part1[i].code xor part2[i % part2.length].code).toChar())
                    val episodesList = AppUtils.parseJson(decodedJson.toString()) as? List<Map<String, Any>>
                    if (episodesList != null) {
                        episodes = episodesList.mapNotNull { ep ->
                            val epUrl = ep["url"]?.toString() ?: return@mapNotNull null
                            val epName = ep["number"]?.toString() ?: ep["title"]?.toString() ?: "حلقة"
                            newEpisode(epUrl) { this.name = epName }
                        }
                    }
                }
            } catch (e: Exception) { logError(e) }
        }
        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster; this.plot = description; this.tags = genres
            this.showStatus = status; addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = fetchWithJS(data)
            processStreamingServers(html, subtitleCallback, callback)
            processDownloadLinks(html, data, subtitleCallback, callback)
            true
        } catch (_: Exception) { false }
    }

    private suspend fun processStreamingServers(
        html: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val resourceRegistry = extractJsObject(html, "resourceRegistry")
        val configRegistry = extractJsObject(html, "configRegistry")
        if (resourceRegistry == null || resourceRegistry.length() == 0) return

        val serverIds = mutableListOf<String>()
        resourceRegistry.keys().forEach { serverIds.add(it) }

        if (serverIds.isEmpty()) return

        val PARALLELISM = 6
        val semaphore = Semaphore(PARALLELISM)

        supervisorScope {
            val tasks = serverIds.map { sid ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val resourceRaw = resourceRegistry.opt(sid)
                            val configRaw = if (configRegistry != null) configRegistry.opt(sid) else null
                            val offset = getParamOffset(configRaw)
                            var link = decodeX18c(resourceRaw, offset)
                            if (link.matches(Regex("""^https:\/\/yonaplay\.net\/embed\.php\?id=\d+$"""))) {
                                link = "$link&apiKey=$FRAMEWORK_HASH"
                            }
                            if (link.isNotBlank()) {
                                when {
                                    link.contains("yonaplay.net", ignoreCase = true) -> decodeYonaplay(link, subtitleCallback, callback)
                                    link.contains("videa.hu", ignoreCase = true) -> {
                                        launch(Dispatchers.IO) { try { VideaExtractor().getUrl(link, null, subtitleCallback, callback) } catch (_: Exception) {} }
                                        try { loadExtractor(link, subtitleCallback, callback) } catch (_: Exception) {}
                                    }
                                    link.contains("my.mail.ru", ignoreCase = true) || link.contains("/video/embed/") -> {
                                        launch(Dispatchers.IO) { try { MailruExtractor().getUrl(link, null, subtitleCallback, callback) } catch (_: Exception) {} }
                                        try { loadExtractor(link, subtitleCallback, callback) } catch (_: Exception) {}
                                    }
                                    else -> loadExtractor(link, mainUrl, subtitleCallback, callback)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            tasks.awaitAll()
        }
    }

    private suspend fun processDownloadLinks(
        html: String, data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pxScripts = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).findAll(html)
            .map { it.groupValues[1] }.filter { "_m" in it && "_p0" in it }.toList()

        var pxMr: String? = null
        var pxS = mutableListOf<String>()
        val pxP = mutableMapOf<String, List<String>>()

        for (s in pxScripts) {
            val mM = Regex("""(?:window\.)?_m\s*=\s*\{\s*\"r\"\s*:\s*\"([^\"]+)\"""").find(s)
            if (mM != null && pxMr.isNullOrBlank()) pxMr = mM.groupValues[1]
            val sM = Regex("""(?:window\.)?_s\s*=\s*\[(.*?)\]\s*;""", RegexOption.DOT_MATCHES_ALL).find(s)
            if (sM != null && pxS.isEmpty()) pxS.addAll(
                Regex("\"([^\"]*)\"").findAll(sM.groupValues[1]).map { it.groupValues[1] }
            )
            val pMatches = Regex("""(?:window\.)?(_p\d+)\s*=\s*\[\s*(.*?)\s*\]\s*;""", RegexOption.DOT_MATCHES_ALL).findAll(s)
            for (pm in pMatches) pxP.putIfAbsent(
                pm.groupValues[1],
                Regex("\"([^\"]*)\"").findAll(pm.groupValues[2]).map { it.groupValues[1] }.toList()
            )
            if (!pxMr.isNullOrBlank() && pxP.isNotEmpty()) break
        }

        if (pxMr.isNullOrBlank() || pxP.isEmpty()) {
            val srcs = Regex("""<script[^>]+src=[\"']([^\"']+)[\"'][^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
                .map { it.groupValues[1] }.toList()
            for (src in srcs) {
                val url = if (src.startsWith("http")) src else try {
                    java.net.URL(java.net.URL(data), src).toString()
                } catch (_: Exception) { src }
                val js = try { app.get(url).text } catch (_: Exception) { "" }
                if (js.isBlank()) continue
                val mM = Regex("""(?:window\.)?_m\s*=\s*\{\s*\"r\"\s*:\s*\"([^\"]+)\"""").find(js)
                if (mM != null && pxMr.isNullOrBlank()) pxMr = mM.groupValues[1]
                val sM = Regex("""(?:window\.)?_s\s*=\s*\[(.*?)\]\s*;""", RegexOption.DOT_MATCHES_ALL).find(js)
                if (sM != null && pxS.isEmpty()) pxS.addAll(
                    Regex("\"([^\"]*)\"").findAll(sM.groupValues[1]).map { it.groupValues[1] }
                )
                val pMatches = Regex("""(?:window\.)?(_p\d+)\s*=\s*\[\s*(.*?)\s*\]\s*;""", RegexOption.DOT_MATCHES_ALL).findAll(js)
                for (pm in pMatches) pxP.putIfAbsent(
                    pm.groupValues[1],
                    Regex("\"([^\"]*)\"").findAll(pm.groupValues[2]).map { it.groupValues[1] }.toList()
                )
                if (!pxMr.isNullOrBlank() && pxP.isNotEmpty()) break
            }
        }

        val downloadLinks = decryptPx9(pxMr, pxS, pxP)
        val PARALLELISM = 6
        val semaphore = Semaphore(PARALLELISM)

        supervisorScope {
            val dlTasks = downloadLinks.map { dl ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            if (dl.isNotBlank()) {
                                val httpIdx = dl.indexOf("http")
                                val cleaned = if (httpIdx >= 0) dl.substring(httpIdx) else dl
                                val finalUrl = cleaned.replace(Regex("[\\x00\\u0000]"), "").trim()
                                if (finalUrl.startsWith("http")) loadExtractor(finalUrl, data, subtitleCallback, callback)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            dlTasks.awaitAll()
        }
    }

    private fun extractJsObject(html: String, varName: String): JSONObject? {
        val idx = html.indexOf("$varName =")
        if (idx < 0) return null
        val start = html.indexOf('{', idx)
        if (start < 0) return null
        var depth = 0
        var end = -1
        for (i in start until html.length) {
            when (html[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i + 1; break } }
            }
        }
        if (end < 0) return null
        return try { JSONObject(html.substring(start, end)) } catch (_: Exception) { null }
    }

    private fun getParamOffset(config: Any?): Int {
        if (config == null) return 0
        return try {
            val k = when (config) {
                is JSONObject -> config.optString("k", null)
                is Map<*, *> -> config["k"] as? String
                else -> null
            } ?: return 0
            val idx = String(Base64.decode(k, Base64.DEFAULT), Charsets.UTF_8).trim { it <= ' ' }.toIntOrNull() ?: return 0
            val d = when (config) {
                is JSONObject -> config.optJSONArray("d")
                is Map<*, *> -> (config["d"] as? List<*>)
                else -> null
            }
            when (d) {
                is JSONArray -> d.optInt(idx, 0)
                is List<*> -> (d.getOrNull(idx) as? Number)?.toInt() ?: 0
                else -> 0
            }
        } catch (_: Exception) { 0 }
    }

    private fun decodeX18c(resource: Any?, offset: Int): String {
        val raw = when (resource) {
            is String -> resource
            is JSONObject -> resource.optString("r") ?: resource.optString("resource") ?: resource.optString("data")
            is Map<*, *> -> (resource["r"] as? String) ?: (resource["resource"] as? String) ?: (resource["data"] as? String)
            else -> null
        } ?: return ""
        val rev = raw.reversed()
        val clean = rev.replace(Regex("[^A-Za-z0-9+/=]"), "")
        val dec = try { Base64.decode(clean, Base64.DEFAULT) } catch (_: Exception) { ByteArray(0) }
        if (dec.isEmpty()) return ""
        val slice = if (offset > 0 && offset < dec.size) dec.copyOf(dec.size - offset) else dec
        val str = try { String(slice, Charsets.UTF_8) } catch (_: Exception) { String(slice, Charset.forName("ISO-8859-1")) }
        return str.replace(Regex("[\\x00\\u0000]"), "").trim()
    }

    private fun decryptPx9(mrBase64: String?, sList: List<String>, pDict: Map<String, List<String>>): List<String> {
        if (mrBase64.isNullOrBlank()) return emptyList()
        val secret = try { Base64.decode(mrBase64, Base64.DEFAULT) } catch (_: Exception) { ByteArray(0) }
        if (secret.isEmpty()) return emptyList()
        val results = mutableListOf<String>()
        val count = maxOf(sList.size, pDict.size)
        for (i in 0 until count) {
            val key = "_p$i"
            val chunks = pDict[key] ?: continue
            val seq: IntArray? = if (i < sList.size) {
                try {
                    val seqDecoded = processPxChunk(sList[i], secret)
                    val arr = JSONArray(seqDecoded)
                    IntArray(arr.length()) { arr.getInt(it) }
                } catch (_: Exception) { null }
            } else null
            val decrypted = chunks.map { processPxChunk(it, secret) }
            val result = if (seq != null && seq.size == decrypted.size) {
                val arr = Array(decrypted.size) { "" }
                for (j in decrypted.indices) {
                    val pos = seq[j]
                    if (pos in arr.indices) arr[pos] = decrypted[j]
                    else { val f = arr.indexOfFirst { it.isEmpty() }; if (f >= 0) arr[f] = decrypted[j] }
                }
                arr.joinToString("")
            } else decrypted.joinToString("")
            results.add(result.replace(Regex("[\\x00\\u0000]"), "").trim())
        }
        return results
    }

    private fun processPxChunk(hex: String?, secret: ByteArray): String {
        if (hex.isNullOrBlank()) return ""
        val h = hex.replace(Regex("[^0-9a-fA-F]"), "")
        if (h.length % 2 != 0) return ""
        val bytes = h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val xored = ByteArray(bytes.size) { (bytes[it].toInt() xor secret[it % secret.size].toInt()).toByte() }
        val str = try { String(xored, Charsets.UTF_8) } catch (_: Exception) { try { String(xored, Charset.forName("ISO-8859-1")) } catch (_: Exception) { "" } }
        return str.replace(Regex("[\\x00\\u0000]"), "").trim()
    }

    private suspend fun decodeYonaplay(
        yonaplayUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(yonaplayUrl, referer = mainUrl,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.5993.90 Safari/537.36")
            ).text
            val regex = Regex("""go_to_player\('([A-Za-z0-9+/=]+)'\)""")
            val matches = regex.findAll(html).map { it.groupValues[1] }.toList()
            if (matches.isEmpty()) return
            for (encoded in matches) {
                var fixed = encoded; val padding = encoded.length % 4
                if (padding != 0) fixed += "=".repeat(4 - padding)
                try {
                    val decoded = String(Base64.decode(fixed, Base64.DEFAULT))
                    if (decoded.contains("drive.google.com/file/d/")) {
                        val match = Regex("""/file/d/([0-9A-Za-z_-]{10,})""").find(decoded)
                        val fileId = match?.groupValues?.get(1)
                        if (fileId != null) {
                            callback(newExtractorLink(name = "Google Drive", source = "Yonaplay", url = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t") {
                                referer = "https://drive.google.com/"; this.quality = Qualities.Unknown.value; this.type = ExtractorLinkType.VIDEO
                            })
                            continue
                        }
                    }
                    loadExtractor(decoded, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}
