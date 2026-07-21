package com.witanime

import android.net.Uri
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class VideaExtractor : ExtractorApi() {
    override val name: String = "Videa"
    override val mainUrl: String = "https://videa.hu"
    override val requiresReferer: Boolean = false

    private val STUPID_KEY = "xHb0ZvME5q8CBcoQi6AngerDu3FGO9fkUlwPmLVY_RTzj2hJIS4NasXWKy1td7p"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val iframeSrc = url.trim()
            if (iframeSrc.isBlank()) return
            val pageResp = app.get(iframeSrc).text
            val nonceMatch = Regex("_xt\\s*=\\s*\"([^\"]+)\"").find(pageResp) ?: return
            val nonce = nonceMatch.groupValues[1]

            val paramL = if (nonce.length >= 32) nonce.substring(0, 32) else nonce.padEnd(32, 'a')
            val paramSPart = if (nonce.length > 32) nonce.substring(32) else ""

            val resultBuilder = StringBuilder()
            for (i in 0 until 32) {
                val ch = paramL.getOrNull(i) ?: 'a'
                val idxInStupid = STUPID_KEY.indexOf(ch).takeIf { it >= 0 } ?: 0
                val index = i - (idxInStupid - 31)
                val safeIndex = when {
                    paramSPart.isEmpty() -> 0
                    index < 0 -> 0
                    index >= paramSPart.length -> paramSPart.length - 1
                    else -> index
                }
                resultBuilder.append(paramSPart.getOrNull(safeIndex) ?: 'a')
            }
            val result = resultBuilder.toString()
            val seed = (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")
            val paramT = if (result.length >= 16) result.substring(0, 16) else result.padEnd(16, '0')
            val rc4KeyPart = if (result.length > 16) result.substring(16) else ""

            val videoId = try { Uri.parse(iframeSrc).getQueryParameter("v") } catch (_: Exception) { Regex("[?&]v=([^&]+)").find(iframeSrc)?.groupValues?.get(1) } ?: return

            val xmlUrl = "https://videa.hu/player/xml?platform=desktop"
            val params = mapOf("_s" to seed, "_t" to paramT, "v" to videoId)
            val headers = mapOf("Referer" to iframeSrc, "Origin" to "https://videa.hu")

            val xmlResponse = app.get(xmlUrl, params = params, headers = headers)
            val body = xmlResponse.text
            val xVideaXsHeader = xmlResponse.headers["x-videa-xs"] ?: ""

            val finalDoc = if (body.trimStart().startsWith("<?xml")) {
                val parsed = Jsoup.parse(body, "", Parser.xmlParser())
                if (parsed.selectFirst("error") != null) return
                parsed
            } else {
                try {
                    val decoded = Base64.decode(body.trim(), Base64.DEFAULT)
                    val finalRc4Key = rc4KeyPart + seed + xVideaXsHeader
                    val decrypted = rc4Decrypt(decoded, finalRc4Key)
                    Jsoup.parse(decrypted, "", Parser.xmlParser())
                } catch (_: Exception) { return }
            }

            val videoSources = finalDoc.select("video_source")
            if (videoSources.isEmpty()) return

            val collected = mutableListOf<Triple<Int, String, String>>()
            for (src in videoSources) {
                try {
                    val name = src.attr("name")
                    val videoUrlPart = src.text().trim()
                    val exp = src.attr("exp")
                    val hashTagName = "hash_value_$name"
                    val hashElem = finalDoc.getElementsByTag(hashTagName).first() ?: continue
                    val md5 = hashElem.text()?.trim() ?: continue
                    val finalUrl = if (videoUrlPart.startsWith("http")) "$videoUrlPart?md5=$md5&expires=$exp" else "https:$videoUrlPart?md5=$md5&expires=$exp"
                    val q = try { getQualityFromName(name).let { if (it <= 0) Qualities.Unknown.value else it } } catch (_: Exception) { Qualities.Unknown.value }
                    collected.add(Triple(q, name, finalUrl))
                } catch (_: Exception) {}
            }

            val sorted = collected.sortedWith(compareByDescending<Triple<Int, String, String>> { it.first }
                .thenByDescending { Regex("(\\d{3,4})").find(it.second)?.groupValues?.get(1)?.toIntOrNull() ?: 0 })

            for ((qualityVal, name, finalUrl) in sorted) {
                try {
                    callback.invoke(newExtractorLink(source = this@VideaExtractor.name, name = "$name (Videa)", url = finalUrl,
                        type = if (finalUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) { this.referer = referer ?: iframeSrc; this.quality = qualityVal })
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun rc4Decrypt(data: ByteArray, key: String): String {
        return try {
            val spec = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "RC4")
            val cipher = Cipher.getInstance("RC4")
            cipher.init(Cipher.DECRYPT_MODE, spec)
            val out = cipher.update(data) ?: cipher.doFinal(data)
            String(out, StandardCharsets.UTF_8)
        } catch (_: Exception) { "" }
    }
}