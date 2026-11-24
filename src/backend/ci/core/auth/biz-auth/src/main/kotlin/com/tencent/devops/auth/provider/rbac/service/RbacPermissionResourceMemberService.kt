package com.tencent.devops.auth.provider.rbac.service

import com.tencent.bk.sdk.iam.constants.ManagerScopesEnum
import com.tencent.bk.sdk.iam.dto.V2PageInfoDTO
import com.tencent.bk.sdk.iam.dto.manager.ManagerMember
import com.tencent.bk.sdk.iam.dto.manager.RoleGroupMemberInfo
import com.tencent.bk.sdk.iam.dto.manager.V2ManagerRoleGroupInfo
import com.tencent.bk.sdk.iam.dto.manager.dto.GroupMemberRenewApplicationDTO
import com.tencent.bk.sdk.iam.dto.manager.dto.ManagerMemberGroupDTO
import com.tencent.bk.sdk.iam.dto.manager.dto.SearchGroupDTO
import com.tencent.bk.sdk.iam.service.v2.V2ManagerService
import com.tencent.devops.auth.constant.AuthI18nConstants
import com.tencent.devops.auth.constant.AuthMessageCode
import com.tencent.devops.auth.dao.AuthResourceGroupDao
import com.tencent.devops.auth.dao.AuthResourceGroupMemberDao
import com.tencent.devops.auth.dao.AuthResourceSyncDao
import com.tencent.devops.auth.pojo.AuthResourceGroupMember
import com.tencent.devops.auth.pojo.ResourceMemberInfo
import com.tencent.devops.auth.pojo.enum.MemberType
import com.tencent.devops.auth.pojo.vo.ResourceMemberCountVO
import com.tencent.devops.auth.provider.rbac.pojo.event.AuthProjectLevelPermissionsSyncEvent
import com.tencent.devops.auth.service.BkInternalPermissionCache
import com.tencent.devops.auth.service.DeptService
import com.tencent.devops.auth.service.iam.PermissionResourceMemberService
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.model.SQLPage
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.api.util.DateTimeUtil
import com.tencent.devops.common.api.util.MessageUtil
import com.tencent.devops.common.api.util.PageUtil
import com.tencent.devops.common.api.util.timestamp
import com.tencent.devops.common.auth.api.AuthResourceType
import com.tencent.devops.common.auth.api.pojo.BkAuthGroup
import com.tencent.devops.common.auth.api.pojo.BkAuthGroupAndUserList
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.event.dispatcher.trace.TraceEventDispatcher
import com.tencent.devops.common.web.utils.I18nUtil
import com.tencent.devops.project.api.service.ServiceProjectResource
import com.tencent.devops.project.constant.ProjectMessageCode
import org.apache.commons.lang3.RandomUtils
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

