package com.lis.spotify.endpoint

import com.lis.spotify.service.SpotifyTopPlaylistsService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import javax.websocket.EndpointConfig
import javax.websocket.Session
import org.junit.jupiter.api.Test

class YearlyPlaylistsEndpointTest {
  @Test
  fun onMessageTriggersUpdate() {
    val service = mockk<SpotifyTopPlaylistsService>(relaxed = true)
    val endpoint = YearlyPlaylistsEndpoint(service)
    val config = mockk<EndpointConfig>()
    every { config.userProperties["clientId"] } returns "cid"
    endpoint.onOpen(mockk<Session>(), config, "login")

    endpoint.onMessage(mockk<Session>(), "msg")

    verify { service.updateYearlyPlaylists("cid", any(), "login") }
  }
}
