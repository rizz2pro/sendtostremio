package com.rizz2pro.sharetostremio

import android.app.IntentService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class StremioService : IntentService("StremioService") {

    companion object {
        const val EXTRA_IMDB_ID = "imdb_id"
        const val EXTRA_AUTH_KEY = "auth_key"
        private const val TAG = "StremioService"
        private const val CHANNEL_ID = "stremio_result"
    }

    private val client = OkHttpClient()
    private lateinit var notifManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notifManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Stremio", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    override fun onHandleIntent(intent: Intent?) {
        val imdbId = intent?.getStringExtra(EXTRA_IMDB_ID) ?: return
        val authKey = intent.getStringExtra(EXTRA_AUTH_KEY) ?: return
        Log.d(TAG, "Service started for $imdbId")
        fetchImdbAndSend(imdbId, authKey)
    }

    private fun fetchImdbAndSend(imdbId: String, authKey: String) {
        try {
            val request = Request.Builder()
                .url("https://www.imdb.com/title/$imdbId/")
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .build()

            client.newCall(request).execute().use { response ->
                val html = response.body?.string()
                if (html.isNullOrBlank()) {
                    notify("Error", "Could not reach IMDb")
                    return
                }

                val rawTitle = "<title>(.*?) - IMDb</title>".toRegex()
                    .find(html)?.groups?.get(1)?.value

                if (rawTitle == null) {
                    notify("Error", "Could not parse IMDb page")
                    return
                }

                val type = if (rawTitle.contains("TV Series", ignoreCase = true)
                    || rawTitle.contains("TV Mini Series", ignoreCase = true)
                ) "series" else "movie"

                val cleanTitle = rawTitle.replace(Regex("\\s*\\(.*?\\)"), "").trim()
                Log.d(TAG, "Title: $cleanTitle | Type: $type")
                sendToStremio(authKey, imdbId, cleanTitle, type)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error fetching IMDb", e)
            notify("Error", "Network failure fetching IMDb")
        }
    }

    private fun sendToStremio(authKey: String, imdbId: String, name: String, type: String) {
        try {
            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date())

            val changeObj = JSONObject().apply {
                put("_id", imdbId)
                put("name", name)
                put("type", type)
                put("poster", "https://images.metahub.space/poster/small/$imdbId/img")
                put("posterShape", "poster")
                put("removed", false)
                put("temp", false)
                put("_ctime", now)
                put("_mtime", now)
                put("state", JSONObject().apply {
                    put("lastWatched", now)
                    put("timeWatched", 0)
                    put("timeOffset", 0)
                    put("overallTimeWatched", 0)
                    put("timesWatched", 0)
                    put("flaggedWatched", 0)
                    put("duration", 0)
                    put("video_id", JSONObject.NULL)
                    put("watched", JSONObject.NULL)
                    put("noNotif", false)
                })
                put("behaviorHints", JSONObject().apply {
                    put("defaultVideoId", imdbId)
                    put("featuredVideoId", JSONObject.NULL)
                    put("hasScheduledVideos", false)
                })
            }

            val payload = JSONObject().apply {
                put("authKey", authKey)
                put("collection", "libraryItem")
                put("changes", JSONArray().put(changeObj))
            }

            val body = payload.toString()
                .toRequestBody("text/plain;charset=UTF-8".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("https://api.strem.io/api/datastorePut")
                .header("User-Agent", "Mozilla/5.0 (Android)")
                .header("Referer", "https://web.stremio.com/")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val respText = response.body?.string() ?: "{}"
                when {
                    respText.contains("Session does not exist", ignoreCase = true) -> {
                        getSharedPreferences("stremio_secure", MODE_PRIVATE)
                            .edit().remove("authKey").apply()
                        notify("Stremio", "Session expired — share again to re-login")
                    }
                    respText.contains("\"result\"", ignoreCase = true) -> {
                        Log.d(TAG, "Successfully added: $name ($type)")
                        notify("Added to Stremio", name)
                    }
                    else -> {
                        Log.e(TAG, "Unexpected response: $respText")
                        notify("Stremio Error", "Unexpected response — check logs")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error sending to Stremio", e)
            notify("Error", "Could not reach Stremio")
        }
    }

    private fun notify(title: String, message: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        notifManager.notify(System.currentTimeMillis().toInt(), notif)
    }
}