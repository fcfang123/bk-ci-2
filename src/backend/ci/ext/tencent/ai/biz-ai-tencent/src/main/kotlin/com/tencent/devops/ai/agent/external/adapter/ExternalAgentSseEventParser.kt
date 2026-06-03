package com.tencent.devops.ai.agent.external.adapter

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.tencent.devops.ai.external.ExternalAgentErrorCategory
import com.tencent.devops.ai.external.ExternalAgentEvent
import org.slf4j.LoggerFactory

object ExternalAgentSseEventParser {

    fun parseBkAiDev(chunk: String): List<ExternalAgentEvent> {
        return parseDataLines(chunk).flatMap { data ->
            if (data == DONE_SENTINEL) {
                return@flatMap listOf(ExternalAgentEvent.Done)
            }
            parseEvent(data) { event ->
                val type = event["type"]?.toString() ?: event["event"]?.toString()
                when (type) {
                    "TEXT_MESSAGE_CONTENT" -> {
                        val delta = event["delta"]?.toString()
                        listOfNotNull(delta?.takeIf { it.isNotEmpty() }?.let { ExternalAgentEvent.TextDelta(it) })
                    }
                    "text" -> {
                        val content = event["content"]?.toString()
                        listOfNotNull(content?.takeIf { it.isNotEmpty() }?.let { ExternalAgentEvent.TextDelta(it) })
                    }
                    "RUN_STARTED" -> {
                        val threadId = event["thread_id"]?.toString()
                        listOfNotNull(
                            threadId?.takeIf { it.isNotBlank() }?.let {
                                ExternalAgentEvent.ConversationId(it)
                            }
                        )
                    }
                    "TEXT_MESSAGE_END", "RUN_FINISHED", "done" -> listOf(ExternalAgentEvent.Done)
                    "error", "RUN_ERROR" -> listOf(upstreamError(event["message"]?.toString()))
                    else -> event.toCustom(type ?: "UNKNOWN")
                }
            }
        }
    }

    fun parseKnot(chunk: String): List<ExternalAgentEvent> {
        return parseDataLines(chunk).flatMap { data ->
            if (data == DONE_SENTINEL) {
                return@flatMap listOf(ExternalAgentEvent.Done)
            }
            parseEvent(data) { event ->
                val type = event["type"]?.toString() ?: event["event"]?.toString()
                when (type) {
                    "TEXT_MESSAGE_CONTENT" -> {
                        val rawEvent = event["rawEvent"] as? Map<*, *>
                        val content = rawEvent?.get("content")?.toString()
                        val conversationId = rawEvent?.get("conversation_id")?.toString()
                        listOfNotNull(
                            content?.takeIf { it.isNotEmpty() }?.let { ExternalAgentEvent.TextDelta(it) },
                            conversationId?.takeIf { it.isNotBlank() }?.let { ExternalAgentEvent.ConversationId(it) }
                        )
                    }
                    "TEXT_MESSAGE_END", "RUN_FINISHED", "done" -> listOf(ExternalAgentEvent.Done)
                    "error", "RUN_ERROR" -> listOf(upstreamError(event["message"]?.toString()))
                    else -> event.toCustom(type ?: "UNKNOWN")
                }
            }
        }
    }

    fun describeChunk(chunk: String): String {
        val trimmed = chunk.trim()
        return buildString {
            append("lines=").append(chunk.lines().size)
            append(",hasDataPrefix=").append(chunk.contains("data:"))
            append(",startsWithBrace=").append(trimmed.startsWith("{"))
            append(",startsWithBracket=").append(trimmed.startsWith("["))
            append(",containsType=").append(chunk.contains("\"type\""))
            append(",containsEvent=").append(chunk.contains("\"event\""))
            append(",containsDelta=").append(chunk.contains("\"delta\""))
            append(",containsRawEvent=").append(chunk.contains("\"rawEvent\""))
            append(",containsConversationId=").append(
                chunk.contains("conversation_id") || chunk.contains("thread_id")
            )
        }
    }

    private fun parseDataLines(chunk: String): List<String> {
        return chunk.lines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("event:") || it.startsWith("id:") || it.startsWith(":") }
            .map { line ->
                if (line.startsWith("data:")) {
                    line.removePrefix("data:").trim()
                } else {
                    line
                }
            }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun parseEvent(
        data: String,
        mapper: (Map<String, Any?>) -> List<ExternalAgentEvent>
    ): List<ExternalAgentEvent> {
        return try {
            val event = objectMapper.readValue(data, MAP_TYPE)
            mapper(event)
        } catch (e: Exception) {
            logger.warn(
                "[ExternalAgentSse] Parse event failed: error={}, shape={}",
                e.message,
                describeChunk(data)
            )
            listOf(
                ExternalAgentEvent.Error(
                    message = "外部智能体事件解析失败",
                    category = ExternalAgentErrorCategory.PARSE_ERROR
                )
            )
        }
    }

    private fun upstreamError(message: String?): ExternalAgentEvent.Error {
        return ExternalAgentEvent.Error(
            message = message ?: "外部智能体返回错误",
            category = ExternalAgentErrorCategory.UPSTREAM_ERROR
        )
    }

    private fun Map<String, Any?>.toCustom(type: String): List<ExternalAgentEvent> {
        return listOf(
            ExternalAgentEvent.Custom(
                eventType = type,
                data = this
            )
        )
    }

    private val logger = LoggerFactory.getLogger(ExternalAgentSseEventParser::class.java)
    private val objectMapper = jacksonObjectMapper()
    private val MAP_TYPE = object : TypeReference<Map<String, Any?>>() {}
    private const val DONE_SENTINEL = "[DONE]"
}
