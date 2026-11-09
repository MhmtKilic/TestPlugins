@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

open class RapidVid : ExtractorApi() {
    override val name            = "RapidVid"
    override val mainUrl         = "https://rapidvid.org"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef = referer ?: ""
        
        // Tarayıcı kimliği ekleyerek sitenin bizi engellemesini önlüyoruz
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.8,en-US;q=0.5,en;q=0.3"
        )
        
        val videoReq = app.get(url, referer = extRef, headers = headers).text

        // 1. YENİ YÖNTEM: window.atomCodecTelemetry 
        val telemetryRegex = Regex("""window\.atomCodecTelemetry\s*=\s*(\{.*?\});""")
        val telemetryMatch = telemetryRegex.find(videoReq)
        
        if (telemetryMatch != null) {
            val jsonStr = telemetryMatch.groupValues[1]
            try {
                val data = jacksonObjectMapper().readValue<Map<String, String>>(jsonStr)
                val videoUrl = data["h264"]?.replace("\\/", "/") ?: data["av1"]?.replace("\\/", "/")
                
                if (!videoUrl.isNullOrBlank()) {
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
            } catch (e: Exception) {
                Log.e("RapidVid", "Telemetry parse hatası: ${e.message}")
            }
        }

        // 2. ESKİ YÖNTEM: window._p8 (Geriye dönük uyumluluk)
        val p8Payload  = Regex("""window\._p8\s*=\s*'([^']+)'""").find(videoReq)?.groupValues?.get(1)
        val p8Json     = p8Payload?.let { decodeP8(it) }
        val decoded    = p8Json?.let { Regex(""""cm"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1)?.replace("\\/", "/") }
            ?: decodeLegacy(videoReq)

        if (!decoded.isNullOrBlank() && !decoded.contains(".vtt")) {
            Log.d("RapidVid", "✅ Eski yöntemle video bulundu: $decoded")
            
            val subUrls  = mutableSetOf<String>()
            val subSource = videoReq + "\n" + (p8Json ?: "")
            Regex(""""file"\s*:\s*"([^"]+\.vtt)",\s*"label"\s*:\s*"([^"]+)"""").findAll(subSource).forEach {
                val (subUrl, subLang) = it.destructured
                if (subUrls.add(subUrl)) {
                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = unescapeUnicode(subLang).trim(),
                            url  = fixUrl(subUrl.replace("\\", ""))
                        )
                    )
                }
            }

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

        // 3. Son Çare: Doğrudan HTML içinde m3u8 arama
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

        throw ErrorLoadingException("RapidVid video linki bulunamadı")
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

    private fun unescapeUnicode(value: String): String =
        Regex("""\\u([0-9a-fA-F]{4})""").replace(value) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }
                              }
