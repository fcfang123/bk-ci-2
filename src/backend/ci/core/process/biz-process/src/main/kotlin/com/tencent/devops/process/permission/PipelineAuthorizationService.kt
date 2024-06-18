package com.tencent.devops.process.permission

import com.tencent.devops.common.api.util.MessageUtil
import com.tencent.devops.common.auth.api.AuthAuthorizationApi
import com.tencent.devops.common.auth.api.AuthPermission
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationDTO
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverConditionRequest
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverDTO
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverResult
import com.tencent.devops.common.auth.enums.ResourceAuthorizationHandoverStatus
import com.tencent.devops.common.web.utils.I18nUtil
import com.tencent.devops.process.constant.ProcessMessageCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class PipelineAuthorizationService constructor(
    val pipelinePermissionService: PipelinePermissionService,
    val authAuthorizationApi: AuthAuthorizationApi
) {
    fun addResourceAuthorization(
        projectId: String,
        resourceAuthorizationList: List<ResourceAuthorizationDTO>
    ) {
        authAuthorizationApi.addResourceAuthorization(
            projectId = projectId,
            resourceAuthorizationList = resourceAuthorizationList
        )
    }

    fun resetPipelineAuthorization(
        userId: String,
        projectId: String,
        condition: ResourceAuthorizationHandoverConditionRequest
    ): Map<ResourceAuthorizationHandoverStatus, List<ResourceAuthorizationDTO>> {
        logger.info("user reset pipeline authorization|$userId|$projectId|$condition")
        return authAuthorizationApi.resetResourceAuthorization(
            operator = userId,
            projectId = projectId,
            condition = condition,
            validateSingleResourcePermission = ::validateSingleResourcePermission,
            handoverResourceAuthorization = ::handoverPipelineAuthorization
        )
    }

    private fun validateSingleResourcePermission(
        operator: String,
        projectCode: String,
        resourceCode: String
    ) {
        pipelinePermissionService.validPipelinePermission(
            userId = operator,
            projectId = projectCode,
            permission = AuthPermission.MANAGE,
            pipelineId = resourceCode,
            message = MessageUtil.getMessageByLocale(
                messageCode = ProcessMessageCode.USER_NEED_PIPELINE_X_PERMISSION,
                params = arrayOf(AuthPermission.MANAGE.getI18n(I18nUtil.getLanguage(operator))),
                language = I18nUtil.getLanguage(operator)
            )
        )
    }

    private fun handoverPipelineAuthorization(
        resourceAuthorizationHandoverDTO: ResourceAuthorizationHandoverDTO
    ): ResourceAuthorizationHandoverResult {
        return with(resourceAuthorizationHandoverDTO) {
            val hasHandoverToPermission = pipelinePermissionService.checkPipelinePermission(
                userId = handoverTo!!,
                projectId = projectCode,
                pipelineId = resourceCode,
                permission = AuthPermission.EXECUTE
            )
            if (hasHandoverToPermission) {
                ResourceAuthorizationHandoverResult(
                    status = ResourceAuthorizationHandoverStatus.SUCCESS
                )
            } else {
                ResourceAuthorizationHandoverResult(
                    status = ResourceAuthorizationHandoverStatus.FAILED,
                    message = MessageUtil.getMessageByLocale(
                        messageCode = ProcessMessageCode.USER_NEED_PIPELINE_X_PERMISSION,
                        params = arrayOf(AuthPermission.EXECUTE.getI18n(I18nUtil.getLanguage(handoverTo))),
                        language = I18nUtil.getLanguage(handoverTo)
                    )
                )
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PipelineAuthorizationService::class.java)
    }
}
