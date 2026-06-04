package com.tencent.devops.ai.agent.external

import com.tencent.devops.ai.pojo.ExternalAgentInfo

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
