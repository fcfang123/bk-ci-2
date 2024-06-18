package com.tencent.devops.common.auth.authorization

import com.tencent.devops.auth.api.service.ServiceAuthAuthorizationResource
import com.tencent.devops.auth.constant.AuthMessageCode
import com.tencent.devops.common.api.exception.PermissionForbiddenException
import com.tencent.devops.common.auth.api.AuthAuthorizationApi
import com.tencent.devops.common.auth.api.AuthProjectApi
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationDTO
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverConditionRequest
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverDTO
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverResult
import com.tencent.devops.common.auth.code.ProjectAuthServiceCode
import com.tencent.devops.common.auth.enums.HandoverChannelCode
import com.tencent.devops.common.auth.enums.ResourceAuthorizationHandoverStatus
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.web.utils.I18nUtil
import org.slf4j.LoggerFactory

class AuthAuthorizationService(
    private val client: Client,
    private val authProjectApi: AuthProjectApi,
    private val projectAuthServiceCode: ProjectAuthServiceCode
) : AuthAuthorizationApi {
    override fun batchModifyHandoverFrom(
        projectId: String,
        resourceAuthorizationHandoverList: List<ResourceAuthorizationHandoverDTO>
    ) {
        logger.info("batch modify handoverfrom|$projectId|$resourceAuthorizationHandoverList")
        client.get(ServiceAuthAuthorizationResource::class).batchModifyHandoverFrom(
            projectId = projectId,
            resourceAuthorizationHandoverList = resourceAuthorizationHandoverList
        )
    }

    override fun addResourceAuthorization(
        projectId: String,
        resourceAuthorizationList: List<ResourceAuthorizationDTO>
    ) {
        logger.info("add resource authorization|$projectId|$resourceAuthorizationList")
        client.get(ServiceAuthAuthorizationResource::class).addResourceAuthorization(
            projectId = projectId,
            resourceAuthorizationList = resourceAuthorizationList
        )
    }

    override fun resetResourceAuthorization(
        operator: String,
        projectId: String,
        condition: ResourceAuthorizationHandoverConditionRequest,
        validateSingleResourcePermission: ((
            operator: String,
            projectCode: String,
            resourceCode: String
        ) -> Unit)?,
        handoverResourceAuthorization: (ResourceAuthorizationHandoverDTO) -> ResourceAuthorizationHandoverResult,
    ): Map<ResourceAuthorizationHandoverStatus, List<ResourceAuthorizationDTO>> {
        logger.info("user reset resource authorization|$operator|$condition")
        validateOperatorPermission(
            operator = operator,
            condition = condition,
            validateSingleResourcePermission = validateSingleResourcePermission,
        )
        val resourceAuthorizationList = getResourceAuthorizationList(condition = condition)
        val (successList, failedList) = resourceAuthorizationList.map { resourceAuthorization ->
            val result = try {
                handoverResourceAuthorization(resourceAuthorization)
            } catch (ignore: Exception) {
                ResourceAuthorizationHandoverResult(
                    status = ResourceAuthorizationHandoverStatus.FAILED,
                    message = ignore.message
                )
            }
            when (result.status) {
                ResourceAuthorizationHandoverStatus.SUCCESS -> resourceAuthorization
                else -> resourceAuthorization.copy(handoverFailedMessage = result.message)
            }
        }.partition { it.handoverFailedMessage == null }

        if (successList.isNotEmpty() && !condition.preCheck) {
            logger.info("batch modify handover from|$successList")
            client.get(ServiceAuthAuthorizationResource::class).batchModifyHandoverFrom(
                projectId = projectId,
                resourceAuthorizationHandoverList = successList
            )
        }
        return mapOf(ResourceAuthorizationHandoverStatus.FAILED to failedList)
    }

    private fun validateOperatorPermission(
        operator: String,
        condition: ResourceAuthorizationHandoverConditionRequest,
        validateSingleResourcePermission: ((
            operator: String,
            projectCode: String,
            resourceCode: String
        ) -> Unit)?,
    ) {
        // 若是在授权管理界面操作，则只要校验操作人是否为管理员即可
        if (condition.handoverChannel == HandoverChannelCode.MANAGER) {
            val hasProjectManagePermission = authProjectApi.checkProjectManager(
                userId = operator,
                serviceCode = projectAuthServiceCode,
                projectCode = condition.projectCode
            )
            if (!hasProjectManagePermission) {
                throw PermissionForbiddenException(
                    message = I18nUtil.getCodeLanMessage(AuthMessageCode.ERROR_AUTH_NO_MANAGE_PERMISSION)
                )
            }
        } else {
            val record = condition.resourceAuthorizationHandoverList.first()
            validateSingleResourcePermission?.invoke(
                operator,
                record.projectCode,
                record.resourceCode
            )
        }
    }

    private fun getResourceAuthorizationList(
        condition: ResourceAuthorizationHandoverConditionRequest
    ): List<ResourceAuthorizationHandoverDTO> {
        return if (condition.fullSelection) {
            client.get(ServiceAuthAuthorizationResource::class).listResourceAuthorization(
                projectId = condition.projectCode,
                condition = condition
            ).data?.records?.map {
                ResourceAuthorizationHandoverDTO(
                    projectCode = it.projectCode,
                    resourceType = it.resourceType,
                    resourceName = it.resourceName,
                    resourceCode = it.resourceCode,
                    handoverFrom = it.handoverFrom,
                    handoverTime = it.handoverTime,
                    handoverTo = condition.handoverTo
                )
            } ?: emptyList()
        } else {
            condition.resourceAuthorizationHandoverList
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AuthAuthorizationService::class.java)
    }
}
