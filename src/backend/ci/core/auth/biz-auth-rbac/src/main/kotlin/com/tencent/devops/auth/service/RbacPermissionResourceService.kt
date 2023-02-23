/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */

package com.tencent.devops.auth.service

import com.tencent.devops.auth.constant.AuthMessageCode
import com.tencent.devops.auth.pojo.AuthResourceInfo
import com.tencent.devops.auth.service.iam.PermissionResourceService
import com.tencent.devops.auth.service.iam.PermissionService
import com.tencent.devops.common.api.exception.PermissionForbiddenException
import com.tencent.devops.common.api.pojo.Pagination
import com.tencent.devops.common.auth.api.AuthPermission
import com.tencent.devops.common.auth.api.AuthResourceType
import com.tencent.devops.common.auth.utils.RbacAuthUtils
import com.tencent.devops.common.service.utils.MessageCodeUtil
import org.slf4j.LoggerFactory

@SuppressWarnings("LongParameterList", "TooManyFunctions")
class RbacPermissionResourceService(
    private val authResourceService: AuthResourceService,
    private val permissionGradeManagerService: PermissionGradeManagerService,
    private val permissionSubsetManagerService: PermissionSubsetManagerService,
    private val authResourceCodeConverter: AuthResourceCodeConverter,
    private val permissionService: PermissionService
) : PermissionResourceService {

    companion object {
        private val logger = LoggerFactory.getLogger(RbacPermissionResourceService::class.java)
    }

    @SuppressWarnings("LongMethod")
    override fun resourceCreateRelation(
        userId: String,
        projectCode: String,
        resourceType: String,
        resourceCode: String,
        resourceName: String
    ): Boolean {
        logger.info("resource create relation|$userId|$projectCode|$resourceType|$resourceCode|$resourceName")
        val iamResourceCode = authResourceCodeConverter.code2IamCode(
            resourceType = resourceType,
            resourceCode = resourceCode
        )
        val managerId = if (resourceType == AuthResourceType.PROJECT.value) {
            permissionGradeManagerService.createGradeManager(
                userId = userId,
                projectCode = projectCode,
                projectName = resourceName,
                resourceType = AuthResourceType.PROJECT.value,
                resourceCode = resourceCode,
                resourceName = resourceName
            )
        } else {
            // 获取分级管理员信息
            val projectInfo = authResourceService.get(
                projectCode = projectCode,
                resourceType = AuthResourceType.PROJECT.value,
                resourceCode = projectCode
            )
            permissionSubsetManagerService.createSubsetManager(
                gradeManagerId = projectInfo.relationId,
                userId = userId,
                projectCode = projectCode,
                projectName = projectInfo.resourceName,
                resourceType = resourceType,
                resourceCode = resourceCode,
                resourceName = resourceName,
                iamResourceCode = iamResourceCode
            )
        }
        // 项目创建需要审批时,不需要保存资源信息
        if (managerId != 0) {
            authResourceService.create(
                userId = userId,
                projectCode = projectCode,
                resourceType = resourceType,
                resourceCode = resourceCode,
                resourceName = resourceName,
                iamResourceCode = iamResourceCode,
                // 项目默认开启权限管理
                enable = resourceType == AuthResourceType.PROJECT.value,
                relationId = managerId.toString()
            )
        }
        return true
    }

    override fun resourceModifyRelation(
        projectCode: String,
        resourceType: String,
        resourceCode: String,
        resourceName: String
    ): Boolean {
        logger.info("resource modify relation|$projectCode|$resourceType|$resourceCode|$resourceName")
        val resourceInfo = authResourceService.get(
            projectCode = projectCode,
            resourceType = resourceType,
            resourceCode = resourceCode
        )
        val updateAuthResource = if (resourceType == AuthResourceType.PROJECT.value) {
            permissionGradeManagerService.modifyGradeManager(
                gradeManagerId = resourceInfo.relationId,
                projectCode = projectCode,
                projectName = resourceName
            )
        } else {
            permissionSubsetManagerService.modifySubsetManager(
                subsetManagerId = resourceInfo.relationId,
                projectCode = projectCode,
                projectName = resourceInfo.resourceName,
                resourceType = resourceType,
                resourceCode = resourceCode,
                resourceName = resourceName,
                iamResourceCode = resourceInfo.iamResourceCode
            )
        }
        if (updateAuthResource) {
            authResourceService.update(
                projectCode = projectCode,
                resourceType = resourceType,
                resourceCode = resourceCode,
                resourceName = resourceName,
            )
        }
        return true
    }

    override fun resourceDeleteRelation(
        projectCode: String,
        resourceType: String,
        resourceCode: String
    ): Boolean {
        logger.info("resource delete relation|$projectCode|$resourceType|$resourceCode")
        if (resourceType == AuthResourceType.PROJECT.value) {
            val projectInfo = authResourceService.get(
                projectCode = projectCode,
                resourceType = AuthResourceType.PROJECT.value,
                resourceCode = projectCode
            )
            permissionGradeManagerService.deleteGradeManager(projectInfo.relationId)
        } else {
            val resourceInfo = authResourceService.get(
                projectCode = projectCode,
                resourceType = resourceType,
                resourceCode = resourceCode
            )
            permissionSubsetManagerService.deleteSubsetManager(resourceInfo.relationId)
        }
        authResourceService.delete(
            projectCode = projectCode,
            resourceType = resourceType,
            resourceCode = resourceCode
        )
        return true
    }

    override fun resourceCancelRelation(
        projectCode: String,
        resourceType: String,
        resourceCode: String
    ): Boolean {
        logger.info("resource cancel relation|$projectCode|$resourceType|$resourceCode")
        // 只有项目才可以取消
        if (resourceType == AuthResourceType.PROJECT.value) {
            permissionGradeManagerService.userCancelApplication(projectCode)
        }
        return true
    }

    override fun hasManagerPermission(
        userId: String,
        projectId: String,
        resourceType: String,
        resourceCode: String
    ): Boolean {
        return permissionService.validateUserResourcePermissionByRelation(
            userId = userId,
            action = RbacAuthUtils.buildAction(
                authPermission = AuthPermission.MANAGE,
                authResourceType = RbacAuthUtils.getResourceTypeByStr(resourceType)
            ),
            projectCode = projectId,
            resourceType = resourceType,
            resourceCode = resourceCode,
            relationResourceType = null
        )
    }

    override fun isEnablePermission(
        projectId: String,
        resourceType: String,
        resourceCode: String
    ): Boolean {
        return authResourceService.get(
            projectCode = projectId,
            resourceType = resourceType,
            resourceCode = resourceCode
        ).enable
    }

    override fun enableResourcePermission(
        userId: String,
        projectId: String,
        resourceType: String,
        resourceCode: String
    ): Boolean {
        logger.info("enable resource permission|$userId|$projectId|$resourceType|$resourceCode")
        if (hasManagerPermission(
                userId = userId,
                projectId = projectId,
                resourceType = resourceType,
                resourceCode = resourceCode
            )
        ) {
            throw PermissionForbiddenException(
                message = MessageCodeUtil.getCodeLanMessage(AuthMessageCode.ERROR_AUTH_NO_MANAGE_PERMISSION)
            )
        }
        val projectInfo = authResourceService.get(
            projectCode = projectId,
            resourceType = AuthResourceType.PROJECT.value,
            resourceCode = projectId
        )
        val resourceInfo = authResourceService.get(
            projectCode = projectId,
            resourceType = resourceType,
            resourceCode = resourceCode
        )
        // 已经启用的不需要再启用
        if (resourceInfo.enable) {
            logger.info("resource has enable permission manager|$userId|$projectId|$resourceType|$resourceCode")
            return true
        }
        permissionSubsetManagerService.createSubsetManagerDefaultGroup(
            subsetManagerId = resourceInfo.relationId.toInt(),
            userId = userId,
            projectCode = projectId,
            projectName = projectInfo.resourceName,
            resourceType = resourceType,
            resourceCode = resourceCode,
            resourceName = resourceInfo.resourceName,
            iamResourceCode = resourceInfo.iamResourceCode,
            createMode = true
        )
        return authResourceService.enable(
            userId = userId,
            projectCode = projectId,
            resourceType = resourceType,
            resourceCode = resourceCode
        )
    }

    override fun disableResourcePermission(
        userId: String,
        projectId: String,
        resourceType: String,
        resourceCode: String
    ): Boolean {
        logger.info("disable resource permission|$userId|$projectId|$resourceType|$resourceCode")
        val hasManagerPermission = hasManagerPermission(
            userId = userId,
            projectId = projectId,
            resourceType = resourceType,
            resourceCode
        )
        if (!hasManagerPermission) {
            throw PermissionForbiddenException(
                message = MessageCodeUtil.getCodeLanMessage(AuthMessageCode.ERROR_AUTH_NO_MANAGE_PERMISSION)
            )
        }
        return authResourceService.disable(
            userId = userId,
            projectCode = projectId,
            resourceType = resourceType,
            resourceCode = resourceCode
        )
    }

    override fun listResoureces(
        userId: String,
        projectId: String,
        resourceType: String,
        resourceName: String?,
        page: Int,
        pageSize: Int
    ): Pagination<AuthResourceInfo> {
        val resourceList = authResourceService.list(
            projectCode = projectId,
            resourceType = resourceType,
            resourceName = resourceName,
            page = page,
            pageSize = pageSize
        )
        if (resourceList.isEmpty()) {
            return Pagination(false, emptyList())
        }
        return Pagination(
            hasNext = resourceList.size == pageSize,
            records = resourceList
        )
    }
}
