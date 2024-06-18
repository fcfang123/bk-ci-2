package com.tencent.devops.process.api

import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationDTO
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverConditionRequest
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.auth.enums.ResourceAuthorizationHandoverStatus
import com.tencent.devops.common.web.RestResource
import com.tencent.devops.process.api.user.UserPipelineAuthorizationResource
import com.tencent.devops.process.permission.PipelineAuthorizationService

@RestResource
class UserPipelineAuthorizationResourceImpl constructor(
    private val pipelineAuthorizationService: PipelineAuthorizationService
) : UserPipelineAuthorizationResource {
    override fun resetPipelineAuthorization(
        userId: String,
        projectId: String,
        condition: ResourceAuthorizationHandoverConditionRequest
    ): Result<Map<ResourceAuthorizationHandoverStatus, List<ResourceAuthorizationDTO>>> {
        return Result(
            pipelineAuthorizationService.resetPipelineAuthorization(
                userId = userId,
                projectId = projectId,
                condition = condition
            )
        )
    }
}
