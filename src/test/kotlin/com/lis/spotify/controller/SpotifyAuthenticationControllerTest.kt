package com.lis.spotify.controller

import com.lis.spotify.AppEnvironment.Spotify
import com.lis.spotify.domain.AuthToken
import com.lis.spotify.domain.User
import com.lis.spotify.service.SpotifyAuthenticationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestTemplate

class SpotifyAuthenticationControllerTest {
  private val spotifyService = mockk<SpotifyAuthenticationService>(relaxed = true)
  private val restTemplate = mockk<RestTemplate>()
  private val builder = mockk<RestTemplateBuilder>()
  private val controller = SpotifyAuthenticationController(spotifyService, builder)

  @Test
  fun getCurrentUserIdHandlesError() {
    every { builder.build() } returns restTemplate
    val token = AuthToken("a", "b", "c", 0, null, "cid")
    every { spotifyService.getHeaders(token) } returns org.springframework.http.HttpHeaders()
    every { restTemplate.exchange<Any>(any<String>(), any(), any(), Any::class.java) } throws
      RuntimeException()
    val id = controller.getCurrentUserId(token)
    assertNull(id)
  }

  @Test
  fun getCurrentUserIdReturnsId() {
    every { builder.build() } returns restTemplate
    val token = AuthToken("a", "b", "c", 0, null, "cid")
    every { spotifyService.getHeaders(token) } returns HttpHeaders()
    every {
      restTemplate.exchange<User>(
        any<String>(),
        HttpMethod.GET,
        any<HttpEntity<*>>(),
        any<ParameterizedTypeReference<User>>(),
      )
    } returns ResponseEntity(User("uid"), HttpStatus.OK)

    val id = controller.getCurrentUserId(token)
    assertEquals("uid", id)
  }

  @Test
  fun authorizeReturnsRedirectAndSetsStateCookie() {
    val request = mockk<HttpServletRequest>()
    every { request.getHeader(any()) } returns null
    every { request.scheme } returns "http"
    every { request.serverName } returns "localhost"
    every { request.serverPort } returns 8080
    every { request.isSecure } returns false

    val response = mockk<HttpServletResponse>(relaxed = true)

    val result = controller.authorize(request, mockk(), response, "cid")

    assert(result.startsWith("redirect:"))
    assert(result.contains("state="))
    assert(result.contains("playlist-modify-private"))
    assert(result.contains("playlist-read-private"))
    verify {
      response.addCookie(
        match {
          it.name == "spotifyAuthState" &&
            it.path == "/" &&
            it.isHttpOnly &&
            !it.secure &&
            it.maxAge == 300 &&
            it.value.isNotBlank()
        }
      )
    }
  }

  @Test
  fun callbackSetsCookiePath() {
    val request = mockk<HttpServletRequest>()
    every { request.getHeader(any()) } returns null
    every { request.scheme } returns "http"
    every { request.serverName } returns "localhost"
    every { request.serverPort } returns 80
    every { request.isSecure } returns false
    every { request.cookies } returns arrayOf(Cookie("spotifyAuthState", "expected-state"))

    val response = mockk<HttpServletResponse>(relaxed = true)

    val builderAuthed = mockk<RestTemplateBuilder>()
    every { builder.basicAuthentication(any(), any()) } returns builderAuthed
    every { builderAuthed.build() } returns restTemplate
    every { builder.build() } returns restTemplate

    val token = AuthToken("a", "b", "c", 0, "r", null)
    every {
      restTemplate.postForObject<AuthToken>(Spotify.TOKEN_URL, any(), AuthToken::class.java)
    } returns token
    every { spotifyService.getHeaders(token) } returns HttpHeaders()
    val userResponse = ResponseEntity(User("cid"), HttpStatus.OK)
    every {
      restTemplate.exchange<User>(
        any<String>(),
        HttpMethod.GET,
        any<HttpEntity<*>>(),
        any<ParameterizedTypeReference<User>>(),
      )
    } returns userResponse

    controller.callback(request, "code", "expected-state", response)

    verify {
      response.addCookie(
        match {
          it.name == "clientId" &&
            it.path == "/" &&
            it.isHttpOnly &&
            !it.secure &&
            it.value == "cid"
        }
      )
    }
    verify {
      response.addCookie(
        match { it.name == "spotifyAuthState" && it.path == "/" && it.maxAge == 0 && !it.secure }
      )
    }
  }

  @Test
  fun callbackRejectsInvalidState() {
    val request = mockk<HttpServletRequest>()
    every { request.getHeader(any()) } returns null
    every { request.scheme } returns "http"
    every { request.isSecure } returns false
    every { request.cookies } returns arrayOf(Cookie("spotifyAuthState", "expected-state"))
    val response = mockk<HttpServletResponse>(relaxed = true)

    val result = controller.callback(request, "code", "wrong-state", response)

    assertEquals("redirect:/error", result)
    verify {
      response.addCookie(
        match { it.name == "spotifyAuthState" && it.maxAge == 0 && it.path == "/" && !it.secure }
      )
    }
  }
}
