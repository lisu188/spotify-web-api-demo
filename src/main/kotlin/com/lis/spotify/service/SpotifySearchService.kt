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

import com.lis.spotify.domain.SearchResult
import com.lis.spotify.domain.Song
import kotlin.system.measureTimeMillis
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SpotifySearchService(val spotifyRestService: SpotifyRestService) {
  companion object {
    val SEARCH_URL = "https://api.spotify.com/v1/search?q={q}&type={type}"
  }

  fun doSearch(song: Song, clientId: String): SearchResult? {
    return spotifyRestService.doGet(
      SEARCH_URL,
      params = mapOf("q" to "track:${song.title} artist:${song.artist}", "type" to "track"),
      clientId = clientId,
    )
  }

  fun doSearch(values: List<Song>, clientId: String, progress: () -> Unit = {}): List<String> {
    LoggerFactory.getLogger(javaClass).info("doSearch: {} {}", clientId, values.size)
    lateinit var retVal: List<String>
    val time = measureTimeMillis {
      retVal =
        values
          .map {
            val doSearch = doSearch(it, clientId)
            progress()
            doSearch
          }
          .mapNotNull { it }
          .map { it.tracks.items }
          .mapNotNull { it.stream().findFirst().orElse(null) }
          .map { it.id }
          .distinct()
    }
    LoggerFactory.getLogger(javaClass).info("doSearch: {} {} took: {}", clientId, values.size, time)
    return retVal
  }
}
