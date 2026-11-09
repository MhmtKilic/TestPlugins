@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller

open class RapidVid : ExtractorApi() {
    override val name            = "RapidVid"
    override val mainUrl         = "https://rapidvid.org"
    override val requiresReferer = true

    private val cloudflareKiller by lazy { CloudflareKiller() }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef = referer ?: ""
        
        // Cloudstream'in isteğinin engellenmemesi için CloudflareKiller kullanıyoruz
        val videoReq = app.get(
            url, 
            referer = extRef, 
            interceptor = cloudflareKiller,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            )
        ).text

        // 1. YENİ YÖNTEM: atomCodecTelemetry (Direkt Regex ile link çekme)
        // JSON parse etmek yerine direkt "h264":"..." kısmını arıyoruz.
        val h264Match = Regex(""""h264"\s*:\s*"([^"]+)"""").find(videoReq)
        val av1Match = Regex(""""av1"\s*:\s*"([^"]+)"""").find(videoReq)
        
        val videoUrl = h264Match?.groupValues?.get(1)?.replace("\\/", "/") 
                    ?: av1Match?.groupValues?.get(1)?.replace("\\/", "/")

        if (!videoUrl.isNullOrBlank() && !videoUrl.contains(".vtt")) {
            Log.d("RapidVid", "✅ Yeni yöntemle video bulundu: $videoUrl")
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        // 2. ESKİ YÖNTEM (Geriye dönük uyumluluk)
        val p8Payload  = Regex("""window\._p8\s*=\s*'([^']+)'""").find(videoReq)?.groupValues?.get(1)
        val p8Json     = p8Payload?.let { decodeP8(it) }
        val decoded    = p8Json?.let { Regex(""""cm"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1)?.replace("\\/", "/") }
            ?: decodeLegacy(videoReq)

        if (!decoded.isNullOrBlank() && !decoded.contains(".vtt")) {
            Log.d("RapidVid", "✅ Eski yöntemle video bulundu: $decoded")
            
            callback.invoke(
                newExtractorLink(
                    source  = this.name,
                    name    = this.name,
                    url     = decoded,
                    type    = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        // 3. Son Çare: HTML içinde m3u8 arama
        val directMatch = Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""").find(videoReq)
        if (directMatch != null) {
            val directUrl = directMatch.groupValues[1].replace("\\/", "/")
            Log.d("RapidVid", "✅ HTML içinden video bulundu: $directUrl")
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = directUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        // Hata mesajına sayfanın uzunluğunu ekleyelim ki engellenip engellenmediğimizi anlayalım
        throw ErrorLoadingException("RapidVid video linki bulunamadı. (HTML Uzunluğu: ${videoReq.length})")
    }

    private fun decodeP8(payload: String): String? {
        return try {
            val step1 = b64Latin1(payload.reversed()) ?: return null
            val key   = "K9L"
            val shifted = buildString(step1.length) {
                step1.forEachIndexed { i, ch ->
                    append((ch.code - (key[i % 3].code % 5 + 1)).toChar())
                }
            }
            b64Latin1(shifted)
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeLegacy(videoReq: String): String? {
        Regex(""""file": "(.*?)","""").find(videoReq)?.groupValues?.get(1)?.let { extractedValue ->
            if (!extractedValue.contains("\\x")) return extractedValue.replace("\\", "")
            val bytes = extractedValue.split("\\x").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
            return String(bytes, Charsets.UTF_8)
        }

        val evalJWSsetup = Regex("""\};\s*(eval\(function[\s\S]*?)var played = \d+;""").find(videoReq)?.groupValues?.get(1)
            ?: return null
        val JWSsetup = getAndUnpack(getAndUnpack(evalJWSsetup)).replace("\\\\", "\\")
        val extracted = Regex(""""file":"(.*)","label"""").find(JWSsetup)?.groupValues?.get(1)?.replace("\\\\x", "")
            ?: return null
        val bytes = extracted.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return String(bytes, Charsets.UTF_8)
    }

    private fun b64Latin1(value: String): String? {
        val cleaned = value.filter { !it.isWhitespace() }
        if (cleaned.isEmpty()) return null
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return try {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.ISO_8859_1)
        } catch (_: Throwable) {
            try {
                String(java.util.Base64.getDecoder().decode(padded), Charsets.ISO_8859_1)
            } catch (_: Throwable) {
                null
            }
        }
    }
                              }
