package com.lis.spotify.controller

import com.lis.spotify.domain.JobId
import com.lis.spotify.domain.JobProgress
import com.lis.spotify.domain.JobStatus
import com.lis.spotify.service.JobService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class JobsControllerTest {
  private val service = mockk<JobService>()
  private val controller = JobsController(service)

  @Test
  fun startReturnsId() {
    every { service.startYearlyJob("c", "login") } returns "id"
    val resp = controller.start("c", JobsController.StartRequest("login"))
    assertEquals(JobId("id"), resp.body)
    assertEquals(HttpStatus.ACCEPTED, resp.statusCode)
  }

  @Test
  fun progressReturnsValue() {
    every { service.progress("1") } returns JobProgress(JobStatus.DONE, 100)
    val resp = controller.progress("1")
    assertEquals(100, resp.body!!.percent)
  }
}
