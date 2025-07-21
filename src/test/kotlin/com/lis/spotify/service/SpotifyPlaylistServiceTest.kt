package com.lis.spotify.service

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpotifyPlaylistServiceTest {
    private val rest = mockk<SpotifyRestService>(relaxed = true)
    private val service = SpotifyPlaylistService(rest)

    @Test
    fun getDiffReturnsRemovedElements() {
        val method = SpotifyPlaylistService::class.java.getDeclaredMethod("getDiff", List::class.java, List::class.java)
        method.isAccessible = true
        val result = method.invoke(service, listOf("a", "b"), listOf("b")) as List<*>
        assertEquals(listOf("a"), result)
    }
}
