package com.rizz2pro.sharetostremio

import android.app.Activity
import android.os.Build
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
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
import org.json.JSONObject

class MainActivity : Activity() {

    private val TAG = "SendToStremio"
    private val client = OkHttpClient()
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }

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

    private fun handleIntent(intent: Intent?) {
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString

        if (sharedText == null) {
            Toast.makeText(this, "No URL shared", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val imdbId = extractImdbId(sharedText)
        if (imdbId == null) {
            Toast.makeText(this, "Invalid IMDb URL", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Log.d(TAG, "Extracted IMDb ID: $imdbId")

        val authKey = prefs.getString("authKey", null)
        if (authKey.isNullOrBlank()) {
            promptLogin(imdbId)
        } else {
            startStremioService(imdbId, authKey)
        }
    }

    private fun extractImdbId(url: String): String? {
        return """/title/(tt\d+)""".toRegex().find(url)?.groups?.get(1)?.value
    }

    private fun startStremioService(imdbId: String, authKey: String) {
        val serviceIntent = Intent(this, StremioService::class.java).apply {
            putExtra(StremioService.EXTRA_IMDB_ID, imdbId)
            putExtra(StremioService.EXTRA_AUTH_KEY, authKey)
        }
        startService(serviceIntent)
        Toast.makeText(this, "Sending to Stremio...", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ==========================
    // LOGIN
    // ==========================
    private fun promptLogin(imdbId: String) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val emailInput = EditText(this).apply { hint = "Email" }
        val passInput = EditText(this).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(emailInput)
        layout.addView(passInput)

        AlertDialog.Builder(this)
            .setTitle("Stremio Login")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Login") { _, _ ->
                login(emailInput.text.toString(), passInput.text.toString(), imdbId)
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun login(email: String, password: String, imdbId: String) {
        Toast.makeText(this, "Logging in...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().apply {
                    put("type", "Login")
                    put("email", email)
                    put("password", password)
                    put("facebook", false)
                }
                val body = json.toString()
                    .toRequestBody("text/plain;charset=UTF-8".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url("https://api.strem.io/api/login")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val authKey = JSONObject(response.body?.string() ?: "{}")
                        .optJSONObject("result")
                        ?.optString("authKey", null)

                    if (!authKey.isNullOrBlank()) {
                        prefs.edit().putString("authKey", authKey).apply()
                        runOnUiThread { startStremioService(imdbId, authKey) }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Login failed — check credentials", Toast.LENGTH_LONG).show()
                            promptLogin(imdbId)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login error", e)
                runOnUiThread { promptLogin(imdbId) }
            }
        }
    }
}