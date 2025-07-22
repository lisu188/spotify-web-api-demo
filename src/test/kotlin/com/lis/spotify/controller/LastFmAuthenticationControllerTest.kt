package com.lis.spotify.controller

import com.lis.spotify.service.LastFmAuthenticationService
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LastFmAuthenticationControllerTest {
  private val service = mockk<LastFmAuthenticationService>()
  private val controller = LastFmAuthenticationController(service)

  @Test
  fun authenticateUserRedirects() {
    every { service.getAuthorizationUrl() } returns "http://x"
    val view = controller.authenticateUser()
    assertEquals("http://x", view.url)
  }

  @Test
  fun handleCallbackWithToken() {
    every { service.getSession("tok") } returns mapOf("session" to mapOf("key" to "v"))
    val result = controller.handleCallback("tok", mockk(relaxed = true))
    assertTrue(result.startsWith("redirect:/"))
  }

  @Test
  fun handleCallbackSetsCookiePath() {
    every { service.getSession("tok") } returns mapOf("session" to mapOf("key" to "v"))
    val response = mockk<HttpServletResponse>(relaxed = true)
    val cookieSlot: CapturingSlot<Cookie> = slot()
    every { response.addCookie(capture(cookieSlot)) } answers {}

    controller.handleCallback("tok", response)

    assertEquals("/", cookieSlot.captured.path)
  }
}
