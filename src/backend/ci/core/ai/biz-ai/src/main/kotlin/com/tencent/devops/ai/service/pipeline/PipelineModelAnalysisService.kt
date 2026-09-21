package com.tencent.devops.ai.service.pipeline

import com.tencent.devops.ai.agent.build.analysis.PipelineAnalysisAgentFactory
import com.tencent.devops.ai.agent.build.analysis.PipelineAnalysisBudget
import com.tencent.devops.ai.agent.build.analysis.PipelineModelAnalysisPlanner
import com.tencent.devops.ai.constant.AiMessageCode
import com.tencent.devops.ai.pojo.PipelineAnalysisCoverage
import com.tencent.devops.ai.pojo.PipelineAnalysisFailure
import com.tencent.devops.ai.pojo.PipelineAnalysisInvocationContext
import com.tencent.devops.ai.pojo.PipelineAnalysisMode
import com.tencent.devops.ai.pojo.PipelineAnalysisProgress
import com.tencent.devops.ai.pojo.PipelineAnalysisResult
import com.tencent.devops.ai.pojo.PipelineAnalysisScope
import com.tencent.devops.ai.pojo.PipelineAnalysisSlice
import com.tencent.devops.ai.pojo.PipelineAnalysisTaskScope
import com.tencent.devops.ai.properties.PipelineAnalysisProperties
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.web.utils.I18nUtil
import com.tencent.devops.process.api.service.ServicePipelineVersionResource
import io.agentscope.core.ReActAgent
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一次授权读取固定版本 Model，
 * 并在本次运行内完成脱敏、切片、隔离分析与分层汇总。
 */
