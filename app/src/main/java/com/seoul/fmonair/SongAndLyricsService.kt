package com.seoul.fmonair

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

data class AcrCloudConfig(
    val host: String,
    val accessKey: String,
    val accessSecret: String
) {
    val isConfigured: Boolean
        get() = host.isNotBlank() && accessKey.isNotBlank() && accessSecret.isNotBlank()
}

private data class InternalStreamAudioSample(
    val decodedWavBytes: ByteArray?,
    val rawStreamBytes: ByteArray,
    val fileExtension: String
)

object SongAndLyricsService {

    private const val PREFS_HISTORY = "seoul_fm_song_history"
    private const val KEY_HISTORY_JSON = "saved_songs_json"
    private const val KEY_AUDD_TOKEN = "audd_api_token"
    private const val KEY_ACR_HOST = "acr_host"
    private const val KEY_ACR_ACCESS_KEY = "acr_access_key"
    private const val KEY_ACR_ACCESS_SECRET = "acr_access_secret"

    const val DEFAULT_ACR_HOST = "identify-ap-southeast-1.acrcloud.com"
    const val DEFAULT_ACR_ACCESS_KEY = "f6abd26110a9254f8256a0ccff81e72a"
    const val DEFAULT_ACR_ACCESS_SECRET = "Eqsfqxj5wCU6wyaVt1mckfZwyRoJcsslFsfmbu8L"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val hangulRegex = Regex("[가-힣]")

