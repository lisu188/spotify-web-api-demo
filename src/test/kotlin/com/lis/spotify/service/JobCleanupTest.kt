package com.lis.spotify.service

import com.lis.spotify.persistence.JobStatusStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JobCleanupTest {
  @Test
  fun pollingDoesNotSweepTheDatabase() {
    val store = mockk<JobStatusStore>(relaxed = true)
    every { store.findById(any()) } returns null
    val service = JobService(mockk(), mockk(), store, mockk())
    repeat(5) { assertNull(service.getJobStatus("missing", "owner")) }
    verify(exactly = 0) { store.deleteExpired(any()) }
    service.cleanupExpiredJobs()
    verify(exactly = 1) { store.deleteExpired(any()) }
  }

  @Test
  fun failedSweepCanBeRetriedWithoutBreakingStatusRequests() {
    val store = mockk<JobStatusStore>(relaxed = true)
    every { store.deleteExpired(any()) } throws
      IllegalStateException("Temporary storage outage") andThen
      0
    val service = JobService(mockk(), mockk(), store, mockk())
    assertDoesNotThrow { service.cleanupExpiredJobs() }
    assertDoesNotThrow { service.cleanupExpiredJobs() }
    verify(exactly = 2) { store.deleteExpired(any()) }
  }
}
