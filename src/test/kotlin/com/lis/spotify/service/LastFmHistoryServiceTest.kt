package com.lis.spotify.service

import java.time.YearMonth
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.web.client.ResourceAccessException

class LastFmHistoryServiceTest {
  @Test
  fun yearlyAndMonthlyRequestsShareTheServiceWideConcurrencyLimit() {
    val activeRequests = AtomicInteger()
    val maxActiveRequests = AtomicInteger()
    val requestCount = AtomicInteger()
    val firstWaveStarted = CountDownLatch(3)
    val fourthRequestStarted = CountDownLatch(1)
    val releaseRequests = CountDownLatch(1)
    val service =
      historyService {
        val active = activeRequests.incrementAndGet()
        maxActiveRequests.accumulateAndGet(active, ::maxOf)
        if (requestCount.incrementAndGet() >= 4) fourthRequestStarted.countDown()
        firstWaveStarted.countDown()
        try {
          check(releaseRequests.await(5, TimeUnit.SECONDS))
          response()
        } finally {
          activeRequests.decrementAndGet()
        }
      }
    val executor = Executors.newFixedThreadPool(8)
    try {
      val requests =
        (1..8).map { index ->
          executor.submit {
            if (index % 2 == 0) {
              service.yearlyArtistSummary("login", 2002 + index)
            } else {
              service.monthlyArtistSummary("login", YearMonth.of(2020, index))
            }
          }
        }
      assertTrue(firstWaveStarted.await(2, TimeUnit.SECONDS))
      assertFalse(fourthRequestStarted.await(200, TimeUnit.MILLISECONDS))
      releaseRequests.countDown()
      requests.forEach { it.get(5, TimeUnit.SECONDS) }

      assertEquals(8, requestCount.get())
      assertEquals(3, maxActiveRequests.get())
    } finally {
      releaseRequests.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun failedHistoryLoadsReleasePermitsAndCanBeRetried() {
    val requestCount = AtomicInteger()
    val service =
      historyService {
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
        executor.submit<LastFmYearSummary> { service.yearlyArtistSummary("login", 2020) }
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
