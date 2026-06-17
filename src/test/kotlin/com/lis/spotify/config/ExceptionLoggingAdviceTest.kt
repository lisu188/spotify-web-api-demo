package com.lis.spotify.config

import com.lis.spotify.service.LastFmException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class ExceptionLoggingAdviceTest {
  @Test
  fun lastFmError17Redirects() {
    val advice = ExceptionLoggingAdvice()
    val result = advice.handleLastFm(LastFmException(17, "Login"))
    assertEquals(HttpStatus.UNAUTHORIZED, result.statusCode)
    assertEquals("/auth/lastfm", result.headers.location.toString())
  }

  @Test
  fun responseStatusExceptionPreservesStatus() {
    val advice = ExceptionLoggingAdvice()
    val result = advice.handleResponseStatus(ResponseStatusException(HttpStatus.BAD_REQUEST, "bad"))

    assertEquals(HttpStatus.BAD_REQUEST, result.statusCode)
    assertEquals("bad", result.body)
  }

  @Test
  fun genericExceptionDoesNotLeakMessage() {
    val advice = ExceptionLoggingAdvice()
    val result = advice.handle(IllegalStateException("secret"))

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.statusCode)
    assertEquals("Internal server error", result.body)
  }
}
