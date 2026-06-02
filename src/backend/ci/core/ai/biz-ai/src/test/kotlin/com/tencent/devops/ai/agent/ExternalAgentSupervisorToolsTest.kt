package com.tencent.devops.ai.agent

import com.tencent.devops.ai.context.AgentSessionContext
import com.tencent.devops.ai.external.ExternalAgentEvent
import com.tencent.devops.ai.external.ExternalAgentGateway
import com.tencent.devops.ai.external.ExternalAgentInput
import com.tencent.devops.ai.service.ExternalAgentService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux

class ExternalAgentSupervisorToolsTest {

    private val externalAgentService = mockk<ExternalAgentService>()
    private val gateway = mockk<ExternalAgentGateway>()

    @Test
    fun `callExternalAgent should collect stream events into structured result`() {
        every {
            externalAgentService.resolveEnabledConfigId(USER_ID, CONFIG_ID)
        } returns CONFIG_ID
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
    }
}
