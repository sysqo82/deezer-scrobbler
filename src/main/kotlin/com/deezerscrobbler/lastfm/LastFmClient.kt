package com.deezerscrobbler.lastfm

import com.deezerscrobbler.util.FileLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class LastFmClient(
    private var apiKey: String = "",
    private var apiSecret: String = "",
    private var sessionKey: String = ""
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun updateCredentials(apiKey: String, apiSecret: String, sessionKey: String) {
        this.apiKey = apiKey
        this.apiSecret = apiSecret
        this.sessionKey = sessionKey
        FileLogger.info("LastFmClient", "Credentials updated. apiKey empty: ${apiKey.isBlank()}, sessionKey empty: ${sessionKey.isBlank()}")
    }

    /**
     * Fetch request token for authentication flow (auth.getToken)
     */
    fun fetchRequestToken(apiKey: String, apiSecret: String): Result<String> {
        FileLogger.info("LastFmClient", "fetchRequestToken called")
        return try {
            val params = mutableMapOf(
                "method" to "auth.getToken",
                "api_key" to apiKey
            )
            val sig = generateApiSignature(params, apiSecret)
            params["api_sig"] = sig
            params["format"] = "json"

            val urlBuilder = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("ws.audioscrobbler.com")
                .addPathSegment("2.0")

            params.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }

            val request = Request.Builder().url(urlBuilder.build()).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: throw Exception("Empty response from Last.fm")
                FileLogger.info("LastFmClient", "fetchRequestToken HTTP ${response.code}: $body")
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}: $body")
                }
                val jsonObj = json.parseToJsonElement(body).jsonObject
                val token = jsonObj["token"]?.jsonPrimitive?.content
                    ?: throw Exception("Token missing in response: $body")
                Result.success(token)
            }
        } catch (e: Exception) {
            FileLogger.error("LastFmClient", "fetchRequestToken failed", e)
            Result.failure(e)
        }
    }

    fun buildAuthUrl(apiKey: String, token: String): String {
        return "https://www.last.fm/api/auth/?api_key=$apiKey&token=$token"
    }

    /**
     * Fetch permanent session key after user approves token on Last.fm website (auth.getSession)
     */
    fun fetchSessionKey(apiKey: String, apiSecret: String, token: String): Result<Pair<String, String>> {
        FileLogger.info("LastFmClient", "fetchSessionKey called")
        return try {
            val params = mutableMapOf(
                "method" to "auth.getSession",
                "api_key" to apiKey,
                "token" to token
            )
            val sig = generateApiSignature(params, apiSecret)
            params["api_sig"] = sig
            params["format"] = "json"

            val urlBuilder = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("ws.audioscrobbler.com")
                .addPathSegment("2.0")

            params.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }

            val request = Request.Builder().url(urlBuilder.build()).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: throw Exception("Empty response from Last.fm")
                FileLogger.info("LastFmClient", "fetchSessionKey HTTP ${response.code}: $body")
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}: $body")
                }
                val jsonObj = json.parseToJsonElement(body).jsonObject
                val session = jsonObj["session"]?.jsonObject
                    ?: throw Exception("Session missing in response: $body")
                val key = session["key"]?.jsonPrimitive?.content
                    ?: throw Exception("Session key missing")
                val username = session["name"]?.jsonPrimitive?.content ?: "User"

                Result.success(Pair(key, username))
            }
        } catch (e: Exception) {
            FileLogger.error("LastFmClient", "fetchSessionKey failed", e)
            Result.failure(e)
        }
    }

    /**
     * Send "Now Playing" notification to Last.fm (track.updateNowPlaying)
     */
    fun updateNowPlaying(artist: String, track: String, album: String? = null): Result<Unit> {
        if (apiKey.isBlank() || sessionKey.isBlank()) {
            val err = "Last.fm not configured or missing session key (apiKey blank: ${apiKey.isBlank()}, sessionKey blank: ${sessionKey.isBlank()})"
            FileLogger.warn("LastFmClient", "updateNowPlaying aborted: $err")
            return Result.failure(IllegalStateException(err))
        }

        FileLogger.info("LastFmClient", "updateNowPlaying requested: artist='$artist', track='$track', album='$album'")
        val startTime = System.currentTimeMillis()

        return try {
            val params = mutableMapOf(
                "method" to "track.updateNowPlaying",
                "artist" to artist,
                "track" to track,
                "api_key" to apiKey,
                "sk" to sessionKey
            )
            if (!album.isNullOrBlank()) {
                params["album"] = album
            }

            val sig = generateApiSignature(params, apiSecret)
            params["api_sig"] = sig
            params["format"] = "json"

            val formBuilder = FormBody.Builder()
            params.forEach { (k, v) -> formBuilder.add(k, v) }

            val request = Request.Builder()
                .url("https://ws.audioscrobbler.com/2.0/")
                .post(formBuilder.build())
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val duration = System.currentTimeMillis() - startTime
                FileLogger.info("LastFmClient", "updateNowPlaying HTTP ${response.code} (${duration}ms): $body")
                if (response.isSuccessful && !body.contains("error")) {
                    Result.success(Unit)
                } else {
                    val ex = Exception("Last.fm NowPlaying failed (HTTP ${response.code}): $body")
                    FileLogger.warn("LastFmClient", ex.message!!)
                    Result.failure(ex)
                }
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            FileLogger.error("LastFmClient", "updateNowPlaying network exception after ${duration}ms", e)
            Result.failure(e)
        }
    }

    /**
     * Scrobble track to Last.fm (track.scrobble)
     */
    fun scrobble(artist: String, track: String, timestampSeconds: Long, album: String? = null): Result<Unit> {
        if (apiKey.isBlank() || sessionKey.isBlank()) {
            val err = "Last.fm not configured or missing session key (apiKey blank: ${apiKey.isBlank()}, sessionKey blank: ${sessionKey.isBlank()})"
            FileLogger.warn("LastFmClient", "scrobble aborted: $err")
            return Result.failure(IllegalStateException(err))
        }

        FileLogger.info("LastFmClient", "scrobble requested: artist='$artist', track='$track', timestamp=$timestampSeconds, album='$album'")
        val startTime = System.currentTimeMillis()

        return try {
            val params = mutableMapOf(
                "method" to "track.scrobble",
                "artist[0]" to artist,
                "track[0]" to track,
                "timestamp[0]" to timestampSeconds.toString(),
                "api_key" to apiKey,
                "sk" to sessionKey
            )
            if (!album.isNullOrBlank()) {
                params["album[0]"] = album
            }

            val sig = generateApiSignature(params, apiSecret)
            params["api_sig"] = sig
            params["format"] = "json"

            val formBuilder = FormBody.Builder()
            params.forEach { (k, v) -> formBuilder.add(k, v) }

            val request = Request.Builder()
                .url("https://ws.audioscrobbler.com/2.0/")
                .post(formBuilder.build())
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val duration = System.currentTimeMillis() - startTime
                FileLogger.info("LastFmClient", "scrobble HTTP ${response.code} (${duration}ms): $body")
                if (response.isSuccessful && !body.contains("\"error\":")) {
                    Result.success(Unit)
                } else {
                    val ex = Exception("Last.fm Scrobble failed (HTTP ${response.code}): $body")
                    FileLogger.warn("LastFmClient", ex.message!!)
                    Result.failure(ex)
                }
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            FileLogger.error("LastFmClient", "scrobble network exception after ${duration}ms", e)
            Result.failure(e)
        }
    }

    /**
     * Last.fm API signature generator
     */
    internal fun generateApiSignature(params: Map<String, String>, secret: String): String {
        // Sort keys alphabetically, excluding format & callback
        val sortedKeys = params.keys
            .filter { it != "format" && it != "callback" }
            .sorted()

        val sb = StringBuilder()
        for (key in sortedKeys) {
            sb.append(key).append(params[key])
        }
        sb.append(secret)

        return md5Hex(sb.toString())
    }

    private fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
