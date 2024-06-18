package com.tencent.devops.environment.permission

import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.auth.api.AuthAuthorizationApi
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationDTO
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverConditionRequest
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverDTO
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverResult
import com.tencent.devops.common.auth.enums.ResourceAuthorizationHandoverStatus
import com.tencent.devops.environment.service.NodeService
import org.springframework.stereotype.Service

@Service
class EnvironmentAuthorizationService constructor(
    val authAuthorizationApi: AuthAuthorizationApi,
    val nodeService: NodeService
) {
    fun batchModifyHandoverFrom(
        projectId: String,
        resourceAuthorizationHandoverList: List<ResourceAuthorizationHandoverDTO>
    ) {
        authAuthorizationApi.batchModifyHandoverFrom(
            projectId = projectId,
            resourceAuthorizationHandoverList = resourceAuthorizationHandoverList
        )
    }

    fun addResourceAuthorization(
        projectId: String,
        resourceAuthorizationList: List<ResourceAuthorizationDTO>
    ) {
        authAuthorizationApi.addResourceAuthorization(
            projectId = projectId,
            resourceAuthorizationList = resourceAuthorizationList
        )
    }

    fun resetEnvNodeAuthorization(
        userId: String,
        projectId: String,
        condition: ResourceAuthorizationHandoverConditionRequest
    ): Map<ResourceAuthorizationHandoverStatus, List<ResourceAuthorizationDTO>> {
        val preCheck = condition.preCheck
        return authAuthorizationApi.resetResourceAuthorization(
            operator = userId,
            projectId = projectId,
            condition = condition,
            validateSingleResourcePermission = ::validateSingleResourcePermission,
            handoverResourceAuthorization = if (preCheck) {
                ::handoverEnvNodeAuthorizationCheck
            } else {
                ::handoverEnvNodeAuthorization
            }
        )
    }

    private fun validateSingleResourcePermission(
        operator: String,
        projectCode: String,
        resourceCode: String
    ) {
        // 校验能否在资源界面操作单个部署节点，得校验操作人是否是节点的主备负责人之一。
        nodeService.checkCmdbOperator(
            userId = operator,
            projectId = projectCode,
            nodeHashId = resourceCode
        )
    }

    private fun handoverEnvNodeAuthorization(
        resourceAuthorizationHandoverDTO: ResourceAuthorizationHandoverDTO
    ): ResourceAuthorizationHandoverResult {
        with(resourceAuthorizationHandoverDTO) {
            try {
                nodeService.changeCreatedUser(
                    userId = handoverTo!!,
                    projectId = projectCode,
                    nodeHashId = resourceCode
                )
            } catch (ignore: Exception) {
                return ResourceAuthorizationHandoverResult(
                    status = ResourceAuthorizationHandoverStatus.FAILED,
                    message = when (ignore) {
                        is ErrorCodeException -> ignore.defaultMessage
                        else -> ignore.message
                    }
                )
            }
        }
        return ResourceAuthorizationHandoverResult(
            status = ResourceAuthorizationHandoverStatus.SUCCESS
        )
    }

    private fun handoverEnvNodeAuthorizationCheck(
        resourceAuthorizationHandoverDTO: ResourceAuthorizationHandoverDTO
    ): ResourceAuthorizationHandoverResult {
        with(resourceAuthorizationHandoverDTO) {
            try {
                nodeService.checkCmdbOperator(
                    userId = handoverTo!!,
                    projectId = projectCode,
                    nodeHashId = resourceCode
                )
            } catch (ignore: Exception) {
                return ResourceAuthorizationHandoverResult(
                    status = ResourceAuthorizationHandoverStatus.FAILED,
                    message = when (ignore) {
                        is ErrorCodeException -> ignore.defaultMessage
                        else -> ignore.message
                    }
                )
            }
        }
        return ResourceAuthorizationHandoverResult(
            status = ResourceAuthorizationHandoverStatus.SUCCESS
        )
    }
}
