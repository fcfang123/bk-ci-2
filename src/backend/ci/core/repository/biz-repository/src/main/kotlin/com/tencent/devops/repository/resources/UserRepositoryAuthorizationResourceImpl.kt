package com.tencent.devops.repository.resources

import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationDTO
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverConditionRequest
import com.tencent.devops.common.auth.enums.ResourceAuthorizationHandoverStatus
import com.tencent.devops.common.web.RestResource
import com.tencent.devops.repository.api.UserRepositoryAuthorizationResource
import com.tencent.devops.repository.service.permission.RepositoryAuthorizationService

@RestResource
class UserRepositoryAuthorizationResourceImpl constructor(
    private val repositoryAuthorizationService: RepositoryAuthorizationService
) : UserRepositoryAuthorizationResource {
    override fun resetRepositoryAuthorization(
        userId: String,
        projectId: String,
        condition: ResourceAuthorizationHandoverConditionRequest
    ): Result<Map<ResourceAuthorizationHandoverStatus, List<ResourceAuthorizationDTO>>> {
        return Result(
            repositoryAuthorizationService.resetRepositoryAuthorization(
                userId = userId,
                projectId = projectId,
                condition = condition
            )
        )
    }
}
