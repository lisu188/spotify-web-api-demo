package com.lis.spotify.controller

import com.lis.spotify.domain.JobId
import com.lis.spotify.domain.JobState
import com.lis.spotify.domain.JobStatus
import com.lis.spotify.service.JobService
import com.lis.spotify.service.LastFmAuthenticationService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class JobsControllerTest {
  private val service = mockk<JobService>()
  private val lastFmAuthenticationService = mockk<LastFmAuthenticationService>()
  private val controller = JobsController(service, lastFmAuthenticationService)

  @Test
  fun startReturnsId() {
    every { lastFmAuthenticationService.isAuthorized("login", "token") } returns true
    every { service.startYearlyJob("c", "login", "token") } returns "id"
    val resp = controller.start("c", "token", JobsController.StartRequest("login"))
    assertEquals(JobId("id"), resp.body)
    assertEquals(HttpStatus.ACCEPTED, resp.statusCode)
  }

  @Test
  fun startForgottenObsessionsReturnsId() {
    every { lastFmAuthenticationService.isAuthorized("login", "token") } returns true
    every { service.startForgottenObsessionsJob("c", "login", "token") } returns "forgotten-id"
    val resp =
      controller.startForgottenObsessions("c", "token", JobsController.StartRequest("login"))
    assertEquals(JobId("forgotten-id"), resp.body)
    assertEquals(HttpStatus.ACCEPTED, resp.statusCode)
  }

  @Test
  fun startPrivateMoodTaxonomyReturnsId() {
    every { lastFmAuthenticationService.isAuthorized("login", "token") } returns true
    every { service.startPrivateMoodTaxonomyJob("c", "login", 50, "token") } returns
      "private-mood-id"

    val resp =
      controller.startPrivateMoodTaxonomy("c", "token", JobsController.StartRequest("login"))

    assertEquals(JobId("private-mood-id"), resp.body)
    assertEquals(HttpStatus.ACCEPTED, resp.statusCode)
  }

  @Test
  fun startRejectsBlankLogin() {
    val resp = controller.start("c", "token", JobsController.StartRequest("   "))
    assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
  }

  @Test
  fun startForgottenObsessionsRejectsBlankLogin() {
    val resp = controller.startForgottenObsessions("c", "token", JobsController.StartRequest("   "))
    assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
  }

  @Test
  fun startPrivateMoodTaxonomyRejectsBlankLogin() {
    val resp = controller.startPrivateMoodTaxonomy("c", "token", JobsController.StartRequest("   "))
    assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
  }

  @Test
  fun startRejectsUnauthorizedLastFmLogin() {
    every { lastFmAuthenticationService.isAuthorized("victim", "attacker-token") } returns false

    val resp = controller.start("c", "attacker-token", JobsController.StartRequest("victim"))

    assertEquals(HttpStatus.UNAUTHORIZED, resp.statusCode)
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
