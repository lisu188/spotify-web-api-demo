package com.lis.spotify.controller

import com.lis.spotify.domain.JobId
import com.lis.spotify.domain.JobState
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
  fun startForgottenObsessionsReturnsId() {
    every { service.startForgottenObsessionsJob("c", "login") } returns "forgotten-id"
    val resp = controller.startForgottenObsessions("c", JobsController.StartRequest("login"))
    assertEquals(JobId("forgotten-id"), resp.body)
    assertEquals(HttpStatus.ACCEPTED, resp.statusCode)
  }

  @Test
  fun startPrivateMoodTaxonomyReturnsId() {
    every { service.startPrivateMoodTaxonomyJob("c", "login", 50) } returns "private-mood-id"

    val resp = controller.startPrivateMoodTaxonomy("c", JobsController.StartRequest("login"))

    assertEquals(JobId("private-mood-id"), resp.body)
    assertEquals(HttpStatus.ACCEPTED, resp.statusCode)
  }

  @Test
  fun startRejectsBlankLogin() {
    val resp = controller.start("c", JobsController.StartRequest("   "))
    assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
  }

  @Test
  fun startForgottenObsessionsRejectsBlankLogin() {
    val resp = controller.startForgottenObsessions("c", JobsController.StartRequest("   "))
    assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
  }

  @Test
  fun startPrivateMoodTaxonomyRejectsBlankLogin() {
    val resp = controller.startPrivateMoodTaxonomy("c", JobsController.StartRequest("   "))
    assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
  }

  @Test
  fun getStatusReturnsJobStatus() {
    val status = JobStatus("id", JobState.RUNNING, 25, "Processing 2025 (1/21)")
    every { service.getJobStatus("id") } returns status

    val response = controller.getStatus("id")

    assertEquals(HttpStatus.OK, response.statusCode)
    assertEquals(status, response.body)
  }
}
