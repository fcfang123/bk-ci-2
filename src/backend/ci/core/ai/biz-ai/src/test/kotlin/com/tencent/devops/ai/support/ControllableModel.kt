/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.ai.support

import io.agentscope.core.message.Msg
import io.agentscope.core.model.ChatResponse
import io.agentscope.core.model.GenerateOptions
import io.agentscope.core.model.Model
import io.agentscope.core.model.ToolSchema
import java.util.concurrent.CopyOnWriteArrayList
import reactor.core.publisher.Flux

/**
 * AgentScope 能力门禁使用的可控模型替身。
 *
 * 记录直接模型调用的不可变输入快照，并允许测试按调用内容生成响应。
 */
class ControllableModel(
    private val name: String = "controllable-model",
    private val responseFactory: (Invocation) -> Flux<ChatResponse> = {
        Flux.just(ChatResponse.builder().build())
    }
) : Model {

    private val recordedInvocations = CopyOnWriteArrayList<Invocation>()

    val invocations: List<Invocation>
        get() = recordedInvocations.toList()

    override fun stream(
        messages: MutableList<Msg>?,
        toolSchemas: MutableList<ToolSchema>?,
        options: GenerateOptions?
    ): Flux<ChatResponse> {
        val invocation = Invocation(
            messages = messages?.toList().orEmpty(),
            toolSchemas = toolSchemas?.toList().orEmpty(),
            options = options,
            threadName = Thread.currentThread().name
        )
        recordedInvocations += invocation
        return responseFactory(invocation)
    }

    override fun getModelName(): String = name

    data class Invocation(
        val messages: List<Msg>,
        val toolSchemas: List<ToolSchema>,
        val options: GenerateOptions?,
        val threadName: String
    )
}
