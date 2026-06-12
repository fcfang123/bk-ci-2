package com.tencent.devops.ai.service

import com.fasterxml.jackson.core.type.TypeReference
import com.tencent.devops.ai.dao.ExternalAgentConfigDao
import com.tencent.devops.ai.agent.external.ExternalAgentAdapter
import com.tencent.devops.ai.agent.external.ExternalAgentAgentIdRecalculationContext
import com.tencent.devops.ai.agent.external.ExternalAgentAgentIdResolveContext
import com.tencent.devops.ai.agent.external.ExternalAgentAuthHeadersBuildContext
import com.tencent.devops.ai.agent.external.ExternalAgentConfigValidationContext
import com.tencent.devops.ai.agent.external.ExternalAgentEvent
import com.tencent.devops.ai.agent.external.ExternalAgentErrors
import com.tencent.devops.ai.agent.external.ExternalAgentGatewayException
import com.tencent.devops.ai.constant.AiMessageCode
import com.tencent.devops.ai.agent.external.ExternalAgentRequest
import com.tencent.devops.ai.pojo.ExternalAgentAuthConfig
import com.tencent.devops.ai.pojo.ExternalAgentAuthFieldInfo
import com.tencent.devops.ai.pojo.ExternalAgentAuthMode
import com.tencent.devops.ai.pojo.ExternalAgentCreate
import com.tencent.devops.ai.pojo.ExternalAgentPlatformConfigInfo
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
import java.net.URI

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
    fun `create should derive knot agentId from apiUrl`() {
        var persistedAgentId: String? = null
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
            persistedAgentId = invocation.args[6] as String?
        }
        every { dao.getById(dslContext, any()) } answers {
            storedRecord(
                platform = ExternalAgentPlatform.KNOT.name,
                apiUrl = "https://example.com/agents/agui/c68413c904234d419279e43dbf815e88",
                headers = PLAIN_HEADERS
            ).apply {
                agentId = persistedAgentId ?: agentId
            }
        }

        val info = service.create(
            USER_ID,
            ExternalAgentCreate(
                agentName = "knot-agent",
                description = "desc",
                platform = ExternalAgentPlatform.KNOT,
                apiUrl = "https://example.com/agents/agui/c68413c904234d419279e43dbf815e88",
                headers = PLAIN_HEADERS,
                enabled = true
            )
        )

        assertEquals("c68413c904234d419279e43dbf815e88", persistedAgentId)
        assertEquals("c68413c904234d419279e43dbf815e88", info.agentId)
    }

    @Test
    fun `create should fallback BkAiDev agentId to agentName`() {
        var persistedAgentId: String? = null
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
            persistedAgentId = invocation.args[6] as String?
        }
        every { dao.getById(dslContext, any()) } answers {
            storedRecord(
                platform = ExternalAgentPlatform.BKAIDEV.name,
                apiUrl = "https://example.com/chat_completion",
                headers = null
            ).apply {
                agentName = "bk-agent"
                agentId = persistedAgentId ?: agentId
            }
        }

        val info = service.create(
            USER_ID,
            ExternalAgentCreate(
                agentName = "bk-agent",
                description = "desc",
                platform = ExternalAgentPlatform.BKAIDEV,
                apiUrl = "https://example.com/chat_completion",
                enabled = true
            )
        )

        assertEquals("bk-agent", persistedAgentId)
        assertEquals("bk-agent", info.agentId)
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

        override fun resolveAgentId(context: ExternalAgentAgentIdResolveContext): String {
            return when (adapterPlatform) {
                ExternalAgentPlatform.KNOT -> extractAgentId(context.apiUrl)
                    ?: context.rawAgentId?.trim()?.takeIf { it.isNotBlank() }
                    ?: throw configError(AiMessageCode.EXTERNAL_AGENT_KNOT_AGENT_ID_FROM_URL)

                ExternalAgentPlatform.BKAIDEV -> context.rawAgentId?.trim()?.takeIf { it.isNotBlank() }
                    ?: context.agentName.trim().takeIf { it.isNotBlank() }
                    ?: throw configError(AiMessageCode.EXTERNAL_AGENT_BKAIDEV_AGENT_NAME_REQUIRED)
            }
        }

        override fun shouldRecalculateAgentId(context: ExternalAgentAgentIdRecalculationContext): Boolean {
            return when (adapterPlatform) {
                ExternalAgentPlatform.KNOT ->
                    context.platformChanged || context.apiUrlChanged || context.agentIdChanged

                ExternalAgentPlatform.BKAIDEV ->
                    context.platformChanged || context.agentIdChanged || context.agentNameChanged
            }
        }

        override fun buildAuthHeaders(context: ExternalAgentAuthHeadersBuildContext): String {
            return when (adapterPlatform) {
                ExternalAgentPlatform.KNOT -> JsonUtil.toJson(
                    linkedMapOf(
                        "x-knot-api-token" to context.authConfig.values.getValue("knotApiToken"),
                        "x-knot-api-user" to context.userId
                    )
                )

                ExternalAgentPlatform.BKAIDEV -> {
                    val payload = linkedMapOf<String, String>()
                    when (context.authConfig.authMode) {
                        ExternalAgentAuthMode.APP -> {
                            payload["bk_app_code"] = context.authConfig.values.getValue("bkAppCode")
                            payload["bk_app_secret"] = context.authConfig.values.getValue("bkAppSecret")
                            JsonUtil.toJson(
                                linkedMapOf(
                                    "X-Bkapi-Authorization" to JsonUtil.toJson(payload, false),
                                    "X-BKAIDEV-USER" to context.userId
                                )
                            )
                        }

                        else -> {
                            payload["access_token"] = context.authConfig.values.getValue("accessToken")
                            JsonUtil.toJson(
                                linkedMapOf(
                                    "X-Bkapi-Authorization" to JsonUtil.toJson(payload, false)
                                )
                            )
                        }
                    }
                }
            }
        }

        override fun parseAuthConfig(headers: String?): ExternalAgentAuthConfig? {
            val parsedHeaders = AiMcpServerService.parseHeaders(headers)
            if (parsedHeaders.isEmpty()) {
                return null
            }
            return when (adapterPlatform) {
                ExternalAgentPlatform.KNOT -> parsedHeaders["x-knot-api-token"]
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        ExternalAgentAuthConfig(values = mapOf("knotApiToken" to it))
                    }

                ExternalAgentPlatform.BKAIDEV -> {
                    val authorization = parsedHeaders["X-Bkapi-Authorization"] ?: return null
                    val values = linkedMapOf<String, String>()
                    val payload = JsonUtil.to(
                        authorization,
                        object : TypeReference<Map<String, String>>() {}
                    )
                    payload["bk_app_code"]?.let { values["bkAppCode"] = it }
                    payload["bk_app_secret"]?.let { values["bkAppSecret"] = it }
                    payload["access_token"]?.let { values["accessToken"] = it }
                    if (values.isEmpty()) {
                        null
                    } else {
                        ExternalAgentAuthConfig(
                            authMode = if ("accessToken" in values) {
                                ExternalAgentAuthMode.USER
                            } else {
                                ExternalAgentAuthMode.APP
                            },
                            values = values
                        )
                    }
                }
            }
        }

        override fun platformConfigInfo(): ExternalAgentPlatformConfigInfo {
            return when (adapterPlatform) {
                ExternalAgentPlatform.KNOT -> ExternalAgentPlatformConfigInfo(
                    platform = ExternalAgentPlatform.KNOT,
                    authFields = listOf(
                        ExternalAgentAuthFieldInfo(
                            key = "knotApiToken",
                            label = "x-knot-api-token",
                            description = "Knot 个人或团队 token",
                            required = true,
                            secret = true
                        )
                    )
                )

                ExternalAgentPlatform.BKAIDEV -> ExternalAgentPlatformConfigInfo(
                    platform = ExternalAgentPlatform.BKAIDEV,
                    authModes = listOf(ExternalAgentAuthMode.APP, ExternalAgentAuthMode.USER),
                    authFields = listOf(
                        ExternalAgentAuthFieldInfo(
                            key = "bkAppCode",
                            label = "bk_app_code",
                            description = "BKAIDEV 应用态调用使用的 bk_app_code",
                            required = true,
                            authModes = listOf(ExternalAgentAuthMode.APP)
                        ),
                        ExternalAgentAuthFieldInfo(
                            key = "bkAppSecret",
                            label = "bk_app_secret",
                            description = "BKAIDEV 应用态调用使用的 bk_app_secret",
                            required = true,
                            secret = true,
                            authModes = listOf(ExternalAgentAuthMode.APP)
                        ),
                        ExternalAgentAuthFieldInfo(
                            key = "accessToken",
                            label = "access_token",
                            description = "BKAIDEV 用户态调用使用的 access_token",
                            required = true,
                            secret = true,
                            authModes = listOf(ExternalAgentAuthMode.USER)
                        )
                    )
                )
            }
        }

        override fun maskAuthConfig(authConfig: ExternalAgentAuthConfig?): ExternalAgentAuthConfig? {
            if (authConfig == null) {
                return null
            }
            val secretKeys = when (adapterPlatform) {
                ExternalAgentPlatform.KNOT -> setOf("knotApiToken")
                ExternalAgentPlatform.BKAIDEV -> setOf("bkAppSecret", "accessToken")
            }
            return authConfig.copy(
                values = authConfig.values.mapValues { (key, value) ->
                    if (key in secretKeys) MASKED_HEADER_VALUE else value
                }
            )
        }

        override fun stream(request: ExternalAgentRequest): Flux<ExternalAgentEvent> = Flux.empty()

        private fun extractAgentId(apiUrl: String): String? {
            return runCatching { URI(apiUrl) }.getOrNull()
                ?.path
                ?.split("/")
                ?.lastOrNull { it.isNotBlank() }
        }

        private fun configError(errorCode: String, vararg params: String): ExternalAgentGatewayException {
            return ExternalAgentErrors.configInvalid(errorCode, *params)
        }
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
