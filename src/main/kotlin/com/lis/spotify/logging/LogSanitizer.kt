package com.lis.spotify.logging

import java.security.MessageDigest

private const val SESSION_ID_PREFIX = "session_"

/**
 * Renders a `clientId` safe for logging. Session-bearing identifiers (which double as bearer
 * session cookies) are redacted to a short stable hash so logs never emit the raw credential, while
 * non-session identifiers and blank/null values render readably.
 */
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
