package com.lis.spotify.service

import com.lis.spotify.config.ExceptionLoggingAdvice
import com.lis.spotify.controller.LastFmController
import io.mockk.mockk
import java.time.YearMonth
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.client.ResourceAccessException

class LastFmHistoryServiceTest {
  @Test
  fun saturatedHistoryRequestsReturn503WithoutExtraHttpAndRecover() {
    val activeRequests = AtomicInteger()
    val maxActiveRequests = AtomicInteger()
    val requestCount = AtomicInteger()
    val firstWaveStarted = CountDownLatch(3)
    val releaseRequests = CountDownLatch(1)
    val service = historyService {
      val active = activeRequests.incrementAndGet()
      maxActiveRequests.accumulateAndGet(active, ::maxOf)
      requestCount.incrementAndGet()
      firstWaveStarted.countDown()
      try {
        check(releaseRequests.await(10, TimeUnit.SECONDS))
        response()
      } finally {
        activeRequests.decrementAndGet()
      }
    }
    val mvc =
      MockMvcBuilders.standaloneSetup(LastFmController(mockk(), mockk(), "login", service))
        .setControllerAdvice(ExceptionLoggingAdvice())
        .build()
    val paths =
      listOf(
        "/api/lastfm/users/login/history?fromYear=2025&toYear=2025",
        "/api/lastfm/users/login/monthly-history?from=2025-01&to=2025-01",
      )
    val executor = Executors.newFixedThreadPool(4)
    try {
      val requests =
        (1..3).map { index ->
          executor.submit {
            if (index % 2 == 0) {
              service.yearlyArtistSummary("login", 2002 + index)
            } else {
              service.monthlyArtistSummary("login", YearMonth.of(2020, index))
            }
          }
        }
      assertTrue(firstWaveStarted.await(2, TimeUnit.SECONDS))
      paths.forEach { path ->
        executor
          .submit {
            mvc
              .perform(get(path))
              .andExpect(status().isServiceUnavailable())
              .andExpect(header().string("Retry-After", "1"))
          }
          .get(2, TimeUnit.SECONDS)
        assertEquals(3, requestCount.get())
      }
      assertEquals(3, maxActiveRequests.get())
      releaseRequests.countDown()
      requests.forEach { it.get(5, TimeUnit.SECONDS) }

      paths.forEach { path -> mvc.perform(get(path)).andExpect(status().isOk()) }
      assertEquals(5, requestCount.get())
    } finally {
      releaseRequests.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun sameKeyFollowersTimeOutWithoutCancellingOwnersOrDuplicatingHttp() {
    val requestCount = AtomicInteger()
    val ownersStarted = CountDownLatch(2)
    val releaseOwners = CountDownLatch(1)
    val service = historyService {
      requestCount.incrementAndGet()
      ownersStarted.countDown()
      check(releaseOwners.await(10, TimeUnit.SECONDS))
      response()
    }
    val mvc =
      MockMvcBuilders.standaloneSetup(LastFmController(mockk(), mockk(), "login", service))
        .setControllerAdvice(ExceptionLoggingAdvice())
        .build()
    val paths =
      listOf(
        "/api/lastfm/users/LOGIN/history?fromYear=2025&toYear=2025",
        "/api/lastfm/users/LOGIN/monthly-history?from=2025-01&to=2025-01",
      )
    val executor = Executors.newFixedThreadPool(3)
    try {
      val yearOwner = executor.submit { service.yearlyArtistSummary("login", 2025) }
      val monthOwner =
        executor.submit { service.monthlyArtistSummary("login", YearMonth.of(2025, 1)) }
      assertTrue(ownersStarted.await(2, TimeUnit.SECONDS))
      paths.forEach { path ->
        executor
          .submit {
            mvc
              .perform(get(path))
              .andExpect(status().isServiceUnavailable())
              .andExpect(header().string("Retry-After", "1"))
          }
          .get(2, TimeUnit.SECONDS)
        assertEquals(2, requestCount.get())
      }
      releaseOwners.countDown()
      yearOwner.get(5, TimeUnit.SECONDS)
      monthOwner.get(5, TimeUnit.SECONDS)

      paths.forEach { path -> mvc.perform(get(path)).andExpect(status().isOk()) }
      assertEquals(2, requestCount.get())
    } finally {
      releaseOwners.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun interruptedPermitWaitPreservesInterruptAndMakesNoHttpRequest() {
    val requestCount = AtomicInteger()
    val service = historyService {
      requestCount.incrementAndGet()
      response()
    }
    val executor = Executors.newSingleThreadExecutor()
    try {
      executor
        .submit {
          Thread.currentThread().interrupt()
          try {
            val error =
              assertThrows(LastFmHistoryBusyException::class.java) {
                service.yearlyArtistSummary("login", 2020)
              }
            assertTrue(error.cause is InterruptedException)
            assertTrue(Thread.currentThread().isInterrupted)
          } finally {
            Thread.interrupted()
          }
        }
        .get(2, TimeUnit.SECONDS)

      assertEquals(0, requestCount.get())
      assertEquals(
        "Recovered Artist",
        service.yearlyArtistSummary("login", 2020).topArtists.single().name,
      )
      assertEquals(1, requestCount.get())
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun failedHistoryLoadsReleasePermitsAndCanBeRetried() {
    val requestCount = AtomicInteger()
    val service = historyService {
      if (requestCount.incrementAndGet() <= 3) {
        throw ResourceAccessException("Provider unavailable")
      }
      response()
    }
    repeat(3) {
      assertThrows(RuntimeException::class.java) { service.yearlyArtistSummary("login", 2020) }
    }
    val executor = Executors.newSingleThreadExecutor()
    try {
      val recovered =
        executor
          .submit<LastFmYearSummary> { service.yearlyArtistSummary("login", 2020) }
          .get(5, TimeUnit.SECONDS)

      assertEquals("Recovered Artist", recovered.topArtists.single().name)
      assertEquals(recovered, service.yearlyArtistSummary("LOGIN", 2020))
      assertEquals(4, requestCount.get())
    } finally {
      executor.shutdownNow()
    }
  }

  private fun historyService(request: () -> MockClientHttpResponse): LastFmHistoryService =
    LastFmHistoryService(
      RestTemplateBuilder()
        .additionalInterceptors(ClientHttpRequestInterceptor { _, _, _ -> request() })
    )

  private fun response(): MockClientHttpResponse =
    MockClientHttpResponse(
        """{"weeklyartistchart":{"artist":[{"name":"Recovered Artist","playcount":"10"}]}}"""
          .toByteArray(),
        HttpStatus.OK,
      )
      .apply { headers.contentType = MediaType.APPLICATION_JSON }
}
