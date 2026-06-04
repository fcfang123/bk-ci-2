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

import io.swagger.v3.oas.annotations.media.Schema

@Schema(title = "外部智能体-结构化认证配置")
data class ExternalAgentAuthConfig(
    @get:Schema(title = "认证模式，仅 BKAIDEV 使用", example = "USER")
    val authMode: ExternalAgentAuthMode? = null,
    @get:Schema(title = "蓝鲸应用 ID，仅 BKAIDEV 应用态使用")
    val bkAppCode: String? = null,
    @get:Schema(title = "蓝鲸应用密钥，仅 BKAIDEV 应用态使用")
    val bkAppSecret: String? = null,
    @get:Schema(title = "用户 access_token，仅 BKAIDEV 用户态使用")
    val accessToken: String? = null,
    @get:Schema(title = "BKAIDEV 用户名，仅 BKAIDEV 应用态使用")
    val bkAiDevUser: String? = null,
    @get:Schema(title = "Knot 个人或团队 token")
    val knotApiToken: String? = null,
    @get:Schema(title = "Knot 当前真实用户")
    val knotApiUser: String? = null
)
