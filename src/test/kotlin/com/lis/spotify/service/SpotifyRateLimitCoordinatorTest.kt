package com.lis.spotify.service

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.retry.backoff.BackOffInterruptedException
import org.springframework.retry.backoff.Sleeper

class SpotifyRateLimitCoordinatorTest {
  @Test
  fun allCallersObserveTheLatestDeadlineWithoutShorteningAnExistingCooldown() {
    val clock = AtomicLong(1000)
    val waits = mutableListOf<Long>()
    lateinit var cooldown: SpotifyRateLimitCoordinator
    cooldown =
      SpotifyRateLimitCoordinator(
        sleeper =
          Sleeper { millis ->
            waits += millis
            clock.addAndGet(millis)
            if (waits.size == 1) cooldown.rateLimited("1")
          },
        nowMillis = clock::get,
      )
    cooldown.rateLimited("3")
    clock.addAndGet(500)
    cooldown.rateLimited("1")
    assertEquals(2500L, cooldown.remainingMillis())
    cooldown.rateLimited("4")
    assertEquals(4000L, cooldown.remainingMillis())

    cooldown.awaitBlocking()
    cooldown.awaitBlocking()

    assertEquals(listOf(4000L, 1000L), waits)
    assertEquals(0L, cooldown.remainingMillis())
    assertEquals(2L to 5000L, cooldown.metrics())
  }

  @Test
  fun missingMalformedNegativeAndOverflowingHeadersUseOneSecondFallback() {
    listOf(null, "bad", "-2", Long.MAX_VALUE.toString()).forEach { header ->
      val cooldown = SpotifyRateLimitCoordinator(nowMillis = { 0L })
      cooldown.rateLimited(header)
      assertEquals(1000L, cooldown.remainingMillis(), "header=$header")
    }
    val cooldown = SpotifyRateLimitCoordinator(nowMillis = { 0L })
    cooldown.rateLimited(" 0 ")
    assertEquals(0L, cooldown.remainingMillis())
  }

  @Test
  fun deadlineCalculationDoesNotOverflow() {
    val cooldown = SpotifyRateLimitCoordinator(nowMillis = { Long.MAX_VALUE - 100 })
    cooldown.rateLimited("1")
    assertEquals(100L, cooldown.remainingMillis())
  }

  @Test
  fun suspendedCallersRecheckDeadlineAfterEachDelay() = runBlocking {
    val clock = AtomicLong()
    val waits = mutableListOf<Long>()
    lateinit var cooldown: SpotifyRateLimitCoordinator
    cooldown =
      SpotifyRateLimitCoordinator(
        nowMillis = clock::get,
        suspendSleep = { millis ->
          waits += millis
          clock.addAndGet(millis)
          if (waits.size == 1) cooldown.rateLimited("2")
        },
      )
    cooldown.rateLimited("1")
    cooldown.awaitSuspending()
    assertEquals(listOf(1000L, 2000L), waits)
    assertEquals(2L to 3000L, cooldown.metrics())
  }

  @Test
  fun cancellingOneWaiterDoesNotClearTheSharedDeadline() = runBlocking {
    val entered = CompletableDeferred<Unit>()
    val never = CompletableDeferred<Unit>()
    val cooldown =
      SpotifyRateLimitCoordinator(
        nowMillis = { 0L },
        suspendSleep = {
          entered.complete(Unit)
          never.await()
        },
      )
    cooldown.rateLimited("3")
    val waiter = launch(start = CoroutineStart.UNDISPATCHED) { cooldown.awaitSuspending() }
    entered.await()
    waiter.cancelAndJoin()
    assertEquals(3000L, cooldown.remainingMillis())
    assertEquals(1L to 0L, cooldown.metrics())
  }

  @Test
  fun interruptedBlockingWaitRestoresTheInterruptFlag() {
    val cooldown =
      SpotifyRateLimitCoordinator(
        sleeper = Sleeper { throw InterruptedException("stop") },
        nowMillis = { 0L },
      )
    cooldown.rateLimited("1")
    try {
      assertThrows(BackOffInterruptedException::class.java) { cooldown.awaitBlocking() }
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }
}
