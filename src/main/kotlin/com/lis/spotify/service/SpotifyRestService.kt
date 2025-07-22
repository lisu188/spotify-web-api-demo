/*
 * MIT License
 *
 * Copyright (c) 2019 Andrzej Lis
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.lis.spotify.service

import org.slf4j.LoggerFactory
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.retry.backoff.Sleeper
import org.springframework.retry.backoff.ThreadWaitSleeper
import org.springframework.retry.support.RetryTemplate
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.exchange

@Service
class SpotifyRestService(
  restTemplateBuilder: RestTemplateBuilder,
  val spotifyAuthenticationService: SpotifyAuthenticationService,
  private val sleeper: Sleeper = ThreadWaitSleeper(),
) {

  val restTemplate: RestTemplate =
    restTemplateBuilder.requestFactory(HttpComponentsClientHttpRequestFactory::class.java).build()
  private val retryTemplate: RetryTemplate =
    RetryTemplate.builder()
      .maxAttempts(3)
      .retryOn(HttpClientErrorException.TooManyRequests::class.java)
      .customBackoff(RetryAfterHeaderBackOffPolicy(sleeper))
      .build()
  @PublishedApi internal val logger = LoggerFactory.getLogger(SpotifyRestService::class.java)

  final inline fun <reified U : Any> doRequest(
    url: String,
    httpMethod: HttpMethod,
    params: Map<String, Any> = HashMap(),
    body: Any? = null,
    clientId: String,
  ): U {
    return doRequest {
      logger.debug("doRequest: {} {} {} {}", url, httpMethod, params, body)
      try {
        val result = doExchange<U>(url, httpMethod, body, clientId, params)
        logger.debug("doRequest result for {} {} -> {}", httpMethod, url, result)
        result
      } catch (e: HttpClientErrorException.Unauthorized) {
        logger.error("Unauthorized Spotify request for clientId={}", clientId, e)
        throw AuthenticationRequiredException("SPOTIFY")
      }
    }
  }

  fun <U> doRequest(task: () -> U): U {
    return retryTemplate.execute<U, Exception> { task() }
  }

  final inline fun <reified U : Any> doExchange(
    url: String,
    httpMethod: HttpMethod,
    body: Any?,
    clientId: String,
    params: Map<String, Any>,
  ): U {
    val response =
      restTemplate.exchange<U>(
        url,
        httpMethod,
        HttpEntity(body, spotifyAuthenticationService.getHeaders(clientId)),
        params,
      )
    logger.debug("doExchange response from {} {} -> {}", httpMethod, url, response.statusCode)
    val result = response.body
    if (result != null) {
      return result
    }
    if (U::class == Unit::class) {
      @Suppress("UNCHECKED_CAST")
      return Unit as U
    }
    throw IllegalStateException("Received null body for ${httpMethod.name()} $url")
  }

  final inline fun <reified U : Any> doGet(
    url: String,
    params: Map<String, Any> = HashMap(),
    body: Any? = null,
    clientId: String,
  ): U {
    return doRequest(url, HttpMethod.GET, params, body, clientId)
  }

  final inline fun <reified U : Any> doDelete(
    url: String,
    params: Map<String, Any> = HashMap(),
    body: Any? = null,
    clientId: String,
  ): U {
    return doRequest(url, HttpMethod.DELETE, params, body, clientId)
  }

  final inline fun <reified U : Any> doPost(
    url: String,
    params: Map<String, Any> = HashMap(),
    body: Any? = null,
    clientId: String,
  ): U {
    return doRequest(url, HttpMethod.POST, params, body, clientId)
  }

  final inline fun <reified U : Any> doPut(
    url: String,
    params: Map<String, Any> = HashMap(),
    body: Any? = null,
    clientId: String,
  ): U {
    return doRequest(url, HttpMethod.PUT, params, body, clientId)
  }
}
