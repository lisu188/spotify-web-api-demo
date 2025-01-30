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

import com.lis.spotify.controller.SpotifyAuthenticationController
import com.lis.spotify.domain.AuthToken
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service
import org.springframework.web.client.postForObject
import org.springframework.web.util.UriComponentsBuilder

@Service
class SpotifyAuthenticationService(private val restTemplateBuilder: RestTemplateBuilder) {

  companion object {
    private val logger: Logger = LoggerFactory.getLogger(SpotifyAuthenticationService::class.java)
  }

  val tokenCache: MutableMap<String, AuthToken> = mutableMapOf()

  fun getHeaders(token: AuthToken): HttpHeaders {
    logger.debug("Creating headers with Bearer token for clientId={}", token.clientId)
    val headers = HttpHeaders()
    headers[HttpHeaders.AUTHORIZATION] = "Bearer ${token.access_token}"
    headers[HttpHeaders.ACCEPT] = "application/json"
    return headers
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
    tokenCache["clientId"] = token
  }

  fun getAuthToken(clientId: String): AuthToken? {
    logger.debug("Attempting to retrieve AuthToken from cache for clientId={}", clientId)
    return tokenCache[clientId]
  }

  fun refreshToken(clientId: String) {
    logger.info("Attempting to refresh token for clientId={}", clientId)
    val code = getAuthToken(clientId)?.refresh_token.orEmpty()
    if (code.isEmpty()) {
      logger.warn("No refresh token available for clientId={}; cannot refresh.", clientId)
      return
    }

    val tokenUrl =
      UriComponentsBuilder.fromHttpUrl(SpotifyAuthenticationController.TOKEN_URL)
        .queryParam("grant_type", "refresh_token")
        .queryParam("refresh_token", code)
        .build()
        .toUri()

    try {
      val authToken =
        restTemplateBuilder
          .basicAuthentication(
            SpotifyAuthenticationController.CLIENT_ID,
            SpotifyAuthenticationController.CLIENT_SECRET,
          )
          .build()
          .postForObject<AuthToken>(tokenUrl) // TODO: handle error case if needed

      if (authToken == null) {
        logger.error(
          "Received null AuthToken from Spotify during refresh for clientId={}",
          clientId,
        )
        return
      }

      // Keep the same refresh token
      authToken.clientId = clientId
      authToken.refresh_token = code

      logger.info("Successfully refreshed token (access_token redacted) for clientId={}", clientId)
      setAuthToken(authToken)
    } catch (ex: Exception) {
      logger.error("Error while refreshing token for clientId={}", clientId, ex)
    }
  }

  fun isAuthorized(clientId: String): Boolean {
    logger.debug("Checking if clientId={} is authorized", clientId)
    return clientId.isNotEmpty() && getAuthToken(clientId) != null
  }
}
