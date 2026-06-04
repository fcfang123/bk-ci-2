package com.tencent.devops.ai.agent.external.adapter

import com.tencent.devops.ai.agent.external.util.ExternalAgentSseEventParser
import com.tencent.devops.ai.agent.external.util.ExternalAgentSseLineBuffer
import com.tencent.devops.ai.agent.external.ExternalAgentErrorCategory
import com.tencent.devops.ai.agent.external.ExternalAgentEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExternalAgentSseLineBufferTest {

    @Test
    fun `BkAiDev buffer should merge split JSON across two chunks`() {
        val buffer = ExternalAgentSseLineBuffer(ExternalAgentSseEventParser::parseBkAiDev)
        val chunk1 = """data: {"type":"TEXT_MESSAGE"""
        val chunk2 = """_CONTENT","delta":"hi"}""" + "\n"

        assertEquals(emptyList<ExternalAgentEvent>(), buffer.accept(chunk1))
        assertEquals(listOf(ExternalAgentEvent.TextDelta("hi")), buffer.accept(chunk2))
        assertEquals(emptyList<ExternalAgentEvent>(), buffer.flush())
    }

    @Test
    fun `BkAiDev buffer should preserve event order when one data line spans three chunks`() {
        val buffer = ExternalAgentSseLineBuffer(ExternalAgentSseEventParser::parseBkAiDev)
        val line = """data: {"type":"TEXT_MESSAGE_CONTENT","delta":"hello"}""" + "\n" +
            """data: {"type":"TEXT_MESSAGE_CONTENT","delta":" world"}""" + "\n"
        val splitAt = line.length / 3
        val chunk1 = line.substring(0, splitAt)
        val chunk2 = line.substring(splitAt, splitAt * 2)
        val chunk3 = line.substring(splitAt * 2)

        val events = buildList {
            addAll(buffer.accept(chunk1))
            addAll(buffer.accept(chunk2))
            addAll(buffer.accept(chunk3))
            addAll(buffer.flush())
        }

        assertEquals(
            listOf(
                ExternalAgentEvent.TextDelta("hello"),
                ExternalAgentEvent.TextDelta(" world")
            ),
            events
        )
    }

    @Test
    fun `BkAiDev buffer flush should parse final line without trailing newline`() {
        val buffer = ExternalAgentSseLineBuffer(ExternalAgentSseEventParser::parseBkAiDev)
        val chunk = """data: {"type":"TEXT_MESSAGE_CONTENT","delta":"hi"}"""

        assertEquals(emptyList<ExternalAgentEvent>(), buffer.accept(chunk))
        assertEquals(listOf(ExternalAgentEvent.TextDelta("hi")), buffer.flush())
        assertEquals(emptyList<ExternalAgentEvent>(), buffer.flush())
    }

    @Test
    fun `BkAiDev buffer should not emit parse error for split JSON`() {
        val buffer = ExternalAgentSseLineBuffer(ExternalAgentSseEventParser::parseBkAiDev)

        buffer.accept("""data: {"type":"TEXT_MESSAGE""")
        val events = buffer.accept("""_CONTENT","delta":"x"}""" + "\n")

        assertTrue(events.none { it is ExternalAgentEvent.Error })
        assertEquals(listOf(ExternalAgentEvent.TextDelta("x")), events)
    }

    @Test
    fun `Knot buffer should merge split JSON across two chunks`() {
        val buffer = ExternalAgentSseLineBuffer(ExternalAgentSseEventParser::parseKnot)
        val chunk1 = """data: {"type":"TEXT_MESSAGE_CONTENT","rawEvent":{"content":"hel"""
        val chunk2 = """lo","conversation_id":"conv-1"}}""" + "\n"

        assertEquals(emptyList<ExternalAgentEvent>(), buffer.accept(chunk1))
        assertEquals(
            listOf(
                ExternalAgentEvent.TextDelta("hello"),
                ExternalAgentEvent.ConversationId("conv-1")
            ),
            buffer.accept(chunk2)
        )
        assertEquals(emptyList<ExternalAgentEvent>(), buffer.flush())
    }

    @Test
    fun `Knot buffer flush should parse final line without trailing newline`() {
        val buffer = ExternalAgentSseLineBuffer(ExternalAgentSseEventParser::parseKnot)
        val chunk =
            """data: {"type":"TEXT_MESSAGE_CONTENT","rawEvent":{"content":"hi","conversation_id":"c-1"}}"""

        assertEquals(emptyList<ExternalAgentEvent>(), buffer.accept(chunk))
        assertEquals(
            listOf(
                ExternalAgentEvent.TextDelta("hi"),
                ExternalAgentEvent.ConversationId("c-1")
            ),
            buffer.flush()
        )
    }

    @Test
    fun `Knot buffer should not emit parse error for split JSON`() {
        val buffer = ExternalAgentSseLineBuffer(ExternalAgentSseEventParser::parseKnot)

        buffer.accept("""data: {"type":"TEXT_MESSAGE_CONTENT","rawEvent":{"content":"a""")
        val events = buffer.accept(""","conversation_id":"id-1"}}""" + "\n")

        assertTrue(events.none { it is ExternalAgentEvent.Error })
        assertEquals(
            listOf(
                ExternalAgentEvent.TextDelta("a"),
                ExternalAgentEvent.ConversationId("id-1")
            ),
            events
        )
    }

    @Test
    fun `buffer accept on empty chunk should be no-op`() {
        val buffer = ExternalAgentSseLineBuffer(ExternalAgentSseEventParser::parseBkAiDev)

        assertEquals(emptyList<ExternalAgentEvent>(), buffer.accept(""))
    }

    @Test
    fun `buffer should match whole-chunk parser for complete SSE payload`() {
        val payload = """
            data: {"type":"TEXT_MESSAGE_CONTENT","delta":"one"}
            data: {"type":"TEXT_MESSAGE_CONTENT","delta":"two"}

        """.trimIndent() + "\n"
        val buffer = ExternalAgentSseLineBuffer(ExternalAgentSseEventParser::parseBkAiDev)

        val buffered = buffer.accept(payload) + buffer.flush()
        val direct = ExternalAgentSseEventParser.parseBkAiDev(payload)

        assertEquals(direct, buffered)
    }

    @Test
    fun `buffer should surface parse error only after complete malformed line`() {
        val buffer = ExternalAgentSseLineBuffer(ExternalAgentSseEventParser::parseBkAiDev)

        val events = buffer.accept("data: {broken\n")

        assertEquals(1, events.size)
        val error = events.single()
        assertTrue(error is ExternalAgentEvent.Error)
        assertEquals(ExternalAgentErrorCategory.PARSE_ERROR, (error as ExternalAgentEvent.Error).category)
    }
}
