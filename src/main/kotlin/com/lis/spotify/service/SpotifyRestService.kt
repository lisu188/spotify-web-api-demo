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
import org.springframework.http.HttpHeaders.RETRY_AFTER
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.exchange

@Service
class SpotifyRestService(
  restTemplateBuilder: RestTemplateBuilder,
  val spotifyAuthenticationService: SpotifyAuthenticationService,
) {

  val restTemplate: RestTemplate = restTemplateBuilder.build()
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
      doExchange<U>(url, httpMethod, body, clientId, params)
    }
  }

  fun <U> doRequest(task: () -> U): U {
    try {
      return task()
    } catch (e: HttpClientErrorException.TooManyRequests) {
      e.responseHeaders?.get(RETRY_AFTER)?.first()?.toInt()?.let {
        Thread.sleep((it) * 1000L + 500L)
      }
      return doRequest(task)
    }
  }

  final inline fun <reified U : Any> doExchange(
    url: String,
    httpMethod: HttpMethod,
    body: Any?,
    clientId: String,
    params: Map<String, Any>,
  ): U {
    return restTemplate
      .exchange<U>(
        url,
        httpMethod,
        HttpEntity(body, spotifyAuthenticationService.getHeaders(clientId)),
        params,
      )
      .body ?: throw Exception() // TODO:
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
}
