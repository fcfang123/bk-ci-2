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
class KnotExternalAgentAdapter : ExternalAgentAdapter {

    private val webClient = WebClient.builder()
        .codecs { it.defaultCodecs().maxInMemorySize(MAX_RESPONSE_SIZE) }
        .build()

    override fun platform(): String = PLATFORM

    override fun validateConfig(config: ExternalAgentConfigValidationContext) {
        if (config.apiUrl.isBlank() || !config.apiUrl.contains(AGUI_PATH)) {
            throw invalidConfig("Knot 配置需要 AG-UI 端点 URL")
        }
        val headers = AiMcpServerService.parseHeaders(config.headers)
        if (headers[HEADER_API_TOKEN].isNullOrBlank()) {
            throw invalidConfig("Knot 配置缺少 $HEADER_API_TOKEN")
        }
        if (headers[HEADER_API_USER].isNullOrBlank()) {
            throw invalidConfig("Knot 配置缺少 $HEADER_API_USER")
        }
    }

    override fun stream(request: ExternalAgentRequest): Flux<ExternalAgentEvent> {
        val config = request.config
        val headers = AiMcpServerService.parseHeaders(config.headers)
        val body = mapOf(
            "input" to mapOf(
                "message" to request.query,
                "conversation_id" to request.conversationId,
                "stream" to true
            )
        )

        logger.info(
            "[ExternalAgentGateway] Knot stream start: userId={}, configId={}, platform={}, url={}",
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
            .flatMapIterable { ExternalAgentSseEventParser.parseKnot(it) }
            .doOnCancel {
                logger.info(
                    "[ExternalAgentGateway] Knot stream cancelled: userId={}, configId={}",
                    request.userId, config.id
                )
            }
    }

    companion object {
        const val PLATFORM = "KNOT"
        private const val HEADER_API_TOKEN = "x-knot-api-token"
        private const val HEADER_API_USER = "x-knot-api-user"
        private const val AGUI_PATH = "/agui/"
        private const val MAX_RESPONSE_SIZE = 10 * 1024 * 1024
        private val logger = LoggerFactory.getLogger(KnotExternalAgentAdapter::class.java)

        private fun invalidConfig(message: String): ExternalAgentGatewayException {
            return ExternalAgentGatewayException(
                category = ExternalAgentErrorCategory.CONFIG_INVALID,
                message = message
            )
        }
    }
}
