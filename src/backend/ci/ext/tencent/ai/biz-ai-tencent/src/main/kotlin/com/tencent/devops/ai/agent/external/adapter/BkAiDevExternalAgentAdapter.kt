package com.tencent.devops.ai.agent.external.adapter

import com.tencent.devops.ai.agent.external.util.ExternalAgentSseEventParser
import com.tencent.devops.ai.agent.external.util.ExternalAgentSseLineBuffer
import com.tencent.devops.ai.agent.external.ExternalAgentAdapter
import com.tencent.devops.ai.agent.external.ExternalAgentConfigValidationContext
import com.tencent.devops.ai.agent.external.ExternalAgentErrorCategory
import com.tencent.devops.ai.agent.external.ExternalAgentEvent
import com.tencent.devops.ai.agent.external.ExternalAgentGatewayException
import com.tencent.devops.ai.agent.external.ExternalAgentRequest
import com.tencent.devops.ai.pojo.ExternalAgentPlatform
import com.tencent.devops.ai.service.AiMcpServerService
import com.tencent.devops.common.api.util.JsonUtil
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux
import reactor.core.publisher.Flux

@Component
class BkAiDevExternalAgentAdapter : ExternalAgentAdapter {

    private val webClient = WebClient.builder()
        .codecs { it.defaultCodecs().maxInMemorySize(MAX_RESPONSE_SIZE) }
        .build()

    override fun platform(): ExternalAgentPlatform = ExternalAgentPlatform.BKAIDEV

    override fun validateConfig(config: ExternalAgentConfigValidationContext) {
        if (config.apiUrl.isBlank()) {
            throw invalidConfig("AIDev API URL 不能为空")
        }
        if (config.apiUrl.contains(PLUGIN_INVOKE_PATH)) {
            throw invalidConfig("AIDev 蓝鲸插件调用接口不支持流式输出，请使用 chat_completion 接口")
        }
        val headers = AiMcpServerService.parseHeaders(config.headers)
        val bkapiAuthorization = headers[HEADER_BKAPI_AUTHORIZATION]
        if (bkapiAuthorization.isNullOrBlank()) {
            throw invalidConfig("AIDev 配置缺少 $HEADER_BKAPI_AUTHORIZATION")
        }
        val hasAppUser = !headers[HEADER_BKAIDEV_USER].isNullOrBlank()
        val hasUserToken = bkapiAuthorization.contains(USER_ACCESS_TOKEN_KEY)
        if (!hasAppUser && !hasUserToken) {
            throw invalidConfig("AIDev 用户态配置需要 access_token，应用态配置需要 $HEADER_BKAIDEV_USER")
        }
    }

