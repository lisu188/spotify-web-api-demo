package com.lis.spotify.config

import io.mockk.every
import io.mockk.mockk
import jakarta.websocket.server.HandshakeRequest
import jakarta.websocket.server.ServerEndpointConfig
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.AutowireCapableBeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.web.socket.server.standard.ServerEndpointExporter

class WebSocketConfigTest {
  @Test
  fun serverEndpointExporterReturnsExporter() {
    val exporter = WebSocketConfig().serverEndpointExporter()
    assertTrue(exporter is ServerEndpointExporter)
  }

  class Dummy

  @Test
  fun getEndpointInstanceReturnsBean() {
    val bean = Dummy()
    val factory = mockk<AutowireCapableBeanFactory>()
    every { factory.createBean(Dummy::class.java) } returns bean
    val ctx = mockk<ApplicationContext>()
    every { ctx.autowireCapableBeanFactory } returns factory
    val configurator = WebsocketSpringConfigurator()
    configurator.setApplicationContext(ctx)
    val result = configurator.getEndpointInstance(Dummy::class.java)
    assertSame(bean, result)
  }

  class DummyRequest(val request: jakarta.servlet.http.HttpServletRequest) : HandshakeRequest {
    override fun getHeaders(): MutableMap<String, MutableList<String>> = mutableMapOf()

    override fun getParameterMap(): MutableMap<String, MutableList<String>> = mutableMapOf()

    override fun getQueryString(): String? = null

    override fun getRequestURI(): java.net.URI? = null

    override fun getUserPrincipal(): java.security.Principal? = null

    override fun isUserInRole(role: String?): Boolean = false

    override fun getHttpSession(): Any? = null
  }

  @Test
  fun modifyHandshakeExtractsClientId() {
    val httpReq = mockk<jakarta.servlet.http.HttpServletRequest>()
    every { httpReq.cookies } returns arrayOf(jakarta.servlet.http.Cookie("clientId", "cid"))
    val req = DummyRequest(httpReq)
    val config = ServerEndpointConfig.Builder.create(Dummy::class.java, "/").build()
    val configurator = WebsocketSpringConfigurator()
    configurator.modifyHandshake(config, req, mockk())
    assertSame("cid", config.userProperties["clientId"])
  }
}
