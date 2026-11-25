package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.webview.WebHostPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Antigrafity Remote", appName)
    }

    @Test
    fun `web host policy enforces https and trusted origins`() {
        assertTrue(WebHostPolicy.shouldKeepInApp("https://antigravity.google.com"))
        assertTrue(WebHostPolicy.shouldKeepInApp("https://accounts.google.com/o/oauth2/v2/auth"))
        assertTrue(WebHostPolicy.isTrustedBridgeOrigin("https://antigravity.google.com/session/1"))

        assertFalse(WebHostPolicy.isSafeHttpsUrl("http://antigravity.google.com"))
        assertFalse(WebHostPolicy.isSafeHttpsUrl("javascript:alert(1)"))
        assertFalse(WebHostPolicy.isSafeHttpsUrl("file:///etc/passwd"))
        assertFalse(WebHostPolicy.isTrustedBridgeOrigin("https://evil.example.com"))
    }
}
