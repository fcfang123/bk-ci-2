/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.process.engine.common

import com.tencent.devops.common.pipeline.enums.BuildRecordTimeStamp
import com.tencent.devops.common.pipeline.pojo.time.BuildRecordTimeCost
import com.tencent.devops.common.pipeline.pojo.time.BuildTimestampType
import com.tencent.devops.process.engine.pojo.BuildInfo
import com.tencent.devops.process.engine.pojo.PipelineBuildContainer
import com.tencent.devops.process.engine.pojo.PipelineBuildStage
import com.tencent.devops.process.engine.pojo.PipelineBuildTask
import com.tencent.devops.process.pojo.pipeline.record.BuildRecordContainer
import com.tencent.devops.process.pojo.pipeline.record.BuildRecordStage
import com.tencent.devops.process.pojo.pipeline.record.BuildRecordTask
import org.slf4j.LoggerFactory
import java.time.Duration

object BuildTimeCostUtils {
    private val logger = LoggerFactory.getLogger(BuildTimeCostUtils::class.java)

    // TODO 某层结束执行时获取内部所有耗时并计算，build对象可能因为数据清理不存在，startTime和endTime也不一定有值，需兜底

    fun generateBuildTimeCost(
        buildInfo: BuildInfo,
        stagePairs: List<Pair<BuildRecordStage, PipelineBuildStage?>>
    ): BuildRecordTimeCost {
        val startTime = buildInfo.startTime
        val endTime = buildInfo.endTime

        stagePairs.forEach { (record, build) ->
            val start = build?.startTime
            val end = build?.endTime
            val timestamps = record.timestamps
        }

        return BuildRecordTimeCost()
    }

    fun generateStageTimeCost(
        buildStage: PipelineBuildStage,
        containerPairs: List<Pair<BuildRecordContainer, PipelineBuildContainer?>>
    ): BuildRecordTimeCost {
        val startTime = buildStage.startTime
        val endTime = buildStage.endTime

        containerPairs.forEach { (record, build) ->
            val start = build?.startTime
            val end = build?.endTime
            val timestamps = record.timestamps
        }
        return BuildRecordTimeCost()
    }

    fun generateContainerTimeCost(
        buildContainer: PipelineBuildContainer,
        recordContainer: BuildRecordContainer,
        taskPairs: List<Pair<BuildRecordTask, PipelineBuildTask?>>
    ): BuildRecordTimeCost {
        val startTime = buildContainer.startTime
        val endTime = buildContainer.endTime
        val totalCost = Duration.between(startTime, endTime).toMillis()
        var executeCost = 0L
        var waitCost = 0L
        val queueCost = recordContainer.timestamps.toList().sumOf { (type, time) ->
            if (!type.containerCheckWait()) return@sumOf 0
            logTimeWhenNull(
                time, "${buildContainer.buildId}|${buildContainer.containerId}|${type.name}"
            )
            return@sumOf time.between()
        }
        taskPairs.forEach { (record, build) ->
            if (build == null) return@forEach
            val cost = generateTaskTimeCost(build, record.timestamps)
            executeCost += cost.executeCost
            waitCost += cost.waitCost
        }
        val systemCost = totalCost - executeCost - waitCost - queueCost
        return BuildRecordTimeCost(totalCost = totalCost,
            executeCost = executeCost,
            waitCost = waitCost,
            queueCost = queueCost,
            systemCost = systemCost)
    }

    /**
     * 计算task级别的所有时间消耗
     * queueCost、 systemCost 保持为 0
     */
    fun generateTaskTimeCost(
        buildTask: PipelineBuildTask,
        timestamps: Map<BuildTimestampType, BuildRecordTimeStamp>
    ): BuildRecordTimeCost {
        val start = buildTask.startTime
        val end = buildTask.endTime
        val totalCost = Duration.between(start, end).toMillis()
        val waitCost = timestamps.toList().sumOf { (type, time) ->
            if (!type.taskCheckWait()) return@sumOf 0
            logTimeWhenNull(
                time, "${buildTask.buildId}|${buildTask.taskId}|${type.name}"
            )
            return@sumOf time.between()
        }
        val executeCost = totalCost - waitCost

        return BuildRecordTimeCost(
            totalCost = totalCost,
            waitCost = waitCost,
            executeCost = executeCost
        )
    }

    private fun logTimeWhenNull(time: BuildRecordTimeStamp, logInfo: String) {
        if (time.startTime == null) {
            logger.warn("$logInfo|warning! start time is null.")
        }
        if (time.endTime == null) {
            logger.warn("$logInfo|warning! end time is null.")
        }
    }
}
