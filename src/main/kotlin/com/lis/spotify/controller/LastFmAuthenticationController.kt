package com.lis.spotify.controller

import com.lis.spotify.service.LastFmAuthenticationService
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.security.SecureRandom
import java.util.Base64
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.view.RedirectView

/**
 * LastFmAuthenticationController provides endpoints for handling Last.fm authentication.
 *
 * Endpoints:
 * 1. GET /auth/lastfm - Redirects the user to Last.fm's authorization page.
 * 2. GET /auth/lastfm/callback - Receives the authentication token and exchanges it for a session.
 */
@Controller
class LastFmAuthenticationController(private val lastFmAuthService: LastFmAuthenticationService) {

  private fun isSecureRequest(request: HttpServletRequest): Boolean {
    val proto = request.getHeader("X-Forwarded-Proto") ?: request.scheme
    return proto.equals("https", ignoreCase = true) || request.isSecure
  }

  private fun createCookie(
    name: String,
    value: String,
    request: HttpServletRequest,
    httpOnly: Boolean,
    maxAgeSeconds: Int? = null,
  ): Cookie {
    return Cookie(name, value).apply {
      path = "/"
      isHttpOnly = httpOnly
      secure = isSecureRequest(request)
      setAttribute("SameSite", "Lax")
      maxAgeSeconds?.let { maxAge = it }
    }
  }

  private fun clearCookie(name: String, request: HttpServletRequest): Cookie {
    return createCookie(name, "", request, httpOnly = true).apply { maxAge = 0 }
  }

  private fun getCookieValue(request: HttpServletRequest, name: String): String? {
    return request.cookies
      .orEmpty()
      .firstOrNull { it.name == name }
      ?.value
      ?.takeIf { it.isNotBlank() }
  }

  private fun createState(): String {
    val bytes = ByteArray(24)
    stateRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  @GetMapping("/auth/lastfm")
  fun authenticateUser(
    request: HttpServletRequest,
    response: HttpServletResponse,
    @RequestParam(required = false) lastFmLogin: String?,
  ): RedirectView {
    logger.debug("authenticateUser() called")
    lastFmLogin
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?.let {
        response.addCookie(createCookie(LAST_FM_LOGIN_COOKIE, it, request, httpOnly = false))
      }
    val state = createState()
    response.addCookie(
      createCookie(
        LAST_FM_AUTH_STATE_COOKIE,
        state,
        request,
        httpOnly = true,
        maxAgeSeconds = AUTH_STATE_COOKIE_MAX_AGE_SECONDS,
      )
    )
    val authUrl = lastFmAuthService.getAuthorizationUrl(state)
    logger.info("Redirecting user to Last.fm auth URL")
    return RedirectView(authUrl)
  }

  @GetMapping("/auth/lastfm/callback")
  fun handleCallback(
    @RequestParam token: String?,
    @RequestParam(required = false) state: String?,
    request: HttpServletRequest,
    response: HttpServletResponse,
  ): String {
    logger.debug(
      "handleCallback tokenPresent={} statePresent={}",
      !token.isNullOrBlank(),
      !state.isNullOrBlank(),
    )
    val expectedState = getCookieValue(request, LAST_FM_AUTH_STATE_COOKIE)
    if (expectedState.isNullOrBlank() || state.isNullOrBlank() || state != expectedState) {
      logger.warn(
        "Rejecting Last.fm callback because OAuth state validation failed. expectedPresent={} actualPresent={}",
        !expectedState.isNullOrBlank(),
        !state.isNullOrBlank(),
      )
      response.addCookie(clearCookie(LAST_FM_AUTH_STATE_COOKIE, request))
      return "redirect:/error"
    }
    response.addCookie(clearCookie(LAST_FM_AUTH_STATE_COOKIE, request))
    if (token.isNullOrEmpty()) {
      logger.warn("Token is missing in Last.fm callback")
      return "redirect:/error"
    }
    val sessionData = lastFmAuthService.getSession(token)
    logger.debug("Session data received: {}", sessionData != null)
    return if (sessionData != null) {
      val session = sessionData["session"] as? Map<*, *>
      val key = session?.get("key") as? String
      val login = (session?.get("name") as? String) ?: getCookieValue(request, LAST_FM_LOGIN_COOKIE)
      if (!key.isNullOrEmpty() && !login.isNullOrEmpty()) {
        response.addCookie(createCookie(LAST_FM_TOKEN_COOKIE, key, request, httpOnly = true))
        response.addCookie(createCookie(LAST_FM_LOGIN_COOKIE, login, request, httpOnly = false))
        lastFmAuthService.setSession(login, key)
        logger.info("Successfully authenticated Last.fm user {}", login)
        "redirect:/"
      } else {
        // No usable session key/login was resolved, so do not present this as a success.
        logger.warn("Last.fm callback returned no usable session key or login")
        "redirect:/error"
      }
    } else {
      "redirect:/error"
    }
  }

  companion object {
    private const val LAST_FM_LOGIN_COOKIE = "lastFmLogin"
    private const val LAST_FM_TOKEN_COOKIE = "lastFmToken"
    private const val LAST_FM_AUTH_STATE_COOKIE = "lastFmAuthState"
    private const val AUTH_STATE_COOKIE_MAX_AGE_SECONDS = 300
    private val stateRandom = SecureRandom()
    private val logger = LoggerFactory.getLogger(LastFmAuthenticationController::class.java)
  }
}
