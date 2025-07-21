package com.example.lastfm.controller

import com.lis.spotify.service.LastFmAuthenticationService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class LastFmAuthenticationControllerTest {
    private val service = mockk<LastFmAuthenticationService>()
    private val controller = LastFmAuthenticationController(service)

    @Test
    fun authenticateUserRedirects() {
        every { service.getAuthorizationUrl() } returns "http://x"
        val view = controller.authenticateUser()
        assertEquals("http://x", view.url)
    }

    @Test
    fun handleCallbackWithToken() {
        every { service.getSession("tok") } returns mapOf("k" to "v")
        val response = controller.handleCallback("tok")
        assertEquals(HttpStatus.OK, response.statusCode)
    }
}
