package com.lis.spotify.logging

import java.security.MessageDigest

private const val SESSION_ID_PREFIX = "session_"

fun String?.asSafeClientIdForLogs(): String {
  if (this.isNullOrBlank()) {
    return "[blank]"
  }

  return if (startsWith(SESSION_ID_PREFIX)) {
    "${SESSION_ID_PREFIX}[redacted:${sha256Prefix()}]"
  } else {
    this
  }
}

private fun String.sha256Prefix(): String {
  val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
  return digest.take(6).joinToString("") { "%02x".format(it) }
}
