package com.tencent.devops.environment.resources

import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationDTO
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverConditionRequest
import com.tencent.devops.common.auth.enums.ResourceAuthorizationHandoverStatus
import com.tencent.devops.common.web.RestResource
import com.tencent.devops.environment.api.UserEnvironmentAuthorizationResource
import com.tencent.devops.environment.permission.EnvironmentAuthorizationService

@RestResource
class UserEnvironmentAuthorizationResourceImpl constructor(
    private val environmentAuthorizationService: EnvironmentAuthorizationService
) : UserEnvironmentAuthorizationResource {
    override fun resetEnvNodeAuthorization(
        userId: String,
        projectId: String,
        condition: ResourceAuthorizationHandoverConditionRequest
    ): Result<Map<ResourceAuthorizationHandoverStatus, List<ResourceAuthorizationDTO>>> {
        return Result(
            environmentAuthorizationService.resetEnvNodeAuthorization(
                userId = userId,
                projectId = projectId,
                condition = condition
            )
        )
    }
}
