package com.tencent.devops.ai.properties

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 大型流水线编排分析预算。
 *
 * 输入预算使用 UTF-8 字节数作为保守 token 上界，并额外计入提示词、Skill 正文/resources、
 * 工具 schema 和输出预留；不依赖 chars/2.5 这类平均值作为硬保障。
 */
@ConfigurationProperties("ai.pipeline-analysis")
data class PipelineAnalysisProperties(
    val overviewTargetTokens: Int = 4_096,
    val taskTargetTokens: Int = 49_152,
    val taskHardLimitTokens: Int = 65_536,
    val reportTargetTokens: Int = 2_048,
    val reportHardLimitTokens: Int = 4_096,
    val mainResultTokens: Int = 6_144,
    val schemaReserveTokens: Int = 1_024,
    val concurrency: Int = 3,
    val maxLeafTasks: Int = 32,
    val maxCalls: Int = 64,
    val maxRetries: Int = 1,
    val taskTimeoutSeconds: Long = 180,
    val totalTimeoutSeconds: Long = 600,
    val summaryFanIn: Int = 6
)
