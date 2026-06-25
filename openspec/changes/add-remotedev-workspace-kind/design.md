## Context

RemoteDev 目前用 `WorkspaceOwnerType` 表达工作空间归属：

- `PERSONAL`：个人工作空间
- `PROJECT`：项目工作空间
- `PROJECT_PUBLIC`：项目公共云桌面

该字段已用于权限判断、共享人查询、公共云桌面转换等业务逻辑。新需求关注的是控制台实例列表上的
“个人云桌面 / 团队云桌面”分类展示，因此需要独立字段，避免把展示分类和权限归属耦合。

## Goals / Non-Goals

### Goals

- 在 `T_WORKSPACE` 持久化云桌面实例类型。
- 创建个人/团队云桌面时写入明确的 `workspaceKind`。
- 支持 OP 批量更新历史或异常数据的 `workspaceKind`。
- 控制台实例列表 `ProjectWorkspace` 出参返回 `workspaceKind`。
- 可选支持按 `workspaceKind` 查询过滤。

### Non-Goals

- 不改变 `ownerType` 的取值和权限语义。
- 不调整共享用户、项目公共云桌面、环境管理节点逻辑。
- 不在本次实现前端 Tab UI。
- 不强制迁移历史数据；历史补偿可由独立数据脚本或 OP 接口完成。

## Decisions

### 决策 1：新增 `WorkspaceKind`，不复用 `WorkspaceOwnerType`

- 方案：新增独立枚举 `WorkspaceKind`，对外值为 `cvd-personal`、`cvd-team`。
- 原因：
  - `ownerType` 是权限/归属维度。
  - `workspaceKind` 是产品分类/展示维度。
  - 两者未来可能出现非一一对应关系，例如公共团队云桌面仍属于团队展示分类。

### 决策 2：数据库使用字符串保存对外值

- 方案：`T_WORKSPACE.WORKSPACE_KIND` 保存 `cvd-personal` 或 `cvd-team`。
- 原因：
  - 满足 TAPD 验收标准。
  - 便于接口直接返回稳定值。
  - 避免枚举 name 与产品定义字符串不一致。

### 决策 3：创建入口按调用场景提供默认值

- 团队创建：`workspaceCreate.workspaceKind ?: cvd-team`
- 个人创建：`workspaceCreate.workspaceKind ?: cvd-personal`
- 显式传入非法值时快速失败，不静默降级。

### 决策 4：OP 更新使用批量接口

- 方案：`workspaceNames` 放 Body，`workspaceKind` 放 QueryParam，类型为 `WorkspaceKind` 枚举。
- 原因：
  - 与现有 `enableCoffeeAI` 接口风格一致。
  - 避免 URL 过长。
  - 适合历史数据批量修正。
  - QueryParam 直接用枚举提升类型安全，并让 Swagger 自动暴露合法取值。
- 注意：JAX-RS 对枚举 QueryParam 默认按枚举名 `valueOf` 匹配，`@JsonCreator` 仅作用于 JSON Body。
  因 `WorkspaceKind` 枚举名（`CVD_TEAM`）与对外值（`cvd-team`）不一致，需在枚举上提供静态
  `fromString` 委托到 `parse`，使 QueryParam 仍按对外值 `cvd-personal`/`cvd-team` 解析；非法值
  在参数转换阶段即返回 400。

### 决策 5：控制台列表出参必须暴露 `workspaceKind`

- 方案：在 `ProjectWorkspace` 增加 `workspaceKind` 字段。
- 数据流：

```text
T_WORKSPACE.WORKSPACE_KIND
  -> WorkspaceDao mapper
  -> WorkspaceRecordInf / WorkspaceRecordWithWindows
  -> WorkspaceService.buildProjectWorkspace
  -> ProjectWorkspace.workspaceKind
```

## Data Changes

### 全量 DDL

在 `support-files/sql/tencent/1001_ci_remotedev_ddl_mysql.sql` 的 `T_WORKSPACE` 中新增：

```sql
`WORKSPACE_KIND` varchar(16) NOT NULL DEFAULT '' COMMENT '云桌面类型：cvd-personal/cvd-team'
```

### 增量 DDL

新增幂等脚本，使用 `information_schema.columns` 判断字段是否存在后再执行 `ALTER TABLE`。

## API / Model Changes

### 入参模型

`WindowsWorkspaceCreate` 新增：

```kotlin
val workspaceKind: WorkspaceKind? = null
```

### 出参模型

`ProjectWorkspace` 新增：

```kotlin
val workspaceKind: WorkspaceKind
```

为保持内部模型一致，以下模型同步增加字段：

- `Workspace`
- `WorkspaceRecord`
- `WorkspaceRecordWithWindows`
- `WorkspaceRecordInf`

### 查询模型

`WorkspaceSearch` 可新增：

```kotlin
val workspaceKind: List<WorkspaceKind>? = null
```

## Migration / Compatibility

- 新字段默认 `''`，保证历史数据和升级过程兼容。
- 新创建数据必须写明确类型。
- 历史数据展示为空时，前端可以临时展示为未知；如需完整 Tab 数据，可通过 OP 接口补偿：
  - `OWNER_TYPE = PERSONAL` -> `cvd-personal`
  - `OWNER_TYPE in (PROJECT, PROJECT_PUBLIC)` -> `cvd-team`

## Risks / Trade-offs

- [风险] 历史数据为空导致控制台 Tab 分类不完整。
  Mitigation：提供 OP 批量更新接口，支持按实例列表补偿。
- [风险] JOOQ 生成依赖目标数据库 schema，若本地 schema 与 SQL 不一致会导致编译失败。
  Mitigation：先执行 DDL，再生成 RemoteDev JOOQ 模型。
- [风险] 将 `workspaceKind` 与 `ownerType` 绑定过死。
  Mitigation：仅在创建默认值处参考调用场景，不在权限逻辑中使用 `workspaceKind`。

## Verification

- 单元测试覆盖 `WorkspaceKind` 解析与默认策略。
- 创建链路测试覆盖团队默认 `cvd-team`、个人默认 `cvd-personal`。
- DAO/Service 测试覆盖批量更新。
- 列表出参测试覆盖 `ProjectWorkspace.workspaceKind` 组装。
- 如支持查询过滤，补充 `WorkspaceJoinDao` 条件测试。
