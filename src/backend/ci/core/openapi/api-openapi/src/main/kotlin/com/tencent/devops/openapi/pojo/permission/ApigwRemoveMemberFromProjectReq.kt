package com.tencent.devops.openapi.pojo.permission

import io.swagger.v3.oas.annotations.media.Schema

@Schema(title = "OpenAPI-移出项目成员请求体")
data class ApigwRemoveMemberFromProjectReq(
    @get:Schema(title = "要移除的成员ID", required = true)
    val targetMemberId: String,
    @get:Schema(title = "权限交接人ID")
    val handoverToMemberId: String? = null
)
