package com.lis.spotify.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lis.spotify.domain.Album
import com.lis.spotify.domain.Artist
import com.lis.spotify.domain.Playlist
import com.lis.spotify.domain.PlaylistTrack
import com.lis.spotify.domain.PlaylistTracks
import com.lis.spotify.domain.Playlists
import com.lis.spotify.domain.SearchResult
import com.lis.spotify.domain.SearchResultInternal
import com.lis.spotify.domain.Song
import com.lis.spotify.domain.Track
import com.lis.spotify.persistence.InMemoryLastFmRecentTracksCacheStore
import com.lis.spotify.persistence.InMemorySpotifySearchCacheStore
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URI
import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.web.client.RestTemplate

/** Opt-in, entirely synthetic benchmark shared unchanged by both measured Git revisions. */
class YearlyPlaylistBenchmark {
  @Test
  fun benchmarkSelectedScenarios() {
    (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger("ROOT").level = Level.WARN
    val selected = System.getProperty("benchmark.scenario", "all")
    val trials = System.getProperty("benchmark.trials", "3").toInt()
    val trialOffset = System.getProperty("benchmark.trialOffset", "0").toInt()
    require(trials > 0)
    val scenarios = listOf("sparse-unique", "dense-unique", "sparse-repeated", "dense-repeated")
    require(selected == "all" || scenarios.any { selected == "$it-cold" || selected == "$it-warm" })
    for (fixtureName in scenarios) {
      for (warm in listOf(false, true)) {
        val scenario = fixtureName + if (warm) "-warm" else "-cold"
        if (selected != "all" && selected != scenario) continue
        val fixture = Fixture(fixtureName)
        val warmContext = if (warm) Context(fixture).also { it.generate() } else null
        try {
          // One untimed warmup in this JVM; cold measured trials still get fresh caches.
          runTrial(fixture, scenario, 0, warmContext, record = false)
          for (trial in 1..trials) runTrial(
            fixture,
            scenario,
            trial + trialOffset,
            warmContext,
            record = true,
          )
        } finally {
          warmContext?.close()
          clearAllMocks()
        }
      }
    }
  }

  private fun runTrial(
    fixture: Fixture,
    scenario: String,
    trial: Int,
    warmContext: Context?,
    record: Boolean,
  ) {
    val context = warmContext ?: Context(fixture)
    context.resetPlaylistsAndCounters()
    val progress = Collections.synchronizedList(mutableListOf<Int>())
    val loadAverageStart = loadAverage()
    val sampler = ResourceSampler()
    val cpuStart = sampler.cpuNanos()
    val start = System.nanoTime()
    var failure: Throwable? = null
    try {
      context.generate { percent, _ -> progress += percent }
    } catch (ex: Throwable) {
      failure = ex
    }
    val elapsedNanos = System.nanoTime() - start
    val cpuNanos = sampler.cpuNanos() - cpuStart
    sampler.close()
    try {
      failure?.let { throw it }
      fixture.songsByYear.forEach { (year, songs) ->
        assertEquals(
          songs.map(::trackId).distinct(),
          context.playlists[year],
          "Playlist order $year",
        )
      }
      assertEquals(0, progress.first())
      assertEquals(100, progress.last())
      assertTrue(progress.zipWithNext().all { (before, after) -> before <= after })
      assertEquals(1, context.count("spotify.playlist.list"))
      assertEquals(0, context.count("spotify.playlist.create"))
      assertEquals(0, context.count("spotify.playlist.delete"))
      val expectedPages = if (warmContext == null) 22 + fixture.populatedYearCount else 0
      assertEquals(expectedPages, context.count("lastfm.page"), "Last.fm request count")
      if (warmContext != null) assertEquals(0, context.count("spotify.search"))
      val optimized = System.getProperty("benchmark.variant", "unknown") == "optimized"
      if (optimized) {
        assertTrue(
          context.peak("spotify.search") <= 8,
          "Shared search concurrency must stay bounded",
        )
        if (warmContext == null)
          assertEquals(fixture.uniqueQueryCount, context.count("spotify.search"))
      }
      assertEquals(0, context.count("spotify.playlist.read.empty-year"))
    } catch (ex: Throwable) {
      failure = ex
    }
    if (record) {
      val result =
        linkedMapOf<String, Any?>(
          "variant" to System.getProperty("benchmark.variant", "unknown"),
          "revision" to System.getProperty("benchmark.revision", "unrecorded"),
          "scenario" to scenario,
          "trial" to trial,
          "recordedAt" to Instant.now().toString(),
          "success" to (failure == null),
          "failure" to failure?.toString(),
          "elapsedMs" to elapsedNanos / 1_000_000.0,
          "cpuMs" to cpuNanos / 1_000_000.0,
          "loadAverageStart" to loadAverageStart,
          "loadAverageEnd" to loadAverage(),
          "peakHeapBytes" to sampler.peakHeap.get(),
          "peakRssBytes" to sampler.peakRss.get(),
          "peakThreads" to sampler.peakThreads.get(),
          "requests" to context.counts.mapValues { it.value.get() }.toSortedMap(),
          "peakConcurrency" to context.peaks.mapValues { it.value.get() }.toSortedMap(),
          "queryRequestCount" to context.queryCounts.values.sumOf { it.get() },
          "uniqueQueriesRequested" to context.queryCounts.size,
          "duplicateQueryRequests" to
            context.queryCounts.values.sumOf { (it.get() - 1).coerceAtLeast(0) },
          "authHeaderCalls" to context.authCalls.get(),
          "populatedYears" to fixture.populatedYearCount,
          "sourceScrobbles" to fixture.songsByYear.values.sumOf { it.size },
          "uniqueFixtureQueries" to fixture.uniqueQueryCount,
          "playlistFingerprint" to fixture.fingerprint(),
          "progressUpdates" to progress.size,
          "javaVersion" to System.getProperty("java.version"),
          "os" to System.getProperty("os.name"),
          "availableProcessors" to Runtime.getRuntime().availableProcessors(),
          "maxHeapBytes" to Runtime.getRuntime().maxMemory(),
          "cpuAffinity" to procValue("Cpus_allowed_list"),
          "latencyMs" to mapOf("spotify.search" to 20, "lastfm.page" to 5, "spotify.playlist" to 5),
        )
      val output = File(System.getProperty("benchmark.output", "build/yearly-benchmark"))
      val variant = result.getValue("variant")
      val destination = File(output, "$variant/$scenario/trial-$trial.json")
      destination.parentFile.mkdirs()
      mapper.writerWithDefaultPrettyPrinter().writeValue(destination, result)
      println(
        "BENCHMARK $variant $scenario trial=$trial elapsedMs=" +
          result["elapsedMs"] +
          " success=" +
          result["success"]
      )
    }
    if (warmContext == null) {
      context.close()
      clearAllMocks()
    }
    failure?.let { throw it }
  }

  private class Fixture(val name: String) {
    val songsByYear =
      (2005..2026).associateWith { year ->
        if (name.startsWith("sparse") && year != 2026) emptyList()
        else
          (0 until 250).map { index ->
            val key =
              when {
                name == "dense-repeated" -> "shared " + index % 50
                name == "sparse-repeated" -> "solo " + index % 25
                else -> "$year $index"
              }
            Song(
              "artist $key",
              "track $key",
              Instant.parse("$year-12-01T00:00:00Z").epochSecond - index,
            )
          }
      }
    val populatedYearCount = songsByYear.values.count { it.isNotEmpty() }
    val uniqueQueryCount = songsByYear.values.flatten().map(::query).distinct().size
    val songsByQuery = songsByYear.values.flatten().associateBy(::query)

    fun fingerprint(): String {
      val text =
        songsByYear.entries.joinToString("|") { (year, songs) ->
          "$year:" + songs.map(::trackId).distinct().joinToString(",")
        }
      return java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
    }
  }

  private class Context(val fixture: Fixture) {
    val playlists = ConcurrentHashMap<Int, MutableList<String>>()
    val counts = ConcurrentHashMap<String, AtomicInteger>()
    val peaks = ConcurrentHashMap<String, AtomicInteger>()
    private val active = ConcurrentHashMap<String, AtomicInteger>()
    val queryCounts = ConcurrentHashMap<String, AtomicInteger>()
    val authCalls = AtomicInteger()
    private val auth = mockk<SpotifyAuthenticationService>()
    private val spotifyTransport = mockk<RestTemplate>()
    private val builder = mockk<RestTemplateBuilder>()
    private val lastFmTransport = mockk<RestTemplate>()
    private val clock = Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC)
    private val yearly: SpotifyTopPlaylistsService
    private val rest: SpotifyRestService

    init {
      every { builder.connectTimeout(any()) } returns builder
      every { builder.readTimeout(any()) } returns builder
      every { builder.requestFactory(HttpComponentsClientHttpRequestFactory::class.java) } returns
        builder
      every { builder.requestFactory(any<Supplier<ClientHttpRequestFactory>>()) } returns builder
      every { builder.build() } returns spotifyTransport
      every { auth.getHeaders(any<String>()) } answers
        {
          authCalls.incrementAndGet()
          HttpHeaders().apply { setBearerAuth("synthetic-benchmark-token") }
        }
      every {
        spotifyTransport.exchange<Any>(
          any<String>(),
          any<HttpMethod>(),
          any<HttpEntity<*>>(),
          any<ParameterizedTypeReference<Any>>(),
          any<Map<String, *>>(),
        )
      } answers
        {
          val url = firstArg<String>()
          val method = secondArg<HttpMethod>()
          val entity = thirdArg<HttpEntity<*>>()
          val params = arg<Map<String, *>>(4)
          ResponseEntity(spotifyResponse(url, method, entity.body, params), HttpStatus.OK)
        }
      every { lastFmTransport.getForObject(any<URI>(), String::class.java) } answers
        {
          val uri = firstArg<URI>()
          request("lastfm.page", 5) {
            val params =
              uri.rawQuery.split("&").associate {
                val parts = it.split("=", limit = 2)
                parts[0] to URLDecoder.decode(parts[1], Charsets.UTF_8)
              }
            val year =
              Instant.ofEpochSecond(params.getValue("from").toLong()).atOffset(ZoneOffset.UTC).year
            val page = params.getValue("page").toInt()
            val songs = fixture.songsByYear.getValue(year)
            mapper.writeValueAsString(
              mapOf(
                "recenttracks" to
                  mapOf(
                    "@attr" to mapOf("totalPages" to if (songs.isEmpty()) "1" else "2"),
                    "track" to
                      songs.drop((page - 1) * 200).take(200).map {
                        mapOf(
                          "artist" to mapOf("#text" to it.artist),
                          "name" to it.title,
                          "date" to mapOf("uts" to it.playedAtEpochSecond.toString()),
                        )
                      },
                  )
              )
            )
          }
        }
      rest = SpotifyRestService(builder, auth)
      val lastFm =
        LastFmService(mockk(relaxed = true), InMemoryLastFmRecentTracksCacheStore(), clock)
      lastFm.rest = lastFmTransport
      yearly =
        SpotifyTopPlaylistsService(
          SpotifyPlaylistService(rest),
          mockk(relaxed = true),
          lastFm,
          SpotifySearchService(rest, InMemorySpotifySearchCacheStore(), clock),
        )
      yearly.currentYearProvider = { 2026 }
      yearly.firstSupportedYear = 2005
      yearly.yearlyParallelism = 4
      resetPlaylistsAndCounters()
    }

    fun resetPlaylistsAndCounters() {
      playlists.clear()
      (2005..2026).forEach { playlists[it] = Collections.synchronizedList(mutableListOf()) }
      counts.clear()
      peaks.clear()
      active.clear()
      queryCounts.clear()
      authCalls.set(0)
    }

    fun generate(progress: (Int, String) -> Unit = { _, _ -> }) {
      yearly.updateYearlyPlaylists("synthetic-client", "synthetic-listener", progress = progress)
    }

    fun close() {
      rest.javaClass.methods
        .firstOrNull { it.name == "close" && it.parameterCount == 0 }
        ?.invoke(rest)
    }

    fun count(key: String) = counts[key]?.get() ?: 0

    fun peak(key: String) = peaks[key]?.get() ?: 0

    private fun <T> request(key: String, latency: Long, operation: () -> T): T {
      counts.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
      val current = active.computeIfAbsent(key) { AtomicInteger() }
      val n = current.incrementAndGet()
      peaks.computeIfAbsent(key) { AtomicInteger() }.accumulateAndGet(n, ::maxOf)
      try {
        Thread.sleep(latency)
        return operation()
      } finally {
        current.decrementAndGet()
      }
    }

    private fun spotifyResponse(
      url: String,
      method: HttpMethod,
      body: Any?,
      params: Map<String, *>,
    ): Any {
      if (url.contains("/search")) {
        val key = params.getValue("q").toString()
        queryCounts.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
        return request("spotify.search", 20) {
          val song = fixture.songsByQuery.getValue(key)
          SearchResult(
            SearchResultInternal(
              listOf(
                track("wrong", "unrelated", "unrelated"),
                track(trackId(song), song.title, song.artist),
              )
            )
          )
        }
      }
      if (url.endsWith("/me/playlists")) {
        require(method == HttpMethod.GET) { "Unexpected playlist creation" }
        return request("spotify.playlist.list", 5) {
          Playlists((2005..2026).map { Playlist("year-$it", "LAST.FM $it") }, null)
        }
      }
      require(url.contains("/playlists/") && url.contains("/items")) {
        "Unexpected transport URL: $url"
      }
      val year =
        params["id"]?.toString()?.removePrefix("year-")?.toInt()
          ?: Regex("/playlists/year-(\\d+)/").find(url)!!.groupValues[1].toInt()
      val current = playlists.getValue(year)
      return when (method) {
        HttpMethod.GET ->
          request("spotify.playlist.read", 5) {
            if (fixture.songsByYear.getValue(year).isEmpty()) {
              counts
                .computeIfAbsent("spotify.playlist.read.empty-year") { AtomicInteger() }
                .incrementAndGet()
            }
            val offset = Regex("offset=(\\d+)").find(url)?.groupValues?.get(1)?.toInt() ?: 0
            val snapshot = synchronized(current) { current.toList() }
            val next =
              if (offset + 100 < snapshot.size) {
                "https://api.spotify.com/v1/playlists/year-$year/items?offset=" + (offset + 100)
              } else null
            PlaylistTracks(
              snapshot.drop(offset).take(100).map { PlaylistTrack(track(it, it, "artist")) },
              next,
            )
          }
        HttpMethod.POST ->
          request("spotify.playlist.add", 5) {
            val uris = (body as Map<*, *>)["uris"] as List<*>
            require(uris.size <= 100)
            current.addAll(uris.map { it.toString().removePrefix("spotify:track:") })
            emptyMap<String, String>()
          }
        else -> error("Unexpected playlist mutation: $method")
      }
    }
  }

