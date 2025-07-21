package com.lis.spotify.config

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
        val ctx = mockk<ApplicationContext>()
        every { ctx.getBean(Dummy::class.java) } returns bean
        val configurator = WebsocketSpringConfigurator()
        configurator.setApplicationContext(ctx)
        val result = configurator.getEndpointInstance(Dummy::class.java)
        assertSame(bean, result)
    }
}
