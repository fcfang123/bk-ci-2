package com.tencent.devops.auth.provider.rbac.service

import com.tencent.bk.sdk.iam.constants.ManagerScopesEnum
import com.tencent.bk.sdk.iam.dto.manager.RoleGroupMemberInfo
import com.tencent.bk.sdk.iam.dto.manager.vo.ManagerGroupMemberVo
import com.tencent.bk.sdk.iam.service.v2.V2ManagerService
import com.tencent.devops.auth.constant.AuthMessageCode
import com.tencent.devops.auth.dao.AuthResourceGroupDao
import com.tencent.devops.auth.dao.AuthResourceGroupMemberDao
import com.tencent.devops.auth.dao.AuthResourceSyncDao
import com.tencent.devops.auth.pojo.AuthResourceGroupMember
import com.tencent.devops.auth.pojo.enum.MemberType
import com.tencent.devops.auth.pojo.vo.UserAndDeptInfoVo
import com.tencent.devops.auth.service.BkInternalPermissionCache
import com.tencent.devops.auth.service.DeptService
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.event.dispatcher.trace.TraceEventDispatcher
import com.tencent.devops.common.web.utils.I18nUtil
import com.tencent.devops.model.auth.tables.records.TAuthResourceGroupRecord
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 覆盖 batchAddResourceGroupMembers 的成员校验前置与非法部门跳过逻辑
 */
class RbacPermissionResourceMemberServiceTest {

    private val authResourceService = mockk<AuthResourceService>()
    private val iamV2ManagerService = mockk<V2ManagerService>()
    private val authResourceGroupDao = mockk<AuthResourceGroupDao>()
    private val authResourceGroupMemberDao = mockk<AuthResourceGroupMemberDao>()
    private val dslContext = mockk<DSLContext>(relaxed = true)
    private val deptService = mockk<DeptService>()
    private val authResourceSyncDao = mockk<AuthResourceSyncDao>()
    private val traceEventDispatcher = mockk<TraceEventDispatcher>(relaxed = true)
    private val client = mockk<Client>(relaxed = true)

    private val service = RbacPermissionResourceMemberService(
        authResourceService = authResourceService,
        iamV2ManagerService = iamV2ManagerService,
        authResourceGroupDao = authResourceGroupDao,
        authResourceGroupMemberDao = authResourceGroupMemberDao,
        dslContext = dslContext,
        deptService = deptService,
        authResourceSyncDao = authResourceSyncDao,
        traceEventDispatcher = traceEventDispatcher,
        client = client
    )

    private val projectCode = "demo-project"
    private val iamGroupId = 100
    private val expiredTime = 4102444800L

    @BeforeEach
    fun setUp() {
        mockkObject(I18nUtil)
        every { I18nUtil.getRequestUserLanguage() } returns "zh_CN"
        mockkObject(BkInternalPermissionCache)
        every { BkInternalPermissionCache.batchInvalidateProjectUserGroups(any(), any()) } just runs
        every { BkInternalPermissionCache.invalidateProjectUserGroups(any(), any()) } just runs
        every {
            authResourceGroupDao.isGroupBelongToProject(any(), projectCode, iamGroupId.toString())
        } returns true
        every { authResourceGroupDao.get(any(), projectCode, iamGroupId.toString()) } returns groupRecord()
        every { deptService.listDepartedMembers(any()) } returns emptyList()
        every { deptService.getUserDeptInfo(any()) } returns emptySet()
        every {
            iamV2ManagerService.createRoleGroupMemberV2(any(), any())
        } just runs
        every {
            authResourceGroupMemberDao.batchCreate(any(), any())
        } just runs
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(I18nUtil)
        unmockkObject(BkInternalPermissionCache)
    }

