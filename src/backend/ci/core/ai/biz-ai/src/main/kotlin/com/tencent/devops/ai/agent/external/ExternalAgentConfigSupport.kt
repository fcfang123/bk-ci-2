package com.tencent.devops.ai.agent.external

import com.tencent.devops.ai.pojo.ExternalAgentAuthConfig

data class ExternalAgentAgentIdResolveContext(
    val rawAgentId: String?,
    val agentName: String,
    val apiUrl: String
)

data class ExternalAgentAgentIdRecalculationContext(
    val platformChanged: Boolean,
    val agentIdChanged: Boolean,
    val agentNameChanged: Boolean,
    val apiUrlChanged: Boolean
)

data class ExternalAgentAuthHeadersBuildContext(
    val userId: String,
    val authConfig: ExternalAgentAuthConfig
)
