package com.tencent.devops.ai.external

import reactor.core.publisher.Flux

interface ExternalAgentAdapter {
    fun platform(): String

    fun validateConfig(config: ExternalAgentConfigValidationContext) {}

    fun stream(request: ExternalAgentRequest): Flux<ExternalAgentEvent>
}