    @Test
    fun `invalid department should be skipped instead of failing whole batch`() {
        every { iamV2ManagerService.getRoleGroupMemberV2(iamGroupId, any()) } returns emptyGroupMembers()
        every {
            deptService.getMemberInfo("dep-invalid", ManagerScopesEnum.DEPARTMENT)
        } throws ErrorCodeException(errorCode = AuthMessageCode.USER_NOT_EXIST)
        every {
            deptService.getMemberInfo("123", ManagerScopesEnum.DEPARTMENT)
        } returns deptInfo(id = 123, name = "研发部")

        val result = service.batchAddResourceGroupMembers(
            projectCode = projectCode,
            iamGroupId = iamGroupId,
            expiredTime = expiredTime,
            members = emptyList(),
            departments = listOf("dep-invalid", "123")
        )

        assertTrue(result.data == true)
        val message = result.message.orEmpty()
        assertTrue(message.contains("以下组织不存在"), "message should contain not found department: $message")
        assertTrue(message.contains("dep-invalid"), "message should contain invalid department id: $message")
        assertTrue(message.contains("成功添加组织"), "message should contain added departments: $message")
        assertTrue(message.contains("123"), "message should contain valid department id: $message")

        val created = slot<List<AuthResourceGroupMember>>()
        verify(exactly = 1) { authResourceGroupMemberDao.batchCreate(any(), capture(created)) }
        assertEquals(listOf("123"), created.captured.map { it.memberId })
        verify(exactly = 1) { traceEventDispatcher.dispatch(any()) }
    }

    @Test
    fun `member validation failure should not write iam`() {
        every { iamV2ManagerService.getRoleGroupMemberV2(iamGroupId, any()) } returns emptyGroupMembers()
        every {
            deptService.getMemberInfo("dep-invalid", ManagerScopesEnum.DEPARTMENT)
        } throws ErrorCodeException(errorCode = AuthMessageCode.USER_NOT_EXIST)

        service.batchAddResourceGroupMembers(
            projectCode = projectCode,
            iamGroupId = iamGroupId,
            expiredTime = expiredTime,
            members = emptyList(),
            departments = listOf("dep-invalid")
        )

        verify(exactly = 0) { iamV2ManagerService.createRoleGroupMemberV2(any(), any()) }
        verify(exactly = 0) { authResourceGroupMemberDao.batchCreate(any(), any()) }
        verify(exactly = 0) { BkInternalPermissionCache.batchInvalidateProjectUserGroups(any(), any()) }
        // 事件仍需下发, 保证成员状态最终一致
        verify(exactly = 1) { traceEventDispatcher.dispatch(any()) }
    }

    @Test
    fun `non numeric department id should be skipped without exception`() {
        every { iamV2ManagerService.getRoleGroupMemberV2(iamGroupId, any()) } returns emptyGroupMembers()
        every {
            deptService.getMemberInfo("not-a-number", ManagerScopesEnum.DEPARTMENT)
        } throws NumberFormatException("For input string: \"not-a-number\"")
        every {
            deptService.getMemberInfo("200", ManagerScopesEnum.DEPARTMENT)
        } returns deptInfo(id = 200, name = "测试部")

        val result = service.batchAddResourceGroupMembers(
            projectCode = projectCode,
            iamGroupId = iamGroupId,
            expiredTime = expiredTime,
            members = emptyList(),
            departments = listOf("not-a-number", "200")
        )

        assertTrue(result.message.orEmpty().contains("not-a-number"))
        val created = slot<List<AuthResourceGroupMember>>()
        verify(exactly = 1) { authResourceGroupMemberDao.batchCreate(any(), capture(created)) }
        assertEquals(listOf("200"), created.captured.map { it.memberId })
    }

    @Test
    fun `user center unavailable should fail explicitly before iam written`() {
        every { iamV2ManagerService.getRoleGroupMemberV2(iamGroupId, any()) } returns emptyGroupMembers()
        every {
            deptService.getMemberInfo("tester", ManagerScopesEnum.USER)
        } throws IllegalStateException("user center is unavailable")

        val exception = assertThrows<ErrorCodeException> {
            service.batchAddResourceGroupMembers(
                projectCode = projectCode,
                iamGroupId = iamGroupId,
                expiredTime = expiredTime,
                members = listOf("tester"),
                departments = emptyList()
            )
        }

        assertEquals(AuthMessageCode.ERROR_BATCH_ADD_GROUP_MEMBERS, exception.errorCode)
        assertTrue(exception.params!!.first().contains("tester"))
        verify(exactly = 0) { iamV2ManagerService.createRoleGroupMemberV2(any(), any()) }
        verify(exactly = 0) { authResourceGroupMemberDao.batchCreate(any(), any()) }
    }

