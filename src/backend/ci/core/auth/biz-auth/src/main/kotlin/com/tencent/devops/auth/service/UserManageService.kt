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

package com.tencent.devops.auth.service

import com.tencent.devops.auth.common.Constants
import com.tencent.devops.auth.dao.DepartmentDao
import com.tencent.devops.auth.dao.UserInfoDao
import com.tencent.devops.auth.entity.SearchUserAndDeptEntity
import com.tencent.devops.auth.pojo.DepartmentInfo
import com.tencent.devops.auth.pojo.UserInfo
import com.tencent.devops.common.api.util.PageUtil
import com.tencent.devops.common.client.Client
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class UserManageService @Autowired constructor(
    val dslContext: DSLContext,
    val userInfoDao: UserInfoDao,
    val departmentDao: DepartmentDao,
    val deptService: DeptService,
    val client: Client
) {
    @Value("\${esb.code:#{null}}")
    val appCode: String = ""

    @Value("\${esb.secret:#{null}}")
    val appSecret: String = ""

    fun syncUserInfoData() {
        var page = 1
        val pageSize = PageUtil.MAX_PAGE_SIZE
        do {
            val bkUserInfos = deptService.listUserInfos(
                searchUserEntity = SearchUserAndDeptEntity(
                    lookupField = Constants.USERNAME,
                    bk_app_code = appCode,
                    bk_app_secret = appSecret,
                    fields = Constants.USER_LABEL,
                    page = page,
                    pageSize = pageSize
                )
            ).results
            bkUserInfos.forEach { bkUserInfo ->
                try {
                    val userDeptDetails = deptService.getUserDeptDetails(userId = bkUserInfo.userName)
                    userInfoDao.create(
                        dslContext = dslContext,
                        userInfo = UserInfo(
                            userId = bkUserInfo.userName,
                            userName = bkUserInfo.displayName,
                            enabled = bkUserInfo.enabled ?: true,
                            departmentName = userDeptDetails.name,
                            departmentId = userDeptDetails.id,
                            departments = userDeptDetails.family
                        )
                    )
                } catch (ex: Exception) {
                    logger.warn("sync User Info Data failed {}", bkUserInfos)
                }
            }
            page += 1
        } while (bkUserInfos.size == pageSize)
    }

    fun syncDepartmentInfoData() {
        var page = 1
        val pageSize = PageUtil.MAX_PAGE_SIZE
        do {
            val deptInfos = deptService.listDeptInfos(
                searchUserEntity = SearchUserAndDeptEntity(
                    bk_app_code = appCode,
                    bk_app_secret = appSecret,
                    page = page,
                    pageSize = pageSize
                )
            )
            deptInfos.results.forEach { deptInfo ->
                departmentDao.create(
                    dslContext = dslContext,
                    departmentInfo = DepartmentInfo(
                        departmentId = deptInfo.id,
                        departmentName = deptInfo.name,
                        parent = deptInfo.parent,
                        level = deptInfo.level,
                        hasChildren = deptInfo.hasChildren
                    )
                )
            }
            page += 1
        } while (deptInfos.results.size == pageSize)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(UserManageService::class.java)
    }
}
