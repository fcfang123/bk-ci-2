package com.tencent.devops.ai.agent.external

import com.tencent.devops.ai.constant.AiMessageCode
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.web.utils.I18nUtil

object ExternalAgentErrors {

    fun configInvalid(
        errorCode: String,
        vararg params: String
    ): ExternalAgentGatewayException {
        return ExternalAgentGatewayException(
            category = ExternalAgentErrorCategory.CONFIG_INVALID,
            errorCode = errorCode,
            params = if (params.isEmpty()) null else arrayOf(*params)
        )
    }

    fun unsupportedPlatform(): ExternalAgentGatewayException {
        return ExternalAgentGatewayException(
            category = ExternalAgentErrorCategory.UNSUPPORTED_PLATFORM,
            errorCode = AiMessageCode.EXTERNAL_AGENT_UNSUPPORTED_PLATFORM
        )
    }

    fun gatewayDisabled(): ExternalAgentGatewayException {
        return ExternalAgentGatewayException(
            category = ExternalAgentErrorCategory.CONFIG_INVALID,
            errorCode = AiMessageCode.EXTERNAL_AGENT_GATEWAY_DISABLED
        )
    }

    fun gatewayTimeout(): ExternalAgentGatewayException {
        return ExternalAgentGatewayException(
            category = ExternalAgentErrorCategory.TIMEOUT,
            errorCode = AiMessageCode.EXTERNAL_AGENT_GATEWAY_TIMEOUT
        )
    }

    fun gatewayUpstreamFailed(): ExternalAgentGatewayException {
        return ExternalAgentGatewayException(
            category = ExternalAgentErrorCategory.UPSTREAM_ERROR,
            errorCode = AiMessageCode.EXTERNAL_AGENT_GATEWAY_UPSTREAM_FAILED
        )
    }

    fun gatewayResponseTooLarge(): ExternalAgentGatewayException {
        return ExternalAgentGatewayException(
            category = ExternalAgentErrorCategory.UPSTREAM_ERROR,
            errorCode = AiMessageCode.EXTERNAL_AGENT_GATEWAY_RESPONSE_TOO_LARGE
        )
    }

    fun sseParseFailed(): ExternalAgentGatewayException {
        return ExternalAgentGatewayException(
            category = ExternalAgentErrorCategory.PARSE_ERROR,
            errorCode = AiMessageCode.EXTERNAL_AGENT_SSE_PARSE_FAILED
        )
    }

    fun sseUpstreamError(message: String?): ExternalAgentGatewayException {
        return if (message.isNullOrBlank()) {
            ExternalAgentGatewayException(
                category = ExternalAgentErrorCategory.UPSTREAM_ERROR,
                errorCode = AiMessageCode.EXTERNAL_AGENT_SSE_UPSTREAM_ERROR
            )
        } else {
            ExternalAgentGatewayException(
                category = ExternalAgentErrorCategory.UPSTREAM_ERROR,
                errorCode = AiMessageCode.EXTERNAL_AGENT_SSE_UPSTREAM_ERROR_WITH_DETAIL,
                params = arrayOf(message)
            )
        }
    }

    fun resolveMessage(
        errorCode: String,
        params: Array<String>? = null
    ): String {
        return runCatching {
            I18nUtil.getCodeLanMessage(
                messageCode = errorCode,
                params = params,
                defaultMessage = errorCode
            )
        }.getOrElse { errorCode }.ifBlank { errorCode }
    }

    fun toErrorCodeException(exception: ExternalAgentGatewayException): ErrorCodeException {
        return ErrorCodeException(
            statusCode = 400,
            errorCode = exception.errorCode,
            params = exception.params
        )
    }
}
