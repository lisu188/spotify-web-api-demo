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

import com.lis.spotify.logging.asSafeClientIdForLogs
import jakarta.annotation.PreDestroy
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.slf4j.LoggerFactory
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.retry.backoff.Sleeper
import org.springframework.retry.backoff.ThreadWaitSleeper
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.exchange

@Service
class SpotifyRestService(
  restTemplateBuilder: RestTemplateBuilder,
  val spotifyAuthenticationService: SpotifyAuthenticationService,
  sleeper: Sleeper = ThreadWaitSleeper(),
) {
  internal val connectionManager =
    PoolingHttpClientConnectionManagerBuilder.create()
      .setMaxConnTotal(16)
      .setMaxConnPerRoute(16)
      .build()
  private val httpClient = HttpClients.custom().setConnectionManager(connectionManager).build()
  internal val requestFactory =
    HttpComponentsClientHttpRequestFactory(httpClient).apply {
      setConnectionRequestTimeout(Duration.ofSeconds(5))
    }
  val restTemplate: RestTemplate =
    restTemplateBuilder
      .withDefaultTimeouts()
      .requestFactory(Supplier<ClientHttpRequestFactory> { requestFactory })
      .build()
  internal var cooldown = SpotifyRateLimitCoordinator(sleeper)
  private val upstreamRequests = AtomicLong()
  @PublishedApi internal val logger = LoggerFactory.getLogger(SpotifyRestService::class.java)

  @PreDestroy
  fun close() {
    val metrics = requestMetrics()
    logger.info(
      "Spotify transport totals: requests={} cooldownWaits={} cooldownWaitMillis={}",
      metrics.requestCount,
      metrics.cooldownWaitCount,
      metrics.cooldownWaitMillis,
    )
    httpClient.close()
  }

  final inline fun <reified U : Any> doRequest(
    url: String,
    httpMethod: HttpMethod,
    params: Map<String, Any> = HashMap(),
    body: Any? = null,
    clientId: String,
    noinline beforeAttempt: () -> Unit = {},
  ): U = doRequest {
    doAuthenticatedExchange(url, httpMethod, body, clientId, params, beforeAttempt)
  }

  /** Only 429 is replayed here; ambiguous transport failures on writes must reach the caller. */
  fun <U> doRequest(task: () -> U): U {
    repeat(MAX_ATTEMPTS) { attempt ->
      cooldown.awaitBlocking()
      try {
        return task()
      } catch (exception: HttpClientErrorException.TooManyRequests) {
        cooldown.rateLimited(exception.responseHeaders?.getFirst("Retry-After"))
        if (attempt == MAX_ATTEMPTS - 1) throw exception
      }
    }
    error("Unreachable retry state")
  }

  /** Search permits cover exchanges only, never cooldown waits, refreshes, or retry backoff. */
  suspend fun <U> doRequestSuspending(
    semaphore: Semaphore,
    clientId: String? = null,
    task: () -> U,
  ): U {
    var rateLimitAttempts = 0
    var refreshed = false
    while (true) {
      while (true) {
        cooldown.awaitSuspending()
        semaphore.acquire()
        if (cooldown.remainingMillis() == 0L) break
        semaphore.release()
      }
      var refreshClient: String? = null
      try {
        currentCoroutineContext().ensureActive()
        return runInterruptible(Dispatchers.IO) { task() }
      } catch (exception: HttpClientErrorException.TooManyRequests) {
        cooldown.rateLimited(exception.responseHeaders?.getFirst("Retry-After"))
        if (++rateLimitAttempts == MAX_ATTEMPTS) throw exception
        refreshed = false
      } catch (exception: HttpClientErrorException.Unauthorized) {
        if (clientId == null) throw exception
        if (refreshed) throw AuthenticationRequiredException("SPOTIFY")
        refreshClient = clientId
      } finally {
        semaphore.release()
      }
      if (refreshClient != null) {
        val succeeded =
          runInterruptible(Dispatchers.IO) {
            spotifyAuthenticationService.refreshToken(refreshClient)
          }
        if (!succeeded) throw AuthenticationRequiredException("SPOTIFY")
        refreshed = true
      }
    }
  }

  @PublishedApi
  internal final inline fun <reified U : Any> doAuthenticatedExchange(
    url: String,
    httpMethod: HttpMethod,
    body: Any?,
    clientId: String,
    params: Map<String, Any>,
    noinline beforeAttempt: () -> Unit = {},
  ): U {
    try {
      return doExchange(url, httpMethod, body, clientId, params, beforeAttempt)
    } catch (exception: HttpClientErrorException.Unauthorized) {
      logger.warn(
        "Unauthorized Spotify request for clientId={}; attempting token refresh.",
        clientId.asSafeClientIdForLogs(),
      )
      if (spotifyAuthenticationService.refreshToken(clientId)) {
        awaitRequestCooldown()
        try {
          return doExchange(url, httpMethod, body, clientId, params, beforeAttempt)
        } catch (retryException: HttpClientErrorException.Unauthorized) {
          logger.warn("Spotify request remains unauthorized after token refresh.")
        }
      }
      throw AuthenticationRequiredException("SPOTIFY")
    }
  }

  @PublishedApi
  internal fun awaitRequestCooldown() {
    cooldown.awaitBlocking()
  }

  @PublishedApi
  internal fun recordUpstreamRequest() {
    upstreamRequests.incrementAndGet()
  }

  internal fun requestMetrics(): SpotifyRequestMetrics {
    val (waits, millis) = cooldown.metrics()
    return SpotifyRequestMetrics(upstreamRequests.get(), waits, millis)
  }

  final inline fun <reified U : Any> doExchange(
    url: String,
    httpMethod: HttpMethod,
    body: Any?,
    clientId: String,
    params: Map<String, Any>,
    noinline beforeAttempt: () -> Unit = {},
  ): U {
    val headers = spotifyAuthenticationService.getHeaders(clientId)
    beforeAttempt()
    recordUpstreamRequest()
    val response = restTemplate.exchange<U>(url, httpMethod, HttpEntity(body, headers), params)
    logger.debug("Spotify exchange completed: method={} status={}", httpMethod, response.statusCode)
    val result = response.body
    if (result != null) return result
    if (U::class == Unit::class) {
      @Suppress("UNCHECKED_CAST")
      return Unit as U
    }
    throw IllegalStateException("Received null body for ${httpMethod.name()} $url")
  }

  final suspend inline fun <reified U : Any> doGetSuspending(
    url: String,
    params: Map<String, Any> = HashMap(),
    body: Any? = null,
    clientId: String,
    semaphore: Semaphore,
  ): U =
    doRequestSuspending(semaphore, clientId) {
      doExchange(url, HttpMethod.GET, body, clientId, params)
    }

  final inline fun <reified U : Any> doGet(
    url: String,
    params: Map<String, Any> = HashMap(),
    body: Any? = null,
    clientId: String,
    noinline beforeAttempt: () -> Unit = {},
  ): U = doRequest(url, HttpMethod.GET, params, body, clientId, beforeAttempt)

  final inline fun <reified U : Any> doDelete(
    url: String,
    params: Map<String, Any> = HashMap(),
    body: Any? = null,
    clientId: String,
    noinline beforeAttempt: () -> Unit = {},
  ): U = doRequest(url, HttpMethod.DELETE, params, body, clientId, beforeAttempt)

  final inline fun <reified U : Any> doPost(
    url: String,
    params: Map<String, Any> = HashMap(),
    body: Any? = null,
    clientId: String,
    noinline beforeAttempt: () -> Unit = {},
  ): U = doRequest(url, HttpMethod.POST, params, body, clientId, beforeAttempt)

  final inline fun <reified U : Any> doPut(
    url: String,
    params: Map<String, Any> = HashMap(),
    body: Any? = null,
    clientId: String,
    noinline beforeAttempt: () -> Unit = {},
  ): U = doRequest(url, HttpMethod.PUT, params, body, clientId, beforeAttempt)

  private companion object {
    const val MAX_ATTEMPTS = 3
  }
}

internal data class SpotifyRequestMetrics(
  val requestCount: Long,
  val cooldownWaitCount: Long,
  val cooldownWaitMillis: Long,
)
