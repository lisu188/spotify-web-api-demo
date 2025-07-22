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
class SpotifyAuthenticationService(private val restTemplateBuilder: RestTemplateBuilder) {

  val tokenCache: MutableMap<String, AuthToken> = mutableMapOf()

  fun getHeaders(token: AuthToken): HttpHeaders {
    logger.debug("Creating headers with Bearer token for clientId={}", token.clientId)
    return HttpHeaders().apply {
      this[HttpHeaders.AUTHORIZATION] = "Bearer ${token.access_token}"
      this[HttpHeaders.ACCEPT] = "application/json"
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
    logger.info("Storing AuthToken in cache for clientId={}", token.clientId)
    tokenCache[token.clientId!!] = token
    logger.debug("AuthToken cached for {}", token.clientId)
  }

  fun getAuthToken(clientId: String): AuthToken? {
    logger.debug("Attempting to retrieve AuthToken from cache for clientId={}", clientId)
    val token = tokenCache[clientId]
    logger.debug("getAuthToken {} found={}", clientId, token != null)
    return token
  }

  fun refreshToken(clientId: String) {
    logger.info("Attempting to refresh token for clientId={}", clientId)
    val currentToken = getAuthToken(clientId)
    val refreshTokenValue = currentToken?.refresh_token.orEmpty()
    if (refreshTokenValue.isEmpty()) {
      logger.warn("No refresh token available for clientId={}; cannot refresh.", clientId)
      return
    }

    val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }
    val body =
      LinkedMultiValueMap<String, String>().apply {
        add("grant_type", "refresh_token")
        add("refresh_token", refreshTokenValue)
      }
    val tokenUrl = UriComponentsBuilder.fromHttpUrl(Spotify.TOKEN_URL).build().toUri()
    val entity = HttpEntity(body, headers)

    try {
      val authToken =
        restTemplateBuilder
          .basicAuthentication(Spotify.CLIENT_ID, Spotify.CLIENT_SECRET)
          .build()
          .postForObject<AuthToken>(tokenUrl, entity)
      // Preserve the existing refresh token.
      authToken.clientId = clientId
      authToken.refresh_token = refreshTokenValue

      logger.info("Successfully refreshed token (access token redacted) for clientId={}", clientId)
      setAuthToken(authToken)
      logger.debug("refreshToken {} new token stored", clientId)
    } catch (ex: Exception) {
      logger.error("Error while refreshing token for clientId={}", clientId, ex)
    }
  }

  fun isAuthorized(clientId: String): Boolean {
    logger.debug("Checking if clientId={} is authorized", clientId)
    val authorized = clientId.isNotEmpty() && getAuthToken(clientId) != null
    logger.debug("isAuthorized {} -> {}", clientId, authorized)
    return authorized
  }

  companion object {
    private val logger: Logger = LoggerFactory.getLogger(SpotifyAuthenticationService::class.java)
  }
}
