package com.tencent.devops.ai.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("ai.external-agent.gateway")
data class ExternalAgentGatewayProperties(
    val enabled: Boolean = true,
    val supervisorToolEnabled: Boolean = true,
    val firstTokenTimeoutSeconds: Long = 60,
    val streamTimeoutSeconds: Long = 300,
    val maxResponseChars: Int = 200_000
)
