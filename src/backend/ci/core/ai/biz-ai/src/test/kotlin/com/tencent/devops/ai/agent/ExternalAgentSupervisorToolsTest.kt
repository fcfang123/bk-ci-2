package com.tencent.devops.ai.agent

import com.tencent.devops.ai.context.AgentSessionContext
import com.tencent.devops.ai.external.ExternalAgentEvent
import com.tencent.devops.ai.external.ExternalAgentGateway
import com.tencent.devops.ai.external.ExternalAgentInput
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux

class ExternalAgentSupervisorToolsTest {

    private val gateway = mockk<ExternalAgentGateway>()

    @Test
    fun `callExternalAgent should collect stream events into structured result`() {
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

    companion object {
        private const val USER_ID = "tester"
        private const val CONFIG_ID = "config-1"
    }
}
