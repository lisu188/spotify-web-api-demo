package com.lis.spotify.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** Real-browser workflow coverage lives in tests/frontend.test.cjs. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IndexPageWorkflowIT {
  @Autowired private lateinit var restTemplate: TestRestTemplate

  @Test
  fun shouldServePublicStudioWithEveryPlaylistWorkflow() {
    val response = restTemplate.getForEntity("/", String::class.java)
    assertEquals(HttpStatus.OK, response.statusCode)
    val html = requireNotNull(response.body)
    assertTrue(html.contains("Replay — Your personal playlist studio"))
    assertTrue(html.contains("href=\"/auth/spotify\""))
    for (id in
      listOf("top", "lastfm", "forgottenObsessions", "privateMoodTaxonomy", "bandPlaylist")) {
      assertTrue(Regex("<button[^>]*id=\"$id\"[^>]*disabled").containsMatchIn(html), id)
    }
    assertTrue(html.contains("for=\"lastFmId\""))
    assertTrue(html.contains("for=\"bandNames\""))
    assertTrue(html.contains("aria-live=\"polite\""))
    assertFalse(html.contains("jquery"))
    assertFalse(html.contains("bootstrap"))
  }

  @Test
  fun shouldServeSelfContainedStudioAssets() {
    for ((path, contentType) in
      listOf(
        "/index.js" to "javascript",
        "/index.css" to "text/css",
        "/favicon.svg" to "image/svg+xml",
      )) {
      val response = restTemplate.getForEntity(path, String::class.java)
      assertEquals(HttpStatus.OK, response.statusCode, path)
      assertTrue(response.headers.contentType.toString().contains(contentType), path)
      assertTrue(requireNotNull(response.body).isNotBlank(), path)
    }
  }

  companion object {
    @JvmStatic
    @DynamicPropertySource
    fun props(registry: DynamicPropertyRegistry) {
      registry.add("BASE_URL") { "http://localhost" }
      registry.add("SPOTIFY_CLIENT_ID") { "id" }
      registry.add("SPOTIFY_CLIENT_SECRET") { "secret" }
      registry.add("LASTFM_API_KEY") { "key" }
      registry.add("LASTFM_API_SECRET") { "secret" }
      registry.add("LASTFM_API_URL") { "http://localhost/2.0/" }
      registry.add("LASTFM_AUTHORIZE_URL") { "http://localhost/auth" }
      registry.add("SPOTIFY_AUTH_URL") { "http://localhost/s-auth" }
      registry.add("SPOTIFY_TOKEN_URL") { "http://localhost/s-token" }
    }
  }
}
