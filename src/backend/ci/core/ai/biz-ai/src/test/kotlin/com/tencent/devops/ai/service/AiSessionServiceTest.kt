package com.tencent.devops.ai.service

import com.tencent.devops.ai.constant.AiMessageCode
import com.tencent.devops.ai.dao.AiSessionDao
import com.tencent.devops.ai.pojo.AiSessionCreate
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.model.ai.tables.records.TAiSessionRecord
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.jooq.DSLContext
import org.jooq.Result
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

class AiSessionServiceTest {

    private val dslContext = mockk<DSLContext>(relaxed = true)
    private val dao = mockk<AiSessionDao>(relaxed = true)
    private val messageService = mockk<AiMessageService>(relaxed = true)
    private val service = AiSessionService(dslContext, dao, messageService)

    @Test
    fun `should create pipeline-level session`() {
        val record = sessionRecord(pipelineId = "p-1")
        every { dao.create(any(), any(), any(), any(), any(), any()) } just runs
        every { dao.getById(dslContext, any()) } returns record

        val result = service.createSession(
            userId = "tester",
            request = AiSessionCreate(
                projectId = "demo",
                pipelineId = "p-1",
                title = "流水线会话"
            )
        )

        assertEquals("p-1", result.pipelineId)
        assertEquals("demo", result.projectId)
        verify {
            dao.create(
                dslContext = dslContext,
                id = any(),
                userId = "tester",
                projectId = "demo",
                pipelineId = "p-1",
                title = "流水线会话"
            )
        }
    }

    @Test
    fun `should reject pipeline session without project`() {
        val error = assertThrows<ErrorCodeException> {
            service.createSession(
                userId = "tester",
                request = AiSessionCreate(pipelineId = "p-1")
            )
        }

        assertEquals(AiMessageCode.SESSION_PIPELINE_REQUIRES_PROJECT, error.errorCode)
        verify(exactly = 0) { dao.create(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `should treat blank pipelineId as project-level session`() {
        val record = sessionRecord(pipelineId = null)
        every { dao.create(any(), any(), any(), any(), any(), any()) } just runs
        every { dao.getById(dslContext, any()) } returns record

        service.createSession(
            userId = "tester",
            request = AiSessionCreate(projectId = "demo", pipelineId = "  ")
        )

        verify {
            dao.create(
                dslContext = dslContext,
                id = any(),
                userId = "tester",
                projectId = "demo",
                pipelineId = null,
                title = "新对话"
            )
        }
    }

    @Test
    fun `should default title to 新对话 when title omitted`() {
        val record = sessionRecord(pipelineId = "p-1", title = "新对话")
        every { dao.create(any(), any(), any(), any(), any(), any()) } just runs
        every { dao.getById(dslContext, any()) } returns record

        service.createSession(
            userId = "tester",
            request = AiSessionCreate(projectId = "demo", pipelineId = "p-1")
        )

        verify {
            dao.create(
                dslContext = dslContext,
                id = any(),
                userId = "tester",
                projectId = "demo",
                pipelineId = "p-1",
                title = "新对话"
            )
        }
    }

    @Test
    fun `should list sessions by pipeline scope`() {
        val records = mockk<Result<TAiSessionRecord>>(relaxed = true)
        every {
            dao.listByUserAndScope(dslContext, "tester", "demo", "p-1")
        } returns records

        service.listSessions("tester", "demo", "p-1")

        verify {
            dao.listByUserAndScope(dslContext, "tester", "demo", "p-1")
        }
    }

    @Test
    fun `should get latest session by pipeline scope`() {
        every {
            dao.getLatest(dslContext, "tester", "demo", "p-1")
        } returns sessionRecord(pipelineId = "p-1")

        val result = service.getLatestSession("tester", "demo", "p-1")

        assertEquals("p-1", result?.pipelineId)
    }

    @Test
    fun `should return null when latest pipeline session missing`() {
        every {
            dao.getLatest(dslContext, "tester", "demo", "p-1")
        } returns null

        assertNull(service.getLatestSession("tester", "demo", "p-1"))
    }

    @Test
    fun `should persist first user message as title when auto-creating session`() {
        every { dao.getById(dslContext, "thread-1") } returns null

        service.ensureSession(
            sessionId = "thread-1",
            userId = "tester",
            projectId = "demo",
            pipelineId = "p-1",
            firstUserMessage = "帮我看下构建"
        )

        verify {
            dao.create(
                dslContext = dslContext,
                id = "thread-1",
                userId = "tester",
                projectId = "demo",
                pipelineId = "p-1",
                title = "帮我看下构建"
            )
        }
    }

    private fun sessionRecord(
        id: String = "s-1",
        userId: String = "tester",
        projectId: String? = "demo",
        pipelineId: String? = null,
        title: String = "流水线会话"
    ): TAiSessionRecord {
        val now = LocalDateTime.of(2026, 1, 1, 0, 0)
        return mockk {
            every { this@mockk.id } returns id
            every { this@mockk.userId } returns userId
            every { this@mockk.projectId } returns projectId
            every { this@mockk.pipelineId } returns pipelineId
            every { this@mockk.title } returns title
            every { createdTime } returns now
            every { updatedTime } returns now
        }
    }
}
