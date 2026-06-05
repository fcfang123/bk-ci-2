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
        if (chunk.isEmpty()) {
            return emptyList()
        }
        buffer.append(chunk)
        val content = buffer.toString()
        val lastNewline = content.lastIndexOf('\n')
        if (lastNewline < 0) {
            if (isStandalonePayloadComplete(content)) {
                buffer.clear()
                return parser(content)
            }
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

    private fun isStandalonePayloadComplete(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("data:")) {
            return false
        }
        if (trimmed == DONE_SENTINEL) {
            return true
        }
        val openingChar = trimmed.first()
        if (openingChar != '{' && openingChar != '[') {
            return false
        }
        return isCompleteJsonLikePayload(trimmed)
    }

    private fun isCompleteJsonLikePayload(content: String): Boolean {
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaping = false
        content.forEachIndexed { index, char ->
            if (escaping) {
                escaping = false
                return@forEachIndexed
            }
            if (char == '\\') {
                if (inString) {
                    escaping = true
                }
                return@forEachIndexed
            }
            if (char == '"') {
                inString = !inString
                return@forEachIndexed
            }
            if (inString) {
                return@forEachIndexed
            }
            when (char) {
                '{', '[' -> stack.addLast(char)
                '}' -> {
                    if (stack.removeLastOrNull() != '{') {
                        return false
                    }
                }
                ']' -> {
                    if (stack.removeLastOrNull() != '[') {
                        return false
                    }
                }
            }
            if (stack.isEmpty() && index != content.lastIndex) {
                if (content.substring(index + 1).any { !it.isWhitespace() }) {
                    return false
                }
            }
        }
        return !inString && !escaping && stack.isEmpty()
    }

    companion object {
        private const val DONE_SENTINEL = "[DONE]"
    }
}