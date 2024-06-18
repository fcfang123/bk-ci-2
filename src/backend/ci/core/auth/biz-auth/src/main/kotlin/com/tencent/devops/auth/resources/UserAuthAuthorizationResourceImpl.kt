package com.tencent.devops.auth.resources

import com.tencent.devops.auth.api.user.UserAuthAuthorizationResource
import com.tencent.devops.auth.constant.AuthMessageCode
import com.tencent.devops.auth.service.PermissionAuthorizationService
import com.tencent.devops.auth.service.iam.PermissionProjectService
import com.tencent.devops.common.api.exception.PermissionForbiddenException
import com.tencent.devops.common.api.model.SQLPage
import com.tencent.devops.common.api.pojo.Pagination
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationConditionRequest
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationResponse
import com.tencent.devops.common.web.RestResource
import com.tencent.devops.common.web.utils.I18nUtil

@RestResource
class UserAuthAuthorizationResourceImpl(
    val permissionAuthorizationService: PermissionAuthorizationService,
    val permissionProjectService: PermissionProjectService
) : UserAuthAuthorizationResource {
    override fun listResourceAuthorization(
        userId: String,
        projectId: String,
        condition: ResourceAuthorizationConditionRequest
    ): Result<SQLPage<ResourceAuthorizationResponse>> {
        verifyProjectManager(userId, projectId)
        return Result(
            permissionAuthorizationService.listResourceAuthorizations(
                condition = condition
            )
        )
    }

    override fun getResourceAuthorization(
        userId: String,
        projectId: String,
        resourceType: String,
        resourceCode: String
    ): Result<ResourceAuthorizationResponse> {
        return Result(
            permissionAuthorizationService.getResourceAuthorization(
                resourceType = resourceType,
                projectCode = projectId,
                resourceCode = resourceCode
            )
        )
    }

    private fun verifyProjectManager(
        userId: String,
        projectId: String
    ) {
        val hasProjectManagePermission = permissionProjectService.checkProjectManager(
            userId = userId,
            projectCode = projectId
        )
        if (!hasProjectManagePermission) {
            throw PermissionForbiddenException(
                message = I18nUtil.getCodeLanMessage(AuthMessageCode.ERROR_AUTH_NO_MANAGE_PERMISSION)
            )
        }
    }
}
