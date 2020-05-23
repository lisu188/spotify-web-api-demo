/*
 * MIT License
 *
 * Copyright (c) 2020 Andrzej Lis
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.lis.spotify.scheduler

import com.lis.spotify.service.LastFmLoginService
import com.lis.spotify.service.SpotifyAuthenticationService
import com.lis.spotify.service.SpotifyTopPlaylistsService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class RefreshPlaylistsScheduler(var spotifyAuthenticationService: SpotifyAuthenticationService,
                                var spotifyTopPlaylistsService: SpotifyTopPlaylistsService,
                                var lastFmLoginService: LastFmLoginService) {

    @Scheduled(initialDelay = 1000 * 60 * 30, fixedDelay = 1000 * 60 * 60)
    fun refreshSpotify() {
        spotifyAuthenticationService.getAuthTokens()
                .map { it.clientId }
                .forEach {
                    it?.let { clientId ->
                        LoggerFactory.getLogger(javaClass).info("Refreshing Spotify playlists for user: {}", clientId)
                        spotifyTopPlaylistsService.updateTopPlaylists(clientId)
                    }
                }
    }

    @Scheduled(initialDelay = 1000 * 60 * 45, fixedDelay = 1000 * 60 * 60)
    fun refreshLastFm() {
        spotifyAuthenticationService.getAuthTokens()
                .map { it.clientId }
                .forEach {
                    it?.let { clientId ->
                        lastFmLoginService.getLastFmLogin(clientId)?.let {
                            LoggerFactory.getLogger(javaClass).info("Refreshing last.fm playlists for user: {} {}", clientId, it)
                            spotifyTopPlaylistsService.updateTopPlaylists(clientId)
                        }
                    }
                }
    }
}