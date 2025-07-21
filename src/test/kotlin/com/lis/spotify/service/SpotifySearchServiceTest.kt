package com.lis.spotify.service

import com.lis.spotify.domain.Album
import com.lis.spotify.domain.Artist
import com.lis.spotify.domain.SearchResult
import com.lis.spotify.domain.SearchResultInternal
import com.lis.spotify.domain.Song
import com.lis.spotify.domain.Track
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SpotifySearchServiceTest {
    @Test
    fun serviceInstantiates() {
        val service = SpotifySearchService(mockk(relaxed = true))
        assertNotNull(service)
    }

    @Test
    fun searchListReturnsIds() {
        val rest = mockk<SpotifyRestService>()
        val service = SpotifySearchService(rest)
        val track = Track("1", "t", listOf(Artist("2","a")), Album("3","al", emptyList()))
        val result = SearchResult(SearchResultInternal(listOf(track)))
        every { rest.doRequest(any<() -> Any>()) } returns result
        val ids = service.doSearch(listOf(Song("a","t")), "cid")
        assertEquals(listOf("1"), ids)
    }
}
