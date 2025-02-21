package com.tencent.devops.auth.dao

import com.tencent.devops.auth.pojo.DepartmentInfo
import com.tencent.devops.model.auth.tables.TDepartment
import org.jooq.DSLContext
import org.springframework.stereotype.Repository

@Repository
class DepartmentDao {
    fun create(
        dslContext: DSLContext,
        departmentInfo: DepartmentInfo
    ) {
        with(TDepartment.T_DEPARTMENT) {
            dslContext.insertInto(
                this,
                DEPARTMENT_ID,
                DEPARTMENT_NAME,
                PARENT,
                LEVEL,
                HAS_CHILDREN
            ).values(
                departmentInfo.departmentId,
                departmentInfo.departmentName,
                departmentInfo.parent,
                departmentInfo.level,
                departmentInfo.hasChildren
            )
        }
    }
}
