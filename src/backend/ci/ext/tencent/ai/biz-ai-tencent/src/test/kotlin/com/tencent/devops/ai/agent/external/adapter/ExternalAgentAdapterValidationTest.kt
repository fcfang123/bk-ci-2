package com.tencent.devops.ai.agent.external.adapter

import com.tencent.devops.ai.agent.external.ExternalAgentConfigValidationContext
import com.tencent.devops.ai.agent.external.ExternalAgentErrorCategory
import com.tencent.devops.ai.agent.external.ExternalAgentGatewayException
import com.tencent.devops.ai.pojo.ExternalAgentPlatform
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.io.IOException
import java.net.URI

class ExternalAgentAdapterValidationTest {

    @Test
    fun `BkAiDev validation should reject plugin invoke endpoint for streaming`() {
        val error = assertThrows(ExternalAgentGatewayException::class.java) {
            BkAiDevExternalAgentAdapter().validateConfig(
                ExternalAgentConfigValidationContext(
                    platform = ExternalAgentPlatform.BKAIDEV.name,
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
                    platform = ExternalAgentPlatform.BKAIDEV.name,
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
                    platform = ExternalAgentPlatform.KNOT.name,
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
                    platform = ExternalAgentPlatform.KNOT.name,
                    agentId = "agent-id",
                    apiUrl = "https://example.com/apigw/api/v1/agents/agui/agent-id",
                    headers = """{"x-knot-api-token":"token","x-knot-api-user":"tester"}"""
                )
            )
        }
    }

    @Test
    fun `BkAiDev upstream error summary should include status body and content type`() {
        val error = WebClientResponseException.create(
            401,
            "Unauthorized",
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
            },
            """{"message":"bad token"}""".toByteArray(),
            Charsets.UTF_8
        )

        val summary = BkAiDevExternalAgentAdapter.summarizeUpstreamError(error)

        assertTrue(summary.contains("status=401"))
        assertTrue(summary.contains("contentType=application/json"))
        assertTrue(summary.contains("""body={"message":"bad token"}"""))
    }

    @Test
    fun `BkAiDev request error summary should include method uri and cause`() {
        val error = WebClientRequestException(
            IOException("Connection refused"),
            HttpMethod.POST,
            URI.create("https://example.com/chat_completion"),
            HttpHeaders.EMPTY
        )

        val summary = BkAiDevExternalAgentAdapter.summarizeUpstreamError(error)

        assertTrue(summary.contains("method=POST"))
        assertTrue(summary.contains("uri=https://example.com/chat_completion"))
        assertTrue(summary.contains("causeType=IOException"))
        assertTrue(summary.contains("causeMessage=Connection refused"))
    }

    @Test
    fun `BkAiDev should sanitize multiline auth header before request`() {
        val sanitized = BkAiDevExternalAgentAdapter.sanitizeHeaders(
            mapOf(
                "X-Bkapi-Authorization" to "{\n  \"access_token\" : \"token\"\n}",
                "X-BKAIDEV-USER" to " tester \n"
            )
        )

        assertEquals("""{"access_token":"token"}""", sanitized["X-Bkapi-Authorization"])
        assertEquals("tester", sanitized["X-BKAIDEV-USER"])
        assertFalse(sanitized["X-Bkapi-Authorization"]!!.contains('\n'))
    }
}
