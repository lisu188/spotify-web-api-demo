package com.lis.spotify.config

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Aspect
@Component
class LoggingAspect {
  @Around("execution(* com.lis.spotify..*(..)) && !within(com.lis.spotify.config.LoggingAspect)")
  fun logAround(pjp: ProceedingJoinPoint): Any? {
    val logger = LoggerFactory.getLogger(pjp.target.javaClass)
    val signature = pjp.signature.toShortString()
    logger.debug("Entering {} with args {}", signature, pjp.args.joinToString())
    return try {
      val result = pjp.proceed()
      logger.debug("Exiting {} with result {}", signature, result)
      result
    } catch (throwable: Throwable) {
      logger.error("Exception in {}", signature, throwable)
      throw throwable
    }
  }
}
