package com.lis.spotify.controller

import com.lis.spotify.service.LastFmAuthenticationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LastFmAuthenticationControllerTest {
  private val service = mockk<LastFmAuthenticationService>(relaxed = true)
  private val controller = LastFmAuthenticationController(service)

  @Test
  fun authenticateUserRedirects() {
    val request = mockk<HttpServletRequest>()
    every { request.getHeader("X-Forwarded-Proto") } returns null
    every { request.getHeader("X-Forwarded-Host") } returns null
    every { request.getHeader("X-Forwarded-Port") } returns null
    every { request.scheme } returns "https"
    every { request.serverName } returns "example.com"
    every { request.serverPort } returns 443

    every { service.getAuthorizationUrl("https://example.com/auth/lastfm/callback") } returns
      "http://x"

    val view = controller.authenticateUser(request, mockk(relaxed = true), "login")

    assertEquals("http://x", view.url)
  }

  @Test
  fun handleCallbackWithToken() {
    every { service.getSession("tok") } returns
      mapOf("session" to mapOf("key" to "v", "name" to "login"))
    val result = controller.handleCallback("tok", mockk(relaxed = true), mockk(relaxed = true))
    assertTrue(result.startsWith("redirect:/"))
  }

  @Test
  fun handleCallbackMissingToken() {
    val result = controller.handleCallback(null, mockk(relaxed = true), mockk(relaxed = true))
    assertEquals("redirect:/error", result)
  }

  @Test
  fun handleCallbackNoSession() {
    every { service.getSession("tok") } returns null
    val result = controller.handleCallback("tok", mockk(relaxed = true), mockk(relaxed = true))
    assertEquals("redirect:/error", result)
  }

  @Test
  fun handleCallbackSetsCookiesAndStoresSession() {
    every { service.getSession("tok") } returns
      mapOf("session" to mapOf("key" to "v", "name" to "login"))
    val request = mockk<HttpServletRequest>()
    every { request.getHeader(any()) } returns null
    every { request.scheme } returns "http"
    every { request.isSecure } returns false
    every { request.cookies } returns emptyArray()

    val response = mockk<HttpServletResponse>(relaxed = true)
    val cookies = mutableListOf<Cookie>()
    every { response.addCookie(capture(cookies)) } answers {}

    controller.handleCallback("tok", request, response)

    verify(exactly = 2) { response.addCookie(any()) }
    assertEquals("/", cookies[0].path)
    assertTrue(cookies[0].isHttpOnly)
    assertEquals(false, cookies[0].secure)
    assertEquals("lastFmToken", cookies[0].name)
    assertEquals("lastFmLogin", cookies[1].name)
    assertFalse(cookies[1].isHttpOnly)
    verify { service.setSession("login", "v") }
  }

  @Test
  fun handleCallbackUsesSavedLoginCookieWhenSessionNameMissing() {
    every { service.getSession("tok") } returns mapOf("session" to mapOf("key" to "v"))
    val request = mockk<HttpServletRequest>()
    every { request.getHeader(any()) } returns null
    every { request.scheme } returns "http"
    every { request.isSecure } returns false
    every { request.cookies } returns arrayOf(Cookie("lastFmLogin", "saved-login"))

    val response = mockk<HttpServletResponse>(relaxed = true)

    controller.handleCallback("tok", request, response)

    verify { service.setSession("saved-login", "v") }
  }
}
