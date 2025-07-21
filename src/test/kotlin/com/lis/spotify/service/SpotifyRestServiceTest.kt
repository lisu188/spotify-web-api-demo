package com.lis.spotify.service

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder

class SpotifyRestServiceTest {
    @Test
    fun serviceCanBeCreated() {
        val service = SpotifyRestService(RestTemplateBuilder(), mockk(relaxed = true))
        assertNotNull(service)
    }
}
