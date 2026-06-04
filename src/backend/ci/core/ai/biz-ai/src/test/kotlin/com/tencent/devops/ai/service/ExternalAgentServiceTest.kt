package com.tencent.devops.ai.service

import com.fasterxml.jackson.core.type.TypeReference
import com.tencent.devops.ai.dao.ExternalAgentConfigDao
import com.tencent.devops.ai.agent.external.ExternalAgentAdapter
import com.tencent.devops.ai.agent.external.ExternalAgentConfigValidationContext
import com.tencent.devops.ai.agent.external.ExternalAgentEvent
import com.tencent.devops.ai.agent.external.ExternalAgentRequest
import com.tencent.devops.ai.pojo.ExternalAgentAuthConfig
import com.tencent.devops.ai.pojo.ExternalAgentAuthMode
import com.tencent.devops.ai.pojo.ExternalAgentCreate
import com.tencent.devops.ai.pojo.ExternalAgentPlatform
import com.tencent.devops.ai.pojo.ExternalAgentUpdate
import com.tencent.devops.common.api.util.AESUtil
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.model.ai.tables.records.TAiExternalAgentConfigRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jooq.DSLContext
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import java.time.LocalDateTime

class ExternalAgentServiceTest {

    private val dslContext = mockk<DSLContext>(relaxed = true)
    private val dao = mockk<ExternalAgentConfigDao>(relaxed = true)
    private val adapters = listOf(
        PermissiveAdapter(ExternalAgentPlatform.KNOT),
        PermissiveAdapter(ExternalAgentPlatform.BKAIDEV)
    )
    private val service = ExternalAgentService(
        dslContext = dslContext,
        dao = dao,
        adapters = adapters,
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

    @Test
    fun `create should assemble BkAiDev user headers from auth config`() {
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
            storedRecord(
                platform = ExternalAgentPlatform.BKAIDEV.name,
                apiUrl = "https://example.com/chat_completion",
                headers = persistedHeaders
            )
        }

        val info = service.create(
            USER_ID,
            ExternalAgentCreate(
                agentName = "aid",
                description = "desc",
                platform = ExternalAgentPlatform.BKAIDEV,
                agentId = "agent-id",
                apiUrl = "https://example.com/chat_completion",
                authConfig = ExternalAgentAuthConfig(
                    authMode = ExternalAgentAuthMode.USER,
                    values = mapOf("accessToken" to "token")
                ),
                enabled = true
            )
        )

        val decryptedHeaders = AESUtil.decrypt(AES_KEY, persistedHeaders!!)
        val parsedHeaders = AiMcpServerService.parseHeaders(decryptedHeaders)
        val authHeader = JsonUtil.to(
            parsedHeaders["X-Bkapi-Authorization"]!!,
            object : TypeReference<Map<String, String>>() {}
        )
        assertNotNull(info.authConfig)
        val authConfig = info.authConfig!!
        assertEquals(ExternalAgentAuthMode.USER, authConfig.authMode)
        assertEquals(MASKED_HEADER_VALUE, authConfig.values["accessToken"])
        assertEquals(mapOf("access_token" to "token"), authHeader)
        assertFalse(parsedHeaders["X-Bkapi-Authorization"]!!.contains('\n'))
    }

    @Test
    fun `update should merge BkAiDev auth config with stored headers`() {
        val encrypted = AESUtil.encrypt(
            AES_KEY,
            JsonUtil.toJson(
                linkedMapOf(
                    "X-Bkapi-Authorization" to """{"bk_app_code":"app","bk_app_secret":"secret"}""",
                    "X-BKAIDEV-USER" to "old-user"
                )
            )
        )
        var persistedHeaders: String? = null
        every { dao.getById(dslContext, CONFIG_ID) } returns storedRecord(
            platform = ExternalAgentPlatform.BKAIDEV.name,
            apiUrl = "https://example.com/chat_completion",
            headers = encrypted
        )
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

        service.update(
            USER_ID,
            CONFIG_ID,
            ExternalAgentUpdate(
                authConfig = ExternalAgentAuthConfig(
                    authMode = ExternalAgentAuthMode.APP
                )
            )
        )

        val decryptedHeaders = AESUtil.decrypt(AES_KEY, persistedHeaders!!)
        val parsedHeaders = AiMcpServerService.parseHeaders(decryptedHeaders)
        assertEquals(USER_ID, parsedHeaders["X-BKAIDEV-USER"])
        assertEquals(
            mapOf(
                "bk_app_code" to "app",
                "bk_app_secret" to "secret"
            ),
            JsonUtil.to(
                parsedHeaders["X-Bkapi-Authorization"]!!,
                object : TypeReference<Map<String, String>>() {}
            )
        )
        assertFalse(parsedHeaders["X-Bkapi-Authorization"]!!.contains('\n'))
    }

    @Test
    fun `getEnabled should expose structured knot auth config`() {
        every { dao.getById(dslContext, CONFIG_ID) } returns storedRecord(
            platform = ExternalAgentPlatform.KNOT.name,
            headers = AESUtil.encrypt(AES_KEY, PLAIN_HEADERS)
        )

        val info = service.getEnabled(USER_ID, CONFIG_ID)

        assertEquals("secret-token", info.authConfig?.values?.get("knotApiToken"))
        assertEquals(null, info.authConfig?.values?.get("knotApiUser"))
    }

    @Test
    fun `listPlatformConfigs should only expose user editable fields`() {
        val platformConfigs = service.listPlatformConfigs()

        val bkAiDevFields = platformConfigs.first { it.platform == ExternalAgentPlatform.BKAIDEV }.authFields
        val knotFields = platformConfigs.first { it.platform == ExternalAgentPlatform.KNOT }.authFields

        assertEquals(setOf("bkAppCode", "bkAppSecret", "accessToken"), bkAiDevFields.map { it.key }.toSet())
        assertEquals(setOf("knotApiToken"), knotFields.map { it.key }.toSet())
    }

    private fun createRequest(): ExternalAgentCreate {
        return ExternalAgentCreate(
            agentName = "agent",
            description = "desc",
            platform = ExternalAgentPlatform.KNOT,
            agentId = "agent-id",
            apiUrl = "https://example.com/agui/run",
            headers = PLAIN_HEADERS,
            enabled = true
        )
    }

    private fun storedRecord(
        platform: String = ExternalAgentPlatform.KNOT.name,
        apiUrl: String = "https://example.com/agui/run",
        headers: String? = PLAIN_HEADERS
    ): TAiExternalAgentConfigRecord {
        val now = LocalDateTime.now()
        return TAiExternalAgentConfigRecord().apply {
            id = CONFIG_ID
            userId = USER_ID
            agentName = "agent"
            description = "desc"
            this.platform = platform
            agentId = "agent-id"
            this.apiUrl = apiUrl
            this.headers = headers
            enabled = true
            createdTime = now
            updatedTime = now
        }
    }

    private class PermissiveAdapter(
        private val adapterPlatform: ExternalAgentPlatform
    ) : ExternalAgentAdapter {
        override fun platform(): ExternalAgentPlatform = adapterPlatform

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
