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
import com.tencent.devops.ai.dao.ExternalAgentConfigDao
import com.tencent.devops.ai.agent.external.ExternalAgentAdapter
import com.tencent.devops.ai.agent.external.ExternalAgentConfigValidationContext
import com.tencent.devops.ai.agent.external.ExternalAgentGatewayException
import com.tencent.devops.ai.pojo.ExternalAgentCreate
import com.tencent.devops.ai.pojo.ExternalAgentInfo
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

    private val adapterMap = adapters.associateBy { it.platform().uppercase() }

    fun create(
        userId: String,
        request: ExternalAgentCreate
    ): ExternalAgentInfo {
        val id = UUIDUtil.generate()
        validateConfig(
            platform = request.platform,
            agentId = request.agentId,
            apiUrl = request.apiUrl,
            headers = request.headers
        )
        logger.info(
            "[ExternalAgent] Creating: id={}, userId={}, " +
                "name={}, platform={}",
            id, userId, request.agentName, request.platform
        )
        dao.create(
            dslContext = dslContext,
            id = id,
            userId = userId,
            agentName = request.agentName,
            description = request.description,
            platform = request.platform,
            agentId = request.agentId,
            apiUrl = request.apiUrl,
            headers = encryptHeaders(request.headers),
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
        val effectiveHeaders = decryptHeaders(request.headers ?: record.headers)
        validateConfig(
            platform = request.platform ?: record.platform,
            agentId = request.agentId ?: record.agentId,
            apiUrl = request.apiUrl ?: record.apiUrl,
            headers = effectiveHeaders
        )
        return dao.update(
            dslContext = dslContext,
            id = configId,
            agentName = request.agentName,
            description = request.description,
            platform = request.platform,
            agentId = request.agentId,
            apiUrl = request.apiUrl,
            headers = resolveEncryptedHeaders(request.headers),
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
        val adapter = adapterMap[platform.uppercase()]
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
        return ExternalAgentInfo(
            id = record.id,
            userId = record.userId,
            agentName = record.agentName,
            description = record.description,
            platform = record.platform,
            agentId = record.agentId,
            apiUrl = record.apiUrl,
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
        private val logger = LoggerFactory.getLogger(
            ExternalAgentService::class.java
        )
    }
}
