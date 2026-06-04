package com.tencent.devops.ai.service

import com.tencent.devops.ai.dao.ExternalAgentConfigDao
import com.tencent.devops.ai.agent.external.ExternalAgentAdapter
import com.tencent.devops.ai.agent.external.ExternalAgentConfigValidationContext
import com.tencent.devops.ai.agent.external.ExternalAgentEvent
import com.tencent.devops.ai.agent.external.ExternalAgentRequest
import com.tencent.devops.ai.pojo.ExternalAgentCreate
import com.tencent.devops.ai.pojo.ExternalAgentUpdate
import com.tencent.devops.common.api.util.AESUtil
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.model.ai.tables.records.TAiExternalAgentConfigRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jooq.DSLContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import java.time.LocalDateTime

class ExternalAgentServiceTest {

    private val dslContext = mockk<DSLContext>(relaxed = true)
    private val dao = mockk<ExternalAgentConfigDao>(relaxed = true)
    private val adapter = PermissiveKnotAdapter()
    private val service = ExternalAgentService(
        dslContext = dslContext,
        dao = dao,
        adapters = listOf(adapter),
        aesKey = AES_KEY
    )

    @Test
    fun `create should encrypt headers before persisting`() {
        var persistedHeaders: String? = null
        every {
            dao.create(
                dslContext = dslContext,
                id = any(),
                userId = any(),
                agentName = any(),
                description = any(),
                platform = any(),
                agentId = any(),
                apiUrl = any(),
                headers = any(),
                enabled = any()
            )
        } answers {
            persistedHeaders = invocation.args[8] as String?
        }
        every { dao.getById(dslContext, any()) } answers {
            storedRecord(headers = persistedHeaders)
        }

        service.create(USER_ID, createRequest())

        assertNotEquals(PLAIN_HEADERS, persistedHeaders)
        assertEquals(PLAIN_HEADERS, AESUtil.decrypt(AES_KEY, persistedHeaders!!))
    }

    @Test
    fun `create should return masked headers`() {
        var persistedHeaders: String? = null
        every {
            dao.create(
                dslContext = dslContext,
                id = any(),
                userId = any(),
                agentName = any(),
                description = any(),
                platform = any(),
                agentId = any(),
                apiUrl = any(),
                headers = any(),
                enabled = any()
            )
        } answers {
            persistedHeaders = invocation.args[8] as String?
        }
        every { dao.getById(dslContext, any()) } answers {
            storedRecord(headers = persistedHeaders)
        }

        val info = service.create(USER_ID, createRequest())

        val maskedHeaders = AiMcpServerService.parseHeaders(info.headers)
        assertEquals(MASKED_HEADER_VALUE, maskedHeaders["x-knot-api-token"])
        assertEquals(MASKED_HEADER_VALUE, maskedHeaders["x-knot-api-user"])
    }

    @Test
    fun `getEnabled should return decrypted headers`() {
        every { dao.getById(dslContext, CONFIG_ID) } returns storedRecord(
            headers = AESUtil.encrypt(AES_KEY, PLAIN_HEADERS)
        )

        val info = service.getEnabled(USER_ID, CONFIG_ID)

        assertEquals(PLAIN_HEADERS, info.headers)
    }

    @Test
    fun `update should preserve encrypted headers when request omits headers`() {
        val encrypted = AESUtil.encrypt(AES_KEY, PLAIN_HEADERS)
        every { dao.getById(dslContext, CONFIG_ID) } returns storedRecord(headers = encrypted)
        every {
            dao.update(
                dslContext = dslContext,
                id = CONFIG_ID,
                agentName = any(),
                description = any(),
                platform = any(),
                agentId = any(),
                apiUrl = any(),
                headers = null,
                enabled = any()
            )
        } returns 1

        service.update(USER_ID, CONFIG_ID, ExternalAgentUpdate(agentName = "new-name"))

        verify {
            dao.update(
                dslContext = dslContext,
                id = CONFIG_ID,
                agentName = "new-name",
                description = null,
                platform = null,
                agentId = null,
                apiUrl = null,
                headers = null,
                enabled = null
            )
        }
    }

    @Test
    fun `update should re-encrypt headers when request provides new headers`() {
        val encrypted = AESUtil.encrypt(AES_KEY, PLAIN_HEADERS)
        var persistedHeaders: String? = null
        every { dao.getById(dslContext, CONFIG_ID) } returns storedRecord(headers = encrypted)
        every {
            dao.update(
                dslContext = dslContext,
                id = CONFIG_ID,
                agentName = any(),
                description = any(),
                platform = any(),
                agentId = any(),
                apiUrl = any(),
                headers = any(),
                enabled = any()
            )
        } answers {
            persistedHeaders = invocation.args[7] as String?
            1
        }

        val updatedHeaders = """{"x-knot-api-token":"new-token","x-knot-api-user":"tester"}"""
        service.update(USER_ID, CONFIG_ID, ExternalAgentUpdate(headers = updatedHeaders))

        assertEquals(updatedHeaders, AESUtil.decrypt(AES_KEY, persistedHeaders!!))
    }

    @Test
    fun `getEnabled should read legacy plaintext headers`() {
        every { dao.getById(dslContext, CONFIG_ID) } returns storedRecord(headers = PLAIN_HEADERS)

        val info = service.getEnabled(USER_ID, CONFIG_ID)

        assertEquals(PLAIN_HEADERS, info.headers)
    }

    private fun createRequest(): ExternalAgentCreate {
        return ExternalAgentCreate(
            agentName = "agent",
            description = "desc",
            platform = "KNOT",
            agentId = "agent-id",
            apiUrl = "https://example.com/agui/run",
            headers = PLAIN_HEADERS,
            enabled = true
        )
    }

    private fun storedRecord(
        headers: String? = PLAIN_HEADERS
    ): TAiExternalAgentConfigRecord {
        val now = LocalDateTime.now()
        return TAiExternalAgentConfigRecord().apply {
            id = CONFIG_ID
            userId = USER_ID
            agentName = "agent"
            description = "desc"
            platform = "KNOT"
            agentId = "agent-id"
            apiUrl = "https://example.com/agui/run"
            this.headers = headers
            enabled = true
            createdTime = now
            updatedTime = now
        }
    }

    private class PermissiveKnotAdapter : ExternalAgentAdapter {
        override fun platform(): String = "KNOT"

        override fun validateConfig(config: ExternalAgentConfigValidationContext) = Unit

        override fun stream(request: ExternalAgentRequest): Flux<ExternalAgentEvent> = Flux.empty()
    }

    companion object {
        private const val USER_ID = "tester"
        private const val CONFIG_ID = "config-1"
        private const val AES_KEY = "aes-key"
        private const val MASKED_HEADER_VALUE = "******"
        private val PLAIN_HEADERS = JsonUtil.toJson(
            mapOf(
                "x-knot-api-token" to "secret-token",
                "x-knot-api-user" to "tester"
            )
        )
    }
}
