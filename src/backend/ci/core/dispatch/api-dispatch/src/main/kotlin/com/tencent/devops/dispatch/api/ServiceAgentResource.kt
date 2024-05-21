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

package com.tencent.devops.dispatch.api

import com.tencent.devops.common.api.pojo.Page
import com.tencent.devops.dispatch.pojo.thirdpartyagent.AgentBuildInfo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import javax.ws.rs.Consumes
import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.PathParam
import javax.ws.rs.Produces
import javax.ws.rs.QueryParam
import javax.ws.rs.core.MediaType
import com.tencent.devops.common.api.pojo.Result
import javax.ws.rs.POST

@Tag(name = "SERVICE_AGENT", description = "服务-Agent")
@Path("/service/agents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface ServiceAgentResource {

    @Operation(summary = "获取agent构建信息")
    @GET
    @Path("/{agentId}/listBuilds")
    fun listAgentBuild(
        @Parameter(description = "agent Hash ID", required = true)
        @PathParam("agentId")
        agentId: String,
        @Parameter(description = "筛选此状态，支持4种输入(QUEUE,RUNNING,DONE,FAILURE)", required = false)
        @QueryParam("status")
        status: String?,
        @Parameter(description = "筛选此pipelineId", required = false)
        @QueryParam("pipelineId")
        pipelineId: String?,
        @Parameter(description = "第几页", required = false)
        @QueryParam("page")
        page: Int?,
        @Parameter(description = "每页条数", required = false)
        @QueryParam("pageSize")
        pageSize: Int?
    ): Page<AgentBuildInfo>

    @Operation(summary = "获取agent登录调试url")
    @GET
    @Path("/docker/debug/url")
    fun getDockerDebugUrl(
        @QueryParam("userId")
        userId: String,
        @QueryParam("projectId")
        projectId: String,
        @QueryParam("pipelineId")
        pipelineId: String,
        @QueryParam("buildId")
        buildId: String?,
        @QueryParam("vmSeqId")
        vmSeqId: String
    ): Result<String>

    @Operation(summary = "获取某个时间后有构建的Agent")
    @POST
    @Path("/fetch_build_by_time")
    fun fetchAgentBuildByTime(
        @QueryParam("time")
        time: Long,
        agentIds: Set<String>
    ): Result<Set<String>>
}
