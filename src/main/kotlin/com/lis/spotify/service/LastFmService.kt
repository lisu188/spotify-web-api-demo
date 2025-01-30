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

import com.lis.spotify.domain.Song
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class LastFmService {

  suspend fun yearlyChartlist(spotifyClientId: String, year: Int, lastFmLogin: String): List<Song> {
    LoggerFactory.getLogger(javaClass).info("yearlyChartlist: {} {}", lastFmLogin, year)
    return (1..7)
      .map { page: Int -> GlobalScope.async { yearlyChartlist(lastFmLogin, year, page) } }
      .map { it.await() }
      .flatten()
  }

  private fun yearlyChartlist(lastFmLogin: String, year: Int, page: Int): List<Song> {
    LoggerFactory.getLogger(javaClass).info("yearlyChartlist: {} {} {}", lastFmLogin, year, page)
    val ret: MutableList<Song> = mutableListOf()
    try {
      val get =
        Jsoup.connect(
            "https://www.last.fm/user/$lastFmLogin/library/tracks?from=$year-01-01&rangetype=year&page=$page"
          )
          .get()
      get.run {
        select(".chartlist-row").forEach {
          try {
            ret.add(parseElement(it))
          } catch (e: Exception) {
            LoggerFactory.getLogger(javaClass).error("Cannot parse: {}", it, e)
          }
        }
      }
      return ret
    } catch (e: Exception) {
      return emptyList()
    }
  }

  fun globalChartlist(lastFmLogin: String, page: Int = 1): List<Song> {
    LoggerFactory.getLogger(javaClass).info("globalChartlist: {} {}", lastFmLogin, page)
    val ret: MutableList<Song> = mutableListOf()
    val get = Jsoup.connect("https://www.last.fm/user/$lastFmLogin/library/tracks?page=$page").get()
    get.run {
      select(".chartlist-row").forEach {
        try {
          ret.add(parseElement(it))
        } catch (e: Exception) {
          LoggerFactory.getLogger(javaClass).error("Cannot parse: {}", it, e)
        }
      }
    }
    return ret
  }

  private fun parseElement(it: Element) =
    Song(
      artist = it.children()[5].children()[0].text().orEmpty(),
      title = it.children()[4].children()[0].text().orEmpty(),
    )
}
