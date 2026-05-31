---
name: git-commit-specification
description: >-
  编写 BK-CI Git 提交信息时使用，例如选择 commit type、整理提交边界、提交前自检、从分支名解析 GitHub Issue 号并按
  CONTRIBUTING 规范拼接 `#issue号`；提交成功后可选认领或评论对应 Issue。用户要求生成 commit message、提交代码或
  git commit 时优先使用。
---

# Git 提交规范

## 适用场景

- 编写 commit message 或执行 `git commit`
- 判断改动应归类为 `feat`、`fix`、`refactor` 等哪一类
- 整理提交边界，避免一个提交塞入多类无关改动
- bk-ci 仓库从分支名关联 GitHub Issue 并同步进展

## 不适用场景

- 讨论具体业务实现方案
- 修改 Git 历史或做高风险 Git 操作
- 只是创建分支，但不涉及提交规范本身

## 快速指导

按以下顺序执行；前 3 步完成前不要进入 message 生成。

1. **提交边界**：一个提交只表达一类主要意图；若混入功能、重构、修复和格式化，优先拆分提交。
2. **提交前自检**：改动范围是否聚焦、是否包含敏感信息、是否通过必要验证。
3. **message 格式**：遵循 [CONTRIBUTING.md](../../../CONTRIBUTING.md)，单行 subject 为 `{标记}: {概要} #{issue号}`。
   标记以**本次改动的实际性质**为准，完整标记集见 CONTRIBUTING，不要机械套用分支前缀。
4. **解析分支 issue 号**（bk-ci 仓库）：

   ```bash
   git branch --show-current
   ```

   分支命名 `{type}-{keywords}-{issue号}` 或 `{type}-{issue号}`；用 `-` 分割，**最后一段**为 issue 号（纯数字）。
   若最后一段不是纯数字，或分支为 `master`/`main`/`develop`/`release-*`，跳过 issue 关联，正常写 message。

5. **可选查 Issue 标题**：解析出 issue 号后，可通过 GitHub MCP（`user-github` / `TencentBlueKing` / `bk-ci`）读取
   title 作概要参考；失败不阻塞，用本次改动自拟概要。
6. **拼接并提交**：subject 须含 `#issue号`（若已知）。多行正文时第一行仍保留 `#issue号`。
7. **提交后可选同步 Issue**：仅在 `git commit` 成功（退出码 0）且已知 issue 号时执行；失败不回滚 commit。
   详细 MCP 调用见 [reference/github-issue-sync.md](reference/github-issue-sync.md)。

**示例：**

```
fix: 修复部分第三方构建机上 worker-agent.jar 进程延迟退出 #12994
```

## 高信号规则

- message 的核心是让评审者快速理解“这次改动为什么存在”
- 提交边界清楚，比 message 写得花更重要
- `#issue号` 服从团队约定，但不要盖过主语义
- GitHub MCP 不可用或解析失败时，提示用户但**不阻塞提交**

## 关键陷阱

- 一个提交同时混入功能、重构、修复和格式化
- message 只写“update”或“fix bug”这类低信息描述
- 仅凭分支前缀选 type，忽略实际改动性质
- 把交互式、破坏性或高风险 Git 操作当成默认规范

## 延伸阅读

- Issue 关联与提交后 MCP 同步：[reference/github-issue-sync.md](reference/github-issue-sync.md)
- 准备 PR 时补充评审上下文和验证结果
- 高风险 Git 历史整理按具体仓库流程单独判断，不在本 skill 中默认展开
