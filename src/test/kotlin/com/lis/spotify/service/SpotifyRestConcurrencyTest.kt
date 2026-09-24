package com.lis.spotify.service

import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.retry.backoff.Sleeper
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.ResourceAccessException

class SpotifyRestConcurrencyTest {
  @Test
  fun transportPoolHasCapacityForSearchesAndPlaylistRequests() {
    val service = service()
    try {
      assertEquals(16, service.connectionManager.maxTotal)
      assertEquals(16, service.connectionManager.defaultMaxPerRoute)
    } finally {
      service.close()
    }
  }

  @Test
  fun synchronous429RetriesAreBoundedAndShareCooldownWithFollowingCallers() {
    val service = service()
    val clock = AtomicLong()
    val sleeps = mutableListOf<Long>()
    service.cooldown =
      SpotifyRateLimitCoordinator(
        sleeper =
          Sleeper { millis ->
            sleeps += millis
            clock.addAndGet(millis)
          },
        nowMillis = clock::get,
      )
    val attempts = AtomicInteger()
    assertThrows(HttpClientErrorException.TooManyRequests::class.java) {
      service.doRequest<String> {
        attempts.incrementAndGet()
        throw rateLimited("2")
      }
    }
    assertEquals(3, attempts.get())
    assertEquals(listOf(2000L, 2000L), sleeps)
    assertEquals("following request", service.doRequest { "following request" })
    assertEquals(listOf(2000L, 2000L, 2000L), sleeps)
    assertEquals(3L, service.requestMetrics().cooldownWaitCount)
    assertEquals(6000L, service.requestMetrics().cooldownWaitMillis)
  }

  @Test
  fun ambiguousTransportFailureIsNotReplayed() {
    val service = service()
    val attempts = AtomicInteger()
    assertThrows(ResourceAccessException::class.java) {
      service.doRequest<Unit> {
        attempts.incrementAndGet()
        throw ResourceAccessException("connection reset")
      }
    }
    assertEquals(1, attempts.get())
  }

  @Test
  fun suspendingRequestsReleasePermitsDuringCooldownAndWaitBeforeNewRequests() = runBlocking {
    val service = service()
    withTimeout(5000) {
      val semaphore = Semaphore(1)
      val clock = AtomicLong()
      val delayEntered = CompletableDeferred<Unit>()
      val resumeDelay = CompletableDeferred<Unit>()
      service.cooldown =
        SpotifyRateLimitCoordinator(
          nowMillis = clock::get,
          suspendSleep = { millis ->
            delayEntered.complete(Unit)
            resumeDelay.await()
            clock.updateAndGet { maxOf(it, millis) }
          },
        )
      val attempts = AtomicInteger()
      val first = async {
        service.doRequestSuspending(semaphore) {
          if (attempts.incrementAndGet() == 1) throw rateLimited("2")
          "first"
        }
      }
      delayEntered.await()
      assertEquals(1, semaphore.availablePermits)
      val secondStarted = CompletableDeferred<Unit>()
      val second = async {
        service.doRequestSuspending(semaphore) {
          secondStarted.complete(Unit)
          "second"
        }
      }
      // Run the second caller until it reaches the shared delay; neither request can bypass it.
      kotlinx.coroutines.yield()
      assertFalse(secondStarted.isCompleted)
      resumeDelay.complete(Unit)
      assertEquals("first", first.await())
      assertEquals("second", second.await())
      assertEquals(2, attempts.get())
      assertEquals(1, semaphore.availablePermits)
    }
  }

  @Test
  fun exhaustedSuspendingRequestsReleaseTheirPermits() = runBlocking {
    val service = service()
    val semaphore = Semaphore(1)
    val attempts = AtomicInteger()
    try {
      service.doRequestSuspending<Unit>(semaphore) {
        attempts.incrementAndGet()
        throw rateLimited("0")
      }
      error("Expected rate-limit failure")
    } catch (_: HttpClientErrorException.TooManyRequests) {
      assertEquals(3, attempts.get())
      assertEquals(1, semaphore.availablePermits)
    }
  }

  @Test
  fun cancellationInterruptsBlockingRequestsAndReleasesTheirPermits() = runBlocking {
    val service = service()
    withTimeout(5000) {
      val semaphore = Semaphore(1)
      val entered = CompletableDeferred<Unit>()
      val interrupted = CountDownLatch(1)
      val worker = launch {
        service.doRequestSuspending(semaphore) {
          entered.complete(Unit)
          try {
            CountDownLatch(1).await()
          } catch (exception: InterruptedException) {
            interrupted.countDown()
            throw exception
          }
        }
      }
      entered.await()
      worker.cancelAndJoin()
      assertTrue(interrupted.await(1, TimeUnit.SECONDS))
      assertEquals(1, semaphore.availablePermits)
    }
  }

  @Test
  fun cancellationWhileWaitingForCapacityDoesNotRunTheRequestOrLeakPermits() = runBlocking {
    val service = service()
    val semaphore = Semaphore(1, acquiredPermits = 1)
    val attempts = AtomicInteger()
    val worker = launch { service.doRequestSuspending(semaphore) { attempts.incrementAndGet() } }
    kotlinx.coroutines.yield()
    worker.cancelAndJoin()
    assertEquals(0, attempts.get())
    assertEquals(0, semaphore.availablePermits)
    semaphore.release()
    assertEquals(1, semaphore.availablePermits)
  }

