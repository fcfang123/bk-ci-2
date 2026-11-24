package com.tencent.devops.auth.provider.rbac.service

import com.tencent.bk.sdk.iam.dto.manager.ManagerMember

/**
 * 候选成员通过用户中心存在性校验后的结果
 *
 * @param member 待写入 IAM 与本地库的成员
 * @param displayName 用户中心返回的展示名, 用于落库 memberName
 */
data class ValidatedGroupMember(
    val member: ManagerMember,
    val displayName: String
)

/**
 * 单个候选成员的校验结果
 *
 * 用于区分「用户中心明确答复该成员不存在」与「用户中心调用本身异常」:
 * 前者降级为跳过, 后者必须显式失败, 否则用户中心不可用时会静默跳过全部成员
 */
sealed class MemberValidateResult {
    /**
     * 校验通过
     */
    data class Valid(val displayName: String) : MemberValidateResult()

    /**
     * 用户中心明确答复该成员不存在, 可安全跳过
     */
    data object NotFound : MemberValidateResult()

    /**
     * 校验过程本身异常(用户中心不可用、返回结构异常等), 不能判定成员是否存在
     */
    data class Failed(val cause: Throwable) : MemberValidateResult()
}
