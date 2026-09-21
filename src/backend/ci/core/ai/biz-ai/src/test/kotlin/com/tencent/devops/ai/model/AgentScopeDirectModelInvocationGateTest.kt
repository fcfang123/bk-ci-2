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

package com.tencent.devops.ai.model

import com.tencent.devops.ai.support.ControllableModel
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.model.GenerateOptions
import io.agentscope.core.model.ToolSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class AgentScopeDirectModelInvocationGateTest {

    @Test
    fun `should invoke model directly without constructing react agent`() {
        val model = ControllableModel()
        val messages = mutableListOf(
            message(MsgRole.SYSTEM, "You are a bounded pipeline analyzer."),
            message(MsgRole.USER, "Analyze stage build.")
        )

        val responses = model.stream(
            messages,
            mutableListOf<ToolSchema>(),
            GenerateOptions.builder().build()
        ).collectList().block()

        assertNotNull(responses)
        assertEquals(1, responses?.size)
        assertEquals(1, model.invocations.size)
        assertEquals(
            listOf(
                "You are a bounded pipeline analyzer.",
                "Analyze stage build."
            ),
            model.invocations.single().messages.map { it.textContent }
        )
        assertEquals(emptyList<ToolSchema>(), model.invocations.single().toolSchemas)
    }

    private fun message(role: MsgRole, content: String): Msg {
        return Msg.builder()
            .name("pipeline-analysis-gate")
            .role(role)
            .textContent(content)
            .build()
    }
}
