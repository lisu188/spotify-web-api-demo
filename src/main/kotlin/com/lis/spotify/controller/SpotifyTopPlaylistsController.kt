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

package com.lis.spotify.controller

import com.lis.spotify.service.LastFmService
import com.lis.spotify.service.SpotifyTopPlaylistsService
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class SpotifyTopPlaylistsController(
  val lastFmService: LastFmService,
  val spotifyTopPlaylistsService: SpotifyTopPlaylistsService,
) {
  @PostMapping("/updateTopPlaylists")
  fun updateTopPlaylists(@CookieValue("clientId") clientId: String): List<String> {
    logger.info("Updating top playlists for {}", clientId)
    logger.debug("updateTopPlaylists for {}", clientId)
    val result = spotifyTopPlaylistsService.updateTopPlaylists(clientId)
    logger.info("UpdateTopPlaylists result for {} -> {}", clientId, result)
    return result
  }

  companion object {
    private val logger = LoggerFactory.getLogger(SpotifyTopPlaylistsController::class.java)
  }
}
