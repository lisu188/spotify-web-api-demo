package com.lis.spotify.integration

import java.net.URL
import org.htmlunit.BrowserVersion
import org.htmlunit.MockWebConnection
import org.htmlunit.WebClient
import org.htmlunit.WebRequest
import org.htmlunit.WebResponse
import org.htmlunit.html.HtmlButton
import org.htmlunit.html.HtmlElement
import org.htmlunit.html.HtmlInput
import org.htmlunit.html.HtmlPage
import org.htmlunit.util.WebConnectionWrapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(webEnvironment = RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IndexPageWorkflowIT {

  @LocalServerPort private var port: Int = 0

  private lateinit var webClient: WebClient
  private lateinit var mockWebConnection: MockWebConnection

  companion object {
    private val JQUERY_URL = URL("https://code.jquery.com/jquery-3.4.1.min.js")

    @JvmStatic
    @DynamicPropertySource
    fun props(registry: DynamicPropertyRegistry) {
      registry.add("BASE_URL") { "http://localhost" }
      registry.add("SPOTIFY_CLIENT_ID") { "id" }
      registry.add("SPOTIFY_CLIENT_SECRET") { "secret" }
      registry.add("LASTFM_API_KEY") { "key" }
      registry.add("LASTFM_API_SECRET") { "secret" }
      registry.add("LASTFM_API_URL") { "http://localhost/2.0/" }
      registry.add("LASTFM_AUTHORIZE_URL") { "http://localhost/auth" }
      registry.add("SPOTIFY_AUTH_URL") { "http://localhost/s-auth" }
      registry.add("SPOTIFY_TOKEN_URL") { "http://localhost/s-token" }
    }
  }

  @BeforeEach
  fun setUp() {
    webClient = WebClient(BrowserVersion.BEST_SUPPORTED)
    webClient.options.isJavaScriptEnabled = true
    webClient.options.isCssEnabled = false
    webClient.options.isThrowExceptionOnFailingStatusCode = false
    webClient.options.isThrowExceptionOnScriptError = false

    mockWebConnection = MockWebConnection()
    mockWebConnection.setResponse(
      JQUERY_URL,
      resourceText("htmlunit/jquery-shim.js"),
      "text/javascript",
    )
    mockWebConnection.setDefaultResponse(
      resourceText("htmlunit/noop.js"),
      200,
      "OK",
      "text/javascript",
    )

    val delegate = webClient.webConnection
    webClient.webConnection =
      object : WebConnectionWrapper(delegate) {
        override fun getResponse(request: WebRequest): WebResponse {
          val url = request.url
          return if (url.host != "localhost" || url.port != port) {
            mockWebConnection.getResponse(request)
          } else {
            super.getResponse(request)
          }
        }
      }
  }

  @AfterEach
  fun tearDown() {
    webClient.close()
  }

  @Test
  fun shouldToggleLastFmButtonWhenLoginIsVerified() {
    val page = loadIndexPage()
    installAjaxMock(
      page,
      """
        window.__uiTestMockAjax = function (options) {
          if (options.url.indexOf('/verifyLastFmId/missing-user') !== -1) {
            return { status: 200, responseText: 'false' };
          }
          if (options.url.indexOf('/verifyLastFmId/valid-user') !== -1) {
            return { status: 200, responseText: 'true' };
          }
          return { status: 404, responseText: 'false' };
        };
      """,
    )

    val login = page.getHtmlElementById("lastFmId") as HtmlInput
    val button = page.getHtmlElementById("lastfm") as HtmlButton
    val forgottenButton = page.getHtmlElementById("forgottenObsessions") as HtmlButton
    val status = page.getHtmlElementById("lastfmStatus") as HtmlElement

    assertTrue(button.isDisabled)
    assertTrue(forgottenButton.isDisabled)
    assertEquals("Enter your Last.fm login to enable Last.fm tools.", status.asNormalizedText())

    setInputValue(page, login, "missing-user")
    waitForJs()

    assertTrue(button.isDisabled)
    assertTrue(forgottenButton.isDisabled)
    assertEquals("Last.fm login not found.", status.asNormalizedText())

    setInputValue(page, login, "valid-user")
    waitForJs()

    assertFalse(button.isDisabled)
    assertFalse(forgottenButton.isDisabled)
    assertEquals("", status.asNormalizedText())
  }

  @Test
  fun shouldRenderProgressBarThroughSuccessfulRefresh() {
    val page = loadIndexPage()
    installAjaxMock(
      page,
      """
        window.__uiTestMockAjax = function (options) {
          if (options.url.indexOf('/verifyLastFmId/valid-user') !== -1) {
            return { status: 200, responseText: 'true' };
          }
          if (options.url.indexOf('/jobs') !== -1 && options.type === 'post') {
            return { status: 202, responseText: '{"jobId":"job-1"}', json: true };
          }
          if (options.url.indexOf('/jobs/job-1') !== -1) {
            return {
              status: 200,
              responseText: '{"jobId":"job-1","state":"COMPLETED","progressPercent":100,"message":"Yearly playlists refreshed","redirectUrl":null,"playlistIds":[]}'
            };
          }
          return { status: 404, responseText: 'null' };
        };
      """,
    )

    val login = page.getHtmlElementById("lastFmId") as HtmlInput
    val button = page.getHtmlElementById("lastfm") as HtmlButton
    val progress = page.getHtmlElementById("lastfmProgress") as HtmlElement
    val progressBar = page.getHtmlElementById("lastfmProgressBar") as HtmlElement
    val status = page.getHtmlElementById("lastfmStatus") as HtmlElement

    setInputValue(page, login, "valid-user")
    waitForJs()
    assertFalse(button.isDisabled)

    triggerClick(page, "lastfm")
    waitForJs(4000)

    assertFalse(progress.getAttribute("class").contains("d-none"))
    assertEquals("100", progressBar.getAttribute("aria-valuenow"))
    assertTrue(progressBar.getAttribute("style").contains("width: 100%"))
    assertTrue(progressBar.getAttribute("class").contains("bg-success"))
    assertFalse(progressBar.getAttribute("class").contains("progress-bar-animated"))
    assertEquals("Yearly playlists refreshed", status.asNormalizedText())
  }

  @Test
  fun shouldNavigateToLastFmAuthWhenRefreshNeedsAuthorization() {
    val page = loadIndexPage()
    installAjaxMock(
      page,
      """
        window.__uiTestMockAjax = function (options) {
          if (options.url.indexOf('/verifyLastFmId/valid-user') !== -1) {
            return { status: 200, responseText: 'true' };
          }
          if (options.url.indexOf('/jobs') !== -1 && options.type === 'post') {
            return { status: 202, responseText: '{"jobId":"job-2"}', json: true };
          }
          if (options.url.indexOf('/jobs/job-2') !== -1) {
            return {
              status: 200,
              responseText: '{"jobId":"job-2","state":"FAILED","progressPercent":40,"message":"Last.fm authentication required","redirectUrl":"/index.html?lastFmAuth=1","playlistIds":[]}'
            };
          }
          return { status: 404, responseText: 'null' };
        };
      """,
    )

    val login = page.getHtmlElementById("lastFmId") as HtmlInput
    val button = page.getHtmlElementById("lastfm") as HtmlButton

    setInputValue(page, login, "valid-user")
    waitForJs()
    assertFalse(button.isDisabled)

    triggerClick(page, "lastfm")
    waitForJs(3000)
    val status = page.getHtmlElementById("lastfmStatus") as HtmlElement
    val progressBar = page.getHtmlElementById("lastfmProgressBar") as HtmlElement

    assertFalse(button.isDisabled)
    assertTrue(progressBar.getAttribute("class").contains("bg-danger"))
    assertFalse(progressBar.getAttribute("class").contains("progress-bar-animated"))
    assertTrue(status.asNormalizedText().contains("Last.fm authentication required"))
    assertTrue(status.asNormalizedText().contains("Redirecting"))
  }

  @Test
  fun shouldRenderForgottenObsessionsPlaylistAfterSuccessfulJob() {
    val page = loadIndexPage()
    installAjaxMock(
      page,
      """
        window.__uiTestMockAjax = function (options) {
          if (options.url.indexOf('/verifyLastFmId/valid-user') !== -1) {
            return { status: 200, responseText: 'true' };
          }
          if (options.url.indexOf('/jobs/forgotten-obsessions') !== -1 && options.type === 'post') {
            return { status: 202, responseText: '{"jobId":"job-3"}', json: true };
          }
          if (options.url.indexOf('/jobs/job-3') !== -1) {
            return {
              status: 200,
              responseText: '{"jobId":"job-3","state":"COMPLETED","progressPercent":100,"message":"Forgotten obsessions playlist refreshed (12 tracks)","redirectUrl":null,"playlistIds":["playlist-1"]}'
            };
          }
          return { status: 404, responseText: 'null' };
        };
      """,
    )

    val login = page.getHtmlElementById("lastFmId") as HtmlInput
    val forgottenButton = page.getHtmlElementById("forgottenObsessions") as HtmlButton

    setInputValue(page, login, "valid-user")
    waitForJs()
    assertFalse(forgottenButton.isDisabled)

    triggerClick(page, "forgottenObsessions")
    waitForJs(4000)

    val status = page.getHtmlElementById("lastfmStatus") as HtmlElement
    val iframeCount =
      (page
          .executeJavaScript(
            "document.querySelectorAll('#forgottenObsessionsPlaylists iframe').length;"
          )
          .javaScriptResult as Number)
        .toInt()

    assertEquals("Forgotten obsessions playlist refreshed (12 tracks)", status.asNormalizedText())
    assertEquals(1, iframeCount)
  }

  private fun loadIndexPage(): HtmlPage {
    val page = webClient.getPage("http://localhost:$port/index.html") as HtmlPage
    waitForJs()
    return page
  }

  private fun setInputValue(page: HtmlPage, input: HtmlInput, value: String) {
    input.setValueAttribute(value)
    page.executeJavaScript(
      """
        var event = document.createEvent('Event');
        event.initEvent('input', true, true);
        document.getElementById('${input.id}').dispatchEvent(event);
      """
        .trimIndent()
    )
  }

  private fun installAjaxMock(page: HtmlPage, script: String) {
    page.executeJavaScript(script)
  }

  private fun triggerClick(page: HtmlPage, elementId: String) {
    page.executeJavaScript(
      """
        var event = document.createEvent('MouseEvents');
        event.initMouseEvent('click', true, true, window, 1, 0, 0, 0, 0,
          false, false, false, false, 0, null);
        document.getElementById('$elementId').dispatchEvent(event);
      """
        .trimIndent()
    )
  }

  private fun waitForJs(millis: Long = 1000) {
    webClient.waitForBackgroundJavaScript(millis)
  }

  private fun resourceText(path: String): String {
    val stream =
      requireNotNull(javaClass.classLoader.getResourceAsStream(path)) {
        "Missing test resource: $path"
      }
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
  }
}
