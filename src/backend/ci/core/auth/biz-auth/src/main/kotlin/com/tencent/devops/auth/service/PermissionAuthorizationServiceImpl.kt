package com.tencent.devops.auth.service

import com.tencent.devops.auth.constant.AuthMessageCode
import com.tencent.devops.auth.dao.AuthAuthorizationDao
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.model.SQLPage
import com.tencent.devops.common.api.util.timestampmilli
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationConditionRequest
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationDTO
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverDTO
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationResponse
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class PermissionAuthorizationServiceImpl constructor(
    private val dslContext: DSLContext,
    private val authAuthorizationDao: AuthAuthorizationDao,
) : PermissionAuthorizationService {
    companion object {
        val logger = LoggerFactory.getLogger(PermissionAuthorizationServiceImpl::class.java)
    }

    override fun addResourceAuthorization(resourceAuthorizationList: List<ResourceAuthorizationDTO>): Boolean {
        logger.info("add resource authorization:$resourceAuthorizationList")
        authAuthorizationDao.batchAddOrUpdate(
            dslContext = dslContext,
            resourceAuthorizationList = resourceAuthorizationList
        )
        return true
    }

    override fun getResourceAuthorization(
        projectCode: String,
        resourceType: String,
        resourceCode: String
    ): ResourceAuthorizationResponse {
        logger.info("get resource authorization:$projectCode|$resourceType|$resourceCode")
        return authAuthorizationDao.get(
            dslContext = dslContext,
            projectCode = projectCode,
            resourceType = resourceType,
            resourceCode = resourceCode
        )?.let {
            ResourceAuthorizationResponse(
                projectCode = it.projectCode,
                resourceType = it.resourceType,
                resourceName = it.resourceName,
                resourceCode = it.resourceCode,
                handoverTime = it.handoverTime.timestampmilli(),
                handoverFrom = it.handoverFrom
            )
        } ?: throw ErrorCodeException(
            errorCode = AuthMessageCode.ERROR_RESOURCE_AUTHORIZATION_NOT_FOUND
        )
    }

    override fun listResourceAuthorizations(
        condition: ResourceAuthorizationConditionRequest
    ): SQLPage<ResourceAuthorizationResponse> {
        logger.info("list resource authorizations:$condition")
        val record = authAuthorizationDao.list(
            dslContext = dslContext,
            condition = condition
        ).map {
            ResourceAuthorizationResponse(
                projectCode = it.projectCode,
                resourceType = it.resourceType,
                resourceName = it.resourceName,
                resourceCode = it.resourceCode,
                handoverTime = it.handoverTime.timestampmilli(),
                handoverFrom = it.handoverFrom
            )
        }
        val count = authAuthorizationDao.count(
            dslContext = dslContext,
            condition = condition
        )
        return SQLPage(
            count = count.toLong(),
            records = record
        )
    }

    override fun modifyResourceAuthorization(resourceAuthorizationList: List<ResourceAuthorizationDTO>): Boolean {
        logger.info("modify resource authorizations:$resourceAuthorizationList")
        authAuthorizationDao.batchUpdate(
            dslContext = dslContext,
            resourceAuthorizationHandoverList = resourceAuthorizationList
        )
        return true
    }

    override fun deleteResourceAuthorization(
        projectCode: String,
        resourceType: String,
        resourceCode: String
    ): Boolean {
        logger.info("delete resource authorizations:$projectCode|$resourceType|$resourceCode")
        authAuthorizationDao.delete(
            dslContext = dslContext,
            projectCode = projectCode,
            resourceType = resourceType,
            resourceCode = resourceCode
        )
        return true
    }

    override fun batchModifyHandoverFrom(
        resourceAuthorizationHandoverList: List<ResourceAuthorizationHandoverDTO>
    ): Boolean {
        logger.info("batch modify handoverFrom:$resourceAuthorizationHandoverList")
        authAuthorizationDao.batchUpdate(
            dslContext = dslContext,
            resourceAuthorizationHandoverList = resourceAuthorizationHandoverList
        )
        return true
    }
}
