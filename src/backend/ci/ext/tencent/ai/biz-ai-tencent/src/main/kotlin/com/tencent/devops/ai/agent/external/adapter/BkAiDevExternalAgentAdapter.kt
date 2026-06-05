package com.tencent.devops.ai.agent.external.adapter

import com.fasterxml.jackson.core.type.TypeReference
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
import com.tencent.devops.ai.pojo.ExternalAgentAuthMode
import com.tencent.devops.ai.pojo.ExternalAgentPlatformConfigInfo
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

    override fun resolveAgentId(context: ExternalAgentAgentIdResolveContext): String {
        return context.rawAgentId?.trim()?.takeIf { it.isNotBlank() }
            ?: context.agentName.trim().takeIf { it.isNotBlank() }
            ?: throw ExternalAgentErrors.configInvalid(AiMessageCode.EXTERNAL_AGENT_BKAIDEV_AGENT_NAME_REQUIRED)
    }

    override fun shouldRecalculateAgentId(context: ExternalAgentAgentIdRecalculationContext): Boolean {
        return context.platformChanged || context.agentIdChanged || context.agentNameChanged
    }

    override fun buildAuthHeaders(context: ExternalAgentAuthHeadersBuildContext): String {
        val authMode = resolveAuthMode(context.authConfig)
        val values = context.authConfig.values
        val headers = linkedMapOf<String, String>()
        val bkapiAuthorization = linkedMapOf<String, String>()
        when (authMode) {
            ExternalAgentAuthMode.APP -> {
                bkapiAuthorization["bk_app_code"] = requireValue(values, "bkAppCode", "bk_app_code")
                bkapiAuthorization["bk_app_secret"] = requireValue(values, "bkAppSecret", "bk_app_secret")
                headers[HEADER_BKAIDEV_USER] = context.userId
            }

            ExternalAgentAuthMode.USER -> {
                bkapiAuthorization[USER_ACCESS_TOKEN_KEY] = requireValue(
                    values = values,
                    key = "accessToken",
                    label = USER_ACCESS_TOKEN_KEY
                )
            }
        }
        headers[HEADER_BKAPI_AUTHORIZATION] = JsonUtil.toJson(bkapiAuthorization, false)
        return JsonUtil.toJson(headers)
    }

    override fun parseAuthConfig(headers: String?): ExternalAgentAuthConfig? {
        val parsedHeaders = AiMcpServerService.parseHeaders(headers)
        if (parsedHeaders.isEmpty()) {
            return null
        }
        val bkapiAuthorization = parseJsonMap(getHeaderIgnoreCase(parsedHeaders, HEADER_BKAPI_AUTHORIZATION))
        val authMode = when {
            !bkapiAuthorization[USER_ACCESS_TOKEN_KEY].isNullOrBlank() -> ExternalAgentAuthMode.USER
            !bkapiAuthorization["bk_app_code"].isNullOrBlank() ||
                !bkapiAuthorization["bk_app_secret"].isNullOrBlank() ||
                !getHeaderIgnoreCase(parsedHeaders, HEADER_BKAIDEV_USER).isNullOrBlank() -> ExternalAgentAuthMode.APP

            else -> null
        }
        val values = linkedMapOf<String, String>()
        bkapiAuthorization["bk_app_code"]?.takeIf { it.isNotBlank() }?.let { values["bkAppCode"] = it }
        bkapiAuthorization["bk_app_secret"]?.takeIf { it.isNotBlank() }?.let { values["bkAppSecret"] = it }
        bkapiAuthorization[USER_ACCESS_TOKEN_KEY]
            ?.takeIf { it.isNotBlank() }
            ?.let { values["accessToken"] = it }
        if (values.isEmpty() && authMode == null) {
            return null
        }
        return ExternalAgentAuthConfig(
            authMode = authMode,
            values = values
        )
    }

    override fun platformConfigInfo(): ExternalAgentPlatformConfigInfo {
        return ExternalAgentPlatformConfigInfo(
            platform = ExternalAgentPlatform.BKAIDEV,
            authModes = listOf(ExternalAgentAuthMode.APP, ExternalAgentAuthMode.USER),
            authFields = listOf(
                ExternalAgentAuthFieldInfo(
                    key = "bkAppCode",
                    label = "bk_app_code",
                    description = "BKAIDEV 应用态调用使用的 bk_app_code",
                    required = true,
                    authModes = listOf(ExternalAgentAuthMode.APP)
                ),
                ExternalAgentAuthFieldInfo(
                    key = "bkAppSecret",
                    label = "bk_app_secret",
                    description = "BKAIDEV 应用态调用使用的 bk_app_secret",
                    required = true,
                    secret = true,
                    authModes = listOf(ExternalAgentAuthMode.APP)
                ),
                ExternalAgentAuthFieldInfo(
                    key = "accessToken",
                    label = "access_token",
                    description = "BKAIDEV 用户态调用使用的 access_token",
                    required = true,
                    secret = true,
                    authModes = listOf(ExternalAgentAuthMode.USER)
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
                if (key in SECRET_KEYS) {
                    MASKED_HEADER_VALUE
                } else {
                    value
                }
            }
        )
    }

    override fun validateConfig(config: ExternalAgentConfigValidationContext) {
        if (config.apiUrl.isBlank()) {
            throw ExternalAgentErrors.configInvalid(AiMessageCode.EXTERNAL_AGENT_BKAIDEV_API_URL_REQUIRED)
        }
        if (config.apiUrl.contains(PLUGIN_INVOKE_PATH)) {
            throw ExternalAgentErrors.configInvalid(
                AiMessageCode.EXTERNAL_AGENT_BKAIDEV_PLUGIN_INVOKE_NOT_STREAMING
            )
        }
        val headers = AiMcpServerService.parseHeaders(config.headers)
        val bkapiAuthorization = headers[HEADER_BKAPI_AUTHORIZATION]
        if (bkapiAuthorization.isNullOrBlank()) {
            throw ExternalAgentErrors.configInvalid(
                AiMessageCode.EXTERNAL_AGENT_BKAIDEV_MISSING_BKAPI_AUTHORIZATION
            )
        }
        val hasAppUser = !headers[HEADER_BKAIDEV_USER].isNullOrBlank()
        val hasUserToken = bkapiAuthorization.contains(USER_ACCESS_TOKEN_KEY)
        if (!hasAppUser && !hasUserToken) {
            throw ExternalAgentErrors.configInvalid(
                AiMessageCode.EXTERNAL_AGENT_BKAIDEV_AUTH_HEADERS_REQUIRED
            )
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
        private const val MASKED_HEADER_VALUE = "******"
        private val SECRET_KEYS = setOf("bkAppSecret", "accessToken")
        private val logger = LoggerFactory.getLogger(BkAiDevExternalAgentAdapter::class.java)

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

        private fun resolveAuthMode(authConfig: ExternalAgentAuthConfig): ExternalAgentAuthMode {
            authConfig.authMode?.let { return it }
            val values = authConfig.values
            return when {
                !values["accessToken"].isNullOrBlank() -> ExternalAgentAuthMode.USER
                !values["bkAppCode"].isNullOrBlank() || !values["bkAppSecret"].isNullOrBlank() ->
                    ExternalAgentAuthMode.APP

                else -> throw ExternalAgentErrors.configInvalid(
                    AiMessageCode.EXTERNAL_AGENT_BKAIDEV_AUTH_MODE_REQUIRED
                )
            }
        }

        private fun requireValue(
            values: Map<String, String>,
            key: String,
            label: String
        ): String {
            return values[key]?.trim()?.takeIf { it.isNotBlank() }
                ?: throw ExternalAgentErrors.configInvalid(
                    AiMessageCode.EXTERNAL_AGENT_BKAIDEV_MISSING_FIELD,
                    label
                )
        }

        private fun getHeaderIgnoreCase(
            headers: Map<String, String>,
            key: String
        ): String? {
            return headers.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
        }

        private fun parseJsonMap(json: String?): Map<String, String> {
            if (json.isNullOrBlank()) {
                return emptyMap()
            }
            return try {
                JsonUtil.to(json, object : TypeReference<Map<String, String>>() {})
            } catch (ignored: Exception) {
                emptyMap()
            }
        }
    }
}
