package com.rizz2pro.sharetostremio

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {

    private val TAG = "SendToStremio"
    private val client = OkHttpClient()
    private var pendingImdbId: String? = null
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        prefs = EncryptedSharedPreferences.create(
            "stremio_secure",
            masterKeyAlias,
            this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val sharedText = intent?.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: intent?.dataString
        if (sharedText == null) {
            showToast("No URL Shared")
            Log.d(TAG, "No URL shared in intent")
            finish()
            return
        }

        val imdbId = extractImdbId(sharedText)
        if (imdbId == null) {
            showToast("Invalid IMDb URL")
            Log.d(TAG, "Failed to extract IMDb ID from: $sharedText")
            finish()
            return
        }

        Log.d(TAG, "Extracted IMDb ID: $imdbId")
        pendingImdbId = imdbId
        val authKey = prefs.getString("authKey", null)
        if (authKey.isNullOrBlank()) {
            Log.d(TAG, "No authKey found, prompting login")
            safeShowLogin()
        } else {
            Log.d(TAG, "Found authKey, fetching IMDb data for $imdbId")
            fetchImdbTitleDirect(imdbId, authKey)
        }
    }

    private fun extractImdbId(url: String): String? {
        val regex = """/title/(tt\d+)""".toRegex()
        return regex.find(url)?.groups?.get(1)?.value
    }

    // ==========================
    // FETCH IMDb TITLE VIA HTTP (Headers added)
    // ==========================
    private fun fetchImdbTitleDirect(imdbId: String, authKey: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "https://www.imdb.com/title/$imdbId/"
                val request = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
                    )
                    .header(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
                    )
                    .build()

                client.newCall(request).execute().use { response ->
                    val html = response.body?.string()
                    if (html.isNullOrBlank()) {
                        Log.e(TAG, "Empty HTML from IMDb")
                        runOnUiThread { showToast("Failed to fetch IMDb title") }
                        finish()
                        return@use
                    }

                    val titleRegex = "<title>(.*?) - IMDb</title>".toRegex()
                    val rawTitle = titleRegex.find(html)?.groups?.get(1)?.value ?: run {
                        Log.e(TAG, "Failed to parse IMDb title")
                        runOnUiThread { showToast("Failed to fetch IMDb title") }
                        finish()
                        return@use
                    }

                    val type = if (rawTitle.contains("TV Series", ignoreCase = true)
                        || rawTitle.contains("TV Mini Series", ignoreCase = true)
                    ) "series" else "movie"
                    val cleanTitle = rawTitle.replace(Regex("\\s*\\(.*?\\)"), "").trim()

                    Log.d(TAG, "Fetched IMDb title: $cleanTitle ($type)")
                    sendToStremio(authKey, imdbId, cleanTitle, type)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching IMDb title", e)
                runOnUiThread {
                    showToast("Network error fetching IMDb title")
                    finish()
                }
            }
        }
    }

    // ==========================
    // LOGIN
    // ==========================
    private fun safeShowLogin() {
        if (!isFinishing) promptLogin() else Log.d(TAG, "Activity finishing, not showing login dialog")
    }

    private fun promptLogin() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val emailInput = EditText(this).apply { hint = "Email" }
        val passInput = EditText(this).apply { hint = "Password"; inputType = 129 }
        layout.addView(emailInput)
        layout.addView(passInput)

        AlertDialog.Builder(this)
            .setTitle("Stremio Login")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Login") { _, _ ->
                login(emailInput.text.toString(), passInput.text.toString())
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun login(email: String, password: String) {
        showToast("Logging in...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().apply {
                    put("type", "Login")
                    put("email", email)
                    put("password", password)
                    put("facebook", false)
                }

                val body = json.toString().toRequestBody("text/plain;charset=UTF-8".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url("https://api.strem.io/api/login")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val respText = response.body?.string()
                    val authKey = JSONObject(respText ?: "{}")
                        .optJSONObject("result")
                        ?.optString("authKey", null)

                    if (!authKey.isNullOrBlank()) {
                        prefs.edit().putString("authKey", authKey).apply()
                        pendingImdbId?.let { fetchImdbTitleDirect(it, authKey) }
                    } else {
                        runOnUiThread { safeShowLogin() }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login error", e)
                runOnUiThread { safeShowLogin() }
            }
        }
    }

    // ==========================
    // SEND TO STREMIO
    // ==========================
    private fun sendToStremio(authKey: String, imdbId: String, name: String, type: String) {
        showToast("Sending: $name ($type)")
        CoroutineScope(Dispatchers.IO).launch {
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

                val body = payload.toString().toRequestBody("text/plain;charset=UTF-8".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url("https://api.strem.io/api/datastorePut")
                    .header("User-Agent", "Mozilla/5.0 (Android)")
                    .header("Referer", "https://web.stremio.com/")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val respText = response.body?.string() ?: "{}"
                    if (respText.contains("Session does not exist", ignoreCase = true)) {
                        runOnUiThread { safeShowLogin() }
                    } else {
                        runOnUiThread {
                            showToast("Added: $name ($type)")
                            finish()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending to Stremio", e)
                runOnUiThread {
                    showToast("Network error")
                    finish()
                }
            }
        }
    }

    // ==========================
    // THREAD-SAFE TOAST
    // ==========================
    private fun showToast(msg: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        } else runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }
}