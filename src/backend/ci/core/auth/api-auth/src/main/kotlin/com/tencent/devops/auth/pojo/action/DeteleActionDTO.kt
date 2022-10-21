package com.tencent.devops.auth.pojo.action

import com.tencent.devops.auth.pojo.enum.ActionType
import io.swagger.annotations.ApiModel
import io.swagger.annotations.ApiModelProperty

@ApiModel("删除操作")
data class DeteleActionDTO(
    @ApiModelProperty("操作ID, 要求加上resource做前缀。如查看流水线: pipeline_view")
    val actionId: String,
    @ApiModelProperty("操作所属资源")
    val resourceId: String,
    @ApiModelProperty("所属动作组名称")
    val actionGroupName: String
)
