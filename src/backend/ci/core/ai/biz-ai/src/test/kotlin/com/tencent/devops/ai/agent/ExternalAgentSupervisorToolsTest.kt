package com.tencent.devops.ai.agent

import com.tencent.devops.ai.context.AgentSessionContext
import com.tencent.devops.ai.external.ExternalAgentEvent
import com.tencent.devops.ai.external.ExternalAgentGateway
import com.tencent.devops.ai.external.ExternalAgentInput
import com.tencent.devops.ai.pojo.ExternalAgentInfo
import com.tencent.devops.ai.service.ExternalAgentService
import io.agentscope.core.agent.Agent
import io.agentscope.core.agui.event.AguiEvent
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks

class ExternalAgentSupervisorToolsTest {

    private val externalAgentService = mockk<ExternalAgentService>()
    private val gateway = mockk<ExternalAgentGateway>()

    @Test
    fun `callExternalAgent should collect stream events into structured result`() {
        every {
            externalAgentService.resolveEnabledConfigId(USER_ID, CONFIG_ID)
        } returns CONFIG_ID
        every {
            externalAgentService.getEnabled(USER_ID, CONFIG_ID)
        } returns enabledAgentInfo()
        every {
            gateway.stream(
                userId = USER_ID,
                configId = CONFIG_ID,
                input = any<ExternalAgentInput>()
            )
        } returns Flux.just(
            ExternalAgentEvent.TextDelta("hello"),
            ExternalAgentEvent.TextDelta(" world"),
            ExternalAgentEvent.ConversationId("conversation-1"),
            ExternalAgentEvent.Done
        )
        val tools = ExternalAgentSupervisorTools(
            externalAgentService = externalAgentService,
            gateway = gateway,
            sessionContext = AgentSessionContext(),
            userIdSupplier = { USER_ID },
            threadId = "thread-1"
        )

        val result = tools.callExternalAgent(
            configId = CONFIG_ID,
            query = "say hello"
        )

        assertTrue(result.contains("success"))
        assertTrue(result.contains("true"))
        assertTrue(result.contains("hello world"))
        assertTrue(result.contains("conversation-1"))
    }

    @Test
    fun `callExternalAgent should resolve agent name to config id`() {
        every {
            externalAgentService.resolveEnabledConfigId(USER_ID, AGENT_NAME)
        } returns CONFIG_ID
        every {
            externalAgentService.getEnabled(USER_ID, CONFIG_ID)
        } returns enabledAgentInfo()
        every {
            gateway.stream(
                userId = USER_ID,
                configId = CONFIG_ID,
                input = any<ExternalAgentInput>()
            )
        } returns Flux.just(ExternalAgentEvent.TextDelta("ok"), ExternalAgentEvent.Done)
        val tools = ExternalAgentSupervisorTools(
            externalAgentService = externalAgentService,
            gateway = gateway,
            sessionContext = AgentSessionContext(),
            userIdSupplier = { USER_ID },
            threadId = "thread-1"
        )

        val result = tools.callExternalAgent(
            configId = AGENT_NAME,
            query = "ping"
        )

        assertTrue(result.contains("ok"))
    }

    @Test
    fun `callExternalAgent should emit subagent style progress events`() {
        val sessionContext = AgentSessionContext()
        val sink = Sinks.many().multicast().onBackpressureBuffer<AguiEvent>()
        val emittedEvents = mutableListOf<AguiEvent>()
        sink.asFlux().subscribe { emittedEvents.add(it) }
        sessionContext.registerSink(
            mockk<Agent>(),
            AgentSessionContext.SinkInfo(
                sink = sink,
                threadId = THREAD_ID,
                runId = RUN_ID
            )
        )
        every {
            externalAgentService.resolveEnabledConfigId(USER_ID, CONFIG_ID)
        } returns CONFIG_ID
        every {
            externalAgentService.getEnabled(USER_ID, CONFIG_ID)
        } returns enabledAgentInfo()
        every {
            gateway.stream(
                userId = USER_ID,
                configId = CONFIG_ID,
                input = any<ExternalAgentInput>()
            )
        } returns Flux.just(
            ExternalAgentEvent.TextDelta("hello"),
            ExternalAgentEvent.ConversationId("conversation-1"),
            ExternalAgentEvent.Done
        )
        val tools = ExternalAgentSupervisorTools(
            externalAgentService = externalAgentService,
            gateway = gateway,
            sessionContext = sessionContext,
            userIdSupplier = { USER_ID },
            threadId = THREAD_ID
        )

        tools.callExternalAgent(
            configId = CONFIG_ID,
            query = "say hello"
        )

        assertEquals(2, emittedEvents.size)
        val progressEvent = emittedEvents[0] as? AguiEvent.Custom
        assertNotNull(progressEvent)
        assertEquals("subagent_event", progressEvent?.name)
        val progressData = progressEvent?.value as? Map<*, *>
        assertEquals(EXTERNAL_AGENT_NAME, progressData?.get("agentName"))
        assertEquals("ASSISTANT", progressData?.get("eventType"))
        assertEquals("hello", progressData?.get("content"))
        assertEquals(false, progressData?.get("isLast"))
        val doneEvent = emittedEvents[1] as? AguiEvent.Custom
        assertNotNull(doneEvent)
        assertEquals("subagent_event", doneEvent?.name)
        val doneData = doneEvent?.value as? Map<*, *>
        assertEquals(true, doneData?.get("isLast"))
    }

    @Test
    fun `callExternalAgent should fail when same name is ambiguous`() {
        every {
            externalAgentService.resolveEnabledConfigId(USER_ID, AGENT_NAME)
        } throws IllegalArgumentException("存在多个同名外部智能体，请改用配置 ID 调用")
        val tools = ExternalAgentSupervisorTools(
            externalAgentService = externalAgentService,
            gateway = gateway,
            sessionContext = AgentSessionContext(),
            userIdSupplier = { USER_ID },
            threadId = "thread-1"
        )

        val result = tools.callExternalAgent(
            configId = AGENT_NAME,
            query = "ping"
        )

        assertTrue(result.contains("false"))
        assertTrue(result.contains("存在多个同名外部智能体"))
    }

    companion object {
        private const val USER_ID = "tester"
        private const val CONFIG_ID = "config-1"
        private const val AGENT_NAME = "agent_name"
        private const val EXTERNAL_AGENT_NAME = "knot-agent"
        private const val THREAD_ID = "thread-1"
        private const val RUN_ID = "run-1"

        private fun enabledAgentInfo(): ExternalAgentInfo {
            return ExternalAgentInfo(
                id = CONFIG_ID,
                userId = USER_ID,
                agentName = EXTERNAL_AGENT_NAME,
                description = "demo",
                platform = "KNOT",
                agentId = "agent-id",
                apiUrl = "https://example.com",
                headers = null,
                enabled = true,
                createdTime = 0L,
                updatedTime = 0L
            )
        }
    }
}
