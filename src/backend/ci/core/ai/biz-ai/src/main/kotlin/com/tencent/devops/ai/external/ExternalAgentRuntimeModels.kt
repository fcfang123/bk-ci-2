package com.tencent.devops.ai.external

import com.tencent.devops.ai.pojo.ExternalAgentInfo
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

data class ExternalAgentInput(
    val query: String,
    val conversationId: String = "",
    val threadId: String? = null,
    val runId: String? = null,
    val chatHistory: List<Map<String, String>> = emptyList()
)

data class ExternalAgentRequest(
    val userId: String,
    val config: ExternalAgentInfo,
    val query: String,
    val conversationId: String = "",
    val threadId: String? = null,
    val runId: String? = null,
    val chatHistory: List<Map<String, String>> = emptyList()
)

data class ExternalAgentConfigValidationContext(
    val platform: String,
    val agentId: String,
    val apiUrl: String,
    val headers: String?
)

sealed class ExternalAgentEvent {
    data class TextDelta(
        val delta: String
    ) : ExternalAgentEvent()

    data class ConversationId(
        val conversationId: String
    ) : ExternalAgentEvent()

    data class Custom(
        val eventType: String,
        val data: Map<String, Any?>
    ) : ExternalAgentEvent()

    data class Error(
        val message: String,
        val category: ExternalAgentErrorCategory
    ) : ExternalAgentEvent()

    data object Done : ExternalAgentEvent()
}

data class ExternalAgentResult(
    val content: String,
    val conversationId: String? = null
)

enum class ExternalAgentErrorCategory {
    CONFIG_INVALID,
    UNSUPPORTED_PLATFORM,
    UPSTREAM_ERROR,
    PARSE_ERROR,
    TIMEOUT,
    CANCELLED
}

class ExternalAgentGatewayException(
    val category: ExternalAgentErrorCategory,
    override val message: String
) : RuntimeException(message)

interface ExternalAgentAdapter {
    fun platform(): String

    fun validateConfig(config: ExternalAgentConfigValidationContext) {}

    fun stream(request: ExternalAgentRequest): Flux<ExternalAgentEvent>

    fun call(request: ExternalAgentRequest): Mono<ExternalAgentResult> {
        return stream(request)
            .collect(
                { ExternalAgentResultBuilder() },
                { builder, event -> builder.accept(event) }
            )
            .map { it.build() }
    }
}

private class ExternalAgentResultBuilder {
    private val content = StringBuilder()
    private var conversationId: String? = null

    fun accept(event: ExternalAgentEvent) {
        when (event) {
            is ExternalAgentEvent.TextDelta -> content.append(event.delta)
            is ExternalAgentEvent.ConversationId -> conversationId = event.conversationId
            else -> {}
        }
    }

    fun build(): ExternalAgentResult {
        return ExternalAgentResult(
            content = content.toString(),
            conversationId = conversationId
        )
    }
}
