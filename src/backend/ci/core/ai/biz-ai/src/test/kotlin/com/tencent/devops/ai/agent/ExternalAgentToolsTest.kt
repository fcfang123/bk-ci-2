package com.tencent.devops.ai.agent

import com.tencent.devops.ai.context.AgentSessionContext
import com.tencent.devops.ai.agent.external.ExternalAgentEvent
import com.tencent.devops.ai.agent.external.ExternalAgentGateway
import com.tencent.devops.ai.agent.external.ExternalAgentInput
import com.tencent.devops.ai.agent.external.ExternalAgentTools
import com.tencent.devops.ai.pojo.ExternalAgentInfo
import com.tencent.devops.ai.service.ExternalAgentService
import io.agentscope.core.agent.Agent
import io.agentscope.core.agui.event.AguiEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks

class ExternalAgentToolsTest {

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
        val tools = createTools(threadId = "thread-1")

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
        val tools = createTools(threadId = "thread-1")

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
        val tools = createTools(sessionContext = sessionContext, threadId = THREAD_ID)

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
    fun `callExternalAgent should map thinking custom event to reasoning progress`() {
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
            ExternalAgentEvent.Custom(
                eventType = "THINKING_TEXT_MESSAGE_CONTENT",
                data = mapOf("content" to "用户")
            ),
            ExternalAgentEvent.Done
        )
        val tools = createTools(sessionContext = sessionContext, threadId = THREAD_ID)

        tools.callExternalAgent(
            configId = CONFIG_ID,
            query = "think"
        )

        assertEquals(2, emittedEvents.size)
        val progressEvent = emittedEvents[0] as? AguiEvent.Custom
        val progressData = progressEvent?.value as? Map<*, *>
        assertEquals("REASONING", progressData?.get("eventType"))
        assertEquals("用户", progressData?.get("content"))
        assertEquals("THINKING_TEXT_MESSAGE_CONTENT", progressData?.get("externalEventType"))
        assertEquals(false, progressData?.get("isLast"))
    }

    @Test
    fun `callExternalAgent should fail when same name is ambiguous`() {
        every {
            externalAgentService.resolveEnabledConfigId(USER_ID, AGENT_NAME)
        } throws IllegalArgumentException("存在多个同名外部智能体，请改用配置 ID 调用")
        val tools = createTools(threadId = "thread-1")

        val result = tools.callExternalAgent(
            configId = AGENT_NAME,
            query = "ping"
        )

        assertTrue(result.contains("false"))
        assertTrue(result.contains("存在多个同名外部智能体"))
    }

    @Test
    fun `callExternalAgent should pass supervisor provided chat history to gateway`() {
        every {
            externalAgentService.resolveEnabledConfigId(USER_ID, CONFIG_ID)
        } returns CONFIG_ID
        every {
            externalAgentService.getEnabled(USER_ID, CONFIG_ID)
        } returns enabledAgentInfo()
        val inputSlot = slot<ExternalAgentInput>()
        every {
            gateway.stream(
                userId = USER_ID,
                configId = CONFIG_ID,
                input = capture(inputSlot)
            )
        } returns Flux.just(ExternalAgentEvent.TextDelta("ok"), ExternalAgentEvent.Done)

        val tools = createTools(threadId = "thread-1")
        tools.callExternalAgent(
            configId = CONFIG_ID,
            query = "round 2",
            chatHistory = """[
                {"role":"user","content":"round 1"},
                {"role":"assistant","content":"answer 1"}
            ]"""
        )

        verify(exactly = 1) {
            gateway.stream(
                userId = USER_ID,
                configId = CONFIG_ID,
                input = any<ExternalAgentInput>()
            )
        }
        assertEquals(2, inputSlot.captured.chatHistory.size)
        assertEquals("user", inputSlot.captured.chatHistory[0]["role"])
        assertEquals("round 1", inputSlot.captured.chatHistory[0]["content"])
        assertEquals("assistant", inputSlot.captured.chatHistory[1]["role"])
        assertEquals("answer 1", inputSlot.captured.chatHistory[1]["content"])
        assertEquals("round 2", inputSlot.captured.query)
        assertTrue(inputSlot.captured.chatHistory.isNotEmpty())
    }

    private fun createTools(
        sessionContext: AgentSessionContext = AgentSessionContext(),
        threadId: String?
    ): ExternalAgentTools {
        return ExternalAgentTools(
            externalAgentService = externalAgentService,
            externalAgentGateway = gateway,
            sessionContext = sessionContext,
            userIdSupplier = { USER_ID },
            threadId = threadId
        )
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
