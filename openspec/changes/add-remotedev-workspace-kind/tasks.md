## 1. 数据库与模型

- [x] 1.1 更新 `support-files/sql/tencent/1001_ci_remotedev_ddl_mysql.sql`，为 `T_WORKSPACE`
  新增 `WORKSPACE_KIND`
- [x] 1.2 新增 RemoteDev 幂等增量 SQL
- [ ] 1.3 重新生成 RemoteDev JOOQ 模型，确保 `TWorkspace.WORKSPACE_KIND` 可用
- [x] 1.4 新增 `WorkspaceKind` 枚举，支持 `cvd-personal` 与 `cvd-team`

## 2. DTO / POJO

- [x] 2.1 `WindowsWorkspaceCreate` 新增 `workspaceKind`
- [x] 2.2 `Workspace` 新增 `workspaceKind`
- [x] 2.3 `WorkspaceRecordInf`、`WorkspaceRecord`、`WorkspaceRecordWithWindows` 新增 `workspaceKind`
- [x] 2.4 `ProjectWorkspace` 新增 `workspaceKind`
- [x] 2.5 `WorkspaceSearch` 按需新增 `workspaceKind` 查询条件

## 3. 创建链路

- [x] 3.1 团队 Windows 云桌面创建默认写入 `cvd-team`
- [x] 3.2 个人 Windows 云桌面创建默认写入 `cvd-personal`
- [x] 3.3 显式传入 `workspaceKind` 时校验合法值并写入数据库
- [x] 3.4 保持 `ownerType` 权限语义不变

## 4. DAO 与 OP 更新接口

- [x] 4.1 `WorkspaceDao.createWorkspace` 写入 `WORKSPACE_KIND`
- [x] 4.2 `WorkspaceDao` mapper 读取 `WORKSPACE_KIND`
- [x] 4.3 `WorkspaceDao` 新增批量 `updateWorkspaceKind`
- [x] 4.4 `WorkspaceService` 新增批量更新封装
- [x] 4.5 `OpWorkspaceResource` / `OpWorkspaceResourceImpl` 新增 `updateWorkspaceKind`
- [x] 4.6 `updateWorkspaceKind` QueryParam 改为 `WorkspaceKind` 枚举，并为 `WorkspaceKind`
  补充静态 `fromString` 以兼容 JAX-RS 枚举转换

## 5. 控制台实例列表

- [x] 5.1 `POST /workspaces_search` 对应的 `ProjectWorkspace` 出参返回 `workspaceKind`
- [x] 5.2 `WorkspaceService` 组装 `ProjectWorkspace` 时透传 `workspaceKind`
- [x] 5.3 如实现 `WorkspaceSearch.workspaceKind`，`WorkspaceJoinDao` 增加过滤条件

## 6. 测试与验证

- [ ] 6.1 新增 `WorkspaceKind` 单元测试（按需求暂不纳入本次变更）
- [x] 6.2 补充创建链路默认值测试
- [ ] 6.3 补充 OP 批量更新测试
- [ ] 6.4 补充控制台列表出参组装测试（按需求暂不纳入本次变更）
- [ ] 6.5 运行 RemoteDev 相关单元测试
- [ ] 6.6 检查新增/修改文件 lint 诊断