    override fun stream(request: ExternalAgentRequest): Flux<ExternalAgentEvent> {
        val config = request.config
        val headers = sanitizeHeaders(AiMcpServerService.parseHeaders(config.headers))
        val executeKwargs = mutableMapOf<String, Any>("stream" to true)
        if (request.conversationId.isNotBlank()) {
            executeKwargs["thread_id"] = request.conversationId
        }
        val body = mapOf(
            "input" to request.query,
            "chat_history" to request.chatHistory.ifEmpty { emptyList<Any>() },
            "execute_kwargs" to executeKwargs
        )

        logger.info(
            "[ExternalAgentGateway] BkAiDev stream start: userId={}, configId={}, platform={}, url={}",
            request.userId, config.id, config.platform, config.apiUrl
        )
        val lineBuffer = ExternalAgentSseLineBuffer(ExternalAgentSseEventParser::parseBkAiDev)
        return webClient.post()
            .uri(config.apiUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .headers { httpHeaders -> headers.forEach { (key, value) -> httpHeaders.set(key, value) } }
            .bodyValue(body)
            .retrieve()
            .bodyToFlux<String>()
            .doOnNext { chunk ->
                logger.debug(
                    "[ExternalAgentGateway] BkAiDev raw chunk: userId={}, configId={}, chars={}, shape={}",
                    request.userId,
                    config.id,
                    chunk.length,
                    ExternalAgentSseEventParser.describeChunk(chunk)
                )
            }
            .concatMap { chunk -> Flux.fromIterable(lineBuffer.accept(chunk)) }
            .concatWith(Flux.defer { Flux.fromIterable(lineBuffer.flush()) })
            .doOnError { error ->
                logger.warn(
                    "[ExternalAgentGateway] BkAiDev upstream failed: userId={}, configId={}, " +
                        "platform={}, url={}, errorType={}, detail={}",
                    request.userId,
                    config.id,
                    config.platform,
                    config.apiUrl,
                    error.javaClass.simpleName,
                    summarizeUpstreamError(error)
                )
            }
            .doOnCancel {
                logger.info(
                    "[ExternalAgentGateway] BkAiDev stream cancelled: userId={}, configId={}",
                    request.userId, config.id
                )
            }
    }

    companion object {
        private const val HEADER_BKAPI_AUTHORIZATION = "X-Bkapi-Authorization"
        private const val HEADER_BKAIDEV_USER = "X-BKAIDEV-USER"
        private const val USER_ACCESS_TOKEN_KEY = "access_token"
        private const val PLUGIN_INVOKE_PATH = "/prod/invoke/"
        private const val MAX_RESPONSE_SIZE = 10 * 1024 * 1024
        private const val MAX_ERROR_DETAIL_CHARS = 1000
        private val logger = LoggerFactory.getLogger(BkAiDevExternalAgentAdapter::class.java)

        private fun invalidConfig(message: String): ExternalAgentGatewayException {
            return ExternalAgentGatewayException(
                category = ExternalAgentErrorCategory.CONFIG_INVALID,
                message = message
            )
        }

        internal fun summarizeUpstreamError(error: Throwable): String {
            return when (error) {
                is WebClientResponseException -> {
                    val contentType = error.headers.contentType?.toString() ?: "unknown"
                    val responseBody = sanitizeForLog(error.responseBodyAsString)
                    "status=${error.statusCode.value()}, statusText=${error.statusText}, " +
                        "contentType=$contentType, body=$responseBody"
                }

                is WebClientRequestException -> {
                    val rootCause = error.cause ?: error
                    val causeMessage = sanitizeForLog(rootCause.message)
                    "method=${error.method}, uri=${error.uri}, causeType=${rootCause.javaClass.simpleName}, " +
                        "causeMessage=$causeMessage"
                }

                else -> {
                    val cause = error.cause
                    val causeType = cause?.javaClass?.simpleName ?: "none"
                    val causeMessage = sanitizeForLog(cause?.message)
                    val message = sanitizeForLog(error.message)
                    "message=$message, causeType=$causeType, causeMessage=$causeMessage"
                }
            }
        }

        internal fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> {
            return headers.mapValues { (key, value) ->
                when (key) {
                    HEADER_BKAPI_AUTHORIZATION -> sanitizeBkapiAuthorization(value)
                    else -> sanitizeHeaderValue(value)
                }
            }
        }

        private fun sanitizeBkapiAuthorization(value: String): String {
            val normalized = sanitizeHeaderValue(value)
            return try {
                JsonUtil.toJson(
                    JsonUtil.to<Map<String, Any>>(normalized),
                    false
                )
            } catch (ignored: Exception) {
                normalized
            }
        }

        private fun sanitizeHeaderValue(value: String): String {
            return value.replace(Regex("[\\r\\n]+"), "").trim()
        }

        private fun sanitizeForLog(message: String?): String {
            if (message.isNullOrBlank()) {
                return "-"
            }
            val normalized = message.replace(Regex("\\s+"), " ").trim()
            return if (normalized.length <= MAX_ERROR_DETAIL_CHARS) {
                normalized
            } else {
                normalized.take(MAX_ERROR_DETAIL_CHARS) + "...(truncated)"
            }
        }
    }
}
