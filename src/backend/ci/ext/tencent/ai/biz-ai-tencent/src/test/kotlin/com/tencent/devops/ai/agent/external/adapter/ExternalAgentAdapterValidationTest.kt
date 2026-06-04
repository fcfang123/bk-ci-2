package com.tencent.devops.ai.agent.external.adapter

import com.tencent.devops.ai.agent.external.ExternalAgentConfigValidationContext
import com.tencent.devops.ai.agent.external.ExternalAgentErrorCategory
import com.tencent.devops.ai.agent.external.ExternalAgentGatewayException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExternalAgentAdapterValidationTest {

    @Test
    fun `BkAiDev validation should reject plugin invoke endpoint for streaming`() {
        val error = assertThrows(ExternalAgentGatewayException::class.java) {
            BkAiDevExternalAgentAdapter().validateConfig(
                ExternalAgentConfigValidationContext(
                    platform = "BKAIDEV",
                    agentId = "agent-id",
                    apiUrl = "https://example.com/prod/invoke/plugin",
                    headers = """{"X-Bkapi-Authorization":"{\"access_token\":\"token\"}"}"""
                )
            )
        }

        assertEquals(ExternalAgentErrorCategory.CONFIG_INVALID, error.category)
    }

    @Test
    fun `BkAiDev validation should accept user token header`() {
        assertDoesNotThrow {
            BkAiDevExternalAgentAdapter().validateConfig(
                ExternalAgentConfigValidationContext(
                    platform = "BKAIDEV",
                    agentId = "agent-id",
                    apiUrl = "https://example.com/chat_completion",
                    headers = """{"X-Bkapi-Authorization":"{\"access_token\":\"token\"}"}"""
                )
            )
        }
    }

    @Test
    fun `Knot validation should require AG-UI endpoint and auth headers`() {
        val error = assertThrows(ExternalAgentGatewayException::class.java) {
            KnotExternalAgentAdapter().validateConfig(
                ExternalAgentConfigValidationContext(
                    platform = "KNOT",
                    agentId = "agent-id",
                    apiUrl = "https://example.com/api/v1/agents/plain/agent-id",
                    headers = """{"x-knot-api-token":"token","x-knot-api-user":"tester"}"""
                )
            )
        }

        assertEquals(ExternalAgentErrorCategory.CONFIG_INVALID, error.category)
    }

    @Test
    fun `Knot validation should accept AG-UI endpoint and required headers`() {
        assertDoesNotThrow {
            KnotExternalAgentAdapter().validateConfig(
                ExternalAgentConfigValidationContext(
                    platform = "KNOT",
                    agentId = "agent-id",
                    apiUrl = "https://example.com/apigw/api/v1/agents/agui/agent-id",
                    headers = """{"x-knot-api-token":"token","x-knot-api-user":"tester"}"""
                )
            )
        }
    }
}
