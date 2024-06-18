package com.tencent.devops.repository.service.permission

import com.tencent.devops.common.api.enums.RepositoryConfig
import com.tencent.devops.common.api.enums.RepositoryType
import com.tencent.devops.common.api.enums.ScmType
import com.tencent.devops.common.api.exception.PermissionForbiddenException
import com.tencent.devops.common.api.util.HashUtil
import com.tencent.devops.common.api.util.MessageUtil
import com.tencent.devops.common.auth.api.AuthAuthorizationApi
import com.tencent.devops.common.auth.api.AuthPermission
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationDTO
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverConditionRequest
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverDTO
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverResult
import com.tencent.devops.common.auth.enums.ResourceAuthorizationHandoverStatus
import com.tencent.devops.common.web.utils.I18nUtil
import com.tencent.devops.repository.constant.RepositoryMessageCode
import com.tencent.devops.repository.service.RepositoryService
import com.tencent.devops.repository.service.RepositoryUserService
import com.tencent.devops.repository.service.github.GithubTokenService
import com.tencent.devops.repository.service.scm.GitOauthService
import org.springframework.stereotype.Service

@Service
class RepositoryAuthorizationService constructor(
    private val authAuthorizationApi: AuthAuthorizationApi,
    private val repositoryService: RepositoryService,
    private val githubTokenService: GithubTokenService,
    private val gitOauthService: GitOauthService,
    private val repositoryUserService: RepositoryUserService
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

    fun resetRepositoryAuthorization(
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
                ::handoverRepositoryAuthorizationCheck
            } else {
                ::handoverRepositoryAuthorization
            }
        )
    }

    private fun validateSingleResourcePermission(
        operator: String,
        projectCode: String,
        // 代码库hashID
        resourceCode: String
    ) {
        val repositoryId = HashUtil.decodeOtherIdToLong(resourceCode)
        repositoryService.validatePermission(
            user = operator,
            projectId = projectCode,
            repositoryId = repositoryId,
            authPermission = AuthPermission.EDIT,
            message = MessageUtil.getMessageByLocale(
                messageCode = RepositoryMessageCode.USER_EDIT_PEM_ERROR,
                params = arrayOf(operator, projectCode, resourceCode),
                language = I18nUtil.getLanguage(operator)
            )
        )
    }

    private fun handoverRepositoryAuthorizationCheck(
        resourceAuthorizationHandoverDTO: ResourceAuthorizationHandoverDTO
    ): ResourceAuthorizationHandoverResult {
        with(resourceAuthorizationHandoverDTO) {
            val handoverTo = handoverTo!!
            try {
                validateSingleResourcePermission(
                    operator = handoverTo,
                    projectCode = projectCode,
                    resourceCode = resourceCode
                )
                // Check if the grantor has used oauth
                val isUsedOauth = repositoryService.serviceGet(
                    projectId = projectCode,
                    repositoryConfig = RepositoryConfig(
                        repositoryHashId = resourceCode,
                        repositoryName = null,
                        repositoryType = RepositoryType.ID
                    )
                ).getScmType().let { scmType ->
                    when (scmType) {
                        ScmType.GITHUB -> githubTokenService.getAccessToken(handoverTo) != null
                        ScmType.CODE_GIT -> gitOauthService.getAccessToken(handoverTo) != null
                        else -> false
                    }
                }
                if (!isUsedOauth) {
                    return ResourceAuthorizationHandoverResult(
                        status = ResourceAuthorizationHandoverStatus.FAILED,
                        message = MessageUtil.getMessageByLocale(
                            messageCode = RepositoryMessageCode.ERROR_USER_HAVE_NOT_USED_OAUTH,
                            language = I18nUtil.getLanguage(handoverTo)
                        )
                    )
                }
            } catch (ignore: Exception) {
                return ResourceAuthorizationHandoverResult(
                    status = ResourceAuthorizationHandoverStatus.FAILED,
                    message = when (ignore) {
                        is PermissionForbiddenException -> ignore.defaultMessage
                        else -> ignore.message
                    }
                )
            }
            return ResourceAuthorizationHandoverResult(
                status = ResourceAuthorizationHandoverStatus.SUCCESS
            )
        }
    }

    private fun handoverRepositoryAuthorization(
        resourceAuthorizationHandoverDTO: ResourceAuthorizationHandoverDTO
    ): ResourceAuthorizationHandoverResult {
        return with(resourceAuthorizationHandoverDTO) {
            val result = handoverRepositoryAuthorizationCheck(this)
            if (result.status == ResourceAuthorizationHandoverStatus.SUCCESS) {
                repositoryUserService.updateRepositoryUserInfo(
                    userId = handoverTo!!,
                    projectCode = projectCode,
                    repositoryHashId = resourceCode,
                )
            }
            result
        }
    }
}
