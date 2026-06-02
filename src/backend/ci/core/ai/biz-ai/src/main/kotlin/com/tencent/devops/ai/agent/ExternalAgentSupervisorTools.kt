package com.tencent.devops.ai.agent

import com.tencent.devops.ai.context.AgentSessionContext
import com.tencent.devops.ai.external.ExternalAgentErrorCategory
import com.tencent.devops.ai.external.ExternalAgentEvent
import com.tencent.devops.ai.external.ExternalAgentGateway
import com.tencent.devops.ai.external.ExternalAgentGatewayException
import com.tencent.devops.ai.external.ExternalAgentInput
import com.tencent.devops.common.api.util.JsonUtil
import io.agentscope.core.agui.event.AguiEvent
import io.agentscope.core.tool.Tool
import io.agentscope.core.tool.ToolParam
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import java.util.function.Supplier

class ExternalAgentSupervisorTools(
    private val gateway: ExternalAgentGateway,
    private val sessionContext: AgentSessionContext,
    private val userIdSupplier: Supplier<String>,
    private val threadId: String?
) {

    @Tool(
        name = "call_external_agent",
        description = "调用用户已启用的外部智能体配置。适合用户明确 @ 外部智能体，" +
            "或问题需要委派给某个外部智能体处理时使用。"
    )
    fun callExternalAgent(
        @ToolParam(name = "config_id", description = "外部智能体配置 ID")
        configId: String,
        @ToolParam(name = "query", description = "需要交给外部智能体处理的问题")
        query: String,
        @ToolParam(name = "conversation_id", description = "外部平台会话 ID，可选", required = false)
        conversationId: String? = null
    ): String {
        val userId = userIdSupplier.get()
        val content = StringBuilder()
        var externalConversationId: String? = conversationId?.takeIf { it.isNotBlank() }
        var errorCategory: ExternalAgentErrorCategory? = null
        var errorMessage: String? = null

        return try {
            gateway.stream(
                userId = userId,
                configId = configId,
                input = ExternalAgentInput(
                    query = query,
                    conversationId = conversationId.orEmpty(),
                    threadId = threadId
                )
            ).doOnNext { event ->
                when (event) {
                    is ExternalAgentEvent.TextDelta -> {
                        content.append(event.delta)
                        emitProgress(configId, "text_delta", event.delta, externalConversationId)
                    }
                    is ExternalAgentEvent.ConversationId -> {
                        externalConversationId = event.conversationId
                        emitProgress(configId, "conversation_id", null, externalConversationId)
                    }
                    is ExternalAgentEvent.Custom -> {
                        emitProgress(configId, event.eventType, null, externalConversationId)
                    }
                    is ExternalAgentEvent.Error -> {
                        errorCategory = event.category
                        errorMessage = event.message
                        emitProgress(configId, "error", event.message, externalConversationId)
                    }
                    ExternalAgentEvent.Done -> emitProgress(configId, "done", null, externalConversationId)
                }
            }.onErrorResume { error ->
                val gatewayError = error as? ExternalAgentGatewayException
                errorCategory = gatewayError?.category ?: ExternalAgentErrorCategory.UPSTREAM_ERROR
                errorMessage = error.message
                Flux.empty()
            }.blockLast()

            JsonUtil.toJson(
                mapOf(
                    "success" to (errorCategory == null),
                    "content" to content.toString(),
                    "conversationId" to externalConversationId,
                    "errorCategory" to errorCategory,
                    "errorMessage" to errorMessage
                )
            )
        } catch (e: Exception) {
            logger.warn(
                "[ExternalAgentSupervisorTool] call failed: userId={}, configId={}, threadId={}, error={}",
                userId,
                configId,
                threadId,
                e.message
            )
            JsonUtil.toJson(
                mapOf(
                    "success" to false,
                    "content" to content.toString(),
                    "conversationId" to externalConversationId,
                    "errorCategory" to (errorCategory ?: ExternalAgentErrorCategory.UPSTREAM_ERROR),
                    "errorMessage" to (errorMessage ?: e.message)
                )
            )
        }
    }

    private fun emitProgress(
        configId: String,
        eventType: String,
        content: String?,
        conversationId: String?
    ) {
        val sinkInfo = threadId?.let { sessionContext.getSinkByThreadId(it) } ?: return
        val data = mutableMapOf<String, Any?>(
            "agentName" to TOOL_NAME,
            "configId" to configId,
            "eventType" to eventType,
            "conversationId" to conversationId
        )
        content?.take(MAX_CONTENT)?.let { data["content"] = it }
        sinkInfo.sink.tryEmitNext(
            AguiEvent.Custom(
                sinkInfo.threadId,
                sinkInfo.runId,
                CUSTOM_EVENT_NAME,
                data
            )
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ExternalAgentSupervisorTools::class.java)
        private const val TOOL_NAME = "call_external_agent"
        private const val CUSTOM_EVENT_NAME = "external_agent_event"
        private const val MAX_CONTENT = 2000
    }
}
