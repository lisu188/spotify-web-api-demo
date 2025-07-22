package com.lis.spotify.config

import com.lis.spotify.service.LastFmException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class ExceptionLoggingAdviceTest {
  @Test
  fun lastFmError17Redirects() {
    val advice = ExceptionLoggingAdvice()
    val result = advice.handleLastFm(LastFmException(17, "Login"))
    assertEquals(HttpStatus.UNAUTHORIZED, result.statusCode)
    assertEquals("/auth/lastfm", result.headers.location.toString())
  }
}
