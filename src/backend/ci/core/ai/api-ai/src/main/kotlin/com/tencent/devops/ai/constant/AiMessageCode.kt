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

package com.tencent.devops.ai.constant

/**
 * AI 模块错误码常量（模块编号 33）。
 *
 * 错误码规则：21 + 33(AI模块) + 3位序号 = 7位数字
 */
object AiMessageCode {

    // ── 对话相关 (001-010) ──
    const val RUN_ALREADY_ACTIVE = "2133001"

    // ── 会话相关 (011-020) ──
    const val CREATE_SESSION_FAILED = "2133011"
    const val SESSION_NOT_FOUND = "2133012"
    const val SESSION_NO_PERMISSION = "2133013"

    // ── 提示词相关 (021-030) ──
    const val CREATE_PROMPT_FAILED = "2133021"
    const val PROMPT_NOT_FOUND = "2133022"
    const val PROMPT_NO_PERMISSION = "2133023"

    // ── 技能相关 (031-040) ──
    const val CREATE_SKILL_FAILED = "2133031"
    const val SKILL_NOT_FOUND = "2133032"
    const val SKILL_NO_PERMISSION = "2133033"

    // ── 外部智能体相关 (041-050) ──
    const val CREATE_EXTERNAL_AGENT_FAILED = "2133041"
    const val EXTERNAL_AGENT_NOT_FOUND = "2133042"
    const val EXTERNAL_AGENT_NO_PERMISSION = "2133043"
    const val EXTERNAL_AGENT_CONFIG_INVALID = "2133044"
    const val EXTERNAL_AGENT_ID_REQUIRED = "2133045"
    const val EXTERNAL_AGENT_UNSUPPORTED_PLATFORM = "2133046"
    const val EXTERNAL_AGENT_DUPLICATE_NAME = "2133047"
    const val EXTERNAL_AGENT_DUPLICATE_SIMILAR_NAME = "2133048"
    const val EXTERNAL_AGENT_PLATFORM_AGENT_ID_REQUIRED = "2133049"
    const val EXTERNAL_AGENT_AUTH_CONFIG_UNSUPPORTED = "2133050"
    const val EXTERNAL_AGENT_CONFIG_DISABLED = "2133077"

    // ── 外部智能体平台校验 (055-060) ──
    const val EXTERNAL_AGENT_KNOT_AGENT_ID_FROM_URL = "2133055"
    const val EXTERNAL_AGENT_KNOT_MISSING_HEADER = "2133056"
    const val EXTERNAL_AGENT_KNOT_AGUI_URL_REQUIRED = "2133057"
    const val EXTERNAL_AGENT_BKAIDEV_AGENT_NAME_REQUIRED = "2133058"
    const val EXTERNAL_AGENT_BKAIDEV_API_URL_REQUIRED = "2133059"
    const val EXTERNAL_AGENT_BKAIDEV_PLUGIN_INVOKE_NOT_STREAMING = "2133060"
    const val EXTERNAL_AGENT_BKAIDEV_MISSING_BKAPI_AUTHORIZATION = "2133064"
    const val EXTERNAL_AGENT_BKAIDEV_AUTH_MODE_REQUIRED = "2133065"
    const val EXTERNAL_AGENT_BKAIDEV_MISSING_FIELD = "2133066"
    const val EXTERNAL_AGENT_BKAIDEV_AUTH_HEADERS_REQUIRED = "2133067"

    // ── 外部智能体流式网关 (064-070) ──
    const val EXTERNAL_AGENT_GATEWAY_DISABLED = "2133068"
    const val EXTERNAL_AGENT_GATEWAY_TIMEOUT = "2133069"
    const val EXTERNAL_AGENT_GATEWAY_UPSTREAM_FAILED = "2133070"
    const val EXTERNAL_AGENT_GATEWAY_RESPONSE_TOO_LARGE = "2133074"
    const val EXTERNAL_AGENT_SSE_PARSE_FAILED = "2133075"
    const val EXTERNAL_AGENT_SSE_UPSTREAM_ERROR = "2133076"
    const val EXTERNAL_AGENT_SSE_UPSTREAM_ERROR_WITH_DETAIL = "2133078"

    // ── MCP 服务相关 (051-060) ──
    const val CREATE_MCP_SERVER_FAILED = "2133051"
    const val MCP_CLIENT_BUILD_FAILED = "2133052"
    const val MCP_SERVER_NOT_FOUND = "2133053"
    const val MCP_SERVER_NO_PERMISSION = "2133054"

    // ── 智能体服务间调用相关 (061-070) ──
    const val AGENT_NOT_FOUND = "2133061"
    const val AGENT_RUN_TIMEOUT = "2133062"
    const val AGENT_RUN_FAILED = "2133063"

    // ── 用户自定义大模型相关 (071-080) ──
    const val USER_LLM_CONFIG_SAVE_FAILED = "2133071"
    const val USER_LLM_CONFIG_INVALID = "2133072"
    const val USER_LLM_CONFIG_AES_KEY_MISSING = "2133073"
}
