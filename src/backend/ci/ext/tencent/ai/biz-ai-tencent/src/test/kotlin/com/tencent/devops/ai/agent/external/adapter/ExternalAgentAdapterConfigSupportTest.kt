package com.tencent.devops.ai.agent.external.adapter

import com.fasterxml.jackson.core.type.TypeReference
import com.tencent.devops.ai.agent.external.ExternalAgentAgentIdRecalculationContext
import com.tencent.devops.ai.agent.external.ExternalAgentAgentIdResolveContext
import com.tencent.devops.ai.agent.external.ExternalAgentAuthHeadersBuildContext
import com.tencent.devops.ai.pojo.ExternalAgentAuthConfig
import com.tencent.devops.ai.pojo.ExternalAgentAuthMode
import com.tencent.devops.common.api.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExternalAgentAdapterConfigSupportTest {

    @Test
    fun `BkAiDev should fallback agentId to agentName`() {
        val agentId = BkAiDevExternalAgentAdapter().resolveAgentId(
            ExternalAgentAgentIdResolveContext(
                rawAgentId = null,
                agentName = "bk-agent",
                apiUrl = "https://example.com/chat_completion"
            )
        )

        assertEquals("bk-agent", agentId)
    }

    @Test
    fun `BkAiDev should build and parse user auth headers`() {
        val adapter = BkAiDevExternalAgentAdapter()

        val headers = adapter.buildAuthHeaders(
            ExternalAgentAuthHeadersBuildContext(
                userId = "tester",
                authConfig = ExternalAgentAuthConfig(
                    authMode = ExternalAgentAuthMode.USER,
                    values = mapOf("accessToken" to "token")
                )
            )
        )
        val parsedHeaders = JsonUtil.to(
            headers,
            object : TypeReference<Map<String, String>>() {}
        )
        val authorization = JsonUtil.to(
            parsedHeaders["X-Bkapi-Authorization"]!!,
            object : TypeReference<Map<String, String>>() {}
        )
        val authConfig = adapter.parseAuthConfig(headers)
        val masked = adapter.maskAuthConfig(authConfig)

        assertEquals(mapOf("access_token" to "token"), authorization)
        assertNull(parsedHeaders["X-BKAIDEV-USER"])
        assertEquals(ExternalAgentAuthMode.USER, authConfig?.authMode)
        assertEquals("token", authConfig?.values?.get("accessToken"))
        assertEquals("******", masked?.values?.get("accessToken"))
        assertFalse(parsedHeaders["X-Bkapi-Authorization"]!!.contains('\n'))
    }

    @Test
    fun `BkAiDev should keep app mode on partial update merge trigger`() {
        val shouldRecalculate = BkAiDevExternalAgentAdapter().shouldRecalculateAgentId(
            ExternalAgentAgentIdRecalculationContext(
                platformChanged = false,
                agentIdChanged = false,
                agentNameChanged = true,
                apiUrlChanged = false
            )
        )

        assertTrue(shouldRecalculate)
    }

    @Test
    fun `Knot should derive agentId from apiUrl`() {
        val agentId = KnotExternalAgentAdapter().resolveAgentId(
            ExternalAgentAgentIdResolveContext(
                rawAgentId = null,
                agentName = "ignored",
                apiUrl = "https://example.com/apigw/api/v1/agents/agui/c68413c904234d419279e43dbf815e88"
            )
        )

        assertEquals("c68413c904234d419279e43dbf815e88", agentId)
    }

    @Test
    fun `Knot should build and parse auth headers`() {
        val adapter = KnotExternalAgentAdapter()

        val headers = adapter.buildAuthHeaders(
            ExternalAgentAuthHeadersBuildContext(
                userId = "tester",
                authConfig = ExternalAgentAuthConfig(
                    values = mapOf("knotApiToken" to "secret-token")
                )
            )
        )
        val parsedHeaders = JsonUtil.to(
            headers,
            object : TypeReference<Map<String, String>>() {}
        )
        val authConfig = adapter.parseAuthConfig(headers)
        val masked = adapter.maskAuthConfig(authConfig)

        assertEquals("secret-token", parsedHeaders["x-knot-api-token"])
        assertEquals("tester", parsedHeaders["x-knot-api-user"])
        assertEquals("secret-token", authConfig?.values?.get("knotApiToken"))
        assertNull(authConfig?.values?.get("knotApiUser"))
        assertEquals("******", masked?.values?.get("knotApiToken"))
    }

    @Test
    fun `platform config info should only expose user editable fields`() {
        val bkAiDev = BkAiDevExternalAgentAdapter().platformConfigInfo()
        val knot = KnotExternalAgentAdapter().platformConfigInfo()

        assertEquals(setOf("bkAppCode", "bkAppSecret", "accessToken"), bkAiDev.authFields.map { it.key }.toSet())
        assertEquals(setOf("knotApiToken"), knot.authFields.map { it.key }.toSet())
    }
}
