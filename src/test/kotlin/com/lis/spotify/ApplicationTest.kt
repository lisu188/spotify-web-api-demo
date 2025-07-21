package com.lis.spotify

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

class ApplicationTest {
  @Test
  fun taskSchedulerReturnsThreadPoolScheduler() {
    val scheduler = Application().taskScheduler()
    assertTrue(scheduler is ThreadPoolTaskScheduler)
  }
}
