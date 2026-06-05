package com.tencent.devops.ai.external

import com.tencent.devops.ai.agent.external.ExternalAgentAdapter
import com.tencent.devops.ai.agent.external.ExternalAgentErrorCategory
import com.tencent.devops.ai.agent.external.ExternalAgentEvent
import com.tencent.devops.ai.agent.external.ExternalAgentGateway
import com.tencent.devops.ai.agent.external.ExternalAgentGatewayException
import com.tencent.devops.ai.agent.external.ExternalAgentInput
import com.tencent.devops.ai.agent.external.ExternalAgentRequest
import com.tencent.devops.ai.pojo.ExternalAgentInfo
import com.tencent.devops.ai.pojo.ExternalAgentPlatform
import com.tencent.devops.ai.properties.ExternalAgentGatewayProperties
import com.tencent.devops.ai.service.ExternalAgentService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import java.time.Duration

class ExternalAgentGatewayTest {

    private val externalAgentService = mockk<ExternalAgentService>()

    @Test
    fun `stream should reject when gateway is disabled`() {
        val gateway = newGateway(
            adapters = emptyList(),
            properties = ExternalAgentGatewayProperties(enabled = false)
        )

        val error = assertThrows(ExternalAgentGatewayException::class.java) {
            gateway.stream(USER_ID, CONFIG_ID, request()).blockLast()
        }

        assertEquals(ExternalAgentErrorCategory.CONFIG_INVALID, error.category)
    }

    @Test
    fun `stream should route to matching platform adapter`() {
        every { externalAgentService.getEnabled(USER_ID, CONFIG_ID) } returns
            config(platform = ExternalAgentPlatform.KNOT)
        val gateway = newGateway(
            adapters = listOf(
                FakeAdapter(
                    ExternalAgentPlatform.KNOT,
                    listOf(ExternalAgentEvent.TextDelta("hello"))
                )
            )
        )

        val events = gateway.stream(USER_ID, CONFIG_ID, request()).collectList().block()

        assertEquals(listOf(ExternalAgentEvent.TextDelta("hello")), events)
    }

    @Test
    fun `stream should fail before upstream call when platform is unsupported`() {
        every { externalAgentService.getEnabled(USER_ID, CONFIG_ID) } returns
            config(platform = ExternalAgentPlatform.KNOT)
        val gateway = newGateway(
            adapters = listOf(FakeAdapter(ExternalAgentPlatform.BKAIDEV, emptyList()))
        )

        val error = assertThrows(ExternalAgentGatewayException::class.java) {
            gateway.stream(USER_ID, CONFIG_ID, request()).blockLast()
        }

        assertEquals(ExternalAgentErrorCategory.UNSUPPORTED_PLATFORM, error.category)
    }

    @Test
    fun `stream should map first token timeout to timeout category`() {
        every { externalAgentService.getEnabled(USER_ID, CONFIG_ID) } returns
            config(platform = ExternalAgentPlatform.KNOT)
        val gateway = newGateway(
            adapters = listOf(NeverAdapter(ExternalAgentPlatform.KNOT)),
            properties = ExternalAgentGatewayProperties(firstTokenTimeoutSeconds = 1)
        )

        val error = assertThrows(ExternalAgentGatewayException::class.java) {
            gateway.stream(USER_ID, CONFIG_ID, request()).blockLast()
        }

        assertEquals(ExternalAgentErrorCategory.TIMEOUT, error.category)
    }

    @Test
    fun `stream should fail when total stream duration exceeds limit`() {
        every { externalAgentService.getEnabled(USER_ID, CONFIG_ID) } returns
            config(platform = ExternalAgentPlatform.KNOT)
        val gateway = newGateway(
            adapters = listOf(DelayedAdapter(ExternalAgentPlatform.KNOT, Duration.ofSeconds(3))),
            properties = ExternalAgentGatewayProperties(streamTimeoutSeconds = 2)
        )

        val error = assertThrows(ExternalAgentGatewayException::class.java) {
            gateway.stream(USER_ID, CONFIG_ID, request()).blockLast()
        }

        assertEquals(ExternalAgentErrorCategory.TIMEOUT, error.category)
    }

    @Test
    fun `stream should fail when upstream stalls after first event`() {
        every { externalAgentService.getEnabled(USER_ID, CONFIG_ID) } returns
            config(platform = ExternalAgentPlatform.KNOT)
        val gateway = newGateway(
            adapters = listOf(StalledAfterFirstAdapter(ExternalAgentPlatform.KNOT)),
            properties = ExternalAgentGatewayProperties(streamTimeoutSeconds = 1)
        )

        val error = assertThrows(ExternalAgentGatewayException::class.java) {
            gateway.stream(USER_ID, CONFIG_ID, request()).blockLast()
        }

        assertEquals(ExternalAgentErrorCategory.TIMEOUT, error.category)
    }

