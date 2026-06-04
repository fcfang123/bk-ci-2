# External Agent Streaming Gateway Test Plan

## 1. 目标

本文档用于验证 `external-agent-streaming-gateway` 变更的实现是否满足设计预期，重点覆盖以下目标：

- 外部智能体网关可按平台选择正确适配器，并在调用第三方前完成归属、启用状态和平台支持校验。
- AIDev、Knot 流式适配器可正确解析 SSE 文本增量、结束事件、错误事件和会话标识。
- Supervisor 可通过轻量工具调用外部智能体，并将结果纳入主智能体回复链路。
- 配置创建、更新、列表查询符合平台白名单、必填项校验和敏感字段脱敏要求。
- 超时、取消、失败分类和结构化日志字段满足可观测性要求。

## 2. 范围

### 2.1 本次纳入验证

- `ExternalAgentGateway`
- `ExternalAgentAdapter` 契约及 `call()` 收集流逻辑
- `BkAiDevExternalAgentAdapter`
- `KnotExternalAgentAdapter`
- `ExternalAgentSseEventParser`
- `ExternalAgentService` 配置校验与列表脱敏
- Supervisor 轻量工具 `call_external_agent`
- Supervisor 工具注册与 `@外部智能体` 提示词约定

### 2.2 本次未完成的高层验证

以下项仍建议在可运行的集成环境中补充：

- 主智能体自动调用外部智能体的端到端集成测试
- `@某个外部智能体` 强提示的完整对话链路测试
- 路由模式下确认未创建旧的外部调度 ReActAgent 的集成级验证

## 3. 测试环境建议

### 3.1 基础环境

- BK-CI 后端开发环境
- 可执行 Gradle 的本地或 CI 环境
- 可用的测试数据库配置
- 至少一组 AIDev 配置样例
- 至少一组 Knot 配置样例

### 3.2 配置样例

#### AIDev 应用态

- `platform=BKAIDEV`
- `apiUrl` 指向 `chat_completion` 流式接口
- `headers` 包含：
  - `X-Bkapi-Authorization`
  - `X-BKAIDEV-USER`

#### AIDev 用户态

- `platform=BKAIDEV`
- `apiUrl` 指向 `chat_completion` 流式接口
- `headers` 中 `X-Bkapi-Authorization` 含 `access_token`

#### Knot

- `platform=KNOT`
- `apiUrl` 指向 AG-UI 端点，路径包含 `/agui/`
- `headers` 包含：
  - `x-knot-api-token`
  - `x-knot-api-user`

## 4. 自动化验证

### 4.1 已执行命令

以下命令已可作为本次改动的基础自动化验证：

```bash
./gradlew :core:ai:biz-ai:test --tests "com.tencent.devops.ai.external.ExternalAgentGatewayTest" --tests "com.tencent.devops.ai.agent.ExternalAgentSupervisorToolsTest"
./gradlew :ext:tencent:ai:biz-ai-tencent:test --tests "com.tencent.devops.ai.agent.external.adapter.*"
```

### 4.2 当前自动化覆盖点

#### 网关层

- 网关关闭时拒绝调用
- 按平台路由适配器，且平台大小写不敏感
- 不支持平台时在上游 HTTP 调用前失败
- 首 token 超时映射为 `TIMEOUT`
- 响应过大时终止并返回错误

#### 适配器 / SSE 解析

- AIDev 标准 AG-UI 文本增量解析
- AIDev 兼容旧文本事件解析
- AIDev `thread_id` 提取为会话标识
- 上游错误事件解析
- `[DONE]` 与结束事件解析
- 多个 `data:` 分片解析
- Knot `rawEvent.content` 与 `conversation_id` 解析
- 畸形事件映射为 `PARSE_ERROR`
- AIDev 插件调用接口被识别为不支持流式
- Knot AG-UI 端点和必填 headers 校验

#### Supervisor 轻量工具

- `call_external_agent` 可收集流式结果并返回结构化 JSON
- Supervisor Toolkit 会显式覆盖 AgentScope 默认 `PT5M` 工具超时，避免长时外部调用被框架先行打断
- 返回内容包含：
  - `success`
  - `content`
  - `conversationId`
  - `errorCategory`
  - `errorMessage`

## 5. 手工测试用例

### 5.1 配置校验

#### Case 1: 创建有效 AIDev 应用态配置

- 前置条件：用户已登录
- 步骤：
  1. 创建 `platform=BKAIDEV` 配置
  2. 提供 `chat_completion` URL
  3. 提供 `X-Bkapi-Authorization` 和 `X-BKAIDEV-USER`
- 预期：
  - 创建成功
  - 返回结果中的 `headers` 不暴露明文密钥

#### Case 2: 创建 AIDev 插件调用配置

- 步骤：
  1. 创建 `platform=BKAIDEV`
  2. `apiUrl` 使用 `/prod/invoke/...`
- 预期：
  - 创建失败
  - 返回用户可理解的配置错误，明确该接口不支持流式

#### Case 3: 创建缺少必填 header 的 Knot 配置

