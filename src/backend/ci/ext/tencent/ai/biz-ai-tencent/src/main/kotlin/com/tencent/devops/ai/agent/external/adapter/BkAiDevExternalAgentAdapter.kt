package com.tencent.devops.ai.agent.external.adapter

import com.tencent.devops.ai.external.ExternalAgentAdapter
import com.tencent.devops.ai.external.ExternalAgentConfigValidationContext
import com.tencent.devops.ai.external.ExternalAgentErrorCategory
import com.tencent.devops.ai.external.ExternalAgentEvent
import com.tencent.devops.ai.external.ExternalAgentGatewayException
import com.tencent.devops.ai.external.ExternalAgentRequest
import com.tencent.devops.ai.service.AiMcpServerService
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux
import reactor.core.publisher.Flux

@Component
class BkAiDevExternalAgentAdapter : ExternalAgentAdapter {

    private val webClient = WebClient.builder()
        .codecs { it.defaultCodecs().maxInMemorySize(MAX_RESPONSE_SIZE) }
        .build()

    override fun platform(): String = PLATFORM

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
        val headers = AiMcpServerService.parseHeaders(config.headers)
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
        return webClient.post()
            .uri(config.apiUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .headers { httpHeaders -> headers.forEach { (key, value) -> httpHeaders.set(key, value) } }
            .bodyValue(body)
            .retrieve()
            .bodyToFlux<String>()
            .flatMapIterable { ExternalAgentSseEventParser.parseBkAiDev(it) }
            .doOnCancel {
                logger.info(
                    "[ExternalAgentGateway] BkAiDev stream cancelled: userId={}, configId={}",
                    request.userId, config.id
                )
            }
    }

    companion object {
        const val PLATFORM = "BKAIDEV"
        private const val HEADER_BKAPI_AUTHORIZATION = "X-Bkapi-Authorization"
        private const val HEADER_BKAIDEV_USER = "X-BKAIDEV-USER"
        private const val USER_ACCESS_TOKEN_KEY = "access_token"
        private const val PLUGIN_INVOKE_PATH = "/prod/invoke/"
        private const val MAX_RESPONSE_SIZE = 10 * 1024 * 1024
        private val logger = LoggerFactory.getLogger(BkAiDevExternalAgentAdapter::class.java)

        private fun invalidConfig(message: String): ExternalAgentGatewayException {
            return ExternalAgentGatewayException(
                category = ExternalAgentErrorCategory.CONFIG_INVALID,
                message = message
            )
        }
    }
}
