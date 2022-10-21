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
 */

package com.tencent.devops.auth.service.iam.impl

import com.tencent.bk.sdk.iam.service.IamActionService
import com.tencent.bk.sdk.iam.service.IamResourceService
import com.tencent.devops.auth.constant.AuthMessageCode
import com.tencent.devops.auth.dao.ActionDao
import com.tencent.devops.auth.pojo.action.CreateActionDTO
import com.tencent.devops.auth.pojo.action.DeteleActionDTO
import com.tencent.devops.auth.service.iam.ActionService
import com.tencent.devops.auth.service.iam.BkResourceService
import com.tencent.devops.common.api.exception.ErrorCodeException
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired

abstract class BKActionServiceImpl @Autowired constructor(
    open val dslContext: DSLContext,
    open val actionDao: ActionDao,
    open val resourceService: BkResourceService,
    open val iamActionService: IamActionService,
    open val iamResourceService: IamResourceService
) : ActionService {
    override fun createAction(userId: String, action: CreateActionDTO): Boolean {
        logger.info("createAction $userId|$action")
        val actionId = action.actionId
        val resourceId = action.resourceId
        // 优先判断action挂靠资源是否存在
        val isExistResource = iamResourceService.resourceCheck(resourceId)
        if (!isExistResource) {
            throw ErrorCodeException(
                errorCode = AuthMessageCode.RESOURCE_NOT_EXSIT,
                params = arrayOf(resourceId)
            )
        }
        // action重复性校验
        val actionInfo = iamActionService.getAction(actionId)
        if (actionInfo != null) {
            throw ErrorCodeException(
                errorCode = AuthMessageCode.ACTION_EXIST,
                params = arrayOf(actionId)
            )
        }
        try {
            // 添加扩展系统权限
            extSystemCreate(userId, action)
            return true
        } catch (e: Exception) {
            logger.warn("create action fail $userId|$action|$e")
            throw ErrorCodeException(
                errorCode = AuthMessageCode.ACTION_CREATE_FAIL
            )
        }
    }

    override fun deleteAction(userId: String, action: DeteleActionDTO): Boolean {
        logger.info("deleteAction $userId|$action")
        val actionId = action.actionId
        val resourceId = action.resourceId
        val isExistResource = iamResourceService.resourceCheck(resourceId)
        if (!isExistResource) {
            throw ErrorCodeException(
                errorCode = AuthMessageCode.RESOURCE_NOT_EXSIT,
                params = arrayOf(resourceId)
            )
        }
        // action是否存在校验
        val actionInfo = iamActionService.getAction(actionId)
        if (actionInfo == null) {
            throw ErrorCodeException(
                errorCode = AuthMessageCode.ACTION_NOT_EXIST,
                params = arrayOf(actionId)
            )
        }
        try {
            // 删除扩展系统权限
            extSystemDelete(userId, action)
            return true
        } catch (e: Exception) {
            logger.warn("create action fail $userId|$action|$e")
            throw ErrorCodeException(
                errorCode = AuthMessageCode.ACTION_DELETE_FAIL
            )
        }
        return true
    }

    abstract fun extSystemCreate(userId: String, action: CreateActionDTO)

    abstract fun extSystemDelete(userId: String, action: DeteleActionDTO)

    companion object {
        val logger = LoggerFactory.getLogger(BKActionServiceImpl::class.java)
    }
}
