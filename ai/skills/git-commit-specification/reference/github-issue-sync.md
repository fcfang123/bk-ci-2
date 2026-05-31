# GitHub Issue 关联与提交后同步

## 固定参数

- 仓库 owner = `TencentBlueKing`，repo = `bk-ci`
- GitHub MCP server = `user-github`

## 分支名解析示例

| 分支名 | 解析 type 参考 | issue |
| ------ | -------------- | ----- |
| `bug-12994` | `bug` → 通常 `fix` | `12994` |
| `feat-tenant-3-11524` | `feat` | `11524` |
| `issue-12123` | 按实际改动选标记 | `12123` |

分支第一段到提交标记的常见映射：`feat`→`feat`、`bug`/`fix`→`fix`、`pref`/`perf`→`perf`、
`refactor`→`refactor`、`docs`→`docs`；最终以本次改动性质为准。

## 查询 Issue 标题（可选）

```
CallMcpTool:
  server: user-github
  toolName: issue_read
  arguments:
    method: get
    owner: TencentBlueKing
    repo: bk-ci
    issue_number: <issue号>
```

## 提交后同步 Issue（可选、非阻塞）

`#issue号` 已能自动建立提交与 Issue 的关联。以下为可选增强，按团队习惯选用。

**认领 Issue**（首次在该分支提交时）：`get_me` 取 `login`，再

```
CallMcpTool:
  server: user-github
  toolName: issue_write
  arguments:
    method: update
    owner: TencentBlueKing
    repo: bk-ci
    issue_number: <issue号>
    assignees: ["<当前用户 login>"]
```

**记录进展评论**：

```
CallMcpTool:
  server: user-github
  toolName: add_issue_comment
  arguments:
    owner: TencentBlueKing
    repo: bk-ci
    issue_number: <issue号>
    body: <本次提交说明，如 commit 概要>
```