  @Test
  fun authenticationRefreshReleasesCapacityAndHonorsCooldownBeforeRetrying() = runBlocking {
    val auth = mockk<SpotifyAuthenticationService>()
    val service = SpotifyRestService(RestTemplateBuilder(), auth)
    val semaphore = Semaphore(1)
    val clock = AtomicLong()
    val delays = mutableListOf<Long>()
    service.cooldown =
      SpotifyRateLimitCoordinator(
        nowMillis = clock::get,
        suspendSleep = { millis ->
          assertEquals(1, semaphore.availablePermits)
          delays += millis
          clock.addAndGet(millis)
        },
      )
    every { auth.refreshToken("listener") } answers
      {
        assertEquals(1, semaphore.availablePermits)
        // A separate Spotify caller may extend the deadline while the token is refreshing.
        service.cooldown.rateLimited("3")
        true
      }
    val attempts = AtomicInteger()
    val result =
      service.doRequestSuspending(semaphore, "listener") {
        if (attempts.incrementAndGet() == 1) throw unauthorized()
        assertEquals(3000L, clock.get())
        "ok"
      }
    assertEquals("ok", result)
    assertEquals(listOf(3000L), delays)
    assertEquals(1, semaphore.availablePermits)
  }

  @Test
  fun failedRefreshAndRepeatedUnauthorizedResponsesRequireAuthentication() = runBlocking {
    for (refreshSucceeds in listOf(false, true)) {
      val auth = mockk<SpotifyAuthenticationService>()
      every { auth.refreshToken("listener") } returns refreshSucceeds
      val service = SpotifyRestService(RestTemplateBuilder(), auth)
      val semaphore = Semaphore(1)
      val attempts = AtomicInteger()
      try {
        service.doRequestSuspending<Unit>(semaphore, "listener") {
          attempts.incrementAndGet()
          throw unauthorized()
        }
        error("Expected authentication failure")
      } catch (_: AuthenticationRequiredException) {
        assertEquals(if (refreshSucceeds) 2 else 1, attempts.get())
        assertEquals(1, semaphore.availablePermits)
      }
    }
  }

  @Test
  fun unauthorizedWithoutClientContextReachesTheCaller() = runBlocking {
    val service = service()
    val semaphore = Semaphore(1)
    try {
      service.doRequestSuspending<Unit>(semaphore) { throw unauthorized() }
      error("Expected unauthorized response")
    } catch (_: HttpClientErrorException.Unauthorized) {
      assertEquals(1, semaphore.availablePermits)
    }
  }

  @Test
  fun queuedRequestRechecksCooldownAfterAcquiringCapacity() = runBlocking {
    val service = service()
    val semaphore = Semaphore(1, acquiredPermits = 1)
    val clock = AtomicLong()
    val waits = mutableListOf<Long>()
    service.cooldown =
      SpotifyRateLimitCoordinator(
        nowMillis = clock::get,
        suspendSleep = { millis ->
          assertEquals(1, semaphore.availablePermits)
          waits += millis
          clock.addAndGet(millis)
        },
      )
    val queued =
      async(start = CoroutineStart.UNDISPATCHED) {
        service.doRequestSuspending(semaphore) {
          assertEquals(2000L, clock.get())
          "ok"
        }
      }
    service.cooldown.rateLimited("2")
    semaphore.release()
    assertEquals("ok", queued.await())
    assertEquals(listOf(2000L), waits)
    assertEquals(1, semaphore.availablePermits)
  }

  @Test
  fun mutationGuardRunsAfterCooldownAndAuthenticationRefreshBeforeEveryExchange() {
    for (status in listOf(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.UNAUTHORIZED)) {
      for (httpMethod in listOf(HttpMethod.POST, HttpMethod.DELETE, HttpMethod.PUT)) {
        val auth = mockk<SpotifyAuthenticationService>()
        every { auth.getHeaders("listener") } returns HttpHeaders()
        val service = SpotifyRestService(RestTemplateBuilder(), auth)
        val server = MockRestServiceServer.bindTo(service.restTemplate).build()
        val clock = AtomicLong()
        var cancelled = false
        service.cooldown =
          SpotifyRateLimitCoordinator(
            sleeper =
              Sleeper { millis ->
                clock.addAndGet(millis)
                cancelled = true
              },
            nowMillis = clock::get,
          )
        every { auth.refreshToken("listener") } answers
          {
            service.cooldown.rateLimited("1")
            true
          }
        server
          .expect(requestTo("https://spotify.test/mutation"))
          .andExpect(method(httpMethod))
          .andRespond(withStatus(status).header("Retry-After", "1"))
        val guard = { if (cancelled) throw CancellationException("cancelled job") }
        assertThrows(CancellationException::class.java) {
          when (httpMethod) {
            HttpMethod.POST ->
              service.doPost<Unit>(
                "https://spotify.test/mutation",
                clientId = "listener",
                beforeAttempt = guard,
              )
            HttpMethod.DELETE ->
              service.doDelete<Unit>(
                "https://spotify.test/mutation",
                clientId = "listener",
                beforeAttempt = guard,
              )
            else ->
              service.doPut<Unit>(
                "https://spotify.test/mutation",
                clientId = "listener",
                beforeAttempt = guard,
              )
          }
        }
        assertEquals(1L, service.requestMetrics().requestCount)
        server.verify()
        service.close()
      }
    }
  }

  private fun unauthorized(): HttpClientErrorException =
    HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "", HttpHeaders(), ByteArray(0), null)

  private fun service() = SpotifyRestService(RestTemplateBuilder(), mockk(relaxed = true))

  private fun rateLimited(retryAfter: String): HttpClientErrorException =
    HttpClientErrorException.create(
      HttpStatus.TOO_MANY_REQUESTS,
      "",
      HttpHeaders().apply { set("Retry-After", retryAfter) },
      ByteArray(0),
      null,
    )
}
