package com.lis.spotify.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.CommonsRequestLoggingFilter

@Configuration
class RequestLoggingConfig {
  @Bean
  fun logFilter(): CommonsRequestLoggingFilter {
    return CommonsRequestLoggingFilter().apply {
      setIncludeClientInfo(true)
      setIncludeQueryString(true)
      setIncludeHeaders(true)
      setIncludePayload(true)
      setMaxPayloadLength(10000)
    }
  }
}
