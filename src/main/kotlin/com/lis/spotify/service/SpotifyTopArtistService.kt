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

import com.lis.spotify.domain.Artist
import com.lis.spotify.domain.Artists
import com.lis.spotify.domain.Track
import com.lis.spotify.domain.Tracks
import org.springframework.stereotype.Service


@Service
class SpotifyTopArtistService(var spotifyRestService: SpotifyRestService) {
    companion object {
        private val URL = "https://api.spotify.com/v1/me/top/artists?limit={limit}&time_range={time_range}"
        private val SHORT_TERM = "short_term"
        private val MID_TERM = "medium_term"
        private val LONG_TERM = "long_term"
    }


    private suspend fun getTopArtists(term: String, clientId: String): Artists {
        return spotifyRestService.doGet<Artists>(URL, params = mapOf("limit" to 10, "time_range" to term), clientId = clientId)
    }

    suspend fun getTopArtistsLongTerm(clientId: String): List<Artist> {
        return getTopArtists(LONG_TERM, clientId).items
    }

    suspend fun getTopArtistsMidTerm(clientId: String): List<Artist> {
        return getTopArtists(MID_TERM, clientId).items
    }

    suspend fun getTopArtistsShortTerm(clientId: String): List<Artist> {
        return getTopArtists(SHORT_TERM, clientId).items
    }
}


