/*
 * MIT License
 *
 * Copyright (c) 2019 Andrzej Lis
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission
 * notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY
 * OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
 * USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.lis.spotify.service

import com.lis.spotify.AppEnvironment.Spotify
import com.lis.spotify.domain.AuthToken
import com.lis.spotify.persistence.SpotifyTokenStore
import com.lis.spotify.persistence.StoredSpotifyAuthToken
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.postForObject
import org.springframework.web.util.UriComponentsBuilder

@Service
class SpotifyAuthenticationService(
  private val restTemplateBuilder: RestTemplateBuilder,
  private val spotifyTokenStore: SpotifyTokenStore,
) {

  private val logger: Logger = LoggerFactory.getLogger(SpotifyAuthenticationService::class.java)
  private val clock: Clock = Clock.systemUTC()
  private val tokenCache = ConcurrentHashMap<String, AuthToken>()

  fun getHeaders(token: AuthToken): HttpHeaders {
    logger.debug("Creating headers with Bearer token for clientId={}", token.clientId)
    return HttpHeaders().apply {
      this[HttpHeaders.AUTHORIZATION] = "Bearer ${token.access_token}"
      this[HttpHeaders.ACCEPT] = "application/json"
      this[HttpHeaders.CONTENT_TYPE] = MediaType.APPLICATION_JSON_VALUE
    }
  }

  fun getHeaders(clientId: String): HttpHeaders {
    val authToken = getAuthToken(clientId)
    return if (authToken != null) {
      logger.debug("Creating headers for clientId={}", clientId)
      getHeaders(authToken)
    } else {
      logger.warn("No token found for clientId={}, returning empty headers", clientId)
      HttpHeaders()
    }
  }

  fun setAuthToken(token: AuthToken) {
    val clientId =
      requireNotNull(token.clientId) { "clientId is required to store a Spotify auth token" }
    logger.info("Storing AuthToken for clientId={}", clientId)
    spotifyTokenStore.save(StoredSpotifyAuthToken.fromAuthToken(token, clock.instant()))
    tokenCache[clientId] = token
    logger.debug("AuthToken persisted and cached for {}", clientId)
  }

  fun seedRefreshToken(clientId: String, refreshToken: String) {
    if (clientId.isBlank() || refreshToken.isBlank()) {
      logger.warn("Cannot seed Spotify refresh token because clientId or refresh token is blank")
      return
    }

    val existing = getAuthToken(clientId)
    if (existing?.refresh_token == refreshToken) {
      logger.debug("Refresh token already cached for clientId={}", clientId)
      return
    }

    setAuthToken(
      AuthToken(
        access_token = existing?.access_token.orEmpty(),
        token_type = existing?.token_type ?: "Bearer",
        scope = existing?.scope ?: Spotify.SCOPES,
        expires_in = existing?.expires_in ?: 0,
        refresh_token = refreshToken,
        clientId = clientId,
      )
    )
  }

  fun getAuthToken(clientId: String): AuthToken? {
    logger.debug("Attempting to retrieve AuthToken from cache for clientId={}", clientId)
    val cached = tokenCache[clientId]
    if (cached != null) {
      logger.debug("getAuthToken {} found in cache", clientId)
      return cached
    }

    val token = spotifyTokenStore.findByClientId(clientId)?.toAuthToken(clock.instant())
    if (token != null) {
      tokenCache[clientId] = token
    }
    logger.debug("getAuthToken {} found={}", clientId, token != null)
    return token
  }

  fun refreshToken(clientId: String): Boolean {
    logger.info("Attempting to refresh token for clientId={}", clientId)
    val currentToken = getAuthToken(clientId)
    val refreshTokenValue = currentToken?.refresh_token.orEmpty()
    if (refreshTokenValue.isEmpty()) {
      logger.warn("No refresh token available for clientId={}; cannot refresh.", clientId)
      return false
    }

    val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }
    val body =
      LinkedMultiValueMap<String, String>().apply {
        add("grant_type", "refresh_token")
        add("refresh_token", refreshTokenValue)
      }
    val tokenUrl = UriComponentsBuilder.fromHttpUrl(Spotify.TOKEN_URL).build().toUri()
    val entity = HttpEntity(body, headers)

    return try {
      val authToken =
        restTemplateBuilder
          .basicAuthentication(Spotify.CLIENT_ID, Spotify.CLIENT_SECRET)
          .build()
          .postForObject<AuthToken>(tokenUrl, entity)

      if (authToken == null) {
        logger.warn("Spotify token refresh returned no body for clientId={}", clientId)
        false
      } else {
        // Preserve the existing refresh token.
        authToken.clientId = clientId
        authToken.refresh_token = refreshTokenValue

        logger.info(
          "Successfully refreshed token (access token redacted) for clientId={}",
          clientId,
        )
        setAuthToken(authToken)
        logger.debug("refreshToken {} new token stored", clientId)
        true
      }
    } catch (ex: Exception) {
      logger.error("Error while refreshing token for clientId={}", clientId, ex)
      false
    }
  }

  fun isAuthorized(clientId: String): Boolean {
    logger.debug("Checking if clientId={} is authorized", clientId)
    val authorized = clientId.isNotEmpty() && getAuthToken(clientId) != null
    logger.debug("isAuthorized {} -> {}", clientId, authorized)
    return authorized
  }

  internal fun clearCache() {
    tokenCache.clear()
  }
}
