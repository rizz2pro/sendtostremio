package com.rizz2pro.sharetostremio

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class MainActivity : Activity() {

    private val TAG = "SendToStremio"
    private lateinit var prefs: SharedPreferences
    private var pendingImdbId: String? = null
    private var webView: WebView? = null
    private var authKeyCaptured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        prefs = EncryptedSharedPreferences.create(
            "stremio_secure", masterKeyAlias, this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString
        if (sharedText == null) {
            Toast.makeText(this, "No URL shared", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val imdbId = extractImdbId(sharedText)
        if (imdbId == null) {
            Toast.makeText(this, "Invalid IMDb URL", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        Log.d(TAG, "Extracted IMDb ID: $imdbId")

        val authKey = prefs.getString("authKey", null)
        if (authKey.isNullOrBlank()) {
            pendingImdbId = imdbId
            showStremioLogin()
        } else {
            startStremioService(imdbId, authKey)
        }
    }

    private fun extractImdbId(url: String): String? =
        """/title/(tt\d+)""".toRegex().find(url)?.groups?.get(1)?.value

    // ==========================
    // JS BRIDGE
    // ==========================
    inner class Bridge {
        @JavascriptInterface
        fun onAuthKey(authKey: String) {
            if (authKey.isBlank() || authKeyCaptured) return
            Log.d(TAG, "Got authKey")
            onAuthKeyFound(authKey)
        }
    }

    private fun onAuthKeyFound(authKey: String) {
        if (authKeyCaptured) return
        authKeyCaptured = true
        prefs.edit().putString("authKey", authKey).apply()
        runOnUiThread {
            webView?.destroy()
            webView = null
            pendingImdbId?.let { startStremioService(it, authKey) } ?: finish()
        }
    }

    // ==========================
    // WEBVIEW
    // ==========================
    private fun showStremioLogin() {
        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "Android")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Log.d(TAG, "Page finished: $url")

                    if (url.contains("/intro")) {
                        // Not logged in — show toast and patch fetch to catch login response
                        Log.d(TAG, "User not logged in — showing login page")
                        Toast.makeText(this@MainActivity, "Please log in to Stremio", Toast.LENGTH_LONG).show()
                        view.evaluateJavascript("""
                            (function() {
                                if (window.__stremioSniff) return;
                                window.__stremioSniff = true;
                                const _fetch = window.fetch.bind(window);
                                window.fetch = async function(input, init) {
                                    const url = typeof input === 'string' ? input : (input && input.url) || '';
                                    const resp = await _fetch(input, init);
                                    if (url.includes('api.strem.io')) {
                                        try {
                                            resp.clone().text().then(function(text) {
                                                try {
                                                    var j = JSON.parse(text);
                                                    if (j.result && j.result.authKey) {
                                                        Android.onAuthKey(j.result.authKey);
                                                    }
                                                } catch(e) {}
                                            });
                                        } catch(e) {}
                                    }
                                    return resp;
                                };
                            })();
                        """.trimIndent(), null)
                    } else {
                        // Already logged in — read authKey from localStorage profile
                        Log.d(TAG, "User already logged in — reading profile")
                        view.evaluateJavascript("""
                            (function() {
                                try {
                                    var p = JSON.parse(localStorage.getItem('profile') || '{}');
                                    if (p.authKey) { Android.onAuthKey(p.authKey); return; }
                                    if (p.auth && p.auth.key) { Android.onAuthKey(p.auth.key); return; }
                                    if (p.user && p.user.authKey) { Android.onAuthKey(p.user.authKey); return; }
                                } catch(e) {}
                            })();
                        """.trimIndent(), null)
                    }
                }
            }
        }

        webView = wv
        setContentView(wv)
        wv.loadUrl("https://web.stremio.com/#/intro?form=login")
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

    override fun onDestroy() {
        super.onDestroy()
        webView?.destroy()
    }
}