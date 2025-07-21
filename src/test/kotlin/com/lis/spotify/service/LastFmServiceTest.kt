package com.lis.spotify.service

import com.lis.spotify.domain.Song
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestTemplate

class LastFmServiceTest {
    @Test
    fun yearlyChartlistParsesSongs() {
        val rest = mockk<RestTemplate>()
        val service = LastFmService()
        val field = LastFmService::class.java.getDeclaredField("rest")
        field.isAccessible = true
        field.set(service, rest)
        val map = mapOf(
            "recenttracks" to mapOf(
                "totalPages" to "1",
                "track" to listOf(mapOf("artist" to mapOf("#text" to "A"), "name" to "T"))
            )
        )
        every { rest.getForObject(any<java.net.URI>(), Map::class.java) } returns map
        val songs = service.yearlyChartlist("cid", 2020, "login")
        assertEquals(listOf(Song("A", "T")), songs)
    }
}
