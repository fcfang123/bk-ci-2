package com.tencent.devops.common.auth.api

import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationDTO
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverConditionRequest
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverDTO
import com.tencent.devops.common.auth.api.pojo.ResourceAuthorizationHandoverResult
import com.tencent.devops.common.auth.enums.ResourceAuthorizationHandoverStatus

interface AuthAuthorizationApi {
    /**
     * 批量重置资源授权人
     * @param projectId 项目ID
     * @param resourceAuthorizationHandoverList 资源授权交接列表
     */
    fun batchModifyHandoverFrom(
        projectId: String,
        resourceAuthorizationHandoverList: List<ResourceAuthorizationHandoverDTO>
    )

    /**
     * 新增资源授权
     * @param projectId 项目ID
     * @param resourceAuthorizationList 资源授权列表
     */
    fun addResourceAuthorization(
        projectId: String,
        resourceAuthorizationList: List<ResourceAuthorizationDTO>
    )

    /**
     * 重置资源授权
     * @param operator 操作人
     * @param projectId 项目ID
     * @param condition 条件
     * @param validateSingleResourcePermission 业务方校验单条资源权限逻辑
     * 重置资源授权操作的渠道有两种，一种是管理员界面视角，一种是在具体资源列表界面对单个资源进行操作。
     * 对于在具体资源列表界面单条操作时，它们的鉴权方式存在差异，需要业务方自己自行进行处理，抛出异常。
     * @param handoverResourceAuthorization 业务方交接授权逻辑
     */
    fun resetResourceAuthorization(
        operator: String,
        projectId: String,
        condition: ResourceAuthorizationHandoverConditionRequest,
        validateSingleResourcePermission: ((
            operator: String,
            projectCode: String,
            resourceCode: String
        ) -> Unit)?,
        handoverResourceAuthorization: (ResourceAuthorizationHandoverDTO) -> ResourceAuthorizationHandoverResult,
    ): Map<ResourceAuthorizationHandoverStatus, List<ResourceAuthorizationDTO>>
}
