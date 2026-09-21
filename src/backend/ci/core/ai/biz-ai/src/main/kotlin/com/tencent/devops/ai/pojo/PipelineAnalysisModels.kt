package com.tencent.devops.ai.pojo

import io.agentscope.core.model.Model

/** 流水线编排分析模式。 */
enum class PipelineAnalysisMode {
    DIRECT,
    SPLIT,
    LOCAL;

    companion object {
        fun parse(value: String?): PipelineAnalysisMode = entries.firstOrNull {
            it.name.equals(value?.trim(), ignoreCase = true)
        } ?: DIRECT
    }
}

/** 流水线编排局部分析范围。 */
data class PipelineAnalysisScope(
    val stageId: String? = null,
    val jobId: String? = null,
    val elementId: String? = null
) {
    fun isLocal(): Boolean = !stageId.isNullOrBlank() || !jobId.isNullOrBlank() || !elementId.isNullOrBlank()
}

/** Build 工具调用隔离分析服务时携带的运行上下文。 */
data class PipelineAnalysisInvocationContext(
    val model: Model,
    val threadId: String? = null,
    val progressConsumer: (PipelineAnalysisProgress) -> Unit = {}
)

/** 仅向主会话发布的阶段级进度，不携带模型原文或子智能体推理。 */
data class PipelineAnalysisProgress(
    val phase: String,
    val current: Int? = null,
    val total: Int? = null,
    val message: String
)

/** 单个隔离分析任务的可定位范围。 */
data class PipelineAnalysisTaskScope(
    val stageIds: Set<String> = emptySet(),
    val jobIds: Set<String> = emptySet(),
    val elementIds: Set<String> = emptySet(),
    val label: String
)

/** 后端规划出的有界分析切片。 */
data class PipelineAnalysisSlice(
    val id: String,
    val scope: PipelineAnalysisTaskScope,
    val content: String,
    val estimatedInputTokens: Int
)

/** 单个失败范围。 */
data class PipelineAnalysisFailure(
    val scope: PipelineAnalysisTaskScope,
    val attempts: Int,
    val reason: String
)

/** 程序化覆盖率，按成功叶子覆盖的唯一节点计算。 */
data class PipelineAnalysisCoverage(
    val totalLeaves: Int,
    val successfulLeaves: Int,
    val stageTotal: Int,
    val stageCovered: Int,
    val jobTotal: Int,
    val jobCovered: Int,
    val elementTotal: Int,
    val elementCovered: Int,
    val percent: Double
)

/** 流水线编排分析最终结果。 */
data class PipelineAnalysisResult(
    val pipelineId: String,
    val requestedVersion: Int?,
    val effectiveVersion: Int,
    val requestedMode: PipelineAnalysisMode,
    val effectiveMode: PipelineAnalysisMode,
    val modeReason: String,
    val status: String,
    val overview: String,
    val report: String,
    val failures: List<PipelineAnalysisFailure>,
    val coverage: PipelineAnalysisCoverage
)

/** 一次规划结果。 */
data class PipelineAnalysisPlan(
    val requestedMode: PipelineAnalysisMode,
    val effectiveMode: PipelineAnalysisMode,
    val modeReason: String,
    val overview: String,
    val slices: List<PipelineAnalysisSlice>,
    val omittedFailures: List<PipelineAnalysisFailure>,
    val totalStageIds: Set<String>,
    val totalJobIds: Set<String>,
    val totalElementIds: Set<String>
)
