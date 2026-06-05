package com.tencent.devops.ai.agent.external.adapter

import com.tencent.devops.ai.agent.external.util.ExternalAgentSseEventParser
import com.tencent.devops.ai.agent.external.util.ExternalAgentSseLineBuffer
import com.tencent.devops.ai.agent.external.ExternalAgentAdapter
import com.tencent.devops.ai.agent.external.ExternalAgentAgentIdRecalculationContext
import com.tencent.devops.ai.agent.external.ExternalAgentAgentIdResolveContext
import com.tencent.devops.ai.agent.external.ExternalAgentAuthHeadersBuildContext
import com.tencent.devops.ai.agent.external.ExternalAgentConfigValidationContext
import com.tencent.devops.ai.agent.external.ExternalAgentErrors
import com.tencent.devops.ai.agent.external.ExternalAgentEvent
import com.tencent.devops.ai.agent.external.ExternalAgentRequest
import com.tencent.devops.ai.constant.AiMessageCode
import com.tencent.devops.ai.pojo.ExternalAgentAuthConfig
import com.tencent.devops.ai.pojo.ExternalAgentAuthFieldInfo
import com.tencent.devops.ai.pojo.ExternalAgentPlatformConfigInfo
import com.tencent.devops.ai.pojo.ExternalAgentPlatform
import com.tencent.devops.ai.service.AiMcpServerService
import com.tencent.devops.common.api.util.JsonUtil
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux
import reactor.core.publisher.Flux
import java.net.URI

@Component
class KnotExternalAgentAdapter : ExternalAgentAdapter {

    private val webClient = WebClient.builder()
        .codecs { it.defaultCodecs().maxInMemorySize(MAX_RESPONSE_SIZE) }
        .build()

    override fun platform(): ExternalAgentPlatform = ExternalAgentPlatform.KNOT

    override fun resolveAgentId(context: ExternalAgentAgentIdResolveContext): String {
        return extractAgentId(context.apiUrl)
            ?: context.rawAgentId?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ExternalAgentErrors.configInvalid(AiMessageCode.EXTERNAL_AGENT_KNOT_AGENT_ID_FROM_URL)
    }

    override fun shouldRecalculateAgentId(context: ExternalAgentAgentIdRecalculationContext): Boolean {
        return context.platformChanged || context.apiUrlChanged || context.agentIdChanged
    }

    override fun buildAuthHeaders(context: ExternalAgentAuthHeadersBuildContext): String {
        val values = context.authConfig.values
        val token = values["knotApiToken"]?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ExternalAgentErrors.configInvalid(
                AiMessageCode.EXTERNAL_AGENT_KNOT_MISSING_HEADER,
                HEADER_API_TOKEN
            )
        return JsonUtil.toJson(
            linkedMapOf(
                HEADER_API_TOKEN to token,
                HEADER_API_USER to context.userId
            )
        )
    }

    override fun parseAuthConfig(headers: String?): ExternalAgentAuthConfig? {
        val parsedHeaders = AiMcpServerService.parseHeaders(headers)
        if (parsedHeaders.isEmpty()) {
            return null
        }
        val values = linkedMapOf<String, String>()
        getHeaderIgnoreCase(parsedHeaders, HEADER_API_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?.let { values["knotApiToken"] = it }
        if (values.isEmpty()) {
            return null
        }
        return ExternalAgentAuthConfig(values = values)
    }

    override fun platformConfigInfo(): ExternalAgentPlatformConfigInfo {
        return ExternalAgentPlatformConfigInfo(
            platform = ExternalAgentPlatform.KNOT,
            authFields = listOf(
                ExternalAgentAuthFieldInfo(
                    key = "knotApiToken",
                    label = "x-knot-api-token",
                    description = "Knot 个人或团队 token",
                    required = true,
                    secret = true
                )
            )
        )
    }

    override fun maskAuthConfig(authConfig: ExternalAgentAuthConfig?): ExternalAgentAuthConfig? {
        if (authConfig == null) {
            return null
        }
        return authConfig.copy(
            values = authConfig.values.mapValues { (key, value) ->
                if (key == "knotApiToken") {
                    MASKED_HEADER_VALUE
                } else {
                    value
                }
            }
        )
    }

    override fun validateConfig(config: ExternalAgentConfigValidationContext) {
        if (config.apiUrl.isBlank() || !config.apiUrl.contains(AGUI_PATH)) {
            throw ExternalAgentErrors.configInvalid(AiMessageCode.EXTERNAL_AGENT_KNOT_AGUI_URL_REQUIRED)
        }
        val headers = AiMcpServerService.parseHeaders(config.headers)
        if (headers[HEADER_API_TOKEN].isNullOrBlank()) {
            throw ExternalAgentErrors.configInvalid(
                AiMessageCode.EXTERNAL_AGENT_KNOT_MISSING_HEADER,
                HEADER_API_TOKEN
            )
        }
        if (headers[HEADER_API_USER].isNullOrBlank()) {
            throw ExternalAgentErrors.configInvalid(
                AiMessageCode.EXTERNAL_AGENT_KNOT_MISSING_HEADER,
                HEADER_API_USER
            )
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
        val lineBuffer = ExternalAgentSseLineBuffer(ExternalAgentSseEventParser::parseKnot)
        return webClient.post()
            .uri(config.apiUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .headers { httpHeaders -> headers.forEach { (key, value) -> httpHeaders.set(key, value) } }
            .bodyValue(body)
            .retrieve()
            .bodyToFlux<String>()
            .doOnNext { chunk ->
                logger.debug(
                    "[ExternalAgentGateway] Knot raw chunk: userId={}, configId={}, chars={}, shape={}",
                    request.userId,
                    config.id,
                    chunk.length,
                    ExternalAgentSseEventParser.describeChunk(chunk)
                )
            }
            .concatMap { chunk -> Flux.fromIterable(lineBuffer.accept(chunk)) }
            .concatWith(Flux.defer { Flux.fromIterable(lineBuffer.flush()) })
            .doOnCancel {
                logger.info(
                    "[ExternalAgentGateway] Knot stream cancelled: userId={}, configId={}",
                    request.userId, config.id
                )
            }
    }

    companion object {
        private const val HEADER_API_TOKEN = "x-knot-api-token"
        private const val HEADER_API_USER = "x-knot-api-user"
        private const val AGUI_PATH = "/agui/"
        private const val MAX_RESPONSE_SIZE = 10 * 1024 * 1024
        private const val MASKED_HEADER_VALUE = "******"
        private val logger = LoggerFactory.getLogger(KnotExternalAgentAdapter::class.java)

        private fun extractAgentId(apiUrl: String): String? {
            return runCatching { URI(apiUrl) }.getOrNull()
                ?.path
                ?.split("/")
                ?.lastOrNull { it.isNotBlank() }
        }

        private fun getHeaderIgnoreCase(
            headers: Map<String, String>,
            key: String
        ): String? {
            return headers.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
        }
    }
}
