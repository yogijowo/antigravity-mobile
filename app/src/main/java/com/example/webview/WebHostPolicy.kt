package com.example.webview

import androidx.core.net.toUri

object WebHostPolicy {

    const val HOME_URL = "https://antigravity.google.com"

    private val inAppAllowedHostSuffixes = listOf(
        "antigravity.google.com",
        "accounts.google.com",
        "accounts.youtube.com",
        "myaccount.google.com",
        "google.com",
        "googleusercontent.com",
        "gstatic.com",
        "googleapis.com",
        "withgoogle.com",
        "g.co",
    )

    private val bridgeAllowedHostSuffixes = listOf(
        "antigravity.google.com",
        "google.com",
    )

    fun isSafeHttpsUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { url.trim().toUri() }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }

    fun shouldKeepInApp(url: String?): Boolean {
        if (!isSafeHttpsUrl(url)) return false
        val host = runCatching { url!!.trim().toUri().host?.lowercase() }.getOrNull() ?: return false
        return inAppAllowedHostSuffixes.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
    }

    fun isTrustedBridgeOrigin(url: String?): Boolean {
        if (!isSafeHttpsUrl(url)) return false
        val host = runCatching { url!!.trim().toUri().host?.lowercase() }.getOrNull() ?: return false
        return bridgeAllowedHostSuffixes.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
    }
}
