package com.lis.spotify.config

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class BrowserSecurityFilterTest {
  @Test
  fun rejectsCrossSiteWritesEvenWithAjaxHeader() {
    for (path in listOf("/jobs", "/updateTopPlaylists", "/api/logout", "/verifyLastFmId/user")) {
      val request = MockHttpServletRequest("POST", path)
      request.addHeader("X-Requested-With", "XMLHttpRequest")
      request.addHeader("Origin", "https://attacker.example")
      val response = MockHttpServletResponse()
      BrowserSecurityFilter()
        .doFilter(request, response, FilterChain { _, _ -> fail("Request must be blocked") })
      assertEquals(403, response.status)
    }
  }

  @Test
  fun rejectsSimpleFormPostsAndCrossSiteFetchMetadata() {
    for (crossSite in listOf(false, true)) {
      val request = MockHttpServletRequest("POST", "/jobs")
      if (crossSite) {
        request.addHeader("X-Requested-With", "XMLHttpRequest")
        request.addHeader("Sec-Fetch-Site", "cross-site")
      }
      val response = MockHttpServletResponse()
      BrowserSecurityFilter()
        .doFilter(request, response, FilterChain { _, _ -> fail("Request must be blocked") })
      assertEquals(403, response.status)
    }
  }

  @Test
  fun acceptsSameOriginAjaxAndHeaderAuthenticatedSchedulerRequests() {
    for (path in listOf("/jobs", "/refreshConfiguredTopPlaylists")) {
      val request = MockHttpServletRequest("POST", path)
      request.servletPath = path
      if (path == "/jobs") {
        request.addHeader("X-Requested-With", "XMLHttpRequest")
        request.addHeader("Origin", "http://localhost")
      }
      val response = MockHttpServletResponse()
      var reachedController = false
      BrowserSecurityFilter()
        .doFilter(request, response, FilterChain { _, _ -> reachedController = true })
      assertTrue(reachedController)
      assertEquals("nosniff", response.getHeader("X-Content-Type-Options"))
      assertEquals("DENY", response.getHeader("X-Frame-Options"))
    }
  }

  @Test
  fun comparesSecretsWithoutAcceptingMissingOrDifferentValues() {
    assertTrue(WebSecurity.secretsEqual("secret-ą", "secret-ą"))
    for (other in listOf(null, "", "secret-a", "short", "secret-ą-longer")) {
      assertFalse(WebSecurity.secretsEqual("secret-ą", other))
    }
    assertFalse(WebSecurity.secretsEqual(null, null))
    assertFalse(WebSecurity.secretsEqual("", ""))
  }

  @Test
  fun comparesCompleteOriginsIncludingSchemePortAndHost() {
    assertTrue(WebSecurity.sameOrigin("https://music.example", "https://music.example:443/jobs"))
    assertFalse(
      WebSecurity.sameOrigin("https://music.example.attacker.example", "https://music.example/jobs")
    )
    assertFalse(WebSecurity.sameOrigin("http://music.example", "https://music.example/jobs"))
    assertFalse(WebSecurity.sameOrigin("https://music.example:8443", "https://music.example/jobs"))
    assertFalse(WebSecurity.sameOrigin("null", "https://music.example/jobs"))
    assertFalse(WebSecurity.sameOrigin("https://music.example/path", "https://music.example/jobs"))
  }

  @Test
  fun secureCookiesFollowConfiguredHttpsAndIgnoreRawForwardedHeader() {
    val previous = System.getProperty("BASE_URL")
    try {
      val request = MockHttpServletRequest()
      request.addHeader("X-Forwarded-Proto", "http")
      System.setProperty("BASE_URL", "https://music.example")
      assertTrue(WebSecurity.secureCookies(request))
      System.setProperty("BASE_URL", "http://localhost")
      request.removeHeader("X-Forwarded-Proto")
      request.addHeader("X-Forwarded-Proto", "https")
      assertFalse(WebSecurity.secureCookies(request))
      request.isSecure = true
      assertTrue(WebSecurity.secureCookies(request))
    } finally {
      if (previous == null) System.clearProperty("BASE_URL")
      else System.setProperty("BASE_URL", previous)
    }
  }
}
