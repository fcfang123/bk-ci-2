package com.tencent.devops.ai.agent.external.adapter

import com.tencent.devops.ai.external.ExternalAgentErrorCategory
import com.tencent.devops.ai.external.ExternalAgentEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExternalAgentSseEventParserTest {

    @Test
    fun `BkAiDev parser should read standard AG-UI text delta`() {
        val chunk = """
            data: {"type":"TEXT_MESSAGE_CONTENT","delta":"hello"}

        """.trimIndent()

        val events = ExternalAgentSseEventParser.parseBkAiDev(chunk)

        assertEquals(listOf(ExternalAgentEvent.TextDelta("hello")), events)
    }

    @Test
    fun `BkAiDev parser should read legacy text event content`() {
        val chunk = """
            data: {"event":"text","content":"hello"}

        """.trimIndent()

        val events = ExternalAgentSseEventParser.parseBkAiDev(chunk)

        assertEquals(listOf(ExternalAgentEvent.TextDelta("hello")), events)
    }

    @Test
    fun `BkAiDev parser should read thread id as conversation id`() {
        val chunk = """
            data: {"type":"RUN_STARTED","thread_id":"thread-1"}

        """.trimIndent()

        val events = ExternalAgentSseEventParser.parseBkAiDev(chunk)

        assertEquals(listOf(ExternalAgentEvent.ConversationId("thread-1")), events)
    }

    @Test
    fun `Knot parser should read rawEvent content and conversation id`() {
        val chunk = """
            data: {"type":"TEXT_MESSAGE_CONTENT","rawEvent":{"content":"hello","conversation_id":"conv-1"}}

        """.trimIndent()

        val events = ExternalAgentSseEventParser.parseKnot(chunk)

        assertEquals(
            listOf(
                ExternalAgentEvent.TextDelta("hello"),
                ExternalAgentEvent.ConversationId("conv-1")
            ),
            events
        )
    }

    @Test
    fun `parser should read multiple data lines in one chunk`() {
        val chunk = """
            data: {"type":"TEXT_MESSAGE_CONTENT","delta":"hello"}
            data: {"type":"TEXT_MESSAGE_CONTENT","delta":" world"}

        """.trimIndent()

        val events = ExternalAgentSseEventParser.parseBkAiDev(chunk)

        assertEquals(
            listOf(
                ExternalAgentEvent.TextDelta("hello"),
                ExternalAgentEvent.TextDelta(" world")
            ),
            events
        )
    }

    @Test
    fun `parser should convert upstream error event`() {
        val chunk = """
            data: {"type":"RUN_ERROR","message":"failed"}

        """.trimIndent()

        val events = ExternalAgentSseEventParser.parseBkAiDev(chunk)

        assertEquals(
            listOf(ExternalAgentEvent.Error("failed", ExternalAgentErrorCategory.UPSTREAM_ERROR)),
            events
        )
    }

    @Test
    fun `parser should convert done sentinel to done event`() {
        val events = ExternalAgentSseEventParser.parseBkAiDev("data: [DONE]")

        assertEquals(listOf(ExternalAgentEvent.Done), events)
    }

    @Test
    fun `parser should return parse error for malformed data event`() {
        val events = ExternalAgentSseEventParser.parseBkAiDev("data: {broken")

        assertEquals(1, events.size)
        val error = events.single()
        assertTrue(error is ExternalAgentEvent.Error)
        assertEquals(ExternalAgentErrorCategory.PARSE_ERROR, (error as ExternalAgentEvent.Error).category)
    }
}
