package com.tencent.devops.auth.dao

import com.tencent.devops.auth.pojo.UserInfo
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.model.auth.tables.TUserInfo
import org.jooq.DSLContext
import org.springframework.stereotype.Repository

@Repository
class UserInfoDao {
    fun create(
        dslContext: DSLContext,
        userInfo: UserInfo
    ) {
        with(TUserInfo.T_USER_INFO) {
            dslContext.insertInto(
                this,
                USER_ID,
                USER_NAME,
                ENABLED,
                DEPARTMENT_NAME,
                DEPARTMENT_ID,
                FULL_DEPARTMENTS
            ).values(
                userInfo.userId,
                userInfo.userName,
                userInfo.enabled,
                userInfo.departmentName,
                userInfo.departmentId,
                JsonUtil.toJson(userInfo.departments)
            ).onDuplicateKeyUpdate()
                .set(ENABLED, userInfo.enabled)
                .set(DEPARTMENT_NAME, userInfo.departmentName)
                .set(DEPARTMENT_ID, userInfo.departmentId)
                .set(FULL_DEPARTMENTS, JsonUtil.toJson(userInfo.departments))
        }
    }
}
