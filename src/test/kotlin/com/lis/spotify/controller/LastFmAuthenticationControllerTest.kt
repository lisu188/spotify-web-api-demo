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
    every { request.getHeader(any()) } returns null
    every { request.scheme } returns "https"
    every { request.isSecure } returns true

    every { service.getAuthorizationUrl(any()) } returns "http://x"
    val response = mockk<HttpServletResponse>(relaxed = true)
    val cookies = mutableListOf<Cookie>()
    every { response.addCookie(capture(cookies)) } answers {}

    val view = controller.authenticateUser(request, response, "login")

    assertEquals("http://x", view.url)
    assertTrue(cookies.any { it.name == "lastFmAuthState" && it.isHttpOnly && it.maxAge == 300 })
    verify { service.getAuthorizationUrl(any()) }
  }

  @Test
  fun handleCallbackWithToken() {
    every { service.getSession("tok") } returns
      mapOf("session" to mapOf("key" to "v", "name" to "login"))
    val result =
      controller.handleCallback("tok", "state", requestWithState(), mockk(relaxed = true))
    assertTrue(result.startsWith("redirect:/"))
  }

  @Test
  fun handleCallbackMissingToken() {
    val result = controller.handleCallback(null, "state", requestWithState(), mockk(relaxed = true))
    assertEquals("redirect:/error", result)
  }

  @Test
  fun handleCallbackNoSession() {
    every { service.getSession("tok") } returns null
    val result =
      controller.handleCallback("tok", "state", requestWithState(), mockk(relaxed = true))
    assertEquals("redirect:/error", result)
  }

  @Test
  fun handleCallbackRejectsInvalidState() {
    val response = mockk<HttpServletResponse>(relaxed = true)
    val result = controller.handleCallback("tok", "wrong", requestWithState(), response)

    assertEquals("redirect:/error", result)
    verify(exactly = 0) { service.getSession(any()) }
  }

  @Test
  fun handleCallbackSetsCookiesAndStoresSession() {
    every { service.getSession("tok") } returns
      mapOf("session" to mapOf("key" to "v", "name" to "login"))
    val request = mockk<HttpServletRequest>()
    every { request.getHeader(any()) } returns null
    every { request.scheme } returns "http"
    every { request.isSecure } returns false
    every { request.cookies } returns arrayOf(Cookie("lastFmAuthState", "state"))

    val response = mockk<HttpServletResponse>(relaxed = true)
    val cookies = mutableListOf<Cookie>()
    every { response.addCookie(capture(cookies)) } answers {}

    controller.handleCallback("tok", "state", request, response)

    verify(exactly = 3) { response.addCookie(any()) }
    val tokenCookie = cookies.first { it.name == "lastFmToken" }
    val loginCookie = cookies.first { it.name == "lastFmLogin" }
    assertEquals("/", tokenCookie.path)
    assertTrue(tokenCookie.isHttpOnly)
    assertEquals(false, tokenCookie.secure)
    assertFalse(loginCookie.isHttpOnly)
    verify { service.setSession("login", "v") }
  }

  @Test
  fun handleCallbackUsesSavedLoginCookieWhenSessionNameMissing() {
    every { service.getSession("tok") } returns mapOf("session" to mapOf("key" to "v"))
    val request = mockk<HttpServletRequest>()
    every { request.getHeader(any()) } returns null
    every { request.scheme } returns "http"
    every { request.isSecure } returns false
    every { request.cookies } returns
      arrayOf(Cookie("lastFmLogin", "saved-login"), Cookie("lastFmAuthState", "state"))

    val response = mockk<HttpServletResponse>(relaxed = true)

    controller.handleCallback("tok", "state", request, response)

    verify { service.setSession("saved-login", "v") }
  }

  private fun requestWithState(): HttpServletRequest {
    val request = mockk<HttpServletRequest>()
    every { request.getHeader(any()) } returns null
    every { request.scheme } returns "http"
    every { request.isSecure } returns false
    every { request.cookies } returns arrayOf(Cookie("lastFmAuthState", "state"))
    return request
  }
}