    @Test
    fun `mixed members should report each branch in message`() {
        val now = System.currentTimeMillis() / 1000
        every { iamV2ManagerService.getRoleGroupMemberV2(iamGroupId, any()) } returns ManagerGroupMemberVo().apply {
            count = 2
            results = listOf(
                RoleGroupMemberInfo(MemberType.USER.type, "existing-user", "已存在用户", now + 86400 * 200),
                RoleGroupMemberInfo(MemberType.DEPARTMENT.type, "300", "已有部门", now + 86400 * 200)
            )
        }
        every { deptService.listDepartedMembers(listOf("new-user", "existing-user", "departed-user")) } returns
            listOf("departed-user")
        every { deptService.getUserDeptInfo("new-user") } returns emptySet()
        every { deptService.getUserDeptInfo("existing-user") } returns emptySet()
        every {
            deptService.getMemberInfo("new-user", ManagerScopesEnum.USER)
        } returns userInfo(name = "new-user", displayName = "新用户")
        every {
            deptService.getMemberInfo("400", ManagerScopesEnum.DEPARTMENT)
        } returns deptInfo(id = 400, name = "新部门")

        val result = service.batchAddResourceGroupMembers(
            projectCode = projectCode,
            iamGroupId = iamGroupId,
            expiredTime = expiredTime,
            members = listOf("new-user", "existing-user", "departed-user"),
            departments = listOf("300", "400")
        )

        val message = result.message.orEmpty()
        assertTrue(message.contains("成功添加用户：new-user"), "added users missing: $message")
        assertTrue(message.contains("成功添加组织：400"), "added departments missing: $message")
        assertTrue(message.contains("已在当前组中"), "already in group missing: $message")
        assertTrue(message.contains("不存在或已离职"), "departed users missing: $message")

        val created = slot<List<AuthResourceGroupMember>>()
        verify(exactly = 1) { authResourceGroupMemberDao.batchCreate(any(), capture(created)) }
        assertEquals(listOf("new-user", "400"), created.captured.map { it.memberId })
        val invalidated = slot<List<String>>()
        verify(exactly = 1) {
            BkInternalPermissionCache.batchInvalidateProjectUserGroups(projectCode, capture(invalidated))
        }
        assertEquals(listOf("new-user", "400"), invalidated.captured)
    }

    @Test
    fun `local write failure after iam written should report member ids`() {
        every { iamV2ManagerService.getRoleGroupMemberV2(iamGroupId, any()) } returns emptyGroupMembers()
        every {
            deptService.getMemberInfo("500", ManagerScopesEnum.DEPARTMENT)
        } returns deptInfo(id = 500, name = "部门")
        every {
            authResourceGroupMemberDao.batchCreate(any(), any())
        } throws IllegalStateException("db is down")

        val exception = assertThrows<ErrorCodeException> {
            service.batchAddResourceGroupMembers(
                projectCode = projectCode,
                iamGroupId = iamGroupId,
                expiredTime = expiredTime,
                members = emptyList(),
                departments = listOf("500")
            )
        }

        assertEquals(AuthMessageCode.ERROR_BATCH_ADD_GROUP_MEMBERS, exception.errorCode)
        assertTrue(exception.params!!.first().contains("500"))
        verify(exactly = 1) { iamV2ManagerService.createRoleGroupMemberV2(any(), any()) }
    }

    private fun emptyGroupMembers(): ManagerGroupMemberVo {
        return ManagerGroupMemberVo().apply {
            count = 0
            results = emptyList()
        }
    }

    private fun groupRecord(): TAuthResourceGroupRecord {
        val record = mockk<TAuthResourceGroupRecord>(relaxed = true)
        every { record.getResourceType() } returns "project"
        every { record.getResourceCode() } returns projectCode
        every { record.getGroupCode() } returns "manager"
        every { record.getRelationId() } returns iamGroupId.toString()
        return record
    }

    private fun deptInfo(id: Int, name: String): UserAndDeptInfoVo {
        return UserAndDeptInfoVo(
            id = id,
            name = name,
            displayName = name,
            type = ManagerScopesEnum.DEPARTMENT
        )
    }

    private fun userInfo(name: String, displayName: String): UserAndDeptInfoVo {
        return UserAndDeptInfoVo(
            id = 0,
            name = name,
            displayName = displayName,
            type = ManagerScopesEnum.USER
        )
    }
}
