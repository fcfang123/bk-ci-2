package com.tencent.devops.ai.agent.external

import com.tencent.devops.ai.context.AgentSessionContext
import com.tencent.devops.ai.service.ExternalAgentService
import com.tencent.devops.common.api.util.JsonUtil
import io.agentscope.core.agui.event.AguiEvent
import io.agentscope.core.tool.Tool
import io.agentscope.core.tool.ToolParam
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import java.util.function.Supplier

class ExternalAgentTools(
    private val externalAgentService: ExternalAgentService,
    private val externalAgentGateway: ExternalAgentGateway,
    private val sessionContext: AgentSessionContext,
    private val userIdSupplier: Supplier<String>,
    private val threadId: String?
) {

    @Tool(
        name = "call_external_agent",
        description = "调用用户已启用的外部智能体配置。适合用户明确 @ 外部智能体，" +
            "或问题需要委派给某个外部智能体处理时使用。多轮对话时优先复用上次返回的 conversation_id；" +
            "无 conversation_id 且需要携带上下文时，可传 chat_history。"
    )
    fun callExternalAgent(
        @ToolParam(name = "config_id", description = "外部智能体配置 ID 或显示名称")
        configId: String,
        @ToolParam(name = "query", description = "需要交给外部智能体处理的问题")
        query: String,
        @ToolParam(
            name = "conversation_id",
            description = "外部平台会话 ID。多轮续聊时传入上次工具返回的 conversationId",
            required = false
        )
        conversationId: String? = null,
        @ToolParam(
            name = "chat_history",
            description = "可选。AIDev 等多轮对话时的会话历史，值为 JSON 数组字符串；" +
                "数组元素为对象，固定两个字段：role（user/assistant/system）、content（消息正文）。" +
                "示例：[{\"role\":\"user\",\"content\":\"第一轮问题\"}," +
                "{\"role\":\"assistant\",\"content\":\"第一轮回答\"}]。" +
                "当前轮用户问题仍放在 query，不要重复写入 chat_history 最后一条。" +
                "未传 conversation_id 时由平台据此续聊；已传 conversation_id 时平台忽略此项。",
            required = false
        )
        chatHistory: String? = null
    ): String {
        val userId = userIdSupplier.get()
        var resolvedConfigId = configId
        var agentDisplayName = configId
        val content = StringBuilder()
        var externalConversationId: String? = conversationId?.takeIf { it.isNotBlank() }
        var errorCategory: ExternalAgentErrorCategory? = null
        var errorMessage: String? = null
        var reasoningPlaceholderEmitted = false

        return try {
            resolvedConfigId = externalAgentService.resolveEnabledConfigId(userId, configId)
            agentDisplayName = externalAgentService.getEnabled(userId, resolvedConfigId).agentName
                .takeIf { it.isNotBlank() }
                ?: resolvedConfigId
            val sinkInfo = threadId?.let { sessionContext.getSinkByThreadId(it) }
            val parsedChatHistory = ExternalAgentChatHistoryParser.parse(chatHistory)
            externalAgentGateway.stream(
                userId = userId,
                configId = resolvedConfigId,
                input = ExternalAgentInput(
                    query = query,
                    conversationId = conversationId.orEmpty(),
                    threadId = threadId,
                    runId = sinkInfo?.runId,
                    chatHistory = parsedChatHistory
                )
            ).doOnNext { event ->
                logger.info(
                    "[ExternalAgentSupervisorTool] upstream event: userId={}, configId={}, threadId={}, " +
                        "event={}, summary={}",
                    userId,
                    resolvedConfigId,
                    threadId,
                    event.javaClass.simpleName,
                    summarizeExternalEvent(event)
                )
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
                        reasoningPlaceholderEmitted = emitCustomProgress(
                            agentName = agentDisplayName,
                            configId = resolvedConfigId,
                            event = event,
                            conversationId = externalConversationId,
                            reasoningPlaceholderEmitted = reasoningPlaceholderEmitted
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
        val sinkInfo = threadId?.let { sessionContext.getSinkByThreadId(it) }
        if (sinkInfo == null) {
            logger.warn(
                "[ExternalAgentSupervisorTool] progress dropped: threadId={}, configId={}, eventType={}",
                threadId,
                configId,
                eventType
            )
            return
        }
        val data = mutableMapOf<String, Any?>(
            "agentName" to agentName,
            "configId" to configId,
            "eventType" to eventType,
            "conversationId" to conversationId,
            "isLast" to isLast
        )
        content?.take(MAX_CONTENT)?.let { data["content"] = it }
        data.putAll(extraData)
        logger.info(
            "[ExternalAgentSupervisorTool] emit subagent_event: threadId={}, configId={}, eventType={}, " +
                "isLast={}, content={}, extra={}",
            sinkInfo.threadId,
            configId,
            eventType,
            isLast,
            summarizeContent(content),
            summarizeExtraData(extraData)
        )
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
        conversationId: String?,
        reasoningPlaceholderEmitted: Boolean
    ): Boolean {
        val mappedEventType = if (isReasoningEventType(event.eventType)) {
            EVENT_TYPE_REASONING
        } else {
            EVENT_TYPE_ASSISTANT
        }
        val rawContent = extractCustomContent(event.data)
        val isReasoningPlaceholder = mappedEventType == EVENT_TYPE_REASONING &&
            !rawContent.isNullOrBlank() &&
            isReasoningPlaceholderContent(rawContent)
        val content = when {
            !isReasoningPlaceholder -> rawContent
            reasoningPlaceholderEmitted -> null
            else -> DEFAULT_REASONING_PLACEHOLDER
        }
        emitProgress(
            agentName = agentName,
            configId = configId,
            eventType = mappedEventType,
            content = content,
            conversationId = conversationId,
            extraData = mapOf("externalEventType" to event.eventType)
        )
        return reasoningPlaceholderEmitted || isReasoningPlaceholder
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

    private fun isReasoningEventType(eventType: String): Boolean {
        return REASONING_EVENT_KEYWORDS.any { keyword ->
            eventType.contains(keyword, ignoreCase = true)
        }
    }

    private fun isReasoningPlaceholderContent(content: String): Boolean {
        val normalized = content
            .replace(Regex("\\s+"), "")
            .replace(Regex("[.。,…，、!！?？~～:：;；·•\\-]+"), "")
        if (normalized.isBlank()) {
            return false
            }
        return REASONING_PLACEHOLDER_TOKENS.any { token ->
            normalized.replace(token, "").isEmpty()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ExternalAgentTools::class.java)
        private const val CUSTOM_EVENT_NAME = "subagent_event"
        private const val EVENT_TYPE_ASSISTANT = "ASSISTANT"
        private const val EVENT_TYPE_REASONING = "REASONING"
        private const val MAX_CONTENT = 2000
        private const val DEFAULT_REASONING_PLACEHOLDER = "正在思考..."
        private val REASONING_EVENT_KEYWORDS = listOf("REASON", "THINK")
        private val REASONING_PLACEHOLDER_TOKENS = listOf("正在思考", "思考中")

        private fun summarizeExternalEvent(event: ExternalAgentEvent): String {
            return when (event) {
                is ExternalAgentEvent.TextDelta ->
                    "delta=${summarizeContent(event.delta)}"

                is ExternalAgentEvent.ConversationId ->
                    "conversationId=${event.conversationId}"

                is ExternalAgentEvent.Custom ->
                    "eventType=${event.eventType}, content=${summarizeContent(extractSummaryContent(event.data))}"

                is ExternalAgentEvent.Error ->
                    "category=${event.category}, message=${summarizeContent(event.message)}"

                ExternalAgentEvent.Done -> "done"
            }
        }

        private fun extractSummaryContent(data: Map<String, Any?>): String? {
            val rawEvent = data["rawEvent"] as? Map<*, *>
            return listOf(
                data["delta"]?.toString(),
                data["content"]?.toString(),
                data["text"]?.toString(),
                rawEvent?.get("content")?.toString(),
                rawEvent?.get("text")?.toString()
            ).firstOrNull { !it.isNullOrBlank() }
        }

        private fun summarizeContent(content: String?): String {
            if (content.isNullOrBlank()) {
                return "-"
            }
            val normalized = content.replace(Regex("\\s+"), " ").trim()
            return if (normalized.length <= 120) {
                normalized
            } else {
                normalized.take(120) + "...(truncated)"
            }
        }

        private fun summarizeExtraData(extraData: Map<String, Any?>): String {
            if (extraData.isEmpty()) {
                return "-"
            }
            return extraData.entries.joinToString(",") { "${it.key}=${it.value}" }
        }
    }
}
