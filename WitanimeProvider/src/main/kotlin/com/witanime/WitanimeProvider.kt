package com.witanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import android.util.Base64
import com.lagradost.cloudstream3.mvvm.logError
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
        val document = html.let {
            try { org.jsoup.Jsoup.parse(it) } catch (_: Exception) { org.jsoup.Jsoup.parse("") }
        }
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
        val FRAMEWORK_HASH = "23a97133-caf3-4eb4-9466-93d0a4ff8198"

        fun cleanBase64Chars(s: String): String = s.replace(Regex("[^A-Za-z0-9+/=]"), "")
        fun base64DecodeBytes(input: String?): ByteArray {
            if (input.isNullOrBlank()) return ByteArray(0)
            return try { Base64.decode(input, Base64.DEFAULT) } catch (_: Exception) { ByteArray(0) }
        }
        fun bytesToStringSafe(bytes: ByteArray): String {
            if (bytes.isEmpty()) return ""
            return try { String(bytes, Charsets.UTF_8) } catch (_: Exception) {
                try { String(bytes, Charset.forName("ISO-8859-1")) } catch (_: Exception) {
                    bytes.joinToString("") { (it.toInt() and 0xFF).toChar().toString() }
                }
            }
        }
        fun hexToByteArray(hex: String?): ByteArray {
            if (hex.isNullOrBlank()) return ByteArray(0)
            val cleaned = hex.replace(Regex("[^0-9a-fA-F]"), "")
            if (cleaned.length % 2 != 0) return ByteArray(0)
            return cleaned.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        fun xorWithKey(data: ByteArray, key: ByteArray): ByteArray {
            if (key.isEmpty()) return data
            val out = ByteArray(data.size)
            for (i in data.indices) out[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
            return out
        }
        fun safeTrim(s: String?): String = s?.replace(Regex("[\\x00\\u0000]"), "")?.trim() ?: ""

        suspend fun fetchUrl(url: String): String {
            return try { app.get(url).text } catch (_: Exception) { "" }
        }

        fun getParamOffsetFromConfig(config: Any?): Int {
            if (config == null) return 0
            return try {
                if (config is Map<*, *>) {
                    val k = config["k"] as? String ?: return 0
                    val d = config["d"]
                    val idx = bytesToStringSafe(base64DecodeBytes(k)).toIntOrNull() ?: return 0
                    when (d) {
                        is List<*> -> (d.getOrNull(idx) as? Number)?.toInt() ?: 0
                        is Array<*> -> (d.getOrNull(idx) as? Number)?.toInt() ?: 0
                        else -> 0
                    }
                } else if (config is JSONObject) {
                    val k = config.optString("k", null) ?: return 0
                    val idx = bytesToStringSafe(base64DecodeBytes(k)).toIntOrNull() ?: return 0
                    val dArr = config.optJSONArray("d") ?: return 0
                    dArr.getInt(idx)
                } else 0
            } catch (_: Exception) { 0 }
        }

        fun decodeX18cResource(resourceRaw: Any?, paramOffset: Int): String {
            var raw: String? = null
            if (resourceRaw is String) raw = resourceRaw
            else if (resourceRaw is Map<*, *>) raw = (resourceRaw["r"] ?: resourceRaw["resource"] ?: resourceRaw["data"]) as? String
            else if (resourceRaw is JSONObject) {
                raw = when {
                    resourceRaw.has("r") -> resourceRaw.optString("r", null)
                    resourceRaw.has("resource") -> resourceRaw.optString("resource", null)
                    resourceRaw.has("data") -> resourceRaw.optString("data", null)
                    else -> null
                }
            }
            if (raw.isNullOrBlank()) return ""
            val rev = raw.reversed()
            val decodedBytes = try { Base64.decode(cleanBase64Chars(rev), Base64.DEFAULT) } catch (_: Exception) { ByteArray(0) }
            val slice = if (paramOffset > 0 && paramOffset <= decodedBytes.size) decodedBytes.copyOf(decodedBytes.size - paramOffset) else decodedBytes
            return safeTrim(bytesToStringSafe(slice))
        }

        fun parsePx9FromScript(js: String): Triple<String?, List<String>, Map<String, List<String>>> {
            val mMatch = Regex("""var\s+_m\s*=\s*\{\s*\"r\"\s*:\s*\"([^\"]+)\"""").find(js)
            val mVal = mMatch?.groupValues?.get(1)
            val sMatch = Regex("""var\s+_s\s*=\s*\[(.*?)\]\s*;""", RegexOption.DOT_MATCHES_ALL).find(js)
            val sList = mutableListOf<String>()
            if (sMatch != null) sList.addAll(Regex("\"([^\"]*)\"").findAll(sMatch.groupValues[1]).map { it.groupValues[1] }.toList())
            val pMatches = Regex("""var\s+(_p\d+)\s*=\s*\[\s*(.*?)\s*\]\s*;""", RegexOption.DOT_MATCHES_ALL).findAll(js)
            val pMap = mutableMapOf<String, List<String>>()
            for (m in pMatches) pMap[m.groupValues[1]] = Regex("\"([^\"]*)\"").findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
            return Triple(mVal, sList, pMap)
        }

        fun processPxChunk(hex: String?, secret: ByteArray): String {
            val xored = xorWithKey(hexToByteArray(hex), secret)
            return safeTrim(bytesToStringSafe(xored))
        }

        fun decryptPx9All(mrBase64: String?, sList: List<String>, pDict: Map<String, List<String>>): List<String> {
            if (mrBase64.isNullOrBlank()) return emptyList()
            val secret = try { Base64.decode(mrBase64, Base64.DEFAULT) } catch (_: Exception) { ByteArray(0) }
            val results = mutableListOf<String>()
            val count = maxOf(sList.size, pDict.size)
            for (i in 0 until count) {
                val key = "_p$i"
                val chunks = pDict[key] ?: continue
                val seq: IntArray? = if (i < sList.size) {
                    try {
                        val seqDecoded = processPxChunk(sList[i], secret)
                        val arr = JSONArray(seqDecoded)
                        IntArray(arr.length()) { idx -> arr.getInt(idx) }
                    } catch (_: Exception) { null }
                } else null
                val decrypted = chunks.map { processPxChunk(it, secret) }
                val final = if (seq != null && seq.size == decrypted.size) {
                    val arr = Array(decrypted.size) { "" }
                    for (j in decrypted.indices) {
                        val pos = seq[j]
                        if (pos in arr.indices) arr[pos] = decrypted[j]
                        else { val idxFallback = arr.indexOfFirst { it.isEmpty() }; if (idxFallback >= 0) arr[idxFallback] = decrypted[j] }
                    }
                    arr.joinToString("")
                } else decrypted.joinToString("")
                results.add(safeTrim(final))
            }
            return results
        }

        fun findServerElements(html: String): List<Pair<String, String>> {
            val items = mutableListOf<Pair<String, String>>()
            val anchorRegex = Regex("""(<a[^>]+class=[\"'][^\"']*server-link[^\"']*[\"'][^>]*>.*?</a>)""", RegexOption.DOT_MATCHES_ALL)
            for (m in anchorRegex.findAll(html)) {
                val tag = m.groupValues[1]
                val sid = Regex("""data-server-id\s*=\s*[\"']([^\"']+)[\"']""").find(tag)?.groupValues?.get(1)
                val label = Regex("""<span[^>]+class=[\"'][^\"']*ser[^\"']*[\"'][^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
                    .find(tag)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()
                if (sid != null) items.add(sid to (label ?: "server-$sid"))
            }
            return items
        }

        return try {
            var html = fetchWithJS(data)

            var zG: String? = Regex("""var\s+_zG\s*=\s*\"([^\"]+)\"""").find(html)?.groupValues?.get(1)
            var zH: String? = Regex("""var\s+_zH\s*=\s*\"([^\"]+)\"""").find(html)?.groupValues?.get(1)

            if (zG.isNullOrBlank() || zH.isNullOrBlank()) {
                val inlineScripts = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).findAll(html)
                    .map { it.groupValues[1] }.toList()
                for (s in inlineScripts) {
                    if (zG.isNullOrBlank()) zG = Regex("""var\s+_zG\s*=\s*\"([^\"]+)\"""").find(s)?.groupValues?.get(1)
                    if (zH.isNullOrBlank()) zH = Regex("""var\s+_zH\s*=\s*\"([^\"]+)\"""").find(s)?.groupValues?.get(1)
                    if (!zG.isNullOrBlank() && !zH.isNullOrBlank()) break
                }
            }

            if (zG.isNullOrBlank() || zH.isNullOrBlank()) {
                val scriptSrcs = Regex("""<script[^>]+src=[\"']([^\"']+)[\"'][^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
                    .map { it.groupValues[1] }.toList()
                for (src in scriptSrcs) {
                    val srcUrl = if (src.startsWith("http")) src else try { java.net.URL(java.net.URL(data), src).toString() } catch (_: Exception) { src }
                    val jsText = fetchUrl(srcUrl)
                    if (zG.isNullOrBlank()) zG = Regex("""var\s+_zG\s*=\s*\"([^\"]+)\"""").find(jsText)?.groupValues?.get(1)
                    if (zH.isNullOrBlank()) zH = Regex("""var\s+_zH\s*=\s*\"([^\"]+)\"""").find(jsText)?.groupValues?.get(1)
                    if (!zG.isNullOrBlank() && !zH.isNullOrBlank()) break
                }
            }

            val resourceRegistryObj: Any? = try {
                val dec = bytesToStringSafe(base64DecodeBytes(zG))
                try { JSONObject(dec) } catch (_: Exception) { try { JSONArray(dec) } catch (_: Exception) { null } }
            } catch (_: Exception) { null }

            val configRegistryObj: Any? = try {
                val dec = bytesToStringSafe(base64DecodeBytes(zH))
                try { JSONObject(dec) } catch (_: Exception) { try { JSONArray(dec) } catch (_: Exception) { null } }
            } catch (_: Exception) { null }

            val servers = findServerElements(html)
            val PARALLELISM = 6
            val semaphore = Semaphore(PARALLELISM)

            fun lookupRegistry(reg: Any?, sid: String): Any? {
                if (reg == null) return null
                return try {
                    when (reg) {
                        is JSONObject -> if (reg.has(sid)) reg.get(sid) else sid.toIntOrNull()?.let { if (reg.has(it.toString())) reg.get(it.toString()) else null }
                        is JSONArray -> sid.toIntOrNull()?.let { if (it >= 0 && it < reg.length()) reg.get(it) else null }
                        is Map<*, *> -> reg[sid] ?: reg[sid.toIntOrNull()]
                        else -> null
                    }
                } catch (_: Exception) { null }
            }

            supervisorScope {
                val tasks: List<Deferred<Unit>> = servers.map { (sid, _) ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                val resourceRaw = lookupRegistry(resourceRegistryObj, sid)
                                val configRaw = lookupRegistry(configRegistryObj, sid)
                                val paramOffset = getParamOffsetFromConfig(configRaw)
                                var link = decodeX18cResource(resourceRaw, paramOffset)
                                if (link.matches(Regex("""^https:\/\/yonaplay\.net\/embed\.php\?id=\d+$"""))) link = "$link&apiKey=$FRAMEWORK_HASH"
                                if (link.isNotBlank()) {
                                    if (link.contains("yonaplay.net", ignoreCase = true)) {
                                        try { decodeYonaplayAndLoad(link, subtitleCallback, callback) } catch (_: Exception) {}
                                    } else {
                                        try {
                                            when {
                                                link.contains("videa.hu", ignoreCase = true) -> {
                                                    launch(Dispatchers.IO) { try { VideaExtractor().getUrl(link, null, subtitleCallback, callback) } catch (_: Exception) {} }
                                                    try { loadExtractor(link, subtitleCallback, callback) } catch (_: Exception) {}
                                                }
                                                link.contains("my.mail.ru", ignoreCase = true) || link.contains("/video/embed/") -> {
                                                    launch(Dispatchers.IO) { try { MailruExtractor().getUrl(link, null, subtitleCallback, callback) } catch (_: Exception) {} }
                                                    try { loadExtractor(link, subtitleCallback, callback) } catch (_: Exception) {}
                                                }
                                                else -> try { loadExtractor(link, mainUrl, subtitleCallback, callback) } catch (_: Exception) {}
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
                tasks.awaitAll()
            }

            var px_mr: String? = null
            var px_s: List<String> = emptyList()
            val px_p = mutableMapOf<String, List<String>>()

            val inlineScripts = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).findAll(html)
                .map { it.groupValues[1] }
            for (s in inlineScripts) {
                if ("_m" in s && "_p0" in s) { val (mVal, sList, pMap) = parsePx9FromScript(s); px_mr = mVal ?: px_mr; if (sList.isNotEmpty()) px_s = sList; px_p.putAll(pMap); if (!px_mr.isNullOrBlank() && px_p.isNotEmpty()) break }
            }

            if (px_p.isEmpty() || px_mr.isNullOrBlank()) {
                val scriptSrcs = Regex("""<script[^>]+src=[\"']([^\"']+)[\"'][^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
                    .map { it.groupValues[1] }.toList()
                for (src in scriptSrcs) {
                    val srcUrl = if (src.startsWith("http")) src else try { java.net.URL(java.net.URL(data), src).toString() } catch (_: Exception) { src }
                    val js = fetchUrl(srcUrl); if (js.isBlank()) continue
                    if (px_p.isEmpty() || px_mr.isNullOrBlank()) { val (mVal2, sList2, pMap2) = parsePx9FromScript(js); if (mVal2 != null && px_mr.isNullOrBlank()) px_mr = mVal2; if (sList2.isNotEmpty() && px_s.isEmpty()) px_s = sList2; if (pMap2.isNotEmpty()) px_p.putAll(pMap2); if (!px_mr.isNullOrBlank() && px_p.isNotEmpty()) break }
                }
            }

            if (px_p.isEmpty()) { val (mVal3, sList3, pMap3) = parsePx9FromScript(html); if (mVal3 != null) px_mr = mVal3; if (sList3.isNotEmpty()) px_s = sList3; if (pMap3.isNotEmpty()) px_p.putAll(pMap3) }

            val downloadLinks = decryptPx9All(px_mr, px_s, px_p)

            supervisorScope {
                val dlTasks: List<Deferred<Unit>> = downloadLinks.mapIndexed { _, dl ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                if (dl.isNotBlank()) {
                                    val httpIndex = dl.indexOf("http")
                                    val cleaned = if (httpIndex >= 0) dl.substring(httpIndex) else dl
                                    val finalUrl = safeTrim(cleaned)
                                    if (finalUrl.startsWith("http")) try { loadExtractor(finalUrl, data, subtitleCallback, callback) } catch (_: Exception) {}
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
                dlTasks.awaitAll()
            }

            true
        } catch (_: Exception) { false }
    }

    private suspend fun decodeYonaplayAndLoad(
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
