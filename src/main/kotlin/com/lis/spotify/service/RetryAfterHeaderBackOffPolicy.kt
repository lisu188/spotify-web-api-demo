package com.lis.spotify.service

import org.springframework.retry.RetryContext
import org.springframework.retry.backoff.BackOffContext
import org.springframework.retry.backoff.BackOffInterruptedException
import org.springframework.retry.backoff.BackOffPolicy
import org.springframework.retry.backoff.Sleeper
import org.springframework.retry.backoff.ThreadWaitSleeper
import org.springframework.web.client.HttpClientErrorException

class RetryAfterHeaderBackOffPolicy(
  private val sleeper: Sleeper = ThreadWaitSleeper(),
  private val defaultBackoff: Long = 1000L,
) : BackOffPolicy {

  private class Context(val retryContext: RetryContext) : BackOffContext

  override fun start(context: RetryContext): BackOffContext = Context(context)

  override fun backOff(backOffContext: BackOffContext) {
    val ctx = backOffContext as Context
    val throwable = ctx.retryContext.lastThrowable
    val retryAfterSeconds =
      (throwable as? HttpClientErrorException.TooManyRequests)
        ?.responseHeaders
        ?.getFirst("Retry-After")
        ?.toLongOrNull()
    val delayMillis = retryAfterSeconds?.times(1000) ?: defaultBackoff
    try {
      sleeper.sleep(delayMillis)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw BackOffInterruptedException("Interrupted while backing off", e)
    }
  }
}
