package com.lis.spotify.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogSanitizerTest {
  @Test
  fun redactsOpaqueSessionIds() {
    val sessionId = "session_abcdefghijklmnopqrstuvwxyz0123456789"

    val sanitized = sessionId.asSafeClientIdForLogs()

    assertTrue(sanitized.startsWith("session_[redacted:"))
    assertFalse(sanitized.contains("abcdefghijklmnopqrstuvwxyz0123456789"))
  }

  @Test
  fun leavesNonSessionClientIdsVisible() {
    assertEquals("spotify-user-id", "spotify-user-id".asSafeClientIdForLogs())
  }

  @Test
  fun rendersBlankClientIdsWithoutRevealingValues() {
    assertEquals("[blank]", "".asSafeClientIdForLogs())
    assertEquals("[blank]", null.asSafeClientIdForLogs())
  }
}