- 步骤：
  1. 创建 `platform=KNOT`
  2. 仅填写 `x-knot-api-token`
- 预期：
  - 创建失败
  - 返回缺少 `x-knot-api-user` 的错误信息

#### Case 4: 查询配置列表

- 步骤：
  1. 查询当前用户外部智能体配置列表
- 预期：
  - 返回已配置项
  - `headers` 仅保留脱敏值或不返回明文

### 5.2 网关与适配器

#### Case 5: 调用已启用的 Knot 配置

- 步骤：
  1. 通过网关调用 Knot 配置
  2. 发送一条可产生流式回复的 query
- 预期：
  - 能收到文本增量
  - 若平台返回 `conversation_id`，最终结果可拿到该值
  - 日志中包含 `userId`、`configId`、`platform`、`runId`、耗时和响应大小

#### Case 6: 调用禁用配置

- 步骤：
  1. 将某配置设为禁用
  2. 通过该配置发起调用
- 预期：
  - 在第三方 HTTP 调用前拒绝
  - 返回友好错误信息

#### Case 7: 使用不支持的平台

- 步骤：
  1. 构造一个不受支持的平台值
  2. 发起调用
- 预期：
  - 网关在适配器选择阶段失败
  - 不向第三方发请求

#### Case 8: 首 token 超时

- 步骤：
  1. 将 `firstTokenTimeoutSeconds` 调小
  2. 调用一个无响应或故意延迟首包的上游
- 预期：
  - 调用超时终止
  - 返回 `TIMEOUT`
  - 日志可见超时分类

### 5.3 Supervisor 路由

#### Case 9: 主智能体自动调用外部智能体

- 步骤：
  1. 为用户准备一个启用的外部智能体配置
  2. 在普通 `/user/ai/chat/run` 聊天中提出明显需要该外部智能体的问题
- 预期：
  - Supervisor 可使用 `call_external_agent`
  - 最终回复由主智能体输出
  - 外部智能体结果被整合到主回复

#### Case 10: `@某个外部智能体` 强提示

- 步骤：
  1. 在普通聊天消息中输入 `@外部智能体名` + 问题
- 预期：
  - 主智能体优先选择对应配置
  - 工具事件或最终结果中可观察到外部智能体来源

#### Case 11: Supervisor Toolkit 超时覆盖生效

- 步骤：
  1. 将外部智能体响应时间控制在 5 分钟以上、但仍低于本次配置的 Supervisor Toolkit 超时
  2. 通过普通聊天触发 `call_external_agent`
- 预期：
  - 不出现 `Tool execution timeout after PT5M`
  - `call_external_agent` 不被 AgentScope 默认 5 分钟工具超时打断
  - 最终仍由更高层的 Supervisor/会话总超时负责兜底
#### Case 12: 路由模式不创建旧调度子智能体

- 步骤：
  1. 打开 Supervisor 轻量工具开关
  2. 触发一轮外部智能体路由
- 预期：
  - 走 `call_external_agent`
  - 不进入旧 `external_agent` 调度子智能体路径
  - 不额外创建外部调度 ReActAgent

### 5.4 持久化与前端展示

#### Case 12: 对话消息持久化

- 步骤：
  1. 发起一轮成功的外部智能体路由调用
  2. 检查 `T_AI_MESSAGE` 对应会话消息
- 预期：
  - 主智能体最终回复进入现有持久化链路
  - 不出现一条重复的外部智能体正文消息

#### Case 13: 前端事件展示

- 步骤：
  1. 在普通聊天页面触发外部智能体调用
- 预期：
  - 前端仍使用原有 `/user/ai/chat/run`
  - 路由模式复用现有 AG-UI 事件管道
  - 若展示来源，应通过工具事件或最终回复元数据体现

## 6. 日志与可观测性检查

每轮验证建议至少检查一次以下字段是否齐全：

- `userId`
- `configId`
- `platform`
- `threadId`
- `runId`
- `firstTokenMs`
- `totalMs`
- `size`
- `category`

同时确认以下敏感信息未出现在日志中：

- `X-Bkapi-Authorization` 明文
- `access_token` 明文
- `x-knot-api-token` 明文
- 完整敏感请求体

## 7. 回归关注点

- 기존普通聊天入口不应新增外部智能体专用前端入口
- 旧存量配置仍可被识别，不因缺少新协议元数据而失效
- `core/ai` 不应引入 AIDev/Knot 的 header 名称、内部域名和协议字段常量
- Supervisor 引入轻量工具后，不应破坏其他已注册子智能体和 MCP 工具

## 8. 结论口径

### 8.1 可判定为通过

需同时满足：

- 自动化定向测试全部通过
- 配置校验、流式解析、Supervisor 调用、脱敏与日志检查通过
- 手工验证至少覆盖一组 AIDev 和一组 Knot 配置

### 8.2 当前剩余风险

- `6.3`、`6.4` 仍需在真实集成环境中完成
- 主智能体自动路由依赖实际提示词、模型行为和运行时环境，单元测试无法完全替代
- 路由模式下“未创建旧调度 ReActAgent”的最终确认，建议结合运行日志和集成链路观察
