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

    private val documentExtensions = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "csv", "txt", "md", "json", "xml", "rtf", "odt", "ods", "odp",
        "zip", "png", "jpg", "jpeg", "webp", "gif",
    )

    private val documentMimePrefixes = listOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.",
        "application/vnd.ms-",
        "application/vnd.oasis.opendocument.",
        "application/rtf",
        "text/plain",
        "text/csv",
        "text/markdown",
        "application/json",
    )

    fun isTrustedBridgeOrigin(url: String?): Boolean {
        if (!isSafeHttpsUrl(url)) return false
        val host = runCatching { url!!.trim().toUri().host?.lowercase() }.getOrNull() ?: return false
        return bridgeAllowedHostSuffixes.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
    }

    fun isLikelyDocumentUrl(url: String?, mimeType: String? = null): Boolean {
        val cleanMime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (cleanMime.isNotBlank() && documentMimePrefixes.any { cleanMime.startsWith(it) }) {
            return true
        }
        if (!isSafeHttpsUrl(url)) return false
        val path = runCatching { url!!.trim().toUri().path?.lowercase() }.getOrNull().orEmpty()
        val ext = path.substringAfterLast('/', "").substringAfterLast('.', "")
        return ext.isNotBlank() && ext in documentExtensions
    }

    fun isSafePreviewDataUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.trim().lowercase()
        if (!lower.startsWith("data:")) return false
        val mime = lower.substringAfter("data:").substringBefore(';').substringBefore(',')
        // Never treat data:text/html or scriptable types as safe preview documents
        return mime == "application/pdf" ||
            mime.startsWith("image/") && mime != "image/svg+xml" ||
            mime == "text/plain" ||
            mime == "text/csv" ||
            mime == "text/markdown" ||
            mime == "application/json" ||
            mime.startsWith("application/vnd.openxmlformats-officedocument.") ||
            mime == "application/msword"
    }
}
