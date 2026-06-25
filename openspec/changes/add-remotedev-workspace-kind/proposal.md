## 背景

RemoteDev 云桌面需要在实例维度标记“个人云桌面”或“团队云桌面”。现有 `OWNER_TYPE`
表示工作空间归属与权限模型，取值包括 `PERSONAL`、`PROJECT`、`PROJECT_PUBLIC`，已经参与权限校验、
共享用户、公共云桌面等业务逻辑，不适合作为控制台 Tab 展示的产品形态标记。

本次新增独立的 `workspaceKind` 字段，用于表达云桌面实例类型：

- `cvd-personal`：个人云桌面
- `cvd-team`：团队云桌面

## 变更内容

### 1. 数据库存储

- `T_WORKSPACE` 新增 `WORKSPACE_KIND VARCHAR(16) NOT NULL DEFAULT ''`。
- 新装环境更新 RemoteDev 全量 DDL。
- 升级环境提供幂等增量 SQL。

### 2. 创建链路写入

- `WindowsWorkspaceCreate` 新增 `workspaceKind` 入参。
- 团队 Windows 云桌面创建接口 `POST /project_win_workspace` 支持显式传入 `workspaceKind`。
- 团队创建默认写入 `cvd-team`。
- 个人创建默认写入 `cvd-personal`。
- 新创建数据不再写空字符串。

### 3. OP 批量修正

- `OpWorkspaceResource` 新增 `updateWorkspaceKind` 接口。
- 支持按工作空间名称列表批量更新实例类型。
- 仅允许合法值 `cvd-personal` 与 `cvd-team`。

### 4. 控制台实例列表出参

- 控制台实例列表接口 `POST /workspaces_search` 的 `ProjectWorkspace` 出参新增 `workspaceKind`。
- 返回值直接来自 `T_WORKSPACE.WORKSPACE_KIND`。
- 为前端“个人云桌面 / 团队云桌面”分组或展示提供稳定字段。

### 5. 查询过滤

- 如前端需要服务端过滤，`WorkspaceSearch` 支持 `workspaceKind` 查询条件。
- `WorkspaceJoinDao` 在列表查询中增加 `WORKSPACE_KIND` 条件。

## 能力变更

### 新增能力

- `workspace-kind-storage`：RemoteDev 工作空间主表保存实例类型。
- `workspace-kind-create`：创建个人/团队云桌面时自动写入类型。
- `workspace-kind-op-update`：OP 批量修正实例类型。
- `workspace-kind-list-output`：控制台实例列表返回实例类型。

## 影响范围

- 影响模块：`remotedev` API、业务服务、DAO、数据库脚本、单元测试。
- 影响接口：
  - `POST /service/remotedev/project_win_workspace`
  - `POST /service/remotedev/workspaces_search`
  - `POST /op/workspace/update_workspace_kind`
- 影响模型：
  - `WindowsWorkspaceCreate`
  - `Workspace`
  - `WorkspaceRecord`
  - `WorkspaceRecordWithWindows`
  - `ProjectWorkspace`
  - `WorkspaceSearch`
- 不改变现有 `ownerType` 权限语义，不调整共享用户与公共云桌面逻辑。
