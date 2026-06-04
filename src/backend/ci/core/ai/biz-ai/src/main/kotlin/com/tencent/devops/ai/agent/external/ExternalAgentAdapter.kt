package com.tencent.devops.ai.agent.external

import com.tencent.devops.ai.pojo.ExternalAgentPlatform
import reactor.core.publisher.Flux

interface ExternalAgentAdapter {
    fun platform(): ExternalAgentPlatform

    fun validateConfig(config: ExternalAgentConfigValidationContext) {}

    fun stream(request: ExternalAgentRequest): Flux<ExternalAgentEvent>
}
