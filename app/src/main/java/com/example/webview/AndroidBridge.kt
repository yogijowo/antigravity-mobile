package com.example.webview

import android.content.Context
import android.webkit.JavascriptInterface
import com.example.data.NotificationSettings
import com.example.notifications.NotificationHelper
import java.util.concurrent.atomic.AtomicLong

/**
 * JavaScript bridge exposed as `window.AndroidBridge` so trusted pages on
 * https://antigrafity.google.com can trigger native Android local notifications
 * when status changes occur.
 */
class AndroidBridge(
    private val context: Context,
    private val currentOriginProvider: () -> String?,
) {
    private val lastNotificationTimestampMs = AtomicLong(0L)

    @JavascriptInterface
    fun showNotification(title: String?, message: String?): Boolean {
        if (!WebHostPolicy.isTrustedBridgeOrigin(currentOriginProvider())) {
            return false
        }
        if (!allowByRateLimit()) {
            return false
        }

        val cleanTitle = sanitize(title, maxLength = 80)
            .ifBlank { NotificationSettings.DEFAULT_TITLE }
        val cleanMessage = sanitize(message, maxLength = 300)
            .ifBlank { "Pembaruan status dari Antigrafity." }

        return NotificationHelper.showWebBridgeNotification(
            context = context.applicationContext,
            title = cleanTitle,
            message = cleanMessage,
        )
    }

    @JavascriptInterface
    fun notifyStatus(status: String?, detail: String?): Boolean {
        val formattedTitle = sanitize(status, maxLength = 64)
            .ifBlank { "Status Antigrafity" }
        val formattedDetail = sanitize(detail, maxLength = 300)
            .ifBlank { "Status sesi telah diperbarui." }
        return showNotification(formattedTitle, formattedDetail)
    }

    @JavascriptInterface
    fun isNotificationEnabled(): Boolean {
        if (!WebHostPolicy.isTrustedBridgeOrigin(currentOriginProvider())) {
            return false
        }
        return NotificationHelper.hasNotificationPermission(context.applicationContext)
    }

    private fun allowByRateLimit(): Boolean {
        val now = System.currentTimeMillis()
        val previous = lastNotificationTimestampMs.get()
        if (now - previous < MIN_BRIDGE_INTERVAL_MS) {
            return false
        }
        return lastNotificationTimestampMs.compareAndSet(previous, now)
    }

    private fun sanitize(raw: String?, maxLength: Int): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace(CONTROL_CHAR_REGEX, " ")
            .trim()
            .take(maxLength)
    }

    companion object {
        const val BRIDGE_NAME = "AndroidBridge"
        private const val MIN_BRIDGE_INTERVAL_MS = 2_000L
        private val CONTROL_CHAR_REGEX = Regex("[\\p{Cntrl}&&[^\n]]")
    }
}
