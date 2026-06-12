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

import com.tencent.devops.ai.constant.AiMessageCode
import com.tencent.devops.ai.agent.external.ExternalAgentAdapter
import com.tencent.devops.ai.agent.external.ExternalAgentAgentIdRecalculationContext
import com.tencent.devops.ai.agent.external.ExternalAgentAgentIdResolveContext
import com.tencent.devops.ai.agent.external.ExternalAgentConfigValidationContext
import com.tencent.devops.ai.agent.external.ExternalAgentAuthHeadersBuildContext
import com.tencent.devops.ai.agent.external.ExternalAgentErrors
import com.tencent.devops.ai.agent.external.ExternalAgentGatewayException
import com.tencent.devops.ai.dao.ExternalAgentConfigDao
import com.tencent.devops.ai.pojo.ExternalAgentAuthConfig
import com.tencent.devops.ai.pojo.ExternalAgentCreate
import com.tencent.devops.ai.pojo.ExternalAgentInfo
import com.tencent.devops.ai.pojo.ExternalAgentPlatform
import com.tencent.devops.ai.pojo.ExternalAgentPlatformConfigInfo
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

    fun listPlatformConfigs(): List<ExternalAgentPlatformConfigInfo> {
        return adapterMap.values
            .mapNotNull { it.platformConfigInfo() }
            .sortedBy { it.platform.ordinal }
    }

    fun create(
        userId: String,
        request: ExternalAgentCreate
    ): ExternalAgentInfo {
        val id = UUIDUtil.generate()
        val platform = request.platform
        val adapter = getAdapter(platform)
        val effectiveAgentId = resolveAgentId(
            adapter = adapter,
            rawAgentId = request.agentId,
            agentName = request.agentName,
            apiUrl = request.apiUrl
        )
        val assembledHeaders = resolveHeaders(
            userId = userId,
            adapter = adapter,
            headers = request.headers,
            authConfig = request.authConfig
        )
        validateConfig(
            platform = platform.name,
            agentId = effectiveAgentId,
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
            agentId = effectiveAgentId,
            apiUrl = request.apiUrl,
            headers = encryptHeaders(assembledHeaders),
            enabled = request.enabled
        )
        val record = dao.getById(dslContext, id)
            ?: throw ErrorCodeException(
                errorCode = AiMessageCode.CREATE_EXTERNAL_AGENT_FAILED
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
                errorCode = AiMessageCode.EXTERNAL_AGENT_CONFIG_DISABLED,
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
            throw invalidConfig(AiMessageCode.EXTERNAL_AGENT_DUPLICATE_NAME)
        }

        val ignoreCaseNameMatches = enabledConfigs.filter {
            it.agentName.equals(normalized, ignoreCase = true)
        }
        if (ignoreCaseNameMatches.size == 1) {
            return ignoreCaseNameMatches.single().id
        }
        if (ignoreCaseNameMatches.size > 1) {
            throw invalidConfig(AiMessageCode.EXTERNAL_AGENT_DUPLICATE_SIMILAR_NAME)
        }

        throw ErrorCodeException(
            statusCode = 404,
            errorCode = AiMessageCode.EXTERNAL_AGENT_NOT_FOUND,
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
        val adapter = getAdapter(effectivePlatform)
        val effectiveAgentName = request.agentName ?: record.agentName
        val effectiveApiUrl = request.apiUrl ?: record.apiUrl
        val effectiveAgentId = if (shouldRecalculateAgentId(adapter, request)) {
            resolveAgentId(
                adapter = adapter,
                rawAgentId = request.agentId ?: record.agentId,
                agentName = effectiveAgentName,
                apiUrl = effectiveApiUrl
            )
        } else {
            record.agentId
        }
        val effectiveHeaders = resolveEffectiveHeaders(
            userId = userId,
            adapter = adapter,
            currentHeaders = currentHeaders,
            rawHeaders = request.headers,
            authConfig = request.authConfig
        )
        validateConfig(
            platform = effectivePlatform.name,
            agentId = effectiveAgentId,
            apiUrl = effectiveApiUrl,
            headers = effectiveHeaders
        )
        return dao.update(
            dslContext = dslContext,
            id = configId,
            agentName = request.agentName,
            description = request.description,
            platform = request.platform?.name,
            agentId = effectiveAgentId.takeIf { it != record.agentId },
            apiUrl = request.apiUrl,
            headers = resolveUpdatedEncryptedHeaders(
                userId = userId,
                adapter = adapter,
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
        val adapter = getAdapter(ExternalAgentPlatform.fromValue(platform))
        if (agentId.isBlank()) {
            throw invalidConfig(AiMessageCode.EXTERNAL_AGENT_ID_REQUIRED)
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
            throw invalidConfig(e)
        }
    }

    private fun invalidConfig(errorCode: String, vararg params: String): ErrorCodeException {
        return ErrorCodeException(
            statusCode = 400,
            errorCode = errorCode,
            params = if (params.isEmpty()) null else arrayOf(*params)
        )
    }

    private fun invalidConfig(exception: ExternalAgentGatewayException): ErrorCodeException {
        return ExternalAgentErrors.toErrorCodeException(exception)
    }

    private fun getOwnedRecord(
        userId: String,
        configId: String
    ): TAiExternalAgentConfigRecord {
        val record = dao.getById(dslContext, configId)
            ?: throw ErrorCodeException(
                statusCode = 404,
                errorCode = AiMessageCode.EXTERNAL_AGENT_NOT_FOUND,
                params = arrayOf(configId)
            )
        if (record.userId != userId) {
            throw ErrorCodeException(
                statusCode = 403,
                errorCode = AiMessageCode.EXTERNAL_AGENT_NO_PERMISSION,
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
        val authConfig = adapterMap[platform]?.parseAuthConfig(decryptedHeaders)
        return ExternalAgentInfo(
            id = record.id,
            userId = record.userId,
            agentName = record.agentName,
            description = record.description,
            platform = platform,
            agentId = record.agentId,
            apiUrl = record.apiUrl,
            authConfig = if (maskHeaders) {
                adapterMap[platform]?.maskAuthConfig(authConfig) ?: authConfig
            } else {
                authConfig
            },
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
        userId: String,
        adapter: ExternalAgentAdapter,
        headers: String?,
        authConfig: ExternalAgentAuthConfig?
    ): String? {
        return authConfig?.let { buildAuthHeaders(adapter, userId, it) } ?: headers
    }

    private fun resolveEffectiveHeaders(
        userId: String,
        adapter: ExternalAgentAdapter,
        currentHeaders: String?,
        rawHeaders: String?,
        authConfig: ExternalAgentAuthConfig?
    ): String? {
        return when {
            authConfig != null -> {
                val existingAuthConfig = adapter.parseAuthConfig(currentHeaders)
                buildAuthHeaders(adapter, userId, mergeAuthConfig(existingAuthConfig, authConfig))
            }

            rawHeaders != null -> rawHeaders
            else -> currentHeaders
        }
    }

    private fun resolveUpdatedEncryptedHeaders(
        userId: String,
        adapter: ExternalAgentAdapter,
        currentHeaders: String?,
        rawHeaders: String?,
        authConfig: ExternalAgentAuthConfig?
    ): String? {
        return when {
            authConfig != null -> {
                val existingAuthConfig = adapter.parseAuthConfig(currentHeaders)
                resolveEncryptedHeaders(
                    buildAuthHeaders(adapter, userId, mergeAuthConfig(existingAuthConfig, authConfig))
                )
            }

            else -> resolveEncryptedHeaders(rawHeaders)
        }
    }

    private fun resolveAgentId(
        adapter: ExternalAgentAdapter,
        rawAgentId: String?,
        agentName: String,
        apiUrl: String
    ): String {
        return try {
            adapter.resolveAgentId(
                ExternalAgentAgentIdResolveContext(
                    rawAgentId = rawAgentId,
                    agentName = agentName,
                    apiUrl = apiUrl
                )
            )
        } catch (e: ExternalAgentGatewayException) {
            throw invalidConfig(e)
        }
    }

    private fun shouldRecalculateAgentId(
        adapter: ExternalAgentAdapter,
        request: ExternalAgentUpdate
    ): Boolean {
        return adapter.shouldRecalculateAgentId(
            ExternalAgentAgentIdRecalculationContext(
                platformChanged = request.platform != null,
                agentIdChanged = request.agentId != null,
                agentNameChanged = request.agentName != null,
                apiUrlChanged = request.apiUrl != null
            )
        )
    }

    private fun buildAuthHeaders(
        adapter: ExternalAgentAdapter,
        userId: String,
        authConfig: ExternalAgentAuthConfig
    ): String? {
        return try {
            adapter.buildAuthHeaders(
                ExternalAgentAuthHeadersBuildContext(
                    userId = userId,
                    authConfig = authConfig
                )
            )
        } catch (e: ExternalAgentGatewayException) {
            throw invalidConfig(e)
        }
    }

    private fun mergeAuthConfig(
        current: ExternalAgentAuthConfig?,
        incoming: ExternalAgentAuthConfig
    ): ExternalAgentAuthConfig {
        return ExternalAgentAuthConfig(
            authMode = incoming.authMode ?: current?.authMode,
            values = current?.values.orEmpty() + incoming.values
        )
    }

    private fun getAdapter(platform: ExternalAgentPlatform): ExternalAgentAdapter {
        return adapterMap[platform]
            ?: throw invalidConfig(AiMessageCode.EXTERNAL_AGENT_UNSUPPORTED_PLATFORM)
    }

    private fun requireAesKey() {
        if (aesKey.isBlank()) {
            throw ErrorCodeException(
                errorCode = AiMessageCode.USER_LLM_CONFIG_AES_KEY_MISSING
            )
        }
    }

    companion object {
        private const val MASKED_HEADER_VALUE = "******"
        private val logger = LoggerFactory.getLogger(
            ExternalAgentService::class.java
        )
    }
}
