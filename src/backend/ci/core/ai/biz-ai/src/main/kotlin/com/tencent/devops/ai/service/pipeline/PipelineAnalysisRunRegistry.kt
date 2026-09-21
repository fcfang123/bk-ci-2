package com.tencent.devops.ai.service.pipeline

import io.agentscope.core.ReActAgent
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 跟踪隔离分析子任务，使显式停止和超时可以协作式 interrupt 全部活动 Agent。 */
@Component
class PipelineAnalysisRunRegistry {

    private val runsByThread = ConcurrentHashMap<String, MutableSet<RunHandle>>()

    fun start(threadId: String?): RunHandle {
        val handle = RunHandle(UUID.randomUUID().toString(), threadId)
        if (!threadId.isNullOrBlank()) {
            runsByThread.computeIfAbsent(threadId) { ConcurrentHashMap.newKeySet() }.add(handle)
        }
        return handle
    }

    fun registerAgent(handle: RunHandle, agent: ReActAgent) {
        if (handle.cancelled.get()) {
            agent.interrupt()
        } else {
            handle.agents.add(agent)
        }
    }

    fun removeAgent(handle: RunHandle, agent: ReActAgent) {
        handle.agents.remove(agent)
    }

    fun finish(handle: RunHandle) {
        handle.agents.forEach { it.interrupt() }
        handle.agents.clear()
        handle.threadId?.let { threadId ->
            runsByThread.computeIfPresent(threadId) { _, runs ->
                runs.remove(handle)
                runs.takeIf { it.isNotEmpty() }
            }
        }
    }

    fun cancel(threadId: String) {
        runsByThread.remove(threadId)?.forEach { handle ->
            handle.cancelled.set(true)
            handle.agents.forEach { it.interrupt() }
        }
    }

    data class RunHandle(
        val runId: String,
        val threadId: String?,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val agents: MutableSet<ReActAgent> = ConcurrentHashMap.newKeySet()
    )
}
