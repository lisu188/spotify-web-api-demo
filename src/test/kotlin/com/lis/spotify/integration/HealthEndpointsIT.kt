package com.lis.spotify.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointsIT @Autowired constructor(private val rest: TestRestTemplate) {
  @Test
  fun healthProbesArePublicWithoutExposingDetails() {
    for (path in listOf("health", "health/liveness", "health/readiness")) {
      val response = rest.getForEntity("/actuator/$path", Map::class.java)
      assertEquals(HttpStatus.OK, response.statusCode)
      assertEquals("UP", response.body?.get("status"))
      assertFalse(response.body.orEmpty().containsKey("components"))
    }
  }

  @Test
  fun operationalAndEnvironmentEndpointsAreNotExposed() {
    for (path in listOf("env", "configprops", "heapdump", "mappings")) {
      val response = rest.getForEntity("/actuator/$path", String::class.java)
      assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
      assertFalse(response.body.toString().contains("SPOTIFY_CLIENT_SECRET"))
    }
  }
}
