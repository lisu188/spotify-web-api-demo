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

package com.lis.spotify.service


import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.atomic.AtomicInteger

@Service
@RestController
class SpotifyTopPlaylistsService(var spotifyPlaylistService: SpotifyPlaylistService,
                                 var spotifyTopTrackService: SpotifyTopTrackService,
                                 var lastFmService: LastFmService,
                                 var spotifySearchService: SpotifySearchService) {

    @PostMapping("/updateTopPlaylists")
    fun updateTopPlaylists(@CookieValue("clientId") clientId: String) {
        LoggerFactory.getLogger(javaClass).info("updateTopPlaylists: {}", clientId)

        runBlocking {
            val shortTerm = GlobalScope.async {
                spotifyTopTrackService.getTopTracksShortTerm(clientId).map { it.id }.toCollection(arrayListOf())
            }
            val midTerm = GlobalScope.async {
                spotifyTopTrackService.getTopTracksMidTerm(clientId).map { it.id }.toCollection(arrayListOf())
            }
            val longTerm = GlobalScope.async {
                spotifyTopTrackService.getTopTracksLongTerm(clientId).map { it.id }.toCollection(arrayListOf())
            }

            val launch = GlobalScope.launch {
                spotifyPlaylistService.modifyPlaylist(spotifyPlaylistService.getOrCreatePlaylist("Short Term", clientId).id, shortTerm.await(), clientId)
            }

            val launch1 = GlobalScope.launch {
                spotifyPlaylistService.modifyPlaylist(spotifyPlaylistService.getOrCreatePlaylist("Mid Term", clientId).id, midTerm.await(), clientId)
            }

            val launch2 = GlobalScope.launch {
                spotifyPlaylistService.modifyPlaylist(spotifyPlaylistService.getOrCreatePlaylist("Long Term", clientId).id, longTerm.await(), clientId)
            }

            val launch3 = GlobalScope.launch {
                val trackList1: List<String> = (shortTerm.await().asIterable()
                        + midTerm.await().asIterable()
                        + longTerm.await().asIterable())
                        .toCollection(arrayListOf())
                        .distinct()

                spotifyPlaylistService.modifyPlaylist(spotifyPlaylistService.getOrCreatePlaylist("Mixed Term", clientId).id, trackList1, clientId)
            }

            launch.join()
            launch1.join()
            launch2.join()
            launch3.join()
        }
    }

    suspend fun updateYearlyPlaylists(lastFmLogin: String,
                                      clientId: String,
                                      progressUpdater: (Pair<Int, Int>) -> Unit = {}) {
        LoggerFactory.getLogger(javaClass).info("updateYearlyPlaylists: {} {}", lastFmLogin, clientId)
        (2005..2020).map { year: Int ->
            GlobalScope.async {
                var progress = AtomicInteger()
                progressUpdater(Pair(year, progress.get()))
                val chartlist = lastFmService.yearlyChartlist(lastFmLogin, year)
                var trackList = spotifySearchService.doSearch(chartlist, clientId) { progressUpdater(Pair(year, progress.incrementAndGet() * 100 / chartlist.size)) }
                progressUpdater(Pair(year, 100))
                Pair(year, trackList);
            }
        }.map {
            it.await()
        }.filter { it.second.isNotEmpty() }
                .map {
                    spotifyPlaylistService.modifyPlaylist(spotifyPlaylistService.getOrCreatePlaylist("LAST.FM " + it.first, clientId).id,
                            it.second, clientId)
                }
    }
}
