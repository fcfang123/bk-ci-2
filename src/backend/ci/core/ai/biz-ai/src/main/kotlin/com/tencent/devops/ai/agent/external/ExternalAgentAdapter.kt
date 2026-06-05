package com.tencent.devops.ai.agent.external

import com.tencent.devops.ai.constant.AiMessageCode
import com.tencent.devops.ai.pojo.ExternalAgentAuthConfig
import com.tencent.devops.ai.pojo.ExternalAgentPlatformConfigInfo
import com.tencent.devops.ai.pojo.ExternalAgentPlatform
import reactor.core.publisher.Flux

interface ExternalAgentAdapter {
    fun platform(): ExternalAgentPlatform

    fun validateConfig(config: ExternalAgentConfigValidationContext) {}

    fun resolveAgentId(context: ExternalAgentAgentIdResolveContext): String {
        return context.rawAgentId?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ExternalAgentErrors.configInvalid(
                AiMessageCode.EXTERNAL_AGENT_PLATFORM_AGENT_ID_REQUIRED,
                platform().name
            )
    }

    fun shouldRecalculateAgentId(context: ExternalAgentAgentIdRecalculationContext): Boolean {
        return context.platformChanged || context.agentIdChanged
    }

    fun buildAuthHeaders(context: ExternalAgentAuthHeadersBuildContext): String {
        throw ExternalAgentErrors.configInvalid(
            AiMessageCode.EXTERNAL_AGENT_AUTH_CONFIG_UNSUPPORTED,
            platform().name
        )
    }

    fun parseAuthConfig(headers: String?): ExternalAgentAuthConfig? = null

    fun maskAuthConfig(authConfig: ExternalAgentAuthConfig?): ExternalAgentAuthConfig? = authConfig

    fun platformConfigInfo(): ExternalAgentPlatformConfigInfo? = null

    fun stream(request: ExternalAgentRequest): Flux<ExternalAgentEvent>
}
