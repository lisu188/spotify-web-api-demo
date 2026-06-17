package com.lis.spotify.service

import java.time.Duration
import org.springframework.boot.web.client.RestTemplateBuilder

internal val DEFAULT_HTTP_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
internal val DEFAULT_HTTP_READ_TIMEOUT: Duration = Duration.ofSeconds(30)

internal fun RestTemplateBuilder.withDefaultTimeouts(): RestTemplateBuilder {
  return connectTimeout(DEFAULT_HTTP_CONNECT_TIMEOUT).readTimeout(DEFAULT_HTTP_READ_TIMEOUT)
}
