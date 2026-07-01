package com.lis.spotify.controller

import com.lis.spotify.domain.JobId
import com.lis.spotify.domain.JobStatus
import com.lis.spotify.logging.asSafeClientIdForLogs
import com.lis.spotify.service.JobLimitExceededException
import com.lis.spotify.service.JobService
import com.lis.spotify.service.LastFmAuthenticationService
import com.lis.spotify.service.SpotifyAuthenticationService
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
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
    @CookieValue("clientId", defaultValue = "") clientId: String,
    @CookieValue("lastFmToken", defaultValue = "") lastFmToken: String,
    @RequestBody request: StartRequest,
  ): ResponseEntity<JobId> {
    requireAuthorizedSession(clientId)
    val lastFmLogin = request.lastFmLogin.trim()
    if (lastFmLogin.isEmpty()) {
      logger.warn(
        "Rejecting yearly job without Last.fm login for clientId={}",
        clientId.asSafeClientIdForLogs(),
      )
      return ResponseEntity.badRequest().build()
    }

    if (!spotifyAuthenticationService.isAuthorized(clientId)) {
      logger.warn(
        "Rejecting yearly job for unauthorized clientId={}",
        clientId.asSafeClientIdForLogs(),
      )
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
    }

    if (!lastFmAuthenticationService.isAuthorized(lastFmLogin, lastFmToken)) {
      logger.warn("Rejecting yearly job for unauthorized Last.fm login={}", lastFmLogin)
      return lastFmAuthenticationRequired(lastFmLogin)
    }

    return startJob("yearly", clientId) {
      jobService.startYearlyJob(clientId, lastFmLogin, lastFmToken)
    }
  }

  @PostMapping("/forgotten-obsessions")
  fun startForgottenObsessions(
    @CookieValue("clientId", defaultValue = "") clientId: String,
    @CookieValue("lastFmToken", defaultValue = "") lastFmToken: String,
    @RequestBody request: StartRequest,
  ): ResponseEntity<JobId> {
    requireAuthorizedSession(clientId)
    val lastFmLogin = request.lastFmLogin.trim()
    if (lastFmLogin.isEmpty()) {
      logger.warn(
        "Rejecting forgotten obsessions job without Last.fm login for clientId={}",
        clientId.asSafeClientIdForLogs(),
      )
      return ResponseEntity.badRequest().build()
    }

    if (!spotifyAuthenticationService.isAuthorized(clientId)) {
      logger.warn(
        "Rejecting forgotten obsessions job for unauthorized clientId={}",
        clientId.asSafeClientIdForLogs(),
      )
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
    }

    if (!lastFmAuthenticationService.isAuthorized(lastFmLogin, lastFmToken)) {
      logger.warn(
        "Rejecting forgotten obsessions job for unauthorized Last.fm login={}",
        lastFmLogin,
      )
      return lastFmAuthenticationRequired(lastFmLogin)
    }

    return startJob("forgotten obsessions", clientId) {
      jobService.startForgottenObsessionsJob(clientId, lastFmLogin, lastFmToken)
    }
  }

  @PostMapping("/private-mood-taxonomy")
  fun startPrivateMoodTaxonomy(
    @CookieValue("clientId", defaultValue = "") clientId: String,
    @CookieValue("lastFmToken", defaultValue = "") lastFmToken: String,
    @RequestBody request: StartRequest,
  ): ResponseEntity<JobId> {
    requireAuthorizedSession(clientId)
    val lastFmLogin = request.lastFmLogin.trim()
    if (lastFmLogin.isEmpty()) {
      logger.warn(
        "Rejecting private mood taxonomy job without Last.fm login for clientId={}",
        clientId.asSafeClientIdForLogs(),
      )
      return ResponseEntity.badRequest().build()
    }

    if (!spotifyAuthenticationService.isAuthorized(clientId)) {
      logger.warn(
        "Rejecting private mood taxonomy job for unauthorized clientId={}",
        clientId.asSafeClientIdForLogs(),
      )
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
    }

    if (!lastFmAuthenticationService.isAuthorized(lastFmLogin, lastFmToken)) {
      logger.warn(
        "Rejecting private mood taxonomy job for unauthorized Last.fm login={} clientId={}",
        lastFmLogin,
        clientId.asSafeClientIdForLogs(),
      )
      return lastFmAuthenticationRequired(lastFmLogin)
    }

    return startJob("private mood taxonomy", clientId) {
      jobService.startPrivateMoodTaxonomyJob(
        clientId,
        lastFmLogin,
        request.playlistSize ?: 50,
        lastFmToken,
      )
    }
  }

  private fun requireAuthorizedSession(clientId: String) {
    if (!spotifyAuthenticationService.isAuthorizedSession(clientId)) {
      logger.warn("Rejecting job request for unauthorized Spotify session")
      throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Spotify authentication required")
    }
  }

  @GetMapping("/{jobId}")
  fun getStatus(
    @CookieValue("clientId", defaultValue = "") clientId: String,
    @PathVariable jobId: String,
  ): ResponseEntity<JobStatus> {
    requireAuthorizedSession(clientId)
    val status =
      jobService.getJobStatus(jobId, clientId) ?: return ResponseEntity.notFound().build()
    return ResponseEntity.ok(status)
  }

  private fun lastFmAuthenticationRequired(lastFmLogin: String): ResponseEntity<JobId> {
    val encodedLogin = URLEncoder.encode(lastFmLogin, StandardCharsets.UTF_8)
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
      .header(HttpHeaders.LOCATION, "/auth/lastfm?lastFmLogin=$encodedLogin")
      .build()
  }

  private fun startJob(
    jobName: String,
    clientId: String,
    starter: () -> String,
  ): ResponseEntity<JobId> {
    logger.info("Starting {} job for clientId={}", jobName, clientId.asSafeClientIdForLogs())
    return try {
      val id = starter()
      logger.info("{} job {} scheduled", jobName, id)
      ResponseEntity.accepted().body(JobId(id))
    } catch (e: JobLimitExceededException) {
      logger.warn(
        "Rejecting {} job for clientId={}: {}",
        jobName,
        clientId.asSafeClientIdForLogs(),
        e.message,
      )
      ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build()
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(JobsController::class.java)
  }
}
