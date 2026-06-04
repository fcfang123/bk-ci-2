package com.tencent.devops.ai.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("ai.supervisor")
data class AiSupervisorProperties(
    val toolkitTimeoutSeconds: Long = 600
)
