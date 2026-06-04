/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.ai.service

import com.fasterxml.jackson.core.type.TypeReference
import com.tencent.devops.ai.constant.AiMessageCode
import com.tencent.devops.ai.dao.ExternalAgentConfigDao
import com.tencent.devops.ai.agent.external.ExternalAgentAdapter
import com.tencent.devops.ai.agent.external.ExternalAgentConfigValidationContext
import com.tencent.devops.ai.agent.external.ExternalAgentGatewayException
import com.tencent.devops.ai.pojo.ExternalAgentAuthConfig
import com.tencent.devops.ai.pojo.ExternalAgentAuthMode
import com.tencent.devops.ai.pojo.ExternalAgentCreate
import com.tencent.devops.ai.pojo.ExternalAgentInfo
import com.tencent.devops.ai.pojo.ExternalAgentPlatform
import com.tencent.devops.ai.pojo.ExternalAgentUpdate
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.util.AESUtil
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.api.util.UUIDUtil
import com.tencent.devops.model.ai.tables.records.TAiExternalAgentConfigRecord
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.ZoneOffset

/**
 * 外部智能体配置管理服务，支持对接第三方智能体平台（如 Knot）。
 */
@Service
class ExternalAgentService @Autowired constructor(
    private val dslContext: DSLContext,
    private val dao: ExternalAgentConfigDao,
    adapters: List<ExternalAgentAdapter> = emptyList(),
    @Value("\${aes.aesKey}")
    private val aesKey: String = ""
) {

    private val adapterMap = adapters.associateBy { it.platform() }

    fun create(
        userId: String,
        request: ExternalAgentCreate
    ): ExternalAgentInfo {
        val id = UUIDUtil.generate()
        val platform = request.platform
        val assembledHeaders = resolveHeaders(
            platform = platform,
            headers = request.headers,
            authConfig = request.authConfig
        )
        validateConfig(
            platform = platform.name,
            agentId = request.agentId,
            apiUrl = request.apiUrl,
            headers = assembledHeaders
        )
        logger.info(
            "[ExternalAgent] Creating: id={}, userId={}, " +
                "name={}, platform={}",
            id, userId, request.agentName, platform.name
        )
        dao.create(
            dslContext = dslContext,
            id = id,
            userId = userId,
            agentName = request.agentName,
            description = request.description,
            platform = platform.name,
            agentId = request.agentId,
            apiUrl = request.apiUrl,
            headers = encryptHeaders(assembledHeaders),
            enabled = request.enabled
        )
        val record = dao.getById(dslContext, id)
            ?: throw ErrorCodeException(
                errorCode = AiMessageCode.CREATE_EXTERNAL_AGENT_FAILED,
                defaultMessage =
                    "Failed to create external agent config"
            )
        return toInfo(record, maskHeaders = true)
    }

    fun list(userId: String): List<ExternalAgentInfo> {
        return dao.listByUser(dslContext, userId)
            .map { toInfo(it, maskHeaders = true) }
    }

    fun listEnabled(userId: String?): List<ExternalAgentInfo> {
        if (userId.isNullOrBlank()) return emptyList()
        return dao.listEnabledByUser(dslContext, userId)
            .map { toInfo(it, maskHeaders = false) }
    }

    fun getEnabled(
        userId: String,
        configId: String
    ): ExternalAgentInfo {
        val record = getOwnedRecord(userId, configId)
        if (!record.enabled) {
            throw ErrorCodeException(
                statusCode = 403,
                errorCode = AiMessageCode.EXTERNAL_AGENT_NO_PERMISSION,
                defaultMessage = "External agent config is disabled",
                params = arrayOf(configId)
            )
        }
        return toInfo(record, maskHeaders = false)
    }

    fun resolveEnabledConfigId(
        userId: String,
        configIdOrName: String
    ): String {
        val normalized = configIdOrName.trim()
        val enabledConfigs = listEnabled(userId)
        enabledConfigs.firstOrNull { it.id == normalized }?.let { return it.id }

        val exactNameMatches = enabledConfigs.filter { it.agentName == normalized }
        if (exactNameMatches.size == 1) {
            return exactNameMatches.single().id
        }
        if (exactNameMatches.size > 1) {
            throw invalidConfig("存在多个同名外部智能体，请改用配置 ID 调用")
        }

        val ignoreCaseNameMatches = enabledConfigs.filter {
            it.agentName.equals(normalized, ignoreCase = true)
        }
        if (ignoreCaseNameMatches.size == 1) {
            return ignoreCaseNameMatches.single().id
        }
        if (ignoreCaseNameMatches.size > 1) {
            throw invalidConfig("存在多个名称近似的外部智能体，请改用配置 ID 调用")
        }

        throw ErrorCodeException(
            statusCode = 404,
            errorCode = AiMessageCode.EXTERNAL_AGENT_NOT_FOUND,
            defaultMessage = "External agent config not found",
            params = arrayOf(configIdOrName)
        )
    }

    fun update(
        userId: String,
        configId: String,
        request: ExternalAgentUpdate
    ): Boolean {
        logger.info(
            "[ExternalAgent] Updating: userId={}, configId={}",
            userId, configId
        )
        val record = getOwnedRecord(userId, configId)
        val currentHeaders = decryptHeaders(record.headers)
        val effectivePlatform = request.platform ?: ExternalAgentPlatform.fromValue(record.platform)
        val effectiveHeaders = resolveEffectiveHeaders(
            platform = effectivePlatform,
            currentHeaders = currentHeaders,
            rawHeaders = request.headers,
            authConfig = request.authConfig
        )
        validateConfig(
            platform = effectivePlatform.name,
            agentId = request.agentId ?: record.agentId,
            apiUrl = request.apiUrl ?: record.apiUrl,
            headers = effectiveHeaders
        )
        return dao.update(
            dslContext = dslContext,
            id = configId,
            agentName = request.agentName,
            description = request.description,
            platform = request.platform?.name,
            agentId = request.agentId,
            apiUrl = request.apiUrl,
            headers = resolveUpdatedEncryptedHeaders(
                platform = effectivePlatform,
                currentHeaders = currentHeaders,
                rawHeaders = request.headers,
                authConfig = request.authConfig
            ),
            enabled = request.enabled
        ) > 0
    }

    fun delete(userId: String, configId: String): Boolean {
        logger.info(
            "[ExternalAgent] Deleting: userId={}, configId={}",
            userId, configId
        )
        checkOwnership(userId, configId)
        return dao.delete(dslContext, configId) > 0
    }

    private fun checkOwnership(userId: String, configId: String) {
        getOwnedRecord(userId, configId)
    }

    private fun validateConfig(
        platform: String,
        agentId: String,
        apiUrl: String,
        headers: String?
    ) {
        val adapter = adapterMap[ExternalAgentPlatform.fromValue(platform)]
            ?: throw invalidConfig("暂不支持该外部智能体平台")
        if (agentId.isBlank()) {
            throw invalidConfig("外部智能体 agentId 不能为空")
        }
        try {
            adapter.validateConfig(
                ExternalAgentConfigValidationContext(
                    platform = platform,
                    agentId = agentId,
                    apiUrl = apiUrl,
                    headers = headers
                )
            )
        } catch (e: ExternalAgentGatewayException) {
            throw invalidConfig(e.message)
        }
    }

    private fun invalidConfig(message: String?): ErrorCodeException {
        return ErrorCodeException(
            statusCode = 400,
            errorCode = AiMessageCode.EXTERNAL_AGENT_CONFIG_INVALID,
            defaultMessage = message ?: "External agent config is invalid"
        )
    }

    private fun getOwnedRecord(
        userId: String,
        configId: String
    ): TAiExternalAgentConfigRecord {
        val record = dao.getById(dslContext, configId)
            ?: throw ErrorCodeException(
                statusCode = 404,
                errorCode = AiMessageCode.EXTERNAL_AGENT_NOT_FOUND,
                defaultMessage =
                    "External agent config not found",
                params = arrayOf(configId)
            )
        if (record.userId != userId) {
            throw ErrorCodeException(
                statusCode = 403,
                errorCode = AiMessageCode.EXTERNAL_AGENT_NO_PERMISSION,
                defaultMessage = "No permission",
                params = arrayOf(configId)
            )
        }
        return record
    }

    private fun toInfo(
        record: TAiExternalAgentConfigRecord,
        maskHeaders: Boolean
    ): ExternalAgentInfo {
        val decryptedHeaders = decryptHeaders(record.headers)
        val platform = ExternalAgentPlatform.fromValue(record.platform)
        val authConfig = parseAuthConfig(platform, decryptedHeaders)
        return ExternalAgentInfo(
            id = record.id,
            userId = record.userId,
            agentName = record.agentName,
            description = record.description,
            platform = platform,
            agentId = record.agentId,
            apiUrl = record.apiUrl,
            authConfig = if (maskHeaders) maskAuthConfig(authConfig) else authConfig,
            headers = if (maskHeaders) maskHeaderValues(decryptedHeaders) else decryptedHeaders,
            enabled = record.enabled,
            createdTime = record.createdTime
                .toInstant(ZoneOffset.ofHours(8)).toEpochMilli(),
            updatedTime = record.updatedTime
                .toInstant(ZoneOffset.ofHours(8)).toEpochMilli()
        )
    }

    private fun maskHeaderValues(headers: String?): String? {
        val parsedHeaders = AiMcpServerService.parseHeaders(headers)
        if (parsedHeaders.isEmpty()) {
            return null
        }
        return JsonUtil.toJson(parsedHeaders.mapValues { MASKED_HEADER_VALUE })
    }

    private fun encryptHeaders(value: String?): String? {
        if (value.isNullOrBlank()) {
            return value
        }
        requireAesKey()
        return AESUtil.encrypt(aesKey, value)
    }

    private fun decryptHeaders(value: String?): String? {
        if (value.isNullOrBlank()) {
            return value
        }
        if (aesKey.isBlank()) {
            return value
        }
        return try {
            AESUtil.decrypt(aesKey, value)
        } catch (ignored: Exception) {
            value
        }
    }

    private fun resolveEncryptedHeaders(incoming: String?): String? {
        return when (incoming) {
            null -> null
            "" -> ""
            else -> encryptHeaders(incoming)
        }
    }

    private fun resolveHeaders(
        platform: ExternalAgentPlatform,
        headers: String?,
        authConfig: ExternalAgentAuthConfig?
    ): String? {
        return authConfig?.let { buildHeaders(platform, it) } ?: headers
    }

    private fun resolveEffectiveHeaders(
        platform: ExternalAgentPlatform,
        currentHeaders: String?,
        rawHeaders: String?,
        authConfig: ExternalAgentAuthConfig?
    ): String? {
        return when {
            authConfig != null -> {
                val existingAuthConfig = parseAuthConfig(platform, currentHeaders)
                buildHeaders(platform, mergeAuthConfig(existingAuthConfig, authConfig))
            }

            rawHeaders != null -> rawHeaders
            else -> currentHeaders
        }
    }

    private fun resolveUpdatedEncryptedHeaders(
        platform: ExternalAgentPlatform,
        currentHeaders: String?,
        rawHeaders: String?,
        authConfig: ExternalAgentAuthConfig?
    ): String? {
        return when {
            authConfig != null -> {
                val existingAuthConfig = parseAuthConfig(platform, currentHeaders)
                resolveEncryptedHeaders(buildHeaders(platform, mergeAuthConfig(existingAuthConfig, authConfig)))
            }

            else -> resolveEncryptedHeaders(rawHeaders)
        }
    }

    private fun buildHeaders(
        platform: ExternalAgentPlatform,
        authConfig: ExternalAgentAuthConfig
    ): String? {
        val headers = when (platform) {
            ExternalAgentPlatform.BKAIDEV -> buildBkAiDevHeaders(authConfig)
            ExternalAgentPlatform.KNOT -> buildKnotHeaders(authConfig)
        }
        return if (headers.isEmpty()) {
            null
        } else {
            JsonUtil.toJson(headers)
        }
    }

    private fun buildBkAiDevHeaders(authConfig: ExternalAgentAuthConfig): Map<String, String> {
        val effectiveAuthMode = when {
            authConfig.authMode != null -> authConfig.authMode
            !authConfig.accessToken.isNullOrBlank() -> ExternalAgentAuthMode.USER
            !authConfig.bkAppCode.isNullOrBlank() || !authConfig.bkAppSecret.isNullOrBlank() ->
                ExternalAgentAuthMode.APP
            else -> null
        }
        val authorization = when {
            effectiveAuthMode == ExternalAgentAuthMode.APP -> JsonUtil.toJson(
                mapOf(
                    BKAIDEV_BK_APP_CODE_KEY to authConfig.bkAppCode,
                    BKAIDEV_BK_APP_SECRET_KEY to authConfig.bkAppSecret
                ).filterValues { !it.isNullOrBlank() }
            )

            effectiveAuthMode == ExternalAgentAuthMode.USER -> JsonUtil.toJson(
                mapOf(BKAIDEV_ACCESS_TOKEN_KEY to authConfig.accessToken)
                    .filterValues { !it.isNullOrBlank() }
            )

            else -> null
        }
        val headers = linkedMapOf<String, String>()
        authorization?.let { headers[BKAIDEV_AUTHORIZATION_HEADER] = it }
        val bkAiDevUser = authConfig.bkAiDevUser
        if (effectiveAuthMode != ExternalAgentAuthMode.USER && !bkAiDevUser.isNullOrBlank()) {
            headers[BKAIDEV_USER_HEADER] = bkAiDevUser
        }
        return headers
    }

    private fun buildKnotHeaders(authConfig: ExternalAgentAuthConfig): Map<String, String> {
        return linkedMapOf<String, String>().apply {
            authConfig.knotApiToken?.takeIf { it.isNotBlank() }?.let { put(KNOT_TOKEN_HEADER, it) }
            authConfig.knotApiUser?.takeIf { it.isNotBlank() }?.let { put(KNOT_USER_HEADER, it) }
        }
    }

    private fun parseAuthConfig(
        platform: ExternalAgentPlatform,
        headers: String?
    ): ExternalAgentAuthConfig? {
        val parsedHeaders = AiMcpServerService.parseHeaders(headers)
        if (parsedHeaders.isEmpty()) {
            return null
        }
        return when (platform) {
            ExternalAgentPlatform.BKAIDEV -> parseBkAiDevAuthConfig(parsedHeaders)
            ExternalAgentPlatform.KNOT -> parseKnotAuthConfig(parsedHeaders)
        }
    }

    private fun parseBkAiDevAuthConfig(headers: Map<String, String>): ExternalAgentAuthConfig? {
        val authorization = getHeaderIgnoreCase(headers, BKAIDEV_AUTHORIZATION_HEADER)
        val user = getHeaderIgnoreCase(headers, BKAIDEV_USER_HEADER)
        val authPayload = parseJsonMap(authorization)
        if (authorization.isNullOrBlank() && user.isNullOrBlank()) {
            return null
        }
        val accessToken = authPayload[BKAIDEV_ACCESS_TOKEN_KEY]
        val bkAppCode = authPayload[BKAIDEV_BK_APP_CODE_KEY]
        val bkAppSecret = authPayload[BKAIDEV_BK_APP_SECRET_KEY]
        val authMode = when {
            !accessToken.isNullOrBlank() -> ExternalAgentAuthMode.USER
            !bkAppCode.isNullOrBlank() || !bkAppSecret.isNullOrBlank() || !user.isNullOrBlank() ->
                ExternalAgentAuthMode.APP
            else -> null
        }
        return ExternalAgentAuthConfig(
            authMode = authMode,
            bkAppCode = bkAppCode,
            bkAppSecret = bkAppSecret,
            accessToken = accessToken,
            bkAiDevUser = user
        )
    }

    private fun parseKnotAuthConfig(headers: Map<String, String>): ExternalAgentAuthConfig? {
        val token = getHeaderIgnoreCase(headers, KNOT_TOKEN_HEADER)
        val user = getHeaderIgnoreCase(headers, KNOT_USER_HEADER)
        if (token.isNullOrBlank() && user.isNullOrBlank()) {
            return null
        }
        return ExternalAgentAuthConfig(
            knotApiToken = token,
            knotApiUser = user
        )
    }

    private fun mergeAuthConfig(
        current: ExternalAgentAuthConfig?,
        incoming: ExternalAgentAuthConfig
    ): ExternalAgentAuthConfig {
        return ExternalAgentAuthConfig(
            authMode = incoming.authMode ?: current?.authMode,
            bkAppCode = incoming.bkAppCode ?: current?.bkAppCode,
            bkAppSecret = incoming.bkAppSecret ?: current?.bkAppSecret,
            accessToken = incoming.accessToken ?: current?.accessToken,
            bkAiDevUser = incoming.bkAiDevUser ?: current?.bkAiDevUser,
            knotApiToken = incoming.knotApiToken ?: current?.knotApiToken,
            knotApiUser = incoming.knotApiUser ?: current?.knotApiUser
        )
    }

    private fun maskAuthConfig(authConfig: ExternalAgentAuthConfig?): ExternalAgentAuthConfig? {
        if (authConfig == null) {
            return null
        }
        return authConfig.copy(
            bkAppSecret = authConfig.bkAppSecret?.let { MASKED_HEADER_VALUE },
            accessToken = authConfig.accessToken?.let { MASKED_HEADER_VALUE },
            knotApiToken = authConfig.knotApiToken?.let { MASKED_HEADER_VALUE }
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

    private fun requireAesKey() {
        if (aesKey.isBlank()) {
            throw ErrorCodeException(
                errorCode = AiMessageCode.USER_LLM_CONFIG_AES_KEY_MISSING,
                defaultMessage = "config[aes.ai] is not found"
            )
        }
    }

    companion object {
        private const val MASKED_HEADER_VALUE = "******"
        private const val BKAIDEV_AUTHORIZATION_HEADER = "X-Bkapi-Authorization"
        private const val BKAIDEV_USER_HEADER = "X-BKAIDEV-USER"
        private const val BKAIDEV_ACCESS_TOKEN_KEY = "access_token"
        private const val BKAIDEV_BK_APP_CODE_KEY = "bk_app_code"
        private const val BKAIDEV_BK_APP_SECRET_KEY = "bk_app_secret"
        private const val KNOT_TOKEN_HEADER = "x-knot-api-token"
        private const val KNOT_USER_HEADER = "x-knot-api-user"
        private val logger = LoggerFactory.getLogger(
            ExternalAgentService::class.java
        )
    }
}
