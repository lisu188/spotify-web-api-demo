package com.lis.spotify.controller

import com.lis.spotify.domain.JobId
import com.lis.spotify.domain.JobStatus
import com.lis.spotify.service.JobService
import com.lis.spotify.service.LastFmAuthenticationService
import com.lis.spotify.service.SpotifyAuthenticationService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/jobs")
class JobsController(
  private val jobService: JobService,
  private val lastFmAuthenticationService: LastFmAuthenticationService,
  private val spotifyAuthenticationService: SpotifyAuthenticationService,
) {
  data class StartRequest(val lastFmLogin: String, val playlistSize: Int? = null)

  @PostMapping
  fun start(
    @CookieValue("clientId") clientId: String,
    @RequestBody request: StartRequest,
  ): ResponseEntity<JobId> {
    requireAuthorizedSession(clientId)
    val lastFmLogin = request.lastFmLogin.trim()
    if (lastFmLogin.isEmpty()) {
      logger.warn("Rejecting yearly job without Last.fm login for clientId={}", clientId)
      return ResponseEntity.badRequest().build()
    }

    logger.info("Starting yearly job for clientId={} lastFmLogin={}", clientId, lastFmLogin)
    val id = jobService.startYearlyJob(clientId, lastFmLogin)
    logger.info("Yearly job {} scheduled", id)
    return ResponseEntity.accepted().body(JobId(id))
  }

  @PostMapping("/forgotten-obsessions")
  fun startForgottenObsessions(
    @CookieValue("clientId") clientId: String,
    @RequestBody request: StartRequest,
  ): ResponseEntity<JobId> {
    requireAuthorizedSession(clientId)
    val lastFmLogin = request.lastFmLogin.trim()
    if (lastFmLogin.isEmpty()) {
      logger.warn(
        "Rejecting forgotten obsessions job without Last.fm login for clientId={}",
        clientId,
      )
      return ResponseEntity.badRequest().build()
    }

    logger.info(
      "Starting forgotten obsessions job for clientId={} lastFmLogin={}",
      clientId,
      lastFmLogin,
    )
    val id = jobService.startForgottenObsessionsJob(clientId, lastFmLogin)
    logger.info("Forgotten obsessions job {} scheduled", id)
    return ResponseEntity.accepted().body(JobId(id))
  }

  @PostMapping("/private-mood-taxonomy")
  fun startPrivateMoodTaxonomy(
    @CookieValue("clientId") clientId: String,
    @CookieValue("lastFmToken", defaultValue = "") lastFmToken: String,
    @RequestBody request: StartRequest,
  ): ResponseEntity<JobId> {
    requireAuthorizedSession(clientId)
    val lastFmLogin = request.lastFmLogin.trim()
    if (lastFmLogin.isEmpty()) {
      logger.warn(
        "Rejecting private mood taxonomy job without Last.fm login for clientId={}",
        clientId,
      )
      return ResponseEntity.badRequest().build()
    }

    if (!lastFmAuthenticationService.isAuthorized(lastFmLogin, lastFmToken)) {
      logger.warn(
        "Rejecting private mood taxonomy job for unauthorized Last.fm login={} clientId={}",
        lastFmLogin,
        clientId,
      )
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
    }

    logger.info(
      "Starting private mood taxonomy job for clientId={} lastFmLogin={} playlistSize={}",
      clientId,
      lastFmLogin,
      request.playlistSize,
    )
    val id =
      jobService.startPrivateMoodTaxonomyJob(clientId, lastFmLogin, request.playlistSize ?: 50)
    logger.info("Private mood taxonomy job {} scheduled", id)
    return ResponseEntity.accepted().body(JobId(id))
  }

  private fun requireAuthorizedSession(clientId: String) {
    if (!spotifyAuthenticationService.isAuthorizedSession(clientId)) {
      logger.warn("Rejecting job request for unauthorized Spotify session")
      throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Spotify authentication required")
    }
  }

  @GetMapping("/{jobId}")
  fun getStatus(@PathVariable jobId: String): ResponseEntity<JobStatus> {
    val status = jobService.getJobStatus(jobId) ?: return ResponseEntity.notFound().build()
    return ResponseEntity.ok(status)
  }

  companion object {
    private val logger = LoggerFactory.getLogger(JobsController::class.java)
  }
}
