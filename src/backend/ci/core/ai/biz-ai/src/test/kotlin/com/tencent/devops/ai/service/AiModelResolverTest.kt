package com.tencent.devops.ai.service

import com.tencent.devops.ai.config.AiModelFactory
import com.tencent.devops.ai.model.FailoverChatModel
import com.tencent.devops.ai.properties.AiLlmModelProperties
import com.tencent.devops.ai.properties.AiLlmProperties
import io.mockk.every
import io.mockk.mockk
import io.agentscope.core.model.OpenAIChatModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiModelResolverTest {

    private val modelFactory = mockk<AiModelFactory>()
    private val userLlmConfigService = mockk<UserLlmConfigService>()

    @Test
    fun `should prefer user model over platform models`() {
        val userConfig = AiLlmModelProperties(
            id = "user-tester",
            baseUrl = "https://user.example.com",
            modelName = "user-model",
            apiKey = "user-key"
        )
        val userModel = mockk<OpenAIChatModel>(relaxed = true)
        every { userLlmConfigService.getEnabledModel("tester") } returns userConfig
        val resolver = AiModelResolver(
            properties = AiLlmProperties(
                models = listOf(
                    AiLlmModelProperties(
                        id = "platform",
                        baseUrl = "https://platform.example.com",
                        modelName = "platform-model",
                        apiKey = "platform-key"
                    )
                )
            ),
            modelFactory = modelFactory,
            userLlmConfigService = userLlmConfigService
        )

        every { modelFactory.create(userConfig) } returns userModel
        val resolved = resolver.resolve("tester")

        assertEquals(AiModelSource.USER, resolved.source)
        assertEquals("user-tester", resolved.identifier)
        assertSame(userModel, resolved.model)
    }

    @Test
    fun `should build platform failover chain when user model is absent`() {
        val primaryConfig = AiLlmModelProperties(
            id = "primary",
            baseUrl = "https://primary.example.com",
            modelName = "primary-model",
            apiKey = "primary-key",
            priority = 10
        )
        val backupConfig = AiLlmModelProperties(
            id = "backup",
            baseUrl = "https://backup.example.com",
            modelName = "backup-model",
            apiKey = "backup-key",
            priority = 20
        )
        every { userLlmConfigService.getEnabledModel("tester") } returns null
        every { modelFactory.create(primaryConfig) } returns mockk(relaxed = true)
        every { modelFactory.create(backupConfig) } returns mockk(relaxed = true)

        val resolver = AiModelResolver(
            properties = AiLlmProperties(models = listOf(backupConfig, primaryConfig)),
            modelFactory = modelFactory,
            userLlmConfigService = userLlmConfigService
        )

        val resolved = resolver.resolve("tester")

        assertEquals(AiModelSource.PLATFORM, resolved.source)
        assertEquals("primary -> backup", resolved.identifier)
        assertTrue(resolved.model is FailoverChatModel)
    }
}
