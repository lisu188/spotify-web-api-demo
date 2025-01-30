/*
 * MIT License
 *
 * Copyright (c) 2019 Andrzej Lis
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.lis.spotify.endpoint

import com.lis.spotify.config.WebsocketSpringConfigurator
import com.lis.spotify.service.SpotifyTopPlaylistsService
import javax.websocket.*
import javax.websocket.server.PathParam
import javax.websocket.server.ServerEndpoint
import org.springframework.stereotype.Component

@Component
@ServerEndpoint("/socket/{login}", configurator = WebsocketSpringConfigurator::class)
class YearlyPlaylistsEndpoint(var spotifyTopPlaylistsService: SpotifyTopPlaylistsService) {

  private lateinit var lastFmLogin: String
  private lateinit var clientId: String
  var progressMap = mutableMapOf<Int, Int>()

  @OnOpen
  fun onOpen(session: Session, config: EndpointConfig, @PathParam("login") lastFmLogin: String) {
    this.clientId = config.userProperties["clientId"] as String
    this.lastFmLogin = lastFmLogin
  }

  private fun updateProgress(it: Pair<Int, Int>) {
    synchronized(this@YearlyPlaylistsEndpoint) { progressMap[it.first] = it.second }
  }

  @OnMessage
  fun onMessage(session: Session, message: String) {
    spotifyTopPlaylistsService.updateYearlyPlaylists(clientId, { updateProgress(it) }, lastFmLogin)
  }

  @OnClose fun onClose(session: Session) {}

  @OnError fun onError(session: Session, e: Throwable) {}
}
