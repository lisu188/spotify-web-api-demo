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

import com.lis.spotify.domain.Track
import com.lis.spotify.domain.Tracks
import org.springframework.stereotype.Service


@Service
class SpotifyTopTrackService(var spotifyRestService: SpotifyRestService) {
    companion object {
        private val URL = "https://api.spotify.com/v1/me/top/tracks?limit={limit}&time_range={time_range}"
        private val SHORT_TERM = "short_term"
        private val MID_TERM = "medium_term"
        private val LONG_TERM = "long_term"
    }


    private suspend fun getTopTracks(term: String, clientId: String): Tracks {
        return spotifyRestService.doGet<Tracks>(
            URL,
            params = mapOf("limit" to 50, "time_range" to term),
            clientId = clientId
        )
    }

    suspend fun getTopTracksLongTerm(clientId: String): List<Track> {
        return getTopTracks(LONG_TERM, clientId).items
    }


    suspend fun getTopTracksMidTerm(clientId: String): List<Track> {
        return getTopTracks(MID_TERM, clientId).items
    }


    suspend fun getTopTracksShortTerm(clientId: String): List<Track> {
        return getTopTracks(SHORT_TERM, clientId).items
    }
}


