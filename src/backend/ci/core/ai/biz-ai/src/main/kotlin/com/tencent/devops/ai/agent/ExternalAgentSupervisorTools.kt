package com.tencent.devops.ai.agent

import com.tencent.devops.ai.context.AgentSessionContext
import com.tencent.devops.ai.external.ExternalAgentErrorCategory
import com.tencent.devops.ai.external.ExternalAgentEvent
import com.tencent.devops.ai.external.ExternalAgentGateway
import com.tencent.devops.ai.external.ExternalAgentGatewayException
import com.tencent.devops.ai.external.ExternalAgentInput
import com.tencent.devops.ai.service.ExternalAgentService
import com.tencent.devops.common.api.util.JsonUtil
import io.agentscope.core.agui.event.AguiEvent
import io.agentscope.core.tool.Tool
import io.agentscope.core.tool.ToolParam
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import java.util.function.Supplier

class ExternalAgentSupervisorTools(
    private val externalAgentService: ExternalAgentService,
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
        @ToolParam(name = "config_id", description = "外部智能体配置 ID 或显示名称")
        configId: String,
        @ToolParam(name = "query", description = "需要交给外部智能体处理的问题")
        query: String,
        @ToolParam(name = "conversation_id", description = "外部平台会话 ID，可选", required = false)
        conversationId: String? = null
    ): String {
        val userId = userIdSupplier.get()
        var resolvedConfigId = configId
        var agentDisplayName = configId
        val content = StringBuilder()
        var externalConversationId: String? = conversationId?.takeIf { it.isNotBlank() }
        var errorCategory: ExternalAgentErrorCategory? = null
        var errorMessage: String? = null

        return try {
            resolvedConfigId = externalAgentService.resolveEnabledConfigId(userId, configId)
            agentDisplayName = externalAgentService.getEnabled(userId, resolvedConfigId).agentName
                .takeIf { it.isNotBlank() }
                ?: resolvedConfigId
            gateway.stream(
                userId = userId,
                configId = resolvedConfigId,
                input = ExternalAgentInput(
                    query = query,
                    conversationId = conversationId.orEmpty(),
                    threadId = threadId
                )
            ).doOnNext { event ->
                when (event) {
                    is ExternalAgentEvent.TextDelta -> {
                        content.append(event.delta)
                        emitProgress(
                            agentName = agentDisplayName,
                            configId = resolvedConfigId,
                            eventType = EVENT_TYPE_ASSISTANT,
                            content = event.delta,
                            conversationId = externalConversationId
                        )
                    }
                    is ExternalAgentEvent.ConversationId -> {
                        externalConversationId = event.conversationId
                    }
                    is ExternalAgentEvent.Custom -> {
                        emitCustomProgress(
                            agentName = agentDisplayName,
                            configId = resolvedConfigId,
                            event = event,
                            conversationId = externalConversationId
                        )
                    }
                    is ExternalAgentEvent.Error -> {
                        errorCategory = event.category
                        errorMessage = event.message
                        emitProgress(
                            agentName = agentDisplayName,
                            configId = resolvedConfigId,
                            eventType = EVENT_TYPE_ASSISTANT,
                            content = event.message,
                            conversationId = externalConversationId,
                            isLast = true,
                            extraData = mapOf("errorCategory" to event.category.name)
                        )
                    }
                    ExternalAgentEvent.Done -> emitProgress(
                        agentName = agentDisplayName,
                        configId = resolvedConfigId,
                        eventType = EVENT_TYPE_ASSISTANT,
                        content = null,
                        conversationId = externalConversationId,
                        isLast = true
                    )
                }
            }.onErrorResume { error ->
                val gatewayError = error as? ExternalAgentGatewayException
                errorCategory = gatewayError?.category ?: ExternalAgentErrorCategory.UPSTREAM_ERROR
                errorMessage = error.message
                Flux.empty()
            }.blockLast()

            if (errorCategory == null && content.isEmpty()) {
                logger.warn(
                    "[ExternalAgentSupervisorTool] call completed without text content: userId={}, " +
                        "configId={}, threadId={}, conversationId={}",
                    userId,
                    resolvedConfigId,
                    threadId,
                    externalConversationId
                )
            }

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
                resolvedConfigId,
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
        agentName: String,
        configId: String,
        eventType: String,
        content: String?,
        conversationId: String?,
        isLast: Boolean = false,
        extraData: Map<String, Any?> = emptyMap()
    ) {
        if (!isLast && content.isNullOrBlank()) {
            return
        }
        val sinkInfo = threadId?.let { sessionContext.getSinkByThreadId(it) } ?: return
        val data = mutableMapOf<String, Any?>(
            "agentName" to agentName,
            "configId" to configId,
            "eventType" to eventType,
            "conversationId" to conversationId,
            "isLast" to isLast
        )
        content?.take(MAX_CONTENT)?.let { data["content"] = it }
        data.putAll(extraData)
        sinkInfo.sink.tryEmitNext(
            AguiEvent.Custom(
                sinkInfo.threadId,
                sinkInfo.runId,
                CUSTOM_EVENT_NAME,
                data
            )
        )
    }

    private fun emitCustomProgress(
        agentName: String,
        configId: String,
        event: ExternalAgentEvent.Custom,
        conversationId: String?
    ) {
        val mappedEventType = if (event.eventType.contains(REASONING_KEYWORD, ignoreCase = true)) {
            EVENT_TYPE_REASONING
        } else {
            EVENT_TYPE_ASSISTANT
        }
        val content = extractCustomContent(event.data)
        emitProgress(
            agentName = agentName,
            configId = configId,
            eventType = mappedEventType,
            content = content,
            conversationId = conversationId,
            extraData = mapOf("externalEventType" to event.eventType)
        )
    }

    private fun extractCustomContent(data: Map<String, Any?>): String? {
        val rawEvent = data["rawEvent"] as? Map<*, *>
        return listOf(
            data["delta"]?.toString(),
            data["content"]?.toString(),
            data["text"]?.toString(),
            rawEvent?.get("content")?.toString(),
            rawEvent?.get("text")?.toString()
        ).firstOrNull { !it.isNullOrBlank() }?.take(MAX_CONTENT)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ExternalAgentSupervisorTools::class.java)
        private const val CUSTOM_EVENT_NAME = "subagent_event"
        private const val EVENT_TYPE_ASSISTANT = "ASSISTANT"
        private const val EVENT_TYPE_REASONING = "REASONING"
        private const val MAX_CONTENT = 2000
        private const val REASONING_KEYWORD = "REASON"
    }
}
