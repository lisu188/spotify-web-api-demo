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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component

@Component
@ServerEndpoint("/socket/{login}", configurator = WebsocketSpringConfigurator::class)
class YearlyPlaylistsEndpoint(var spotifyTopPlaylistsService: SpotifyTopPlaylistsService) {

  var progressMap = mutableMapOf<Int, Int>()

  @OnOpen
  fun onOpen(session: Session, config: EndpointConfig, @PathParam("login") lastFmLogin: String) {
    GlobalScope.launch {
      val launch = launch {
        while (true) {
          delay(1000)
          reportProgress(session)
        }
      }
      spotifyTopPlaylistsService.updateYearlyPlaylists(
        config.userProperties["clientId"] as String,
        { updateProgress(it) },
        lastFmLogin,
      )
      launch.cancel()
      launch.join()
      reportProgress(session)
      synchronized(this@YearlyPlaylistsEndpoint) { session.close() }
    }
  }

  private fun updateProgress(it: Pair<Int, Int>) {
    synchronized(this@YearlyPlaylistsEndpoint) { progressMap[it.first] = it.second }
  }

  private fun reportProgress(session: Session) {
    synchronized(this@YearlyPlaylistsEndpoint) {
      session.basicRemote.sendText((progressMap.values.sum() / progressMap.size).toString())
    }
  }

  @OnMessage fun onMessage(session: Session, payload: String) {}

  @OnClose fun onClose(session: Session) {}

  @OnError fun onError(session: Session, throwable: Throwable) {}
}
