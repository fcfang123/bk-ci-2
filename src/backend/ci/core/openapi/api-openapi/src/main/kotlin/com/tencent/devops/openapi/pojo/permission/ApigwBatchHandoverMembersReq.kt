package com.tencent.devops.openapi.pojo.permission

import io.swagger.v3.oas.annotations.media.Schema

@Schema(title = "OpenAPI-批量交接用户组成员请求体")
data class ApigwBatchHandoverMembersReq(
    @get:Schema(title = "用户组ID列表", required = true)
    val groupIds: List<Int>,
    @get:Schema(title = "要交接的成员ID", required = true)
    val targetMemberId: String,
    @get:Schema(title = "接收人ID", required = true)
    val handoverToMemberId: String
)
