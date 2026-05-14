package com.lis.spotify.controller

import com.lis.spotify.domain.JobId
import com.lis.spotify.domain.JobStatus
import com.lis.spotify.service.JobService
import com.lis.spotify.service.LastFmAuthenticationService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/jobs")
class JobsController(
  private val jobService: JobService,
  private val lastFmAuthenticationService: LastFmAuthenticationService,
) {

  companion object {
    private val logger = LoggerFactory.getLogger(JobsController::class.java)
  }

  data class StartRequest(val lastFmLogin: String, val playlistSize: Int? = null)

  @PostMapping
  fun start(
    @CookieValue("clientId") clientId: String,
    @CookieValue("lastFmToken", defaultValue = "") lastFmSessionKey: String,
    @RequestBody request: StartRequest,
  ): ResponseEntity<JobId> {
    val lastFmLogin = request.lastFmLogin.trim()
    if (lastFmLogin.isEmpty()) {
      logger.warn("Rejecting yearly job without Last.fm login for clientId={}", clientId)
      return ResponseEntity.badRequest().build()
    }

    if (!lastFmAuthenticationService.isAuthorized(lastFmLogin, lastFmSessionKey)) {
      logger.warn("Rejecting yearly job for unauthorized Last.fm login {}", lastFmLogin)
      return ResponseEntity.status(401).build()
    }

    logger.info("Starting yearly job for clientId={} lastFmLogin={}", clientId, lastFmLogin)
    val id = jobService.startYearlyJob(clientId, lastFmLogin, lastFmSessionKey)
    logger.info("Yearly job {} scheduled", id)
    return ResponseEntity.accepted().body(JobId(id))
  }

  @PostMapping("/forgotten-obsessions")
  fun startForgottenObsessions(
    @CookieValue("clientId") clientId: String,
    @CookieValue("lastFmToken", defaultValue = "") lastFmSessionKey: String,
    @RequestBody request: StartRequest,
  ): ResponseEntity<JobId> {
    val lastFmLogin = request.lastFmLogin.trim()
    if (lastFmLogin.isEmpty()) {
      logger.warn(
        "Rejecting forgotten obsessions job without Last.fm login for clientId={}",
        clientId,
      )
      return ResponseEntity.badRequest().build()
    }

    if (!lastFmAuthenticationService.isAuthorized(lastFmLogin, lastFmSessionKey)) {
      logger.warn(
        "Rejecting forgotten obsessions job for unauthorized Last.fm login {}",
        lastFmLogin,
      )
      return ResponseEntity.status(401).build()
    }

    logger.info(
      "Starting forgotten obsessions job for clientId={} lastFmLogin={}",
      clientId,
      lastFmLogin,
    )
    val id = jobService.startForgottenObsessionsJob(clientId, lastFmLogin, lastFmSessionKey)
    logger.info("Forgotten obsessions job {} scheduled", id)
    return ResponseEntity.accepted().body(JobId(id))
  }

  @PostMapping("/private-mood-taxonomy")
  fun startPrivateMoodTaxonomy(
    @CookieValue("clientId") clientId: String,
    @CookieValue("lastFmToken", defaultValue = "") lastFmSessionKey: String,
    @RequestBody request: StartRequest,
  ): ResponseEntity<JobId> {
    val lastFmLogin = request.lastFmLogin.trim()
    if (lastFmLogin.isEmpty()) {
      logger.warn(
        "Rejecting private mood taxonomy job without Last.fm login for clientId={}",
        clientId,
      )
      return ResponseEntity.badRequest().build()
    }

    if (!lastFmAuthenticationService.isAuthorized(lastFmLogin, lastFmSessionKey)) {
      logger.warn(
        "Rejecting private mood taxonomy job for unauthorized Last.fm login {}",
        lastFmLogin,
      )
      return ResponseEntity.status(401).build()
    }

    logger.info(
      "Starting private mood taxonomy job for clientId={} lastFmLogin={} playlistSize={}",
      clientId,
      lastFmLogin,
      request.playlistSize,
    )
    val id =
      jobService.startPrivateMoodTaxonomyJob(
        clientId,
        lastFmLogin,
        request.playlistSize ?: 50,
        lastFmSessionKey,
      )
    logger.info("Private mood taxonomy job {} scheduled", id)
    return ResponseEntity.accepted().body(JobId(id))
  }

  @GetMapping("/{jobId}")
  fun getStatus(@PathVariable jobId: String): ResponseEntity<JobStatus> {
    val status = jobService.getJobStatus(jobId) ?: return ResponseEntity.notFound().build()
    return ResponseEntity.ok(status)
  }
}
