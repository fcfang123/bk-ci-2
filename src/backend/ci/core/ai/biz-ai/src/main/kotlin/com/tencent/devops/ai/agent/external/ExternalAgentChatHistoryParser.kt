package com.tencent.devops.ai.agent.external

import com.fasterxml.jackson.core.type.TypeReference
import com.tencent.devops.common.api.util.JsonUtil
import org.slf4j.LoggerFactory

/**
 * 解析 Supervisor 工具入参中的 AIDev 风格 chat_history JSON。
 */
object ExternalAgentChatHistoryParser {

    private val logger = LoggerFactory.getLogger(ExternalAgentChatHistoryParser::class.java)

    fun parse(chatHistoryJson: String?): List<Map<String, String>> {
        if (chatHistoryJson.isNullOrBlank()) {
            return emptyList()
        }
        return try {
            val items = JsonUtil.to(
                chatHistoryJson,
                object : TypeReference<List<Map<String, Any?>>>() {}
            )
            items.mapNotNull { normalizeEntry(it) }
        } catch (e: Exception) {
            logger.warn("[ExternalAgentTools] invalid chat_history JSON: {}", e.message)
            emptyList()
        }
    }

    private fun normalizeEntry(item: Map<String, Any?>): Map<String, String>? {
        val role = item[KEY_ROLE]?.toString()?.trim()?.lowercase() ?: return null
        if (role !in ALLOWED_ROLES) {
            return null
        }
        val content = item[KEY_CONTENT]?.toString()?.trim().orEmpty()
        if (content.isBlank()) {
            return null
        }
        return mapOf(KEY_ROLE to role, KEY_CONTENT to content)
    }

    private val ALLOWED_ROLES = setOf("user", "assistant", "system")
    private const val KEY_ROLE = "role"
    private const val KEY_CONTENT = "content"
}
