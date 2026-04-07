package com.lis.spotify.controller

import com.lis.spotify.AppEnvironment.Spotify
import com.lis.spotify.domain.AuthToken
import com.lis.spotify.domain.User
import com.lis.spotify.service.SpotifyAuthenticationService
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.security.SecureRandom
import java.util.Base64
import org.slf4j.LoggerFactory
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.client.exchange
import org.springframework.web.client.postForObject
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.springframework.web.util.UriComponentsBuilder

@Controller
class SpotifyAuthenticationController(
  private val spotifyAuthenticationService: SpotifyAuthenticationService,
  private val restTemplateBuilder: RestTemplateBuilder,
) {

  private fun callbackUrl(request: HttpServletRequest): String {
    val proto = request.getHeader("X-Forwarded-Proto") ?: request.scheme
    val host = request.getHeader("X-Forwarded-Host") ?: request.serverName
    val portHeader = request.getHeader("X-Forwarded-Port")
    val port = portHeader ?: request.serverPort.toString()
    val defaultPort = if (proto == "https") "443" else "80"
    val portPart = if (port == defaultPort || port.isEmpty()) "" else ":$port"
    return "$proto://$host$portPart" + Spotify.CALLBACK_PATH
  }

  fun getCurrentUserId(token: AuthToken): String? {
    logger.debug("Attempting to retrieve current user ID using provided AuthToken.")
    return try {
      val response =
        restTemplateBuilder
          .build()
          .exchange<User>(
            "https://api.spotify.com/v1/me",
            HttpMethod.GET,
            HttpEntity(null, spotifyAuthenticationService.getHeaders(token)),
          )
      logger.info("Successfully retrieved user info from Spotify.")
      response.body?.id
    } catch (ex: Exception) {
      logger.error("Failed to retrieve current user ID from Spotify API.", ex)
      null
    }
  }

  private fun isSecureRequest(request: HttpServletRequest): Boolean {
    val proto = request.getHeader("X-Forwarded-Proto") ?: request.scheme
    return proto.equals("https", ignoreCase = true) || request.isSecure
  }

  private fun createCookie(
    name: String,
    value: String,
    request: HttpServletRequest,
    maxAgeSeconds: Int? = null,
  ): Cookie {
    return Cookie(name, value).apply {
      path = "/"
      isHttpOnly = true
      secure = isSecureRequest(request)
      maxAgeSeconds?.let { maxAge = it }
    }
  }

  private fun clearCookie(name: String, request: HttpServletRequest): Cookie {
    return createCookie(name, "", request).apply { maxAge = 0 }
  }

  private fun getCookieValue(request: HttpServletRequest, name: String): String? {
    return request.cookies.orEmpty().firstOrNull { it.name == name }?.value?.takeIf { it.isNotBlank() }
  }

  private fun createState(): String {
    val bytes = ByteArray(24)
    stateRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  @GetMapping(Spotify.CALLBACK_PATH)
  fun callback(
    request: HttpServletRequest,
    code: String,
    @RequestParam(required = false) state: String?,
    response: HttpServletResponse,
  ): String {
    logger.debug("callback with code {}", code)
    logger.info("Received callback from Spotify with code: {}", code)

    val expectedState = getCookieValue(request, SPOTIFY_AUTH_STATE_COOKIE)
    if (expectedState.isNullOrBlank() || state.isNullOrBlank() || state != expectedState) {
      logger.warn(
        "Rejecting Spotify callback because OAuth state validation failed. expectedPresent={} actualPresent={}",
        !expectedState.isNullOrBlank(),
        !state.isNullOrBlank(),
      )
      response.addCookie(clearCookie(SPOTIFY_AUTH_STATE_COOKIE, request))
      return "redirect:/error"
    }
    response.addCookie(clearCookie(SPOTIFY_AUTH_STATE_COOKIE, request))

    val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }
    val body =
      LinkedMultiValueMap<String, String>().apply {
        add("grant_type", "authorization_code")
        add("code", code)
        add("redirect_uri", callbackUrl(request))
      }
    val entity = HttpEntity(body, headers)

    return try {
      val authToken =
        restTemplateBuilder
          .basicAuthentication(Spotify.CLIENT_ID, Spotify.CLIENT_SECRET)
          .build()
          .postForObject<AuthToken>(Spotify.TOKEN_URL, entity)
      logger.debug("Received AuthToken from Spotify (access token redacted).")
      val clientId = getCurrentUserId(authToken)
      if (clientId != null) {
        authToken.clientId = clientId
        spotifyAuthenticationService.setAuthToken(authToken)
        response.addCookie(createCookie("clientId", clientId, request))
        logger.info("Successfully set auth token for user: {}", clientId)
      } else {
        logger.warn("Could not retrieve client ID. Auth token not stored.")
      }
      "redirect:/"
    } catch (ex: Exception) {
      logger.error("Error occurred while handling Spotify callback.", ex)
      "redirect:/error"
    }
  }

  @GetMapping("/auth/spotify")
  fun authorize(
    request: HttpServletRequest,
    attributes: RedirectAttributes,
    response: HttpServletResponse,
    @CookieValue("clientId", defaultValue = "") clientId: String,
  ): String {
    logger.debug("authorize with clientId {}", clientId)
    logger.info("Authorize endpoint called. Current clientId from cookie: {}", clientId)
    val state = createState()
    response.addCookie(
      createCookie(
        SPOTIFY_AUTH_STATE_COOKIE,
        state,
        request,
        AUTH_STATE_COOKIE_MAX_AGE_SECONDS,
      )
    )
    val builder =
      UriComponentsBuilder.fromHttpUrl(Spotify.AUTH_URL)
        .queryParam("response_type", "code")
        .queryParam("client_id", Spotify.CLIENT_ID)
        .queryParam("scope", Spotify.SCOPES)
        .queryParam("redirect_uri", callbackUrl(request))
        .queryParam("state", state)
    logger.debug("Redirecting user to Spotify authorization URL.")
    return "redirect:" + builder.toUriString()
  }

  companion object {
    private const val SPOTIFY_AUTH_STATE_COOKIE = "spotifyAuthState"
    private const val AUTH_STATE_COOKIE_MAX_AGE_SECONDS = 300
    private val stateRandom = SecureRandom()
    private val logger = LoggerFactory.getLogger(SpotifyAuthenticationController::class.java)
  }
}
