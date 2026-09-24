package com.lis.spotify.service

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import org.springframework.retry.backoff.BackOffInterruptedException
import org.springframework.retry.backoff.Sleeper
import org.springframework.retry.backoff.ThreadWaitSleeper

/** A single monotonic Retry-After deadline shared by every Spotify API caller in this instance. */
internal class SpotifyRateLimitCoordinator(
  private val sleeper: Sleeper = ThreadWaitSleeper(),
  private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
  private val suspendSleep: suspend (Long) -> Unit = { delay(it) },
) {
  private val lock = Any()
  private var deadlineMillis: Long? = null
  private val waitCount = AtomicLong()
  private val waitedMillis = AtomicLong()

  fun rateLimited(retryAfter: String?) {
    val seconds = retryAfter?.trim()?.toLongOrNull()
    val duration =
      if (seconds != null && seconds >= 0 && seconds <= Long.MAX_VALUE / 1000) seconds * 1000
      else 1000L
    synchronized(lock) {
      val now = nowMillis()
      val candidate = if (now > Long.MAX_VALUE - duration) Long.MAX_VALUE else now + duration
      deadlineMillis = maxOf(deadlineMillis ?: candidate, candidate)
    }
  }

  fun remainingMillis(): Long =
    synchronized(lock) {
      val deadline = deadlineMillis ?: return@synchronized 0L
      (deadline - nowMillis()).coerceAtLeast(0)
    }

  fun awaitBlocking() {
    while (true) {
      val remaining = remainingMillis()
      if (remaining == 0L) return
      waitCount.incrementAndGet()
      val started = nowMillis()
      try {
        sleeper.sleep(remaining)
      } catch (exception: InterruptedException) {
        Thread.currentThread().interrupt()
        throw BackOffInterruptedException("Interrupted while backing off", exception)
      } finally {
        waitedMillis.addAndGet((nowMillis() - started).coerceAtLeast(0))
      }
    }
  }

  suspend fun awaitSuspending() {
    while (true) {
      val remaining = remainingMillis()
      if (remaining == 0L) return
      waitCount.incrementAndGet()
      val started = nowMillis()
      try {
        suspendSleep(remaining)
      } finally {
        waitedMillis.addAndGet((nowMillis() - started).coerceAtLeast(0))
      }
    }
  }

  fun metrics(): Pair<Long, Long> = waitCount.get() to waitedMillis.get()
}