  private class ResourceSampler : AutoCloseable {
    val peakHeap = AtomicLong()
    val peakRss = AtomicLong()
    val peakThreads = AtomicLong()
    private val running = AtomicBoolean(true)
    private val os =
      ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
    private val worker =
      Thread(
          {
            while (running.get()) {
              sample()
              Thread.sleep(10)
            }
          },
          "benchmark-resource-sampler",
        )
        .apply {
          isDaemon = true
          start()
        }

    fun cpuNanos() = os.processCpuTime

    private fun sample() {
      peakHeap.accumulateAndGet(ManagementFactory.getMemoryMXBean().heapMemoryUsage.used, ::maxOf)
      peakThreads.accumulateAndGet(
        ManagementFactory.getThreadMXBean().threadCount.toLong(),
        ::maxOf,
      )
      val rss = procValue("VmRSS").substringBefore(" ").toLongOrNull()?.times(1024) ?: -1
      peakRss.accumulateAndGet(rss, ::maxOf)
    }

    override fun close() {
      running.set(false)
      worker.join()
      sample()
    }
  }

  companion object {
    @JvmStatic
    fun main(args: Array<String>) {
      YearlyPlaylistBenchmark().benchmarkSelectedScenarios()
    }

    private fun loadAverage(): List<Double> =
      runCatching {
          File("/proc/loadavg").readText().trim().split(" ").take(3).map { it.toDouble() }
        }
        .getOrDefault(emptyList())

    private val mapper = jacksonObjectMapper()

    private fun query(song: Song) = "track:" + song.title + " artist:" + song.artist

    private fun trackId(song: Song) = song.title.replace(" ", "-")

    private fun track(id: String, title: String, artist: String) =
      Track(id, title, listOf(Artist("artist-id", artist)), Album("album", "album", emptyList()))

    private fun procValue(key: String): String =
      runCatching {
          File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("$key:") }?.substringAfter(":")?.trim().orEmpty()
          }
        }
        .getOrDefault("")
  }
}