    fun getAuddApiToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AUDD_TOKEN, "test") ?: "test"
    }

    fun saveAuddApiToken(context: Context, token: String) {
        val prefs = context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_AUDD_TOKEN, token.trim().ifEmpty { "test" }).apply()
    }

    fun getAcrCloudConfig(context: Context): AcrCloudConfig {
        val prefs = context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
        val savedHost = prefs.getString(KEY_ACR_HOST, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_ACR_HOST
        val savedKey = prefs.getString(KEY_ACR_ACCESS_KEY, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_ACR_ACCESS_KEY
        val savedSecret = prefs.getString(KEY_ACR_ACCESS_SECRET, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_ACR_ACCESS_SECRET
        return AcrCloudConfig(
            host = savedHost,
            accessKey = savedKey,
            accessSecret = savedSecret
        )
    }

    fun saveAcrCloudConfig(context: Context, host: String, accessKey: String, accessSecret: String) {
        val cleanHost = host.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/v1/identify")
            .removeSuffix("/")
            .ifEmpty { DEFAULT_ACR_HOST }

        val cleanKey = accessKey.trim().ifEmpty { DEFAULT_ACR_ACCESS_KEY }
        val cleanSecret = accessSecret.trim().ifEmpty { DEFAULT_ACR_ACCESS_SECRET }

        val prefs = context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_ACR_HOST, cleanHost)
            .putString(KEY_ACR_ACCESS_KEY, cleanKey)
            .putString(KEY_ACR_ACCESS_SECRET, cleanSecret)
            .apply()
    }

    suspend fun localizeTrackToKorean(
        rawTitle: String,
        rawArtist: String,
        appleMusicTrackId: String = ""
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        var bestTitle = rawTitle.trim()
        var bestArtist = rawArtist.trim()

        if (hangulRegex.containsMatchIn(bestTitle) && hangulRegex.containsMatchIn(bestArtist)) {
            return@withContext bestTitle to bestArtist
        }

        if (appleMusicTrackId.isNotBlank()) {
            try {
                val lookupUrl = "https://itunes.apple.com/lookup?id=${URLEncoder.encode(appleMusicTrackId, "UTF-8")}&country=kr&lang=ko_kr"
                val req = Request.Builder()
                    .url(lookupUrl)
                    .header("Accept-Language", "ko-KR,ko;q=0.9")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; ko-KR)")
                    .build()

                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (body.startsWith("{")) {
                        val json = JSONObject(body)
                        val results = json.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            val item = results.getJSONObject(0)
                            val krTitle = item.optString("trackName", "").trim()
                            val krArtist = item.optString("artistName", "").trim()
                            if (krTitle.isNotBlank()) bestTitle = krTitle
                            if (krArtist.isNotBlank()) bestArtist = krArtist
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (!hangulRegex.containsMatchIn(bestTitle) || !hangulRegex.containsMatchIn(bestArtist)) {
            try {
                val cleanTitle = bestTitle.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim()
                val query = "$bestArtist $cleanTitle".trim()
                val searchUrl = "https://itunes.apple.com/search?term=${URLEncoder.encode(query, "UTF-8")}&country=kr&lang=ko_kr&entity=song&limit=5"
                val req = Request.Builder()
                    .url(searchUrl)
                    .header("Accept-Language", "ko-KR,ko;q=0.9")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; ko-KR)")
                    .build()

                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (body.startsWith("{")) {
                        val json = JSONObject(body)
                        val results = json.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            for (i in 0 until results.length()) {
                                val item = results.getJSONObject(i)
                                val candTitle = item.optString("trackName", "").trim()
                                val candArtist = item.optString("artistName", "").trim()
                                if (hangulRegex.containsMatchIn(candTitle) || hangulRegex.containsMatchIn(candArtist)) {
                                    if (candTitle.isNotBlank()) bestTitle = candTitle
                                    if (candArtist.isNotBlank()) bestArtist = candArtist
                                    break
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        try {
            val cleanQuery = "${bestArtist.split("(").first().trim()} ${bestTitle.split("(").first().trim()}".trim()
            val melonUrl = "https://www.melon.com/search/keyword/index.json?query=${URLEncoder.encode(cleanQuery, "UTF-8")}"
            val req = Request.Builder()
                .url(melonUrl)
                .header("Referer", "https://www.melon.com/")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; ko-KR)")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()?.trim().orEmpty()
                if (body.startsWith("{")) {
                    val json = JSONObject(body)
                    val songs = json.optJSONArray("SONGCONTENTS")
                    if (songs != null && songs.length() > 0) {
                        val topSong = songs.getJSONObject(0)
                        val melonTitle = topSong.optString("SONGNAME", "").trim()
                        val melonArtist = topSong.optString("ARTISTNAME", "").trim()
                        if (melonTitle.isNotBlank() && hangulRegex.containsMatchIn(melonTitle)) {
                            bestTitle = melonTitle
                        }
                        if (melonArtist.isNotBlank() && hangulRegex.containsMatchIn(melonArtist)) {
                            bestArtist = melonArtist
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        return@withContext bestTitle to bestArtist
    }

    suspend fun searchLyricsFromLrcLib(
        query: String,
        stationName: String = "실시간 검색",
        frequencyText: String = "LRCLIB"
    ): List<RecognizedSong> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val results = mutableListOf<RecognizedSong>()
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val req = Request.Builder()
                .url("https://lrclib.net/api/search?q=$encoded")
                .header("User-Agent", "SeoulFMRadioApp/2.5 (https://github.com/seoul-fm-radio)")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (body.startsWith("[")) {
                    val arr = JSONArray(body)
                    val limit = minOf(arr.length(), 12)
                    for (i in 0 until limit) {
                        val obj = arr.getJSONObject(i)
                        val title = obj.optString("trackName", "제목 미상")
                        val artist = obj.optString("artistName", "아티스트 미상")
                        val album = obj.optString("albumName", "")
                        val plain = obj.optString("plainLyrics", "")
                        val syncedRaw = obj.optString("syncedLyrics", "")
                        val parsedSynced = parseLrcText(syncedRaw, plain)

                        val fallbackPlain = if (plain.isNotBlank()) {
                            plain
                        } else {
                            parsedSynced.joinToString(separator = "\n") { it.text }
                        }

                        results.add(
                            RecognizedSong(
                                id = UUID.randomUUID().toString(),
                                title = title,
                                artist = artist,
                                album = album,
                                stationName = stationName,
                                frequencyText = frequencyText,
                                recognizedAt = nowTimeString(),
                                plainLyrics = fallbackPlain,
                                syncedLyrics = parsedSynced,
                                sourceMethod = "LRCLIB 실시간 가사 API"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext results
    }

    private fun resolveRelativeUrl(baseUrl: String, relativeOrFull: String): String {
        val trimmed = relativeOrFull.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        return try {
            URI(baseUrl).resolve(trimmed).toString()
        } catch (_: Exception) {
            val slashIdx = baseUrl.lastIndexOf('/')
            if (slashIdx > 8) {
                baseUrl.substring(0, slashIdx + 1) + trimmed.removePrefix("/")
            } else {
                trimmed
            }
        }
    }

    /**
     * ★ 핵심 변경: 마이크 대신 스마트폰에서 재생 중인 라디오 스트림(HLS 세그먼트)에서
     * 직접 디지털 오디오를 추출하고 무손실 WAV로 디코딩합니다. (블루투스 이어폰 완벽 지원)
     */
    private suspend fun captureInternalStreamAudio(
        context: Context,
        station: RadioStation
    ): InternalStreamAudioSample? = withContext(Dispatchers.IO) {
        try {
            val streamUrl = StationRepository.resolvePlayableStreamUrl(station)
            if (!streamUrl.startsWith("http")) return@withContext null

            var currentPlaylistUrl = streamUrl
            var playlistBody = ""
            var finalResponseUrl = streamUrl

            val req1 = Request.Builder()
                .url(currentPlaylistUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .build()

            client.newCall(req1).execute().use { resp ->
                finalResponseUrl = resp.request.url.toString()
                val contentType = resp.header("Content-Type", "").orEmpty().lowercase(Locale.ROOT)
                // 직접 오디오 스트림인 경우 6초 분량 바이트 캡처
                if (!finalResponseUrl.contains(".m3u8") &&
                    (contentType.contains("audio/") || contentType.contains("video/mp2t") || contentType.contains("octet-stream"))
                ) {
                    val stream = resp.body?.byteStream() ?: return@use
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    val deadline = System.currentTimeMillis() + 4500L
                    while (System.currentTimeMillis() < deadline && buffer.size() < 240_000) {
                        val read = stream.read(chunk)
                        if (read <= 0) break
                        buffer.write(chunk, 0, read)
                    }
                    val rawBytes = buffer.toByteArray()
                    if (rawBytes.size > 4000) {
                        val tempFile = File(context.cacheDir, "direct_stream_sample.aac")
                        FileOutputStream(tempFile).use { it.write(rawBytes) }
                        val wav = decodeStreamFileToWav(tempFile)
                        tempFile.delete()
                        return@withContext InternalStreamAudioSample(wav, rawBytes, "aac")
                    }
                }
                playlistBody = resp.body?.string().orEmpty()
            }

            if (playlistBody.isBlank()) return@withContext null

            // Master m3u8인 경우 하위 Media m3u8 주소 추적 (최대 2단계)
            for (depth in 0 until 2) {
                val subM3u8Line = playlistBody.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && it.contains(".m3u8", ignoreCase = true) }

                if (subM3u8Line != null) {
                    currentPlaylistUrl = resolveRelativeUrl(finalResponseUrl, subM3u8Line)
                    val subReq = Request.Builder()
                        .url(currentPlaylistUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                        .build()
                    client.newCall(subReq).execute().use { subResp ->
                        finalResponseUrl = subResp.request.url.toString()
                        playlistBody = subResp.body?.string().orEmpty()
                    }
                } else {
                    break
                }
            }

            // Media m3u8에서 최신 오디오 세그먼트(.ts / .aac / .m4s) 3개 추출
            val segmentLines = playlistBody.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()

            if (segmentLines.isEmpty()) return@withContext null

            val targetSegments = segmentLines.takeLast(3)
            val combinedOut = ByteArrayOutputStream()
            var detectedExt = "ts"

            for (segLine in targetSegments) {
                val segUrl = resolveRelativeUrl(finalResponseUrl, segLine)
                if (segUrl.contains(".aac", ignoreCase = true)) detectedExt = "aac"
                else if (segUrl.contains(".m4a", ignoreCase = true) || segUrl.contains(".mp4", ignoreCase = true)) detectedExt = "m4a"

                val segReq = Request.Builder()
                    .url(segUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                    .build()

                client.newCall(segReq).execute().use { segResp ->
                    val bytes = segResp.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        combinedOut.write(bytes)
                    }
                }
                if (combinedOut.size() >= 380_000) break
            }

            val rawStreamBytes = combinedOut.toByteArray()
            if (rawStreamBytes.size < 2000) return@withContext null

            val tempFile = File(context.cacheDir, "live_radio_internal_sample.$detectedExt")
            FileOutputStream(tempFile).use { it.write(rawStreamBytes) }
            val decodedWav = decodeStreamFileToWav(tempFile)
            tempFile.delete()

            return@withContext InternalStreamAudioSample(
                decodedWavBytes = decodedWav,
                rawStreamBytes = rawStreamBytes,
                fileExtension = detectedExt
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    /**
     * 다운로드된 라디오 스트림 세그먼트를 안드로이드 내장 MediaExtractor + MediaCodec으로
     * 16-bit PCM WAV 바이트 배열로 디코딩합니다.
     */
    private fun decodeStreamFileToWav(streamFile: File): ByteArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(streamFile.absolutePath)
            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = format
                    break
                }
            }
            if (audioTrackIndex < 0 || trackFormat == null) {
                extractor.release()
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            val pcmOut = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEOS = false
            var isOutputEOS = false
            var sampleRate = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            var channels = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            val timeoutUs = 10_000L
            var loopGuard = 0
            // 약 6.5초 분량의 PCM 추출
            val maxPcmBytes = sampleRate * channels * 2 * 6

            while (!isOutputEOS && loopGuard < 1600 && pcmOut.size() < maxPcmBytes) {
                loopGuard++
                if (!isInputEOS) {
                    val inIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEOS = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    outIndex >= 0 -> {
                        val outputBuffer = decoder.getOutputBuffer(outIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)
                            pcmOut.write(chunk)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isOutputEOS = true
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = decoder.outputFormat
                        if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                }
            }

            decoder.stop()
            decoder.release()
            codec = null
            extractor.release()

            val rawPcm = pcmOut.toByteArray()
            if (rawPcm.size < 4000) null else encodePcmToWav(rawPcm, sampleRate, channels.coerceAtLeast(1), 16)
        } catch (_: Exception) {
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
            null
        }
    }

    private fun identifyWithAcrCloud(
        audioBytes: ByteArray,
        config: AcrCloudConfig,
        fileExt: String = "wav"
    ): Triple<String, String, String>? {
        if (!config.isConfigured || audioBytes.size < 1000) return null
        try {
            val httpMethod = "POST"
            val httpUri = "/v1/identify"
            val dataType = "audio"
            val signatureVersion = "1"
            val timestamp = (System.currentTimeMillis() / 1000L).toString()

            val stringToSign = "$httpMethod\n$httpUri\n${config.accessKey}\n$dataType\n$signatureVersion\n$timestamp"
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(config.accessSecret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            val rawHmac = mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8))
            val signature = Base64.encodeToString(rawHmac, Base64.NO_WRAP)

            val mimeType = when (fileExt) {
                "aac" -> "audio/aac"
                "ts" -> "video/mp2t"
                else -> "audio/wav"
            }

            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("access_key", config.accessKey)
                .addFormDataPart("sample_bytes", audioBytes.size.toString())
                .addFormDataPart("timestamp", timestamp)
                .addFormDataPart("signature", signature)
                .addFormDataPart("data_type", dataType)
                .addFormDataPart("signature_version", signatureVersion)
                .addFormDataPart(
                    "sample",
                    "radio_internal_sample.$fileExt",
                    audioBytes.toRequestBody(mimeType.toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url("https://${config.host}/v1/identify")
                .post(multipart)
                .build()

            client.newCall(request).execute().use { response ->
                val jsonStr = response.body?.string().orEmpty()
                if (jsonStr.startsWith("{")) {
                    val root = JSONObject(jsonStr)
                    val status = root.optJSONObject("status")
                    if (status != null && status.optInt("code", -1) == 0) {
                        val metadata = root.optJSONObject("metadata")
                        val musicArr = metadata?.optJSONArray("music")
                        if (musicArr != null && musicArr.length() > 0) {
                            val music = musicArr.getJSONObject(0)
                            val title = music.optString("title", "").trim()
                            val artistsArr = music.optJSONArray("artists")
                            val artist = if (artistsArr != null && artistsArr.length() > 0) {
                                artistsArr.getJSONObject(0).optString("name", "").trim()
                            } else ""

                            val extMeta = music.optJSONObject("external_metadata")
                            val appleTrackId = extMeta
                                ?.optJSONObject("applemusic")
                                ?.optJSONObject("track")
                                ?.optString("id", "")
                                .orEmpty()

                            if (title.isNotBlank()) {
                                return Triple(title, artist, appleTrackId)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun identifyWithAudd(
        audioBytes: ByteArray,
        apiToken: String,
        fileExt: String = "wav"
    ): Triple<String, String, String>? {
        if (audioBytes.size < 1000) return null
        try {
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("api_token", apiToken)
                .addFormDataPart("return", "lyrics,apple_music,spotify")
                .addFormDataPart(
                    "file",
                    "radio_internal_sample.$fileExt",
                    audioBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url("https://api.audd.io/")
                .post(multipart)
                .build()

            client.newCall(request).execute().use { response ->
                val jsonStr = response.body?.string().orEmpty()
                if (jsonStr.startsWith("{")) {
                    val root = JSONObject(jsonStr)
                    val resultObj = root.optJSONObject("result")
                    if (resultObj != null) {
                        val title = resultObj.optString("title", "").trim()
                        val artist = resultObj.optString("artist", "").trim()
                        val appleId = resultObj.optJSONObject("apple_music")?.optString("id", "").orEmpty()
                        if (title.isNotBlank()) {
                            return Triple(title, artist, appleId)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * ★ 블루투스 이어폰 완벽 대응 내부 스트림 기반 음원 인식 엔진:
     * 1순위: 스마트폰에서 재생 중인 라디오 스트림 오디오 직접 추출 -> ACRCloud / AudD 핑거프린팅
     * 2순위: 스트림 내부 실시간 ICY 메타데이터
     * 3순위: 방송사(KBS/MBC/SBS) 실시간 온에어 선곡표 API
     * 4순위: (스트림 추출 실패 시에만 보조) 마이크 녹음
     */
    suspend fun recognizeCurrentRadioSong(
        context: Context,
        station: RadioStation,
        icyMetadataTitle: String? = null
    ): RecognizedSong = withContext(Dispatchers.IO) {
        var detectedTitle = ""
        var detectedArtist = ""
        var appleMusicId = ""
        var methodLabel = ""

        val acrConfig = getAcrCloudConfig(context)
        val auddToken = getAuddApiToken(context)

        // [1단계] 스마트폰 내부 라디오 스트림에서 직접 디지털 오디오 추출 (블루투스 이어폰 사용 시에도 100% 작동)
        val internalSample = captureInternalStreamAudio(context, station)
        if (internalSample != null) {
            val wavBytes = internalSample.decodedWavBytes
            // 1-A: 디코딩된 무손실 WAV로 ACRCloud 조회
            if (wavBytes != null && acrConfig.isConfigured) {
                val acrWavMatch = identifyWithAcrCloud(wavBytes, acrConfig, "wav")
                if (acrWavMatch != null) {
                    detectedTitle = acrWavMatch.first
                    detectedArtist = acrWavMatch.second
                    appleMusicId = acrWavMatch.third
                    methodLabel = "스마트폰 내부 스트림 인식 (ACRCloud)"
                }
            }
            // 1-B: 원본 디지털 스트림(AAC/TS)으로 ACRCloud 조회
            if (detectedTitle.isBlank() && acrConfig.isConfigured) {
                val acrRawMatch = identifyWithAcrCloud(
                    internalSample.rawStreamBytes,
                    acrConfig,
                    internalSample.fileExtension
                )
                if (acrRawMatch != null) {
                    detectedTitle = acrRawMatch.first
                    detectedArtist = acrRawMatch.second
                    appleMusicId = acrRawMatch.third
                    methodLabel = "스마트폰 내부 스트림 인식 (ACRCloud)"
                }
            }
            // 1-C: AudD 보조 핑거프린팅 조회
            if (detectedTitle.isBlank()) {
                val targetBytes = wavBytes ?: internalSample.rawStreamBytes
                val targetExt = if (wavBytes != null) "wav" else internalSample.fileExtension
                val auddMatch = identifyWithAudd(targetBytes, auddToken, targetExt)
                if (auddMatch != null) {
                    detectedTitle = auddMatch.first
                    detectedArtist = auddMatch.second
                    appleMusicId = auddMatch.third
                    methodLabel = "스마트폰 내부 스트림 인식 (AudD)"
                }
            }
        }

        // [2단계] 스트림 내부 실시간 ICY/HLS 태그 확인
        if (detectedTitle.isBlank() && !icyMetadataTitle.isNullOrBlank()) {
            val cleaned = icyMetadataTitle.trim()
            if (cleaned.contains(" - ")) {
                val parts = cleaned.split(" - ", limit = 2)
                detectedArtist = parts[0].trim()
                detectedTitle = parts[1].trim()
                methodLabel = "실시간 스트림 메타데이터"
            } else if (cleaned.contains("-")) {
                val parts = cleaned.split("-", limit = 2)
                detectedArtist = parts[0].trim()
                detectedTitle = parts[1].trim()
                methodLabel = "실시간 스트림 메타데이터"
            }
        }

        // [3단계] 방송사 실시간 온에어/선곡 API 조회 (KBS, MBC, SBS)
        if (detectedTitle.isBlank()) {
            val stationMeta = fetchBroadcasterRealtimeSong(station)
            if (stationMeta != null) {
                detectedTitle = stationMeta.first
                detectedArtist = stationMeta.second
                methodLabel = "${station.broadcaster} 실시간 선곡표 API"
            }
        }

        // [4단계] 내부 스트림 캡처가 불가한 특수 환경일 때만 마이크 보조 시도
        if (detectedTitle.isBlank() && internalSample == null) {
            val micWav = recordHighQualityWavAudio()
            if (micWav != null && micWav.size > 2000) {
                val acrMic = identifyWithAcrCloud(micWav, acrConfig, "wav")
                if (acrMic != null) {
                    detectedTitle = acrMic.first
                    detectedArtist = acrMic.second
                    appleMusicId = acrMic.third
                    methodLabel = "오디오 핑거프린팅 (ACRCloud)"
                }
            }
        }

        // [5단계] 영문으로 인식된 한국 노래를 한국어 제목·가수로 자동 변환 후 가사 조회
        if (detectedTitle.isNotBlank()) {
            val rawEnTitle = detectedTitle
            val rawEnArtist = detectedArtist

            val (korTitle, korArtist) = localizeTrackToKorean(
                rawTitle = rawEnTitle,
                rawArtist = rawEnArtist,
                appleMusicTrackId = appleMusicId
            )

            detectedTitle = korTitle
            detectedArtist = korArtist

            var lrcMatches = searchLyricsFromLrcLib(
                query = "$detectedTitle $detectedArtist".trim(),
                stationName = station.name,
                frequencyText = station.frequencyText
            )

            if (lrcMatches.isEmpty() && (rawEnTitle != detectedTitle || rawEnArtist != detectedArtist)) {
                lrcMatches = searchLyricsFromLrcLib(
                    query = "$rawEnTitle $rawEnArtist".trim(),
                    stationName = station.name,
                    frequencyText = station.frequencyText
                )
            }

            if (lrcMatches.isNotEmpty()) {
                val best = lrcMatches.first()
                return@withContext best.copy(
                    title = detectedTitle,
                    artist = detectedArtist,
                    stationName = station.name,
                    frequencyText = station.frequencyText,
                    recognizedAt = nowTimeString(),
                    sourceMethod = methodLabel
                )
            }

            val guideLines = listOf(
                LyricLine(0f, "🎵 인식된 곡: $detectedArtist - $detectedTitle"),
                LyricLine(3.5f, "🍈 바로 위 [멜론에서 보기] 버튼을 누르면 멜론에서 곡과 가사를 바로 볼 수 있습니다."),
                LyricLine(7.0f, "채널: ${station.name} (${station.frequencyText})"),
                LyricLine(10.5f, "인식 방식: $methodLabel (블루투스 이어폰 지원)")
            )
            return@withContext RecognizedSong(
                id = UUID.randomUUID().toString(),
                title = detectedTitle,
                artist = detectedArtist,
                album = "${station.name} ON-AIR",
                stationName = station.name,
                frequencyText = station.frequencyText,
                recognizedAt = nowTimeString(),
                plainLyrics = guideLines.joinToString(separator = "\n") { it.text },
                syncedLyrics = guideLines,
                sourceMethod = methodLabel
            )
        }

        val noticeLines = listOf(
            LyricLine(0f, "🎙️ 현재 DJ 멘트/광고 중이거나 음원이 감지되지 않았습니다."),
            LyricLine(3.5f, "1) 스마트폰 내부 라디오 스트림을 직접 분석하므로 블루투스 이어폰 상태에서도 그대로 작동합니다."),
            LyricLine(7.0f, "2) 노래가 나오는 구간에서 [🎵 BGM 노래 인식] 버튼을 다시 눌러주세요."),
            LyricLine(10.5f, "3) 또는 [🍈 멜론] 버튼을 눌러 들리는 가사나 곡명을 멜론에서 바로 검색해 보세요.")
        )
        return@withContext RecognizedSong(
            id = UUID.randomUUID().toString(),
            title = "현재 곡 미감지 (DJ 멘트 또는 광고 시간)",
            artist = station.name,
            album = station.defaultProgram,
            stationName = station.name,
            frequencyText = station.frequencyText,
            recognizedAt = nowTimeString(),
            plainLyrics = noticeLines.joinToString(separator = "\n") { it.text },
            syncedLyrics = noticeLines,
            sourceMethod = "스마트폰 내부 스트림 분석"
        )
    }

    private fun fetchBroadcasterRealtimeSong(station: RadioStation): Pair<String, String>? {
        try {
            when (station.broadcaster) {
                "KBS" -> {
                    val req = Request.Builder()
                        .url(station.endpointOrStreamUrl)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (body.startsWith("{")) {
                            val json = JSONObject(body)
                            val items = json.optJSONArray("channel_item")
                            if (items != null && items.length() > 0) {
                                for (i in 0 until items.length()) {
                                    val obj = items.getJSONObject(i)
                                    val song = obj.optString("song_title", "").trim()
                                    val artist = obj.optString("artist_name", "").trim()
                                    if (song.isNotEmpty()) {
                                        return song to artist.ifEmpty { "KBS On-Air" }
                                    }
                                }
                            }
                        }
                    }
                }
                "MBC" -> {
                    val req = Request.Builder()
                        .url("https://miniapi.imbc.com/BroadCast/OnAirList")
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (body.contains("SomItem") || body.contains("SongTitle")) {
                            val json = JSONObject(body)
                            val song = json.optString("SongTitle", "").trim()
                            val singer = json.optString("Singer", "").trim()
                            if (song.isNotEmpty()) return song to singer
                        }
                    }
                }
                "SBS" -> {
                    val sbsChannelId = if (station.channelCode == "powerfm") "S07" else "S08"
                    val req = Request.Builder()
                        .url("https://static.apis.sbs.co.kr/play-api/1.0/onair/channel/$sbsChannelId")
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (body.startsWith("{")) {
                            val root = JSONObject(body)
                            val music = root.optJSONObject("onair")?.optJSONObject("music")
                            if (music != null) {
                                val title = music.optString("title", "").trim()
                                val artist = music.optString("singer", "").trim()
                                if (title.isNotEmpty()) return title to artist
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun recordHighQualityWavAudio(): ByteArray? {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuf <= 0) return null

        var recorder: AudioRecord? = null
        return try {
            val activeRecorder: AudioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    minBuf * 4
                )
            } catch (_: Exception) {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    minBuf * 4
                )
            }
            recorder = activeRecorder

            if (activeRecorder.state != AudioRecord.STATE_INITIALIZED) {
                activeRecorder.release()
                return null
            }

            activeRecorder.startRecording()
            val pcmShorts = ArrayList<Short>(sampleRate * 4)
            val readBuffer = ShortArray(minBuf / 2)
            val endTime = System.currentTimeMillis() + 3500L

            var maxPeak = 1
            while (System.currentTimeMillis() < endTime) {
                val readCount = activeRecorder.read(readBuffer, 0, readBuffer.size)
                if (readCount > 0) {
                    for (i in 0 until readCount) {
                        val s = readBuffer[i]
                        pcmShorts.add(s)
                        val absVal = abs(s.toInt())
                        if (absVal > maxPeak) maxPeak = absVal
                    }
                }
            }
            activeRecorder.stop()
            activeRecorder.release()
            recorder = null

            if (pcmShorts.isEmpty()) return null

            val targetPeak = 28000.0f
            val gain = (targetPeak / maxPeak.toFloat()).coerceIn(1.0f, 12.0f)

            val pcmByteBuffer = ByteBuffer.allocate(pcmShorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcmShorts) {
                val amplified = (s * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                pcmByteBuffer.putShort(amplified)
            }

            encodePcmToWav(pcmByteBuffer.array(), sampleRate, 1, 16)
        } catch (_: Exception) {
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
            null
        }
    }

    private fun encodePcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = pcmData.size + 36
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bitsPerSample / 8).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmData.size)

        val out = ByteArrayOutputStream(44 + pcmData.size)
        out.write(header.array())
        out.write(pcmData)
        return out.toByteArray()
    }

    fun parseLrcText(syncedLrc: String, plainLyrics: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()

        if (syncedLrc.isNotBlank()) {
            syncedLrc.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.startsWith("[") && line.contains("]")) {
                    val closeIdx = line.indexOf(']')
                    val timeTag = line.substring(1, closeIdx)
                    val lyricText = line.substring(closeIdx + 1).trim()
                    val colonIdx = timeTag.indexOf(':')
                    if (colonIdx > 0 && lyricText.isNotEmpty()) {
                        val minVal = timeTag.substring(0, colonIdx).toFloatOrNull() ?: 0f
                        val secVal = timeTag.substring(colonIdx + 1).toFloatOrNull() ?: 0f
                        lines.add(LyricLine(minVal * 60f + secVal, lyricText))
                    }
                }
            }
        }

        if (lines.isEmpty() && plainLyrics.isNotBlank()) {
            plainLyrics.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEachIndexed { idx, text ->
                    lines.add(LyricLine(idx * 3.5f, text))
                }
        }

        return lines
    }

    fun loadSavedSongs(context: Context): List<RecognizedSong> {
        val prefs = context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_HISTORY_JSON, null) ?: return emptyList()
        val list = mutableListOf<RecognizedSong>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val plain = o.optString("plainLyrics", "")
                list.add(
                    RecognizedSong(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        album = o.optString("album"),
                        stationName = o.optString("stationName"),
                        frequencyText = o.optString("frequencyText"),
                        recognizedAt = o.optString("recognizedAt"),
                        plainLyrics = plain,
                        syncedLyrics = parseLrcText("", plain),
                        sourceMethod = o.optString("sourceMethod", "스마트폰 내부 스트림 인식")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun saveSongToHistory(context: Context, song: RecognizedSong): List<RecognizedSong> {
        val current = loadSavedSongs(context).toMutableList()
        current.removeAll { it.title.equals(song.title, ignoreCase = true) && it.artist.equals(song.artist, ignoreCase = true) }
        current.add(0, song)
        persistSongs(context, current.take(50))
        return current.take(50)
    }

    fun deleteSongFromHistory(context: Context, songId: String): List<RecognizedSong> {
        val current = loadSavedSongs(context).filterNot { it.id == songId }
        persistSongs(context, current)
        return current
    }

    fun clearAllHistory(context: Context): List<RecognizedSong> {
        persistSongs(context, emptyList())
        return emptyList()
    }

    private fun persistSongs(context: Context, songs: List<RecognizedSong>) {
        val arr = JSONArray()
        songs.forEach { s ->
            val obj = JSONObject().apply {
                put("id", s.id)
                put("title", s.title)
                put("artist", s.artist)
                put("album", s.album)
                put("stationName", s.stationName)
                put("frequencyText", s.frequencyText)
                put("recognizedAt", s.recognizedAt)
                put("plainLyrics", s.plainLyrics)
                put("sourceMethod", s.sourceMethod)
            }
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY_JSON, arr.toString())
            .apply()
    }

    private fun nowTimeString(): String {
        val sdf = SimpleDateFormat("MM.dd HH:mm", Locale.KOREA)
        return sdf.format(Date())
    }
}