@Suppress("SpreadOperator", "LongParameterList")
class RbacPermissionResourceMemberService(
    private val authResourceService: AuthResourceService,
    private val iamV2ManagerService: V2ManagerService,
    private val authResourceGroupDao: AuthResourceGroupDao,
    private val authResourceGroupMemberDao: AuthResourceGroupMemberDao,
    private val dslContext: DSLContext,
    private val deptService: DeptService,
    private val authResourceSyncDao: AuthResourceSyncDao,
    private val traceEventDispatcher: TraceEventDispatcher,
    private val client: Client
) : PermissionResourceMemberService {
    override fun getResourceGroupMembers(
        projectCode: String,
        resourceType: String,
        resourceCode: String,
        group: BkAuthGroup?
    ): List<String> {
        logger.info("[RBAC-IAM] get resource group members:$projectCode|$resourceType|$resourceCode|$group")
        return when (group) {
            // 新的rbac版本中，没有ci管理员组，不可以调用此接口来获取ci管理员组的成员
            BkAuthGroup.CIADMIN, BkAuthGroup.CI_MANAGER -> emptyList()
            // 获取特定资源下全部成员
            null -> {
                authResourceGroupMemberDao.listResourceGroupMember(
                    dslContext = dslContext,
                    projectCode = projectCode,
                    resourceType = resourceType,
                    resourceCode = resourceCode,
                    minExpiredTime = LocalDateTime.now(),
                    memberType = MemberType.USER.type
                ).map { it.memberId }.distinct()
            }
            // 获取特定资源下特定用户组成员
            else -> {
                authResourceGroupMemberDao.listResourceGroupMember(
                    dslContext = dslContext,
                    projectCode = projectCode,
                    resourceType = resourceType,
                    resourceCode = resourceCode,
                    minExpiredTime = LocalDateTime.now(),
                    groupCode = group.value,
                    memberType = MemberType.USER.type
                ).map { it.memberId }
            }
        }
    }

    override fun getResourceGroupAndMembers(
        projectCode: String,
        resourceType: String,
        resourceCode: String
    ): List<BkAuthGroupAndUserList> {
        val enabled = client.get(ServiceProjectResource::class).get(projectCode).data?.enabled ?: return emptyList()
        val resourceGroups = authResourceGroupDao.listByResourceCode(
            dslContext = dslContext,
            projectCode = projectCode,
            resourceType = resourceType,
            resourceCode = resourceCode
        )
        return if (enabled) {
            val groupId2Members = authResourceGroupMemberDao.listResourceGroupMember(
                dslContext = dslContext,
                projectCode = projectCode,
                resourceType = resourceType,
                resourceCode = resourceCode,
                minExpiredTime = LocalDateTime.now()
            ).groupBy { it.iamGroupId }

            resourceGroups.map { groupInfo ->
                val groupId = groupInfo.relationId.toInt()
                val groupName = groupInfo.groupName
                val members = groupId2Members[groupId] ?: emptyList()
                val userMembers = members.filter { it.memberType == MemberType.USER.type }
                val deptMembers = members.filter { it.memberType == MemberType.DEPARTMENT.type }
                BkAuthGroupAndUserList(
                    displayName = groupName,
                    roleId = groupId,
                    roleName = groupName,
                    userIdList = userMembers.map { it.memberId },
                    deptInfoList = deptMembers.map { deptInfo ->
                        RoleGroupMemberInfo().apply {
                            id = deptInfo.memberId
                            name = deptInfo.memberName
                        }
                    },
                    applyDisable = groupInfo.applyDisable
                )
            }
        } else {
            resourceGroups.map {
                getMembersUnderGroupByIam(
                    projectCode = projectCode,
                    groupId = it.relationId.toInt(),
                    groupName = it.groupName
                )
            }
        }
    }

    override fun getProjectMemberCount(projectCode: String): ResourceMemberCountVO {
        val projectMemberCount = authResourceGroupMemberDao.countProjectMember(
            dslContext = dslContext,
            projectCode = projectCode
        )
        return ResourceMemberCountVO(
            userCount = projectMemberCount[MemberType.USER.type] ?: 0,
            departmentCount = projectMemberCount[MemberType.DEPARTMENT.type] ?: 0
        )
    }

    override fun listProjectMembers(
        projectCode: String,
        memberType: String?,
        userName: String?,
        deptName: String?,
        departedFlag: Boolean?,
        page: Int,
        pageSize: Int
    ): SQLPage<ResourceMemberInfo> {
        logger.info("list project members:$projectCode|$departedFlag|$memberType|$userName|$deptName")
        if (!userName.isNullOrEmpty() && !deptName.isNullOrEmpty()) {
            return SQLPage(count = 0, records = emptyList())
        }

        val limit = PageUtil.convertPageSizeToSQLLimit(page, pageSize)
        val count = authResourceGroupMemberDao.countProjectMember(
            dslContext = dslContext,
            projectCode = projectCode,
            memberType = memberType,
            userName = userName,
            deptName = deptName
        )
        val records = authResourceGroupMemberDao.listProjectMembers(
            dslContext = dslContext,
            projectCode = projectCode,
            memberType = memberType,
            userName = userName,
            deptName = deptName,
            offset = limit.offset,
            limit = limit.limit
        )
        return SQLPage(count = count, records = records)
    }

    override fun addDepartedFlagToMembers(records: List<ResourceMemberInfo>): List<ResourceMemberInfo> {
        val userMembers = records.filter {
            it.type == MemberType.USER.type
        }.map { it.id }
        val departedMembers = if (userMembers.isNotEmpty()) {
            deptService.listDepartedMembers(
                memberIds = userMembers
            )
        } else {
            return records
        }
        return records.map {
            if (it.type != MemberType.USER.type) {
                it.copy(departed = false)
            } else {
                it.copy(departed = departedMembers.contains(it.id))
            }
        }
    }

    override fun addGroupMember(
        projectCode: String,
        memberId: String,
        /*user 或 department*/
        memberType: String,
        expiredAt: Long,
        iamGroupId: Int
    ): Boolean {
        if (memberType == MemberType.USER.type && deptService.isUserDeparted(memberId)) {
            return true
        }
        // 获取对应的资源组
        val authResourceGroup = authResourceGroupDao.get(
            dslContext = dslContext,
            projectCode = projectCode,
            relationId = iamGroupId.toString()
        ) ?: throw ErrorCodeException(
            errorCode = AuthMessageCode.GROUP_NOT_EXIST
        )
        val managerMember = ManagerMember(memberType, memberId)
        // 先校验成员信息, 校验通过后再写 IAM, 避免 IAM 已写入而本地库未写入
        val memberDetails = deptService.getMemberInfo(
            memberId = memberId,
            memberType = ManagerScopesEnum.valueOf(memberType.uppercase())
        )
        addIamGroupMember(
            groupId = iamGroupId,
            members = listOf(managerMember),
            expiredAt = expiredAt
        )

        with(authResourceGroup) {
            authResourceGroupMemberDao.create(
                dslContext = dslContext,
                projectCode = projectCode,
                resourceType = resourceType,
                resourceCode = resourceCode,
                groupCode = groupCode,
                iamGroupId = relationId.toInt(),
                memberId = memberId,
                memberName = memberDetails.displayName,
                memberType = memberType,
                expiredTime = DateTimeUtil.convertTimestampToLocalDateTime(expiredAt)
            )
            BkInternalPermissionCache.invalidateProjectUserGroups(projectCode, memberId)
        }
        traceEventDispatcher.dispatch(
            AuthProjectLevelPermissionsSyncEvent(
                projectCode = projectCode,
                iamGroupIds = listOf(iamGroupId)
            )
        )
        return true
    }

    override fun addIamGroupMember(
        groupId: Int,
        members: List<ManagerMember>,
        expiredAt: Long
    ): Boolean {
        val membersOfNeedToAdd = members.toMutableList().removeDepartedMembers()
        if (membersOfNeedToAdd.isNotEmpty()) {
            val managerMemberGroup =
                ManagerMemberGroupDTO.builder().members(membersOfNeedToAdd).expiredAt(expiredAt).build()
            iamV2ManagerService.createRoleGroupMemberV2(groupId, managerMemberGroup)
        }
        return true
    }

    @Suppress("NestedBlockDepth", "LongMethod", "ComplexMethod")
    override fun batchAddResourceGroupMembers(
        projectCode: String,
        iamGroupId: Int,
        expiredTime: Long,
        members: List<String>?,
        departments: List<String>?
    ): Result<Boolean> {
        logger.info("batch add resource group members :$projectCode|$iamGroupId|$expiredTime|$members|$departments")
        // 校验用户组是否属于该项目
        verifyGroupBelongToProject(
            projectCode = projectCode,
            iamGroupId = iamGroupId
        )
        // 获取用户组中用户以及部门
        val userType = MemberType.USER.type
        val deptType = MemberType.DEPARTMENT.type
        val groupMembers = getAllRoleGroupMembersV2(iamGroupId)
        val groupUserMap = groupMembers.filter { it.type == userType }.associateBy { it.id }
        val groupDepartmentSet = groupMembers.filter {
            it.type == deptType && it.expiredAt > LocalDateTime.now().timestamp()
        }.map { it.id }.toSet()
        // 校验用户是否应该加入用户组
        val candidateMembers = mutableListOf<ManagerMember>()
        val skippedUsersByReason = linkedMapOf<BatchAddSkipReason, MutableList<String>>()
        val skippedDepartments = mutableListOf<String>()
        if (!members.isNullOrEmpty()) {
            val departedMembers = deptService.listDepartedMembers(
                memberIds = members
            )
            members.forEach { member ->
                if (departedMembers.contains(member)) {
                    logger.warn(
                        "This user does not exist or has departed and does not need to join " +
                                "$projectCode|$iamGroupId|$member"
                    )
                    skippedUsersByReason
                        .getOrPut(BatchAddSkipReason.USER_NOT_FOUND_OR_DEPARTED) { mutableListOf() }
                        .add(member)
                } else {
                    when (
                        val batchAddUserResult = checkUserCanBeAddedToGroup(
                            projectCode = projectCode,
                            iamGroupId = iamGroupId,
                            groupUserMap = groupUserMap,
                            groupDepartmentSet = groupDepartmentSet,
                            member = member
                        )
                    ) {
                        BatchAddUserResult.ADD -> candidateMembers.add(ManagerMember(userType, member))

                        BatchAddUserResult.ALREADY_IN_GROUP,
                        BatchAddUserResult.ALREADY_IN_GROUP_BY_DEPARTMENT,
                        BatchAddUserResult.USER_NOT_FOUND_OR_DEPARTED -> {
                            skippedUsersByReason
                                .getOrPut(batchAddUserResult.reason!!) { mutableListOf() }
                                .add(member)
                        }
                    }
                }
            }
        }
        if (!departments.isNullOrEmpty()) {
            departments.forEach { department ->
                if (!groupDepartmentSet.contains(department)) {
                    candidateMembers.add(ManagerMember(deptType, department))
                } else {
                    skippedDepartments.add(department)
                }
            }
        }
        // 成员信息校验前置到写 IAM 之前, 校验不通过的成员跳过, 避免 IAM 已写入而本地库未写入的数据不一致
        val validatedMembers = mutableListOf<ValidatedGroupMember>()
        val invalidMemberIds = mutableListOf<String>()
        candidateMembers.forEach { candidateMember ->
            when (val validateResult = validateMemberInfo(candidateMember)) {
                is MemberValidateResult.Valid -> validatedMembers.add(
                    ValidatedGroupMember(member = candidateMember, displayName = validateResult.displayName)
                )

                MemberValidateResult.NotFound -> {
                    val skipReason = if (candidateMember.type == deptType) {
                        BatchAddSkipReason.DEPARTMENT_NOT_FOUND
                    } else {
                        BatchAddSkipReason.USER_NOT_FOUND_OR_DEPARTED
                    }
                    skippedUsersByReason.getOrPut(skipReason) { mutableListOf() }.add(candidateMember.id)
                }

                is MemberValidateResult.Failed -> invalidMemberIds.add(candidateMember.id)
            }
        }
        // 校验过程本身异常时显式失败, 此时尚未写 IAM, 无副作用且调用方可安全重试
        if (invalidMemberIds.isNotEmpty()) {
            logger.warn("batch add group members, member validate error|$projectCode|$iamGroupId|$invalidMemberIds")
            throw ErrorCodeException(
                errorCode = AuthMessageCode.ERROR_BATCH_ADD_GROUP_MEMBERS,
                params = arrayOf(invalidMemberIds.joinToString(","), "validate member info failed"),
                defaultMessage = "validate member info failed, members: $invalidMemberIds"
            )
        }
        val addedMembers = validatedMembers.map { it.member }
        val addedUsers = validatedMembers.filter { it.member.type == userType }.map { it.member.id }
        val addedDepartments = validatedMembers.filter { it.member.type == deptType }.map { it.member.id }

        logger.info("batch add project user:|$projectCode|$iamGroupId|$expiredTime|$addedMembers")
        if (addedMembers.isNotEmpty()) {
            // 提前获取资源组, 避免 IAM 写入成功后再因本地数据缺失而失败
            val authResourceGroup = authResourceGroupDao.get(
                dslContext = dslContext,
                projectCode = projectCode,
                relationId = iamGroupId.toString()
            ) ?: throw ErrorCodeException(
                errorCode = AuthMessageCode.GROUP_NOT_EXIST
            )
            addIamGroupMember(
                groupId = iamGroupId,
                members = addedMembers,
                expiredAt = expiredTime
            )
            val groupMembersList = validatedMembers.map {
                AuthResourceGroupMember(
                    projectCode = projectCode,
                    resourceType = authResourceGroup.resourceType,
                    resourceCode = authResourceGroup.resourceCode,
                    groupCode = authResourceGroup.groupCode,
                    iamGroupId = authResourceGroup.relationId.toInt(),
                    memberId = it.member.id,
                    memberName = it.displayName,
                    memberType = it.member.type,
                    expiredTime = DateTimeUtil.convertTimestampToLocalDateTime(expiredTime),
                    joinedAt = LocalDateTime.now()
                )
            }
            try {
                authResourceGroupMemberDao.batchCreate(
                    dslContext = dslContext,
                    groupMembers = groupMembersList
                )
                BkInternalPermissionCache.batchInvalidateProjectUserGroups(
                    projectCode = projectCode,
                    userIds = addedMembers.map { it.id }
                )
            } catch (e: Exception) {
                logger.error(
                    "batch add group members failed after iam written|" +
                            "$projectCode|$iamGroupId|$addedMembers",
                    e
                )
                throw ErrorCodeException(
                    errorCode = AuthMessageCode.ERROR_BATCH_ADD_GROUP_MEMBERS,
                    params = arrayOf(addedMembers.joinToString(",") { it.id }, e.message ?: "unknown"),
                    defaultMessage = "batch add group members failed, members: ${addedMembers.map { it.id }}"
                )
            }
        }
        traceEventDispatcher.dispatch(
            AuthProjectLevelPermissionsSyncEvent(
                projectCode = projectCode,
                iamGroupIds = listOf(iamGroupId)
            )
        )
        val resultMessage = buildBatchAddResourceGroupMembersMessage(
            addedUsers = addedUsers,
            addedDepartments = addedDepartments,
            skippedUsersByReason = skippedUsersByReason,
            skippedDepartments = skippedDepartments
        )
        return Result(message = resultMessage, data = true)
    }

    /**
     * 校验成员在用户中心是否存在, 并返回其展示信息
     *
     * 用户中心明确答复不存在时返回 [MemberValidateResult.NotFound], 由调用方降级为跳过;
     * 调用本身失败(网络异常、用户中心不可用、返回结构异常)时返回 [MemberValidateResult.Failed],
     * 由调用方显式失败, 避免静默跳过全部成员。
     */
    private fun validateMemberInfo(member: ManagerMember): MemberValidateResult {
        return try {
            val memberInfo = deptService.getMemberInfo(
                memberId = member.id,
                memberType = ManagerScopesEnum.valueOf(member.type.uppercase())
            )
            MemberValidateResult.Valid(displayName = memberInfo.displayName)
        } catch (e: ErrorCodeException) {
            if (e.errorCode == AuthMessageCode.USER_NOT_EXIST) {
                logger.warn("batch add member not exist|${member.type}|${member.id}")
                MemberValidateResult.NotFound
            } else {
                logger.warn("batch add member validate failed|${member.type}|${member.id}|${e.errorCode}", e)
                MemberValidateResult.Failed(cause = e)
            }
        } catch (e: NumberFormatException) {
            // 部门 ID 非数字时用户中心查询会抛 NumberFormatException, 等价于该部门不存在
            logger.warn("batch add member id invalid|${member.type}|${member.id}", e)
            MemberValidateResult.NotFound
        } catch (e: Exception) {
            logger.warn("batch add member validate error|${member.type}|${member.id}", e)
            MemberValidateResult.Failed(cause = e)
        }
    }

    private fun verifyGroupBelongToProject(
        projectCode: String,
        iamGroupId: Int
    ) {
        val isGroupBelongToProject = authResourceGroupDao.isGroupBelongToProject(
            dslContext = dslContext,
            projectCode = projectCode,
            groupId = iamGroupId.toString()
        )
        if (!isGroupBelongToProject) {
            throw ErrorCodeException(
                errorCode = ProjectMessageCode.ERROR_GROUP_NOT_BELONG_TO_PROJECT,
                defaultMessage = "The group($iamGroupId) does not belong to the project($projectCode)!"
            )
        }
    }

    private fun checkUserCanBeAddedToGroup(
        projectCode: String,
        iamGroupId: Int,
        groupUserMap: Map<String, RoleGroupMemberInfo>,
        groupDepartmentSet: Set<String>,
        member: String
    ): BatchAddUserResult {
        // 校验是否将用户加入组，如果用户已经在用户组,并且过期时间超过180天,则不再添加
        val expectExpiredAt = System.currentTimeMillis() / 1000 + TimeUnit.DAYS.toSeconds(VALID_EXPIRED_AT)
        if (groupUserMap.containsKey(member) && groupUserMap[member]!!.expiredAt > expectExpiredAt) {
            logger.warn(
                "The user's validity period in the group exceeds 180 days and does not need to be added!" +
                        "$projectCode|$iamGroupId|$member"
            )
            return BatchAddUserResult.ALREADY_IN_GROUP
        }
        // 校验用户的部门是否已经加入组，若部门已经加入，则不再添加该用户
        try {
            val userDeptInfoSet = deptService.getUserDeptInfo(userId = member)
            val isUserBelongGroupByDepartments = groupDepartmentSet.intersect(userDeptInfoSet).isNotEmpty()
            if (isUserBelongGroupByDepartments) {
                logger.warn(
                    "The department of this user has already been added to the group. No need to join!" +
                            "$projectCode|$groupDepartmentSet|$iamGroupId|$member"
                )
                return BatchAddUserResult.ALREADY_IN_GROUP_BY_DEPARTMENT
            }
        } catch (ignore: Exception) {
            logger.warn(
                "failed to get user department info, skip add user $projectCode|$iamGroupId|$member",
                ignore
            )
            return BatchAddUserResult.USER_NOT_FOUND_OR_DEPARTED
        }
        return BatchAddUserResult.ADD
    }

    private fun buildBatchAddResourceGroupMembersMessage(
        addedUsers: List<String>,
        addedDepartments: List<String>,
        skippedUsersByReason: Map<BatchAddSkipReason, List<String>>,
        skippedDepartments: List<String>
    ): String {
        val language = I18nUtil.getRequestUserLanguage()
        val messageParts = mutableListOf<String>()
        if (addedUsers.isNotEmpty()) {
            messageParts.add(
                getBatchAddMembersMessage(
                    messageCode = AuthI18nConstants.BK_BATCH_ADD_MEMBERS_ADDED_USERS,
                    language = language,
                    memberIds = addedUsers,
                    defaultMessagePrefix = "成功添加用户"
                )
            )
        }
        if (addedDepartments.isNotEmpty()) {
            messageParts.add(
                getBatchAddMembersMessage(
                    messageCode = AuthI18nConstants.BK_BATCH_ADD_MEMBERS_ADDED_DEPARTMENTS,
                    language = language,
                    memberIds = addedDepartments,
                    defaultMessagePrefix = "成功添加组织"
                )
            )
        }
        skippedUsersByReason.forEach { (reason, memberIds) ->
            if (memberIds.isNotEmpty()) {
                messageParts.add(
                    getBatchAddMembersMessage(
                        messageCode = reason.messageCode,
                        language = language,
                        memberIds = memberIds,
                        defaultMessagePrefix = reason.defaultMessage
                    )
                )
            }
        }
        if (skippedDepartments.isNotEmpty()) {
            messageParts.add(
                getBatchAddMembersMessage(
                    messageCode = AuthI18nConstants.BK_BATCH_ADD_MEMBERS_SKIP_DEPARTMENTS,
                    language = language,
                    memberIds = skippedDepartments,
                    defaultMessagePrefix = "以下组织已在当前组中，无需重复添加"
                )
            )
        }
        if (messageParts.isEmpty()) {
            return MessageUtil.getMessageByLocale(
                messageCode = AuthI18nConstants.BK_BATCH_ADD_MEMBERS_NO_INPUT,
                language = language,
                defaultMessage = "未传入用户或组织，无法添加成员"
            )
        }
        val summary = if (addedUsers.isNotEmpty() || addedDepartments.isNotEmpty()) {
            MessageUtil.getMessageByLocale(
                messageCode = AuthI18nConstants.BK_BATCH_ADD_MEMBERS_COMPLETED,
                language = language,
                defaultMessage = "成员添加完成"
            )
        } else {
            MessageUtil.getMessageByLocale(
                messageCode = AuthI18nConstants.BK_BATCH_ADD_MEMBERS_NONE_ADDED,
                language = language,
                defaultMessage = "未新增任何成员"
            )
        }
        return listOf(summary)
            .plus(messageParts)
            .joinToString(separator = "; ")
    }

    private fun getBatchAddMembersMessage(
        messageCode: String,
        language: String,
        memberIds: List<String>,
        defaultMessagePrefix: String
    ): String {
        val memberIdsText = memberIds.joinToString(",")
        return MessageUtil.getMessageByLocale(
            messageCode = messageCode,
            language = language,
            params = arrayOf(memberIdsText),
            defaultMessage = "$defaultMessagePrefix：$memberIdsText"
        )
    }

    override fun batchDeleteResourceGroupMembers(
        projectCode: String,
        iamGroupId: Int,
        members: List<String>?,
        departments: List<String>?
    ): Boolean {
        logger.info("batch delete resource group members :|$projectCode|$iamGroupId||$members|$departments")
        verifyGroupBelongToProject(
            projectCode = projectCode,
            iamGroupId = iamGroupId
        )
        val userType = MemberType.USER.type
        val deptType = MemberType.DEPARTMENT.type
        val allMemberIds = mutableListOf<String>()
        if (!members.isNullOrEmpty()) {
            deleteIamGroupMembers(
                groupId = iamGroupId,
                type = userType,
                memberIds = members
            )
            allMemberIds.addAll(members)
        }
        if (!departments.isNullOrEmpty()) {
            deleteIamGroupMembers(
                groupId = iamGroupId,
                type = deptType,
                memberIds = departments
            )
            allMemberIds.addAll(departments)
        }
        authResourceGroupMemberDao.batchDeleteGroupMembers(
            dslContext = dslContext,
            projectCode = projectCode,
            iamGroupId = iamGroupId,
            memberIds = allMemberIds
        )
        BkInternalPermissionCache.batchInvalidateProjectUserGroups(
            projectCode = projectCode,
            userIds = allMemberIds
        )
        traceEventDispatcher.dispatch(
            AuthProjectLevelPermissionsSyncEvent(
                projectCode = projectCode,
                iamGroupIds = listOf(iamGroupId)
            )
        )
        return true
    }

    private fun getGroupInfoList(
        resourceType: String,
        managerId: String
    ): List<V2ManagerRoleGroupInfo> {
        return if (resourceType == AuthResourceType.PROJECT.value) {
            val searchGroupDTO = SearchGroupDTO.builder().inherit(false).build()
            val pageInfoDTO = V2PageInfoDTO()
            pageInfoDTO.page = 1
            pageInfoDTO.pageSize = 1000
            val iamGroupInfoList = iamV2ManagerService.getGradeManagerRoleGroupV2(
                managerId,
                searchGroupDTO,
                pageInfoDTO
            )
            iamGroupInfoList.results
        } else {
            val v2PageInfoDTO = V2PageInfoDTO().apply {
                pageSize = 1000
                page = 1
            }
            iamV2ManagerService.getSubsetManagerRoleGroup(
                managerId.toInt(),
                v2PageInfoDTO
            ).results
        }
    }

    private fun getMembersUnderGroupByIam(
        projectCode: String,
        groupId: Int,
        groupName: String
    ): BkAuthGroupAndUserList {
        // 获取组成员信息
        val groupMemberInfoList = getAllRoleGroupMembersV2(groupId)
        // 获取组信息
        val gradeManagerId = authResourceService.get(
            projectCode = projectCode,
            resourceType = AuthResourceType.PROJECT.value,
            resourceCode = projectCode
        ).relationId
        val searchGroupDTO = SearchGroupDTO.builder().id(groupId).build()
        val groupDetails = iamV2ManagerService.getGradeManagerRoleGroupV2(
            gradeManagerId,
            searchGroupDTO,
            V2PageInfoDTO().apply {
                this.pageSize = 10
                this.page = 1
            }
        ).results.firstOrNull()
        val nowTimestamp = System.currentTimeMillis() / 1000
        val (members, deptInfoList) = groupMemberInfoList
            .filter { it.expiredAt > nowTimestamp }
            .partition { it.type == MemberType.USER.type }

        return BkAuthGroupAndUserList(
            displayName = groupName,
            roleId = groupId,
            roleName = groupName,
            userIdList = members.map { it.id },
            deptInfoList = deptInfoList.map { memberInfo ->
                RoleGroupMemberInfo().apply {
                    id = memberInfo.id
                    name = memberInfo.name
                }
            },
            applyDisable = groupDetails?.applyDisable
        )
    }

    override fun roleCodeToIamGroupId(
        projectCode: String,
        resourceType: String,
        resourceCode: String,
        roleCode: String
    ): Int {
        return if (roleCode == BkAuthGroup.CI_MANAGER.value) {
            authResourceGroupDao.getByGroupName(
                dslContext = dslContext,
                projectCode = projectCode,
                resourceType = AuthResourceType.PROJECT.value,
                resourceCode = projectCode,
                groupName = BkAuthGroup.CI_MANAGER.groupName
            )?.relationId
        } else {
            authResourceGroupDao.get(
                dslContext = dslContext,
                projectCode = projectCode,
                resourceType = resourceType,
                resourceCode = resourceCode,
                groupCode = roleCode
            )?.relationId
        }?.toInt() ?: throw ErrorCodeException(
            errorCode = AuthMessageCode.ERROR_AUTH_GROUP_NOT_EXIST,
            params = arrayOf(roleCode),
            defaultMessage = "group $roleCode not exist"
        )
    }

    override fun autoRenewal(
        projectCode: String,
        resourceType: String,
        resourceCode: String,
        validExpiredDay: Int
    ) {
        // 1、获取分级管理员或者二级管理员ID
        val managerId = authResourceService.get(
            projectCode = projectCode,
            resourceType = resourceType,
            resourceCode = resourceCode
        ).relationId
        // 2、获取分级管理员下所有的用户组
        val iamGroupInfoList = getGroupInfoList(
            resourceType = resourceType,
            managerId = managerId
        )
        // 3. 获取由蓝盾创建的用户组列表
        val resourceGroupInfoList = authResourceGroupDao.listByRelationId(
            dslContext = dslContext,
            projectCode = projectCode,
            iamGroupIds = iamGroupInfoList.map { it.id.toString() }
        )
        val currentTime = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
        // 预期的自动过期天数
        val expectAutoExpiredAt = currentTime + TimeUnit.DAYS.toSeconds(validExpiredDay.toLong())
        val autoRenewalMembers = mutableSetOf<String>()
        resourceGroupInfoList.forEach group@{ resourceGroup ->
            val iamGroupId = resourceGroup.relationId.toInt()
            val groupMemberInfoList = getAllRoleGroupMembersV2(iamGroupId)
            groupMemberInfoList.forEach member@{ member ->
                // 已过期或者小于自动续期范围内的不做续期
                if (member.expiredAt < currentTime ||
                    member.expiredAt > expectAutoExpiredAt
                ) {
                    val dataTime = DateTimeUtil.convertTimestampToLocalDateTime(member.expiredAt)
                    logger.info("Group member does not need to be renewed|$iamGroupId|$member|$dataTime")
                    return@member
                }
                // 自动续期时间由半年+随机天数,防止同一时间同时过期
                val expiredTime = currentTime + AUTO_RENEWAL_EXPIRED_AT +
                        TimeUnit.DAYS.toSeconds(RandomUtils.nextLong(0, 180))
                autoRenewalMembers.add(member.id)
                try {
                    addGroupMember(
                        projectCode = projectCode,
                        memberId = member.id,
                        memberType = member.type,
                        expiredAt = expiredTime,
                        iamGroupId = iamGroupId
                    )
                } catch (ignored: Exception) {
                    // 用户不存在时,iam会抛异常
                    logger.error(
                        "auto renewal member, user not existed||$projectCode|$resourceType|$resourceCode",
                        ignored
                    )
                }
            }
        }
        if (autoRenewalMembers.isNotEmpty()) {
            logger.info("auto renewal member|$projectCode|$resourceType|$resourceCode|$autoRenewalMembers")
        }
    }

    override fun renewalGroupMember(
        userId: String,
        projectCode: String,
        groupIds: List<Int>,
        expiredAt: Long
    ): Boolean {
        logger.info("renewal group member|$userId|$projectCode|$groupIds|$expiredAt")
        val managerMemberGroupDTO = GroupMemberRenewApplicationDTO.builder()
            .groupIds(groupIds)
            .expiredAt(expiredAt)
            .reason("renewal user group")
            .applicant(userId).build()
        iamV2ManagerService.renewalRoleGroupMemberApplication(managerMemberGroupDTO)
        return true
    }

    override fun renewalIamGroupMembers(
        groupId: Int,
        members: List<ManagerMember>,
        expiredAt: Long
    ): Boolean {
        val membersOfNeedToRenewal = members.toMutableList().removeDepartedMembers()
        if (membersOfNeedToRenewal.isNotEmpty()) {
            iamV2ManagerService.renewalRoleGroupMemberV2(
                groupId,
                ManagerMemberGroupDTO.builder()
                    .members(membersOfNeedToRenewal)
                    .expiredAt(expiredAt)
                    .build()
            )
        }
        return true
    }

    override fun deleteIamGroupMembers(
        groupId: Int,
        type: String,
        memberIds: List<String>
    ): Boolean {
        if (memberIds.isNotEmpty()) {
            try {
                iamV2ManagerService.deleteRoleGroupMemberV2(
                    groupId,
                    type,
                    memberIds.joinToString(",")
                )
            } catch (e: Exception) {
                logger.warn("delete iam group member failed, groupId=$groupId, type=$type, memberIds=$memberIds", e)
            }
        }
        return true
    }

    private fun MutableList<ManagerMember>.removeDepartedMembers(): List<ManagerMember> {
        val userMemberIds = this.filter { it.type == MemberType.USER.type }.map { it.id }
        if (userMemberIds.isEmpty()) return this
        // 获取离职的人员
        val departedMembers = deptService.listDepartedMembers(
            memberIds = userMemberIds
        )
        return this.filterNot {
            it.type == MemberType.USER.type &&
                    departedMembers.contains(it.id)
        }
    }

    override fun getMemberGroupsInProject(
        projectCode: String,
        memberId: String
    ): List<Int> {
        logger.info("[RBAC-IAM] get member groups in project: $projectCode|$memberId")
        // 获取用户的所属组织
        val memberDeptInfos = deptService.getUserInfo(memberId)?.deptInfo?.let {
            if (it.isNotEmpty()) {
                deptService.getUserDeptInfo(memberId).toList()
            } else {
                emptyList()
            }
        } ?: emptyList()
        // 查询项目下包含该成员及所属组织的用户组列表
        val projectGroupIds = authResourceGroupMemberDao.listResourceGroupMember(
            dslContext = dslContext,
            projectCode = projectCode,
            resourceType = AuthResourceType.PROJECT.value,
            memberIds = memberDeptInfos + memberId,
            minExpiredTime = LocalDateTime.now()
        ).map { it.iamGroupId.toString() }
        // 通过项目组ID获取人员模板ID
        val iamTemplateIds = authResourceGroupDao.listByRelationId(
            dslContext = dslContext,
            projectCode = projectCode,
            iamGroupIds = projectGroupIds
        ).filter { it.iamTemplateId != null }
            .map { it.iamTemplateId.toString() }
        // 获取用户在项目下加入的所有用户组ID（未过期）
        return authResourceGroupMemberDao.listMemberGroupIdsInProject(
            dslContext = dslContext,
            projectCode = projectCode,
            memberId = memberId,
            iamTemplateIds = iamTemplateIds,
            memberDeptInfos = memberDeptInfos
        )
    }

    @Suppress("NestedBlockDepth")
    override fun copyResourceGroupMembers(
        sourceProjectCode: String,
        targetProjectCode: String,
        resourceType: String,
        sourceResourceCode: String,
        targetResourceCode: String
    ): Boolean {
        logger.info(
            "[RBAC-IAM] copy resource group members|$sourceProjectCode|$targetProjectCode|" +
                "$resourceType|$sourceResourceCode|$targetResourceCode"
        )
        val sourceGroups = authResourceGroupDao.listByResourceCode(
            dslContext = dslContext,
            projectCode = sourceProjectCode,
            resourceType = resourceType,
            resourceCode = sourceResourceCode
        )
        if (sourceGroups.isEmpty()) {
            return true
        }
        val now = LocalDateTime.now()
        sourceGroups.forEach { sourceGroup ->
            try {
                val targetIamGroupId = waitTargetIamGroupId(
                    targetProjectCode = targetProjectCode,
                    resourceType = resourceType,
                    targetResourceCode = targetResourceCode,
                    groupCode = sourceGroup.groupCode
                ) ?: run {
                    logger.warn(
                        "copy group failed|target project group not exist after retry|" +
                            "$targetProjectCode|$resourceType|$targetResourceCode|${sourceGroup.groupCode}"
                    )
                    return@forEach
                }
                val sourceMembers = authResourceGroupMemberDao.listResourceGroupMember(
                    dslContext = dslContext,
                    projectCode = sourceProjectCode,
                    iamGroupId = sourceGroup.relationId.toInt(),
                    minExpiredTime = now
                )
                sourceMembers.forEach { member ->
                    try {
                        addGroupMember(
                            projectCode = targetProjectCode,
                            memberId = member.memberId,
                            memberType = member.memberType,
                            expiredAt = member.expiredTime.timestamp(),
                            iamGroupId = targetIamGroupId
                        )
                    } catch (ignored: Exception) {
                        logger.warn(
                            "copy single member failed|${sourceGroup.groupCode}|${member.memberId}",
                            ignored
                        )
                    }
                }
            } catch (ignored: Exception) {
                logger.warn("copy group failed|${sourceGroup.groupCode}", ignored)
            }
        }
        return true
    }

    /**
     * 等待目标项目组创建,因为用户组是异步创建的,所以轮询等待
     */
    private fun waitTargetIamGroupId(
        targetProjectCode: String,
        resourceType: String,
        targetResourceCode: String,
        groupCode: String
    ): Int? {
        authResourceGroupDao.getByGroupCode(
            dslContext = dslContext,
            projectCode = targetProjectCode,
            resourceType = resourceType,
            resourceCode = targetResourceCode,
            groupCode = groupCode
        )?.relationId?.let { return it }

        TARGET_GROUP_CREATE_RETRY_INTERVAL_MILLIS.forEach { intervalMillis ->
            logger.info(
                "wait target project group created|" +
                        "$targetProjectCode|$resourceType|$targetResourceCode|$groupCode|$intervalMillis"
            )
            try {
                Thread.sleep(intervalMillis)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warn(
                    "wait target project group interrupted|" +
                            "$targetProjectCode|$resourceType|$targetResourceCode|$groupCode",
                    ignored
                )
                return null
            }
            authResourceGroupDao.getByGroupCode(
                dslContext = dslContext,
                projectCode = targetProjectCode,
                resourceType = resourceType,
                resourceCode = targetResourceCode,
                groupCode = groupCode
            )?.relationId?.let { return it }
        }
        return null
    }

    private fun getAllRoleGroupMembersV2(
        iamGroupId: Int
    ): List<RoleGroupMemberInfo> {
        val allMembers = mutableListOf<RoleGroupMemberInfo>()
        val pageSize = 1000
        var page = 1
        do {
            val pageInfoDTO = V2PageInfoDTO().apply {
                this.pageSize = pageSize
                this.page = page
            }
            val pageResult = iamV2ManagerService
                .getRoleGroupMemberV2(iamGroupId, pageInfoDTO)
            allMembers.addAll(pageResult.results)
            page++
        } while (pageResult.results.size == pageSize)
        return allMembers
    }

    companion object {
        private val logger = LoggerFactory.getLogger(
            RbacPermissionResourceMemberService::class.java
        )

        // 有效的过期时间,在30天内就是有效的
        private const val VALID_EXPIRED_AT = 180L

        // 自动续期有效的过期时间,在180天以上就不需要自动续期
        private val AUTO_VALID_EXPIRED_AT = TimeUnit.DAYS.toSeconds(180)

        // 自动续期默认180天
        private val AUTO_RENEWAL_EXPIRED_AT = TimeUnit.DAYS.toSeconds(180)

        private val TARGET_GROUP_CREATE_RETRY_INTERVAL_MILLIS = listOf(200L, 500L, 1000L, 1500L, 2000L)
    }
}
