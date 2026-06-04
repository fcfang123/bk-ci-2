package com.tencent.devops.ai.agent.external.util

import com.tencent.devops.ai.agent.external.ExternalAgentEvent

/**
 * Accumulates partial SSE chunks until complete lines (terminated by `\n`) are available for parsing.
 */
class ExternalAgentSseLineBuffer(
    private val parser: (String) -> List<ExternalAgentEvent>
) {

    private val buffer = StringBuilder()

    fun accept(chunk: String): List<ExternalAgentEvent> {
        buffer.append(chunk)
        val content = buffer.toString()
        val lastNewline = content.lastIndexOf('\n')
        if (lastNewline < 0) {
            return emptyList()
        }
        val completePart = content.substring(0, lastNewline + 1)
        buffer.clear()
        buffer.append(content.substring(lastNewline + 1))
        return parser(completePart)
    }

    fun flush(): List<ExternalAgentEvent> {
        if (buffer.isEmpty()) {
            return emptyList()
        }
        val remaining = buffer.toString()
        buffer.clear()
        return parser(remaining)
    }
}