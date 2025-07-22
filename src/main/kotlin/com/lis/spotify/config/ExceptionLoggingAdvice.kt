package com.lis.spotify.config

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@ControllerAdvice
class ExceptionLoggingAdvice {
  private val logger = LoggerFactory.getLogger(ExceptionLoggingAdvice::class.java)

  @ExceptionHandler(Exception::class)
  fun handle(ex: Exception): ResponseEntity<String> {
    logger.error("Unhandled exception", ex)
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ex.message)
  }
}
