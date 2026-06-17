package com.lis.spotify.service

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.retry.RetryContext
import org.springframework.retry.backoff.BackOffInterruptedException
import org.springframework.retry.backoff.Sleeper
import org.springframework.web.client.HttpClientErrorException

class RetryAfterHeaderBackOffPolicyTest {
  @Test
  fun retryAfterHeaderControlsDelay() {
    val delays = mutableListOf<Long>()
    val headers = HttpHeaders()
    headers.set("Retry-After", "3")
    val policy =
      RetryAfterHeaderBackOffPolicy(
        sleeper = Sleeper { millis -> delays += millis },
        defaultBackoff = 1000L,
      )

    policy.backOff(policy.start(retryContextWith(tooManyRequests(headers))))

    assertEquals(listOf(3000L), delays)
  }

  @Test
  fun defaultBackoffIsUsedWhenRetryAfterHeaderIsMissing() {
    val delays = mutableListOf<Long>()
    val policy =
      RetryAfterHeaderBackOffPolicy(
        sleeper = Sleeper { millis -> delays += millis },
        defaultBackoff = 1500L,
      )

    policy.backOff(policy.start(retryContextWith(RuntimeException("boom"))))

    assertEquals(listOf(1500L), delays)
  }

  @Test
  fun interruptedSleeperRestoresInterruptFlagAndThrowsBackOffInterruptedException() {
    val policy =
      RetryAfterHeaderBackOffPolicy(
        sleeper = Sleeper { throw InterruptedException("stop") },
        defaultBackoff = 1L,
      )

    try {
      val exception =
        assertThrows(BackOffInterruptedException::class.java) {
          policy.backOff(policy.start(retryContextWith(RuntimeException("boom"))))
        }

      assertEquals("Interrupted while backing off", exception.message)
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }

  private fun retryContextWith(throwable: Throwable): RetryContext {
    val context = mockk<RetryContext>()
    every { context.lastThrowable } returns throwable
    return context
  }

  private fun tooManyRequests(headers: HttpHeaders): HttpClientErrorException {
    return HttpClientErrorException.create(
      HttpStatus.TOO_MANY_REQUESTS,
      "Too Many Requests",
      headers,
      ByteArray(0),
      null,
    )
  }
}
