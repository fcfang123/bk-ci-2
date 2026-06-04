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

package com.tencent.devops.ai.pojo

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import io.swagger.v3.oas.annotations.media.Schema

enum class ExternalAgentPlatform(
    @get:JsonValue
    val value: String
) {
    BKAIDEV("BKAIDEV"),
    KNOT("KNOT");

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromValue(value: String): ExternalAgentPlatform {
            return entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unsupported external agent platform: $value")
        }
    }
}

enum class ExternalAgentAuthMode(
    @get:JsonValue
    val value: String
) {
    APP("APP"),
    USER("USER");

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromValue(value: String): ExternalAgentAuthMode {
            return entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unsupported external agent auth mode: $value")
        }
    }
}

@Schema(title = "外部智能体-认证字段元数据")
data class ExternalAgentAuthFieldInfo(
    @get:Schema(title = "前端字段 key")
    val key: String,
    @get:Schema(title = "字段显示名称")
    val label: String,
    @get:Schema(title = "字段说明")
    val description: String,
    @get:Schema(title = "是否必填")
    val required: Boolean,
    @get:Schema(title = "是否敏感字段")
    val secret: Boolean = false,
    @get:Schema(title = "适用认证模式列表")
    val authModes: List<ExternalAgentAuthMode> = emptyList()
)

@Schema(title = "外部智能体-平台动态配置元数据")
data class ExternalAgentPlatformConfigInfo(
    @get:Schema(title = "平台")
    val platform: ExternalAgentPlatform,
    @get:Schema(title = "支持的认证模式列表")
    val authModes: List<ExternalAgentAuthMode> = emptyList(),
    @get:Schema(title = "认证字段列表")
    val authFields: List<ExternalAgentAuthFieldInfo>
)

object ExternalAgentMetadata {

    sealed interface AuthFieldTarget {
        data class Header(val headerName: String) : AuthFieldTarget
        data class BkapiAuthorization(val payloadKey: String) : AuthFieldTarget
    }

    data class ExternalAgentAuthFieldDefinition(
        val key: String,
        val label: String,
        val description: String,
        val required: Boolean,
        val secret: Boolean = false,
        val autoFillCurrentUser: Boolean = false,
        val authModes: Set<ExternalAgentAuthMode> = emptySet(),
        val target: AuthFieldTarget
    ) {
        fun toInfo(): ExternalAgentAuthFieldInfo {
            return ExternalAgentAuthFieldInfo(
                key = key,
                label = label,
                description = description,
                required = required,
                secret = secret,
                authModes = authModes.toList()
            )
        }
    }

    data class ExternalAgentPlatformDefinition(
        val platform: ExternalAgentPlatform,
        val authModes: List<ExternalAgentAuthMode> = emptyList(),
        val authFields: List<ExternalAgentAuthFieldDefinition>
    ) {
        fun toInfo(): ExternalAgentPlatformConfigInfo {
            return ExternalAgentPlatformConfigInfo(
                platform = platform,
                authModes = authModes,
                authFields = authFields
                    .filterNot { it.autoFillCurrentUser }
                    .map { it.toInfo() }
            )
        }
    }

    private val platformDefinitions = linkedMapOf(
        ExternalAgentPlatform.BKAIDEV to ExternalAgentPlatformDefinition(
            platform = ExternalAgentPlatform.BKAIDEV,
            authModes = listOf(ExternalAgentAuthMode.APP, ExternalAgentAuthMode.USER),
            authFields = listOf(
                ExternalAgentAuthFieldDefinition(
                    key = "bkAppCode",
                    label = "bk_app_code",
                    description = "BKAIDEV 应用态调用使用的 bk_app_code",
                    required = true,
                    authModes = setOf(ExternalAgentAuthMode.APP),
                    target = AuthFieldTarget.BkapiAuthorization("bk_app_code")
                ),
                ExternalAgentAuthFieldDefinition(
                    key = "bkAppSecret",
                    label = "bk_app_secret",
                    description = "BKAIDEV 应用态调用使用的 bk_app_secret",
                    required = true,
                    secret = true,
                    authModes = setOf(ExternalAgentAuthMode.APP),
                    target = AuthFieldTarget.BkapiAuthorization("bk_app_secret")
                ),
                ExternalAgentAuthFieldDefinition(
                    key = "accessToken",
                    label = "access_token",
                    description = "BKAIDEV 用户态调用使用的 access_token",
                    required = true,
                    secret = true,
                    authModes = setOf(ExternalAgentAuthMode.USER),
                    target = AuthFieldTarget.BkapiAuthorization("access_token")
                ),
                ExternalAgentAuthFieldDefinition(
                    key = "bkAiDevUser",
                    label = "X-BKAIDEV-USER",
                    description = "应用态由后端自动使用当前登录用户填充，无需前端传值",
                    required = true,
                    autoFillCurrentUser = true,
                    authModes = setOf(ExternalAgentAuthMode.APP),
                    target = AuthFieldTarget.Header("X-BKAIDEV-USER")
                )
            )
        ),
        ExternalAgentPlatform.KNOT to ExternalAgentPlatformDefinition(
            platform = ExternalAgentPlatform.KNOT,
            authFields = listOf(
                ExternalAgentAuthFieldDefinition(
                    key = "knotApiToken",
                    label = "x-knot-api-token",
                    description = "Knot 个人或团队 token",
                    required = true,
                    secret = true,
                    target = AuthFieldTarget.Header("x-knot-api-token")
                ),
                ExternalAgentAuthFieldDefinition(
                    key = "knotApiUser",
                    label = "x-knot-api-user",
                    description = "由后端自动使用当前登录用户填充，无需前端传值",
                    required = true,
                    autoFillCurrentUser = true,
                    target = AuthFieldTarget.Header("x-knot-api-user")
                )
            )
        )
    )

    fun listPlatformConfigInfos(): List<ExternalAgentPlatformConfigInfo> {
        return platformDefinitions.values.map { it.toInfo() }
    }

    fun getDefinition(platform: ExternalAgentPlatform): ExternalAgentPlatformDefinition {
        return platformDefinitions.getValue(platform)
    }
}
