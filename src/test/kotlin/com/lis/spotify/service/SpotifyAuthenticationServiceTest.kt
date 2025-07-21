package com.lis.spotify.service

import com.lis.spotify.domain.AuthToken
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.web.client.RestTemplate

class SpotifyAuthenticationServiceTest {
    private val restTemplate = mockk<RestTemplate>()
    private val builder = mockk<RestTemplateBuilder>()
    private val service = SpotifyAuthenticationService(builder)

    init {
        every { builder.build() } returns restTemplate
    }

    @Test
    fun setAndGetAuthTokenWorks() {
        val token = AuthToken("a","b","c",0,"refresh","cid")
        service.setAuthToken(token)
        assertEquals(token, service.getAuthToken("cid"))
    }

    @Test
    fun isAuthorizedChecksCache() {
        assertFalse(service.isAuthorized("cid"))
        service.setAuthToken(AuthToken("a","b","c",0,"r","cid"))
        assertTrue(service.isAuthorized("cid"))
    }
}