    @Test
    fun `stream should complete when total stream duration is within limit`() {
        every { externalAgentService.getEnabled(USER_ID, CONFIG_ID) } returns
            config(platform = ExternalAgentPlatform.KNOT)
        val gateway = newGateway(
            adapters = listOf(DelayedAdapter(ExternalAgentPlatform.KNOT, Duration.ofMillis(100))),
            properties = ExternalAgentGatewayProperties(streamTimeoutSeconds = 120)
        )

        val events = gateway.stream(USER_ID, CONFIG_ID, request()).collectList().block()

        assertEquals(
            listOf(
                ExternalAgentEvent.TextDelta("first"),
                ExternalAgentEvent.TextDelta("second")
            ),
            events
        )
    }

    @Test
    fun `stream should fail when response exceeds max chars`() {
        every { externalAgentService.getEnabled(USER_ID, CONFIG_ID) } returns
            config(platform = ExternalAgentPlatform.KNOT)
        val gateway = newGateway(
            adapters = listOf(
                FakeAdapter(
                    ExternalAgentPlatform.KNOT,
                    listOf(ExternalAgentEvent.TextDelta("too-long"))
                )
            ),
            properties = ExternalAgentGatewayProperties(maxResponseChars = 3)
        )

        val error = assertThrows(ExternalAgentGatewayException::class.java) {
            gateway.stream(USER_ID, CONFIG_ID, request()).blockLast()
        }

        assertEquals(ExternalAgentErrorCategory.UPSTREAM_ERROR, error.category)
    }

    @Test
    fun `stream should retain original cause when mapping upstream error`() {
        every { externalAgentService.getEnabled(USER_ID, CONFIG_ID) } returns
            config(platform = ExternalAgentPlatform.KNOT)
        val gateway = newGateway(
            adapters = listOf(
                FailingAdapter(
                    ExternalAgentPlatform.KNOT,
                    IllegalStateException("boom")
                )
            )
        )

        val error = assertThrows(ExternalAgentGatewayException::class.java) {
            gateway.stream(USER_ID, CONFIG_ID, request()).blockLast()
        }

        assertEquals(ExternalAgentErrorCategory.UPSTREAM_ERROR, error.category)
        assertEquals(IllegalStateException::class.java, error.cause!!::class.java)
        assertEquals("boom", error.cause!!.message)
    }

    private fun newGateway(
        adapters: List<ExternalAgentAdapter>,
        properties: ExternalAgentGatewayProperties = ExternalAgentGatewayProperties()
    ): ExternalAgentGateway {
        return ExternalAgentGateway(
            externalAgentService = externalAgentService,
            adapters = adapters,
            properties = properties
        )
    }

    private fun request(): ExternalAgentInput {
        return ExternalAgentInput(query = "hello")
    }

    private fun config(platform: ExternalAgentPlatform): ExternalAgentInfo {
        return ExternalAgentInfo(
            id = CONFIG_ID,
            userId = USER_ID,
            agentName = "agent",
            description = "desc",
            platform = platform,
            agentId = "agent-id",
            apiUrl = "https://example.com/agent",
            headers = null,
            enabled = true,
            createdTime = 1L,
            updatedTime = 1L
        )
    }

    private class FakeAdapter(
        private val platform: ExternalAgentPlatform,
        private val events: List<ExternalAgentEvent>
    ) : ExternalAgentAdapter {
        override fun platform(): ExternalAgentPlatform = platform

        override fun stream(request: ExternalAgentRequest): Flux<ExternalAgentEvent> {
            return Flux.fromIterable(events)
        }
    }

    private class NeverAdapter(
        private val platform: ExternalAgentPlatform
    ) : ExternalAgentAdapter {
        override fun platform(): ExternalAgentPlatform = platform

        override fun stream(request: ExternalAgentRequest): Flux<ExternalAgentEvent> {
            return Flux.never()
        }
    }

    private class StalledAfterFirstAdapter(
        private val platform: ExternalAgentPlatform
    ) : ExternalAgentAdapter {
        override fun platform(): ExternalAgentPlatform = platform

        override fun stream(request: ExternalAgentRequest): Flux<ExternalAgentEvent> {
            return Flux.concat(
                Flux.just(ExternalAgentEvent.TextDelta("first")),
                Flux.never()
            )
        }
    }

    private class DelayedAdapter(
        private val platform: ExternalAgentPlatform,
        private val delayAfterFirst: Duration
    ) : ExternalAgentAdapter {
        override fun platform(): ExternalAgentPlatform = platform

        override fun stream(request: ExternalAgentRequest): Flux<ExternalAgentEvent> {
            return Flux.concat(
                Flux.just(ExternalAgentEvent.TextDelta("first")),
                Flux.just(ExternalAgentEvent.TextDelta("second"))
                    .delayElements(delayAfterFirst)
            )
        }
    }

    private class FailingAdapter(
        private val platform: ExternalAgentPlatform,
        private val error: Throwable
    ) : ExternalAgentAdapter {
        override fun platform(): ExternalAgentPlatform = platform

        override fun stream(request: ExternalAgentRequest): Flux<ExternalAgentEvent> {
            return Flux.error(error)
        }
    }

    companion object {
        private const val USER_ID = "tester"
        private const val CONFIG_ID = "config-1"
    }
}