@Service
class PipelineModelAnalysisService(
    private val client: Client,
    private val planner: PipelineModelAnalysisPlanner,
    private val agentFactory: PipelineAnalysisAgentFactory,
    private val runRegistry: PipelineAnalysisRunRegistry,
    private val properties: PipelineAnalysisProperties
) {

    fun analyze(
        userId: String,
        projectId: String,
        pipelineId: String,
        version: Int?,
        question: String,
        requestedMode: PipelineAnalysisMode,
        scope: PipelineAnalysisScope,
        invocationContext: PipelineAnalysisInvocationContext
    ): PipelineAnalysisResult {
        val run = runRegistry.start(invocationContext.threadId)
        val callCount = AtomicInteger(0)
        val startedAt = System.nanoTime()
        val language = I18nUtil.getRequestUserLanguage()
        try {
            progress(
                context = invocationContext,
                language = language,
                phase = PHASE_READING,
                messageCode = AiMessageCode.PIPELINE_ANALYSIS_READING
            )
            val snapshot = client.get(ServicePipelineVersionResource::class).getVersionModel(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                version = version
            ).data ?: error("未找到流水线 $pipelineId 的编排信息")
            ensureActive(run, startedAt)
            progress(
                context = invocationContext,
                language = language,
                phase = PHASE_PLANNING,
                messageCode = AiMessageCode.PIPELINE_ANALYSIS_PLANNING
            )
            val reserved = agentFactory.reservedInputTokens(userId) +
                PipelineAnalysisBudget.estimateTokens(question) + QUESTION_ENVELOPE_TOKENS
            val plan = planner.plan(
                pipelineId = pipelineId,
                snapshot = snapshot,
                requestedMode = requestedMode,
                scope = scope,
                reservedInputTokens = reserved
            )
            if (plan.slices.isEmpty()) {
                val scopeNotFound = I18nUtil.getCodeLanMessage(
                    messageCode = AiMessageCode.PIPELINE_ANALYSIS_SCOPE_NOT_FOUND,
                    language = language
                )
                val failure = PipelineAnalysisFailure(
                    scope = PipelineAnalysisTaskScope(
                        label = I18nUtil.getCodeLanMessage(
                            messageCode = AiMessageCode.PIPELINE_ANALYSIS_REQUEST_SCOPE,
                            language = language
                        )
                    ),
                    attempts = 0,
                    reason = scopeNotFound
                )
                progress(
                    context = invocationContext,
                    language = language,
                    phase = PHASE_PARTIAL,
                    message = failure.reason
                )
                return PipelineAnalysisResult(
                    pipelineId = pipelineId,
                    requestedVersion = version,
                    effectiveVersion = snapshot.version,
                    requestedMode = plan.requestedMode,
                    effectiveMode = plan.effectiveMode,
                    modeReason = plan.modeReason,
                    status = STATUS_PARTIAL,
                    overview = plan.overview,
                    report = failure.reason,
                    failures = listOf(failure),
                    coverage = coverage(plan, emptyList())
                )
            }
            progress(
                context = invocationContext,
                language = language,
                phase = PHASE_SPLITTING,
                current = 0,
                total = plan.slices.size,
                messageCode = AiMessageCode.PIPELINE_ANALYSIS_TASKS_PLANNED,
                params = arrayOf(plan.slices.size.toString())
            )
            val leafResults = executeLeaves(
                slices = plan.slices,
                model = invocationContext.model,
                userId = userId,
                question = question,
                run = run,
                callCount = callCount,
                startedAt = startedAt,
                invocationContext = invocationContext,
                language = language
            )
            val successes = leafResults.filterIsInstance<TaskResult.Success>()
            val failures = plan.omittedFailures + leafResults.filterIsInstance<TaskResult.Failure>().map { it.failure }
            ensureActive(run, startedAt)
            progress(
                context = invocationContext,
                language = language,
                phase = PHASE_SUMMARIZING,
                messageCode = AiMessageCode.PIPELINE_ANALYSIS_SUMMARIZING
            )
            val report = summarize(
                successes = successes,
                model = invocationContext.model,
                userId = userId,
                question = question,
                run = run,
                callCount = callCount,
                startedAt = startedAt
            )
            val status = if (failures.isEmpty()) STATUS_COMPLETED else STATUS_PARTIAL
            val phase = if (failures.isEmpty()) PHASE_COMPLETED else PHASE_PARTIAL
            val completionMessageCode = if (failures.isEmpty()) {
                AiMessageCode.PIPELINE_ANALYSIS_COMPLETED
            } else {
                AiMessageCode.PIPELINE_ANALYSIS_PARTIALLY_COMPLETED
            }
            progress(
                context = invocationContext,
                language = language,
                phase = phase,
                messageCode = completionMessageCode
            )
            val reportBudget = (properties.mainResultTokens -
                PipelineAnalysisBudget.estimateTokens(plan.overview) - RESULT_ENVELOPE_TOKENS)
                .coerceAtLeast(MIN_REPORT_TOKENS)
            return PipelineAnalysisResult(
                pipelineId = pipelineId,
                requestedVersion = version,
                effectiveVersion = snapshot.version,
                requestedMode = plan.requestedMode,
                effectiveMode = plan.effectiveMode,
                modeReason = plan.modeReason,
                status = status,
                overview = plan.overview,
                report = PipelineAnalysisBudget.truncate(report, reportBudget),
                failures = failures,
                coverage = coverage(plan, successes)
            )
        } finally {
            runRegistry.finish(run)
        }
    }

    private fun executeLeaves(
        slices: List<PipelineAnalysisSlice>,
        model: io.agentscope.core.model.Model,
        userId: String,
        question: String,
        run: PipelineAnalysisRunRegistry.RunHandle,
        callCount: AtomicInteger,
        startedAt: Long,
        invocationContext: PipelineAnalysisInvocationContext,
        language: String
    ): List<TaskResult> {
        val executor = Executors.newFixedThreadPool(properties.concurrency.coerceAtLeast(1))
        val completed = AtomicInteger(0)
        return try {
            val futures = slices.map { slice ->
                slice to CompletableFuture.supplyAsync(
                    {
                        val result = analyzeLeaf(
                            slice,
                            model,
                            userId,
                            question,
                            run,
                            callCount,
                            startedAt
                        )
                        val current = completed.incrementAndGet()
                        progress(
                            context = invocationContext,
                            language = language,
                            phase = PHASE_SPLITTING,
                            current = current,
                            total = slices.size,
                            messageCode = AiMessageCode.PIPELINE_ANALYSIS_TASK_COMPLETED,
                            params = arrayOf(current.toString(), slices.size.toString())
                        )
                        result
                    },
                    executor
                )
            }
            futures.map { (slice, future) ->
                try {
                    future.get(remainingMillis(startedAt), TimeUnit.MILLISECONDS)
                } catch (e: Exception) {
                    TaskResult.Failure(
                        PipelineAnalysisFailure(
                            scope = slice.scope,
                            attempts = 0,
                            reason = safeError(e)
                        )
                    )
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun analyzeLeaf(
        slice: PipelineAnalysisSlice,
        model: io.agentscope.core.model.Model,
        userId: String,
        question: String,
        run: PipelineAnalysisRunRegistry.RunHandle,
        callCount: AtomicInteger,
        startedAt: Long
    ): TaskResult {
        val prompt = """
            用户目标：$question
            固定范围：${slice.scope.label}
            这是已脱敏的固定版本 Model 切片。只分析提供内容，不推断未提供节点。
            请输出范围、职责、关键控制流、变量/依赖、风险与未知项。
            报告目标 ${properties.reportTargetTokens} tokens。
            Model切片：
            ${slice.content}
        """.trimIndent()
        return callWithRetry(slice.scope, prompt, model, userId, run, callCount, startedAt)
    }

    private fun summarize(
        successes: List<TaskResult.Success>,
        model: io.agentscope.core.model.Model,
        userId: String,
        question: String,
        run: PipelineAnalysisRunRegistry.RunHandle,
        callCount: AtomicInteger,
        startedAt: Long
    ): String {
        if (successes.isEmpty()) {
            return "所有已调度范围均分析失败，请根据 failures 重试具体范围。"
        }
        if (successes.size == 1) return successes.single().report
        var level = successes.map { it.scope.label to it.report }
        var depth = 1
        while (level.size > 1) {
            ensureActive(run, startedAt)
            level = level.chunked(properties.summaryFanIn.coerceAtLeast(2)).mapIndexed { index, group ->
                val summaryScope = PipelineAnalysisTaskScope(
                    label = "汇总层级$depth-${index + 1}"
                )
                val prompt = buildSummaryPrompt(question, group, depth)
                when (
                    val result = callWithRetry(
                        summaryScope,
                        prompt,
                        model,
                        userId,
                        run,
                        callCount,
                        startedAt
                    )
                ) {
                    is TaskResult.Success -> result.scope.label to result.report
                    is TaskResult.Failure -> summaryScope.label to deterministicSummary(group, result.failure.reason)
                }
            }
            depth++
        }
        return level.single().second
    }

    private fun buildSummaryPrompt(
        question: String,
        reports: List<Pair<String, String>>,
        depth: Int
    ): String {
        val content = reports.joinToString("\n\n") { (scope, report) ->
            "## $scope\n$report"
        }
        return """
            用户目标：$question
            当前为第 $depth 层汇总。只能基于以下子报告做去重、关联和归纳。
            不得索取原始 Model 或子历史。
            保留范围定位、跨 Stage/Job 依赖、风险、未知项。
            报告目标 ${properties.reportTargetTokens} tokens。
            子报告：
            $content
        """.trimIndent()
    }

    private fun callWithRetry(
        scope: PipelineAnalysisTaskScope,
        prompt: String,
        model: io.agentscope.core.model.Model,
        userId: String,
        run: PipelineAnalysisRunRegistry.RunHandle,
        callCount: AtomicInteger,
        startedAt: Long
    ): TaskResult {
        var lastError = "unknown"
        repeat(properties.maxRetries.coerceAtLeast(0) + 1) { attempt ->
            ensureActive(run, startedAt)
            if (callCount.incrementAndGet() > properties.maxCalls) {
                return TaskResult.Failure(
                    PipelineAnalysisFailure(scope, attempt, "超过总调用上限 ${properties.maxCalls}")
                )
            }
            var agent: ReActAgent? = null
            try {
                agent = agentFactory.create(model, userId)
                runRegistry.registerAgent(run, agent)
                ensureActive(run, startedAt)
                val message = Msg.builder()
                    .name("pipeline-analysis-user")
                    .role(MsgRole.USER)
                    .textContent(prompt)
                    .build()
                val response = agent.call(message)
                    .block(Duration.ofSeconds(properties.taskTimeoutSeconds))
                    ?: error("分析 Agent 未返回结果")
                if (run.cancelled.get()) error("分析已取消")
                return TaskResult.Success(scope, response.textContent)
            } catch (e: Exception) {
                lastError = safeError(e)
                logger.warn(
                    "[PipelineAnalysis] Task failed: scope={}, attempt={}, error={}",
                    scope.label,
                    attempt + 1,
                    lastError
                )
            } finally {
                agent?.let {
                    it.interrupt()
                    runRegistry.removeAgent(run, it)
                }
            }
        }
        return TaskResult.Failure(
            PipelineAnalysisFailure(
                scope = scope,
                attempts = properties.maxRetries.coerceAtLeast(0) + 1,
                reason = lastError
            )
        )
    }

    private fun coverage(
        plan: com.tencent.devops.ai.pojo.PipelineAnalysisPlan,
        successes: List<TaskResult.Success>
    ): PipelineAnalysisCoverage {
        val coveredStages = successes.flatMap { it.scope.stageIds }.toSet()
        val coveredJobs = successes.flatMap { it.scope.jobIds }.toSet()
        val coveredElements = successes.flatMap { it.scope.elementIds }.toSet()
        val total = plan.totalStageIds.size + plan.totalJobIds.size + plan.totalElementIds.size
        val covered = coveredStages.size + coveredJobs.size + coveredElements.size
        return PipelineAnalysisCoverage(
            totalLeaves = plan.slices.size + plan.omittedFailures.size,
            successfulLeaves = successes.size,
            stageTotal = plan.totalStageIds.size,
            stageCovered = coveredStages.intersect(plan.totalStageIds).size,
            jobTotal = plan.totalJobIds.size,
            jobCovered = coveredJobs.intersect(plan.totalJobIds).size,
            elementTotal = plan.totalElementIds.size,
            elementCovered = coveredElements.intersect(plan.totalElementIds).size,
            percent = if (total == 0) 0.0 else covered.coerceAtMost(total) * 100.0 / total
        )
    }

    private fun progress(
        context: PipelineAnalysisInvocationContext,
        language: String,
        phase: String,
        current: Int? = null,
        total: Int? = null,
        messageCode: String? = null,
        params: Array<String>? = null,
        message: String? = null
    ) {
        val localizedMessage = message ?: requireNotNull(messageCode) {
            "Progress message or messageCode is required"
        }.let {
            I18nUtil.getCodeLanMessage(
                messageCode = it,
                language = language,
                params = params
            )
        }
        runCatching {
            context.progressConsumer(PipelineAnalysisProgress(phase, current, total, localizedMessage))
        }.onFailure {
            logger.debug("[PipelineAnalysis] Progress delivery failed: phase={}", phase)
        }
    }

    private fun ensureActive(run: PipelineAnalysisRunRegistry.RunHandle, startedAt: Long) {
        check(!run.cancelled.get()) { "流水线编排分析已取消" }
        check(remainingMillis(startedAt) > 0L) { "流水线编排分析已超时" }
    }

    private fun remainingMillis(startedAt: Long): Long {
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        return (TimeUnit.SECONDS.toMillis(properties.totalTimeoutSeconds) - elapsed).coerceAtLeast(1L)
    }

    private fun deterministicSummary(reports: List<Pair<String, String>>, reason: String): String {
        return PipelineAnalysisBudget.truncate(
            reports.joinToString("\n\n") { (scope, report) -> "## $scope\n$report" } +
                "\n\n汇总 Agent 失败，已保留子报告：$reason",
            properties.reportHardLimitTokens
        )
    }

    private fun safeError(error: Throwable): String {
        val cause = generateSequence(error) { it.cause }.last()
        return cause.message?.take(MAX_ERROR_LENGTH) ?: cause.javaClass.simpleName
    }

    private sealed interface TaskResult {
        data class Success(
            val scope: PipelineAnalysisTaskScope,
            val report: String
        ) : TaskResult

        data class Failure(val failure: PipelineAnalysisFailure) : TaskResult
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PipelineModelAnalysisService::class.java)
        private const val PHASE_READING = "READING"
        private const val PHASE_PLANNING = "PLANNING"
        private const val PHASE_SPLITTING = "SPLITTING"
        private const val PHASE_SUMMARIZING = "SUMMARIZING"
        private const val PHASE_COMPLETED = "COMPLETED"
        private const val PHASE_PARTIAL = "PARTIALLY_COMPLETED"
        private const val STATUS_COMPLETED = "COMPLETED"
        private const val STATUS_PARTIAL = "PARTIAL"
        private const val QUESTION_ENVELOPE_TOKENS = 1_024
        private const val RESULT_ENVELOPE_TOKENS = 1_024
        private const val MIN_REPORT_TOKENS = 1_024
        private const val MAX_ERROR_LENGTH = 500
    }
}
