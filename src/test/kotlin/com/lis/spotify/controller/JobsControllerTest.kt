package com.lis.spotify.controller

import com.lis.spotify.domain.JobId
import com.lis.spotify.domain.JobState
import com.lis.spotify.domain.JobStatus
import com.lis.spotify.service.JobService
import com.lis.spotify.service.LastFmAuthenticationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class JobsControllerTest {
  private val service = mockk<JobService>()
  private val lastFmAuthenticationService = mockk<LastFmAuthenticationService>()
  private val controller = JobsController(service, lastFmAuthenticationService)

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
    every { lastFmAuthenticationService.isAuthorized("login", "token") } returns true
    every { service.startPrivateMoodTaxonomyJob("c", "login", 50) } returns "private-mood-id"

    val resp =
      controller.startPrivateMoodTaxonomy("c", "token", JobsController.StartRequest("login"))

    assertEquals(JobId("private-mood-id"), resp.body)
    assertEquals(HttpStatus.ACCEPTED, resp.statusCode)
  }

  @Test
  fun startPrivateMoodTaxonomyRejectsUnauthorizedLastFmLogin() {
    every { lastFmAuthenticationService.isAuthorized("victim", "attacker-token") } returns false

    val resp =
      controller.startPrivateMoodTaxonomy(
        "c",
        "attacker-token",
        JobsController.StartRequest("victim"),
      )

    assertEquals(HttpStatus.FORBIDDEN, resp.statusCode)
    verify(exactly = 0) { service.startPrivateMoodTaxonomyJob(any(), any(), any()) }
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
    val resp = controller.startPrivateMoodTaxonomy("c", "token", JobsController.StartRequest("   "))
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
