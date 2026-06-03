package com.tencent.devops.ai.external

import com.tencent.devops.ai.properties.ExternalAgentGatewayProperties
import com.tencent.devops.ai.service.ExternalAgentService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Service
class ExternalAgentGateway @Autowired constructor(
    private val externalAgentService: ExternalAgentService,
    adapters: List<ExternalAgentAdapter>,
    private val properties: ExternalAgentGatewayProperties
) {

    private val adapterMap = adapters.associateBy { it.platform().uppercase() }

    fun stream(
        userId: String,
        configId: String,
        input: ExternalAgentInput
    ): Flux<ExternalAgentEvent> {
        if (!properties.enabled) {
            return Flux.error(
                ExternalAgentGatewayException(
                    category = ExternalAgentErrorCategory.CONFIG_INVALID,
                    message = "外部智能体流式网关未启用"
                )
            )
        }

        val config = externalAgentService.getEnabled(userId = userId, configId = configId)
        val platform = config.platform.uppercase()
        val adapter = adapterMap[platform]
            ?: return Flux.error(
                ExternalAgentGatewayException(
                    category = ExternalAgentErrorCategory.UNSUPPORTED_PLATFORM,
                    message = "暂不支持该外部智能体平台"
                )
            )

        logger.info(
            "[ExternalAgentGateway] stream start: userId={}, configId={}, platform={}, threadId={}, runId={}",
            userId, configId, config.platform, input.threadId, input.runId
        )

        val responseChars = AtomicInteger(0)
        val eventCount = AtomicInteger(0)
        val eventTypeCounts = ConcurrentHashMap<String, AtomicInteger>()
        val firstTokenRecorded = AtomicBoolean(false)
        val startedAt = System.currentTimeMillis()
        val externalRequest = ExternalAgentRequest(
            userId = userId,
            config = config,
            query = input.query,
            conversationId = input.conversationId,
            threadId = input.threadId,
            runId = input.runId,
            chatHistory = input.chatHistory
        )

        return adapter.stream(externalRequest)
            .timeout(Duration.ofSeconds(properties.firstTokenTimeoutSeconds))
            .doOnNext { event ->
                recordFirstToken(
                    recorded = firstTokenRecorded,
                    startedAt = startedAt,
                    userId = userId,
                    configId = configId,
                    platform = config.platform,
                    input = input
                )
                recordEventType(eventCount, eventTypeCounts, event)
                recordEventSize(responseChars, event)
            }
            .timeout(Duration.ofSeconds(properties.streamTimeoutSeconds))
            .doOnCancel {
                logger.info(
                    "[ExternalAgentGateway] stream cancelled: userId={}, configId={}, platform={}, " +
                        "threadId={}, runId={}, totalMs={}, size={}, category={}",
                    userId,
                    configId,
                    config.platform,
                    input.threadId,
                    input.runId,
                    System.currentTimeMillis() - startedAt,
                    responseChars.get(),
                    ExternalAgentErrorCategory.CANCELLED
                )
            }
            .doOnComplete {
                val eventSummary = eventTypeCounts.entries
                    .sortedBy { it.key }
                    .joinToString(",") { "${it.key}=${it.value.get()}" }
                if (responseChars.get() == 0) {
                    logger.warn(
                        "[ExternalAgentGateway] stream completed without text delta: userId={}, configId={}, " +
                            "platform={}, threadId={}, runId={}, totalMs={}, events={}, eventSummary={}",
                        userId,
                        configId,
                        config.platform,
                        input.threadId,
                        input.runId,
                        System.currentTimeMillis() - startedAt,
                        eventCount.get(),
                        eventSummary
                    )
                }
                logger.info(
                    "[ExternalAgentGateway] stream complete: userId={}, configId={}, platform={}, " +
                        "threadId={}, runId={}, totalMs={}, size={}, events={}, eventSummary={}",
                    userId,
                    configId,
                    config.platform,
                    input.threadId,
                    input.runId,
                    System.currentTimeMillis() - startedAt,
                    responseChars.get(),
                    eventCount.get(),
                    eventSummary
                )
            }
            .onErrorMap { error ->
                if (error is ExternalAgentGatewayException) {
                    error
                } else if (error is TimeoutException) {
                    ExternalAgentGatewayException(
                        category = ExternalAgentErrorCategory.TIMEOUT,
                        message = "外部智能体响应超时，请稍后重试"
                    )
                } else {
                    ExternalAgentGatewayException(
                        category = ExternalAgentErrorCategory.UPSTREAM_ERROR,
                        message = "外部智能体调用失败，请稍后重试"
                    )
                }
            }
            .doOnError { error ->
                val category = (error as? ExternalAgentGatewayException)?.category
                    ?: ExternalAgentErrorCategory.UPSTREAM_ERROR
                logger.warn(
                    "[ExternalAgentGateway] stream failed: userId={}, configId={}, platform={}, " +
                        "threadId={}, runId={}, totalMs={}, size={}, category={}, error={}",
                    userId,
                    configId,
                    config.platform,
                    input.threadId,
                    input.runId,
                    System.currentTimeMillis() - startedAt,
                    responseChars.get(),
                    category,
                    error.message
                )
            }
    }

    private fun recordFirstToken(
        recorded: AtomicBoolean,
        startedAt: Long,
        userId: String,
        configId: String,
        platform: String,
        input: ExternalAgentInput
    ) {
        if (!recorded.compareAndSet(false, true)) {
            return
        }
        logger.info(
            "[ExternalAgentGateway] first token: userId={}, configId={}, platform={}, " +
                "threadId={}, runId={}, firstTokenMs={}",
            userId,
            configId,
            platform,
            input.threadId,
            input.runId,
            System.currentTimeMillis() - startedAt
        )
    }

    private fun recordEventSize(
        responseChars: AtomicInteger,
        event: ExternalAgentEvent
    ) {
        if (event !is ExternalAgentEvent.TextDelta) {
            return
        }
        val currentSize = responseChars.addAndGet(event.delta.length)
        if (currentSize > properties.maxResponseChars) {
            throw ExternalAgentGatewayException(
                category = ExternalAgentErrorCategory.UPSTREAM_ERROR,
                message = "外部智能体响应过大"
            )
        }
    }

    private fun recordEventType(
        eventCount: AtomicInteger,
        eventTypeCounts: ConcurrentHashMap<String, AtomicInteger>,
        event: ExternalAgentEvent
    ) {
        eventCount.incrementAndGet()
        val eventType = when (event) {
            is ExternalAgentEvent.TextDelta -> "text_delta"
            is ExternalAgentEvent.ConversationId -> "conversation_id"
            is ExternalAgentEvent.Custom -> "custom:${event.eventType}"
            is ExternalAgentEvent.Error -> "error:${event.category}"
            ExternalAgentEvent.Done -> "done"
        }
        eventTypeCounts.computeIfAbsent(eventType) { AtomicInteger(0) }.incrementAndGet()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ExternalAgentGateway::class.java)
    }
}
