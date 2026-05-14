package com.lis.spotify.config

import com.lis.spotify.service.AuthenticationRequiredException
import com.lis.spotify.service.LastFmException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.server.ResponseStatusException

@ControllerAdvice
class ExceptionLoggingAdvice {
  private val logger = LoggerFactory.getLogger(ExceptionLoggingAdvice::class.java)

  @ExceptionHandler(LastFmException::class)
  fun handleLastFm(ex: LastFmException): ResponseEntity<Void> {
    logger.warn("Last.fm exception {} {}", ex.code, ex.message)
    return if (ex.code == 17) {
      ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(HttpHeaders.LOCATION, "/auth/lastfm")
        .build()
    } else {
      ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
    }
  }

  @ExceptionHandler(AuthenticationRequiredException::class)
  fun handleAuthenticationRequired(ex: AuthenticationRequiredException): ResponseEntity<Void> {
    logger.warn("{} authentication is required", ex.provider)
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
      .header(HttpHeaders.LOCATION, "/auth/spotify")
      .build()
  }

  @ExceptionHandler(ResponseStatusException::class)
  fun handleResponseStatus(ex: ResponseStatusException): ResponseEntity<String> {
    logger.warn("Request rejected with status {} {}", ex.statusCode, ex.reason)
    return ResponseEntity.status(ex.statusCode).body(ex.reason)
  }

  @ExceptionHandler(Exception::class)
  fun handle(ex: Exception): ResponseEntity<String> {
    logger.error("Unhandled exception", ex)
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ex.message)
  }
}